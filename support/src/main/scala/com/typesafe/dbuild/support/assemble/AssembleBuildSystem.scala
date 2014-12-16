package com.typesafe.dbuild.support.assemble

import com.typesafe.dbuild.model._
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import _root_.java.io.File
import _root_.sbt.Path._
import _root_.sbt.IO
import _root_.sbt.IO.relativize
import com.typesafe.dbuild.logging.Logger
import sys.process._
import com.typesafe.dbuild.repo.core.LocalRepoHelper
import com.typesafe.dbuild.model.Utils.{ writeValue, readValue }
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.project.build.LocalBuildRunner
import com.typesafe.dbuild.project.BuildData
import com.typesafe.dbuild.project.BuildSystem
import com.typesafe.dbuild.support.BuildSystemCore
import com.typesafe.dbuild.hashing
import collection.JavaConverters._
import org.apache.maven.model.{ Model, Dependency }
import org.apache.maven.model.io.xpp3.{ MavenXpp3Reader, MavenXpp3Writer }
import org.apache.maven.model.Dependency
import org.apache.ivy.util.ChecksumHelper
import com.typesafe.dbuild.support.NameFixer.fixName
import _root_.sbt.NameFilter
import org.apache.ivy
import com.typesafe.dbuild.project.build.BuildDirs.localRepos

/**
 * The "assemble" build system accepts a list of nested projects, with the same format
 * as the "build" section of a normal dbuild configuration file.
 * All of those nested projects will be built *independently*, meaning that they
 * will not use one the artifacts of the others. At the end, when all of the
 * projects are built, the "group" build system will collect all of the artifacts
 * generated by the nested projects, and patch their pom/ivy files by modifying their
 * dependencies, so that they refer to one another. The net result is that they
 * will all appear to have been originating from a single project.
 */
object AssembleBuildSystem extends BuildSystemCore {
  val name: String = "assemble"
  type ExtraType = AssembleExtraConfig

  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = extra match {
    case None => AssembleExtraConfig() // pick default values
    case Some(ec: AssembleExtraConfig) =>
      // perform the defaults substitution in turn on the nested projects
      ec.copy(parts = Seq(DBuildConfig(BuildSystem.expandDBuildConfig(ec.parts, systems), None)))
    case _ => throw new Exception("Internal error: Assemble build config options have the wrong type. Please report")
  }

  private def projectsDir(base: File, config: ProjectBuildConfig) = {
    // don't use the entire nested project config, as it changes after resolution (for the components)
    // also, avoid using the name as-is as the last path component (it might confuse the dbuild's heuristic
    // used to determine sbt's default project names, see dbuild's issue #66)
    val uuid = hashing sha1 config.name
    base / "projects" / uuid
  }

  // overriding resolve, as we need to resolve its nested projects as well
  override def resolve(config: ProjectBuildConfig, dir: File, extractor: Extractor, log: Logger): ProjectBuildConfig = {
    if (config.uri != "nil" && !config.uri.startsWith("nil:"))
      sys.error("Fatal: the uri in Assemble " + config.name + " must start with the string \"nil:\"")
    // resolve the main URI (which will do nothing since it is "nil", but we may have
    // some debugging diagnostic, so let's call it anyway)
    val rootResolved = super.resolve(config, dir, extractor, log)
    // and then the nested projects (if any)
    val newExtra = rootResolved.extra match {
      case None => None
      case Some(extra: AssembleExtraConfig) =>
        val newParts = extra.parts map { nestedConf =>
          val buildConfig = nestedConf // use expandDBuildConfig !!!!!!
          val nestedResolvedProjects =
            buildConfig.projects.map { p =>
              val projDir = projectsDir(dir, p)
              projDir.mkdirs()
              log.info("----------")
              log.info("Resolving part: " + p.name)
              extractor.dependencyExtractor.resolve(p, projDir, extractor, log.newNestedLogger(p.name, p.name))
            }
          DBuildConfig(nestedResolvedProjects, buildConfig.options)
        }
        Some(extra.copy(parts = newParts))
      case _ => throw new Exception("Internal error: Assemble build config options are the wrong type in project \"" + config.name + "\". Please report")
    }
    rootResolved.copy(extra = newExtra)
  }

  def extractDependencies(config: ExtractionConfig, dir: File, extractor: Extractor, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    val ec = config.extra[ExtraType]

    // we consider the names of parts in the same way as subprojects, allowing for a
    // partial deploy, etc.
    val subProjects = ec.parts.toSeq.flatMap(_.projects).map(_.name)
    if (subProjects.size != subProjects.distinct.size) {
      sys.error(subProjects.diff(subProjects.distinct).distinct.mkString("These subproject names appear twice: ", ", ", ""))
    }
    val partOutcomes = ec.parts.toSeq flatMap { buildConfig =>
      buildConfig.projects map { p =>
        log.info("----------")
        val nestedExtractionConfig = ExtractionConfig(p)
        extractor.extractedResolvedWithCache(nestedExtractionConfig, projectsDir(dir, p),
          log.newNestedLogger(p.name, p.name), debug)
      }
    }
    if (partOutcomes.exists(_.isInstanceOf[ExtractionFailed])) {
      sys.error(partOutcomes.filter { _.isInstanceOf[ExtractionFailed] }.map { _.project }.mkString("failed: ", ", ", ""))
    }
    val partsOK = partOutcomes.collect({ case e: ExtractionOK => e })
    val allConfigAndExtracted = (partsOK flatMap { _.pces })

    // time to do some more checking:
    // - do we have a duplication in provided artifacts?
    // let's start building a sequence of all modules, with the name of the subproject they come from
    val artiSeq = allConfigAndExtracted.flatMap { pce => pce.extracted.projects.map(art => ((art.organization + "#" + art.name), pce.config.name)) }
    log.debug("artifacts: " + artiSeq.toString)
    // group by module ID, and check for duplications
    val artiMap = artiSeq.groupBy(_._1)
    log.debug("artifacts, grouped by ModuleID: " + artiMap.toString)
    val duplicates = artiMap.filter(_._2.size > 1)
    if (duplicates.nonEmpty) {
      duplicates.foreach { z =>
        log.error(z._2.map(_._2).mkString(z._1 + " is provided by: ", ", ", ""))
      }
      sys.error("Duplicate artifacts found in project")
    }

    // ok, now we just have to merge everything together. There is no version number in the assemble
    // per se, since the versions are decided by the components.
    log.info("----------")
    log.info("Assembling dependencies...")
    val artifacts = allConfigAndExtracted.flatMap(_.extracted.projects.flatMap(_.artifacts))
    // we create a list of subprojects using the lists of subprojects extracted from the
    // component projects. The way in which said subprojects are created MUST match the
    // equivalent logic in the runBuild() method, below.
    // TODO: the alignment between subproject name, moduleinfo, and set of artifacts is
    // rather lax in the code at the moment. It should be reinforced and made more systematic.
    //
    // first, associate the list of subprojects with each component project
    val projectsAndSubprojects = allConfigAndExtracted.map { pce =>
      val projMeta = pce.extracted.getHead
      pce.config.name -> projMeta.subproj
    }
    // postprocessing of the subproject names
    val adapted = adaptSubProjects(projectsAndSubprojects)

    val newMeta = ExtractedBuildMeta("0.0.0",
      allConfigAndExtracted.flatMap(_.extracted.projects.map { p =>
        // remove all dependencies that are not already provided by this
        // assembled project (we pretend the resulting assembled set has
        // no external dependency)
        val ignoredDeps = p.dependencies.filterNot(artifacts contains _)
        ignoredDeps.foreach { d =>
          log.warn("WARN: The dependency of " + p.name + " on " + d.organization + "#" + d.name + " will be ignored.")
        }
        p.copy(dependencies = p.dependencies.diff(ignoredDeps))
      }),
      adapted.flatMap(_._2))
    log.info(newMeta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    newMeta
  }

  // postprocess the list of subprojects obtained from the component projects, and
  // make them unique and recognizable when grouped together
  private def adaptSubProjects(projectsAndSubprojects: Seq[(String, Seq[String])]): Seq[(String, Seq[String])] = {
    // in order to avoid ambiguities, replace "default-sbt-project" with the project name, or
    // prepend to "default-sbt-project" the project name, in case the project name is already taken
    // as a subproject name.
    val projectsAndSubprojects1 = projectsAndSubprojects.map {
      case (name, subs) => (name, subs.map { sub =>
        if (sub == "default-sbt-project") {
          if (!subs.contains(name))
            name // if we can use "name" alone, do so
          else // else, make it (more) unique
            name + "-default-sbt-project"
        } else sub
      })
    }
    // finally, if any project names are duplicated, make them unique
    val allSubProjects = projectsAndSubprojects1.flatMap { _._2 }
    val nonUniqueSubProjs = allSubProjects.diff(allSubProjects.distinct).distinct
    val projectsAndSubprojects2 = projectsAndSubprojects1.map {
      case (proj, subs) => (proj, subs.map { sub =>
        if (nonUniqueSubProjs.contains(sub)) proj + "-" + sub else sub
      })
    }
    // here, projectsAndSubprojects2 contains the updated map from
    // project names to the new list of subprojects, which is in
    // the same order of the original list of subprojects and can
    // be therefore used with a "zip" on data indexed by the original
    // one, in order to perform a replacement (in BuildArtifactsOut).
    projectsAndSubprojects2
  }
  // runBuild() is called with the (empty) root source resolved, but the parts have not been checked out yet.
  // Therefore, we will call localBuildRunner.checkCacheThenBuild() on each part,
  // which will in turn resolve it and then build it (if not already in cache).
  def runBuild(project: RepeatableProjectBuild, dir: File, input: BuildInput, localBuildRunner: LocalBuildRunner,
    buildData: BuildData): BuildArtifactsOut = {

    val ec = project.extra[ExtraType]
    val version = input.version // IGNORED!!

    val log = buildData.log
    log.info(ec.parts.toSeq.flatMap(_.projects).map(_.name).mkString("These subprojects will be built: ", ", ", ""))

    val localRepo = input.outRepo
    // We do a bunch of in-place file operations in the localRepo, before returning.
    // To avoid problems due to stale files, delete all contents before proceeding.
    IO.delete(localRepo.*("*").get)

    def mavenArtifactDir(repoDir: File, ref: ProjectRef, crossSuffix: String) =
      ref.organization.split('.').foldLeft(repoDir)(_ / _) / (ref.name + crossSuffix)

    def ivyArtifactDir(repoDir: File, ref: ProjectRef, crossSuffix: String) =
      repoDir / ref.organization / (ref.name + crossSuffix)

    // Since we know the repository format, and the list of "subprojects", we grab
    // the files corresponding to each one of them right from the relevant subdirectory.
    // We then calculate the sha, and package each subproj's results as a BuildSubArtifactsOut.
    def scanFiles[Out](artifacts: Seq[ProjectRef], crossSuffix: String)(f: File => Out) = {
      // use the list of artifacts as a hint as to which directories should be looked up,
      // but actually scan the dirs rather than using the list of artifacts (there may be
      // additional files like checksums, for instance).
      artifacts.flatMap { art =>
        val artCross = if (isScalaCoreRef(art)) "" else crossSuffix
        Seq(mavenArtifactDir(localRepo, art, artCross),
          ivyArtifactDir(localRepo, art, artCross))
      }.distinct.flatMap { _.***.get }.
        // Since this may be a real local maven repo, it also contains
        // the "maven-metadata-local.xml" files, which should /not/ end up in the repository.
        filterNot(file => file.isDirectory || file.getName == "maven-metadata-local.xml").map(f)
    }

    def projSHAs(artifacts: Seq[ProjectRef], crossSuffix: String): Seq[ArtifactSha] = scanFiles(artifacts, crossSuffix) {
      LocalRepoHelper.makeArtifactSha(_, localRepo)
    }

    // OK, now build the parts
    val (preCrossPreDupsArtifactsMap, repeatableProjectBuilds) = (ec.parts.toSeq flatMap { build =>
      build.projects map { p =>
        // the parts are build built independently from one another. Their list
        // of dependencies is cleared before building, so that they do not rely on one another
        log.info("----------")
        log.info("Building part: " + p.name)
        val nestedExtractionConfig = ExtractionConfig(p)
        val partConfigAndExtracted = localBuildRunner.extractor.cachedExtractOr(nestedExtractionConfig,
          log.newNestedLogger(p.name, p.name)) {
            // if it's not cached, something wrong happened.
            sys.error("Internal error: extraction metadata not found for part " + p.name)
          } match {
            case outcome: ExtractionOK => outcome.pces.headOption getOrElse
            sys.error("Internal error: PCES empty after cachedExtractOr(); please report")
            case _ => sys.error("Internal error: cachedExtractOr() returned incorrect outcome; please report.")
          }
        val repeatableProjectBuild = RepeatableProjectBuild(partConfigAndExtracted,
          // remove all dependencies, and pretend that this project stands alone))
          partConfigAndExtracted.extracted.projInfo.map { pm => RepeatableDepInfo(pm.version, Seq.empty, Seq.empty) })
        val outcome = localBuildRunner.checkCacheThenBuild(projectsDir(dir, p), repeatableProjectBuild,
          Seq.empty, Seq.empty, BuildData(log.newNestedLogger(p.name, p.name), buildData.debug))
        val artifactsOut = outcome match {
          case o: BuildGood => o.artsOut
          case o: BuildBad => sys.error("Part " + p.name + ": " + o.status)
        }
        val q = (p.name, artifactsOut)
        log.debug("---> " + q)
        (q, repeatableProjectBuild)
      }
    }).unzip

    val projectsAndSubprojects = preCrossPreDupsArtifactsMap.map {
      case (proj, bao) =>
        proj -> bao.results.map { _.subName }
    }
    // postprocessing the subproject names, to make them unique
    val adapted = adaptSubProjects(projectsAndSubprojects).toMap
    val preCrossArtifactsMap = preCrossPreDupsArtifactsMap.map {
      case (proj, bao) =>
        val newSubProjs = adapted(proj)
        (proj, bao.copy(results = (bao.results zip newSubProjs).map {
          case (subArt, newSubName) =>
            subArt.copy(subName = newSubName)
        }))
    }

    // Excellent, we now have in preCrossArtifactsMap a sequence of BuildArtifactsOut from the parts
    log.info("----------")
    log.info("Assembling:")

    val preCrossArtifacts = preCrossArtifactsMap.map(_._2).flatMap(_.results).flatMap(_.artifacts)
    val patcher = new NamePatcher(preCrossArtifacts, project.config)

    // ------
    //
    // now, let's retrieve the parts' artifacts again (they have already been published)
    val uuids = repeatableProjectBuilds map { _.uuid }
    log.info("Retrieving artifacts")
    log.debug("into " + localRepo)
    val artifactLocations = LocalRepoHelper.getArtifactsFromUUIDs(log.info, localBuildRunner.repository,
      Seq(localRepo), Seq(uuids), Seq(""), buildData.debug) // retrieve only the base level, space "" (no external dependencies)

    // ------
    // ok. At this point, we have:
    // preCrossArtifactsMap: map name -> artifacts, from the nested parts. Each mapping corresponds to one nested project,
    //   and the list of artifacts may contain multiple subprojects, each with their own BuildSubArtifactsOut
    //
    // ------
    //
    // Before rearranging the poms, we may need to adapt the cross-version strings in the part
    // names. That depends on the value of cross-version in our main project.
    // If it is "disabled" (default), the parts should already have a version without a cross-version
    // string; we might have to remove the cross suffix, if present, from the modules compiled by the
    // scala ant task, as that is not affected by the cross-version selector. In any case, we just need
    // to remove all cross suffixes. If it is anything else, we need to adjust the cross-version suffix
    // of all artifacts (except those of the scala core) according to the value of the new scala
    // version, according to the "scala-library" artifact we have in our "Assemble". If we have
    // no scala-library, however, we can't change the suffixes at all, so we stop.
    // If cross-version is "full", the parts will have a cross suffix like
    // "_2.11.0-M5"; we should replace that with the new full Scala version.
    // For "standard" it may be either "_2.11.0-M5" or "_2.11", depending on what each part
    // decides. For binaryFull, it will be "_2.11" even for milestones.
    // The cross suffix for the parts depends on their own cross-version selection.
    // 
    // We change that in conformance to project.crossVersion, so that:
    // - disabled => no suffix
    // - full => full version string
    // - binaryFull => binaryScalaVersion
    // - standard => binary if stable, full otherwise
    // For "standard" we rely on the simple 0.12 algorithm (contains "-"), as opposed to the
    // algorithms detailed in sbt's pull request #600.
    //
    // We have to patch both the list of BuildSubArtifactsOut, as well as the actual filenames
    // (including checksums, if any)

    // this is the renaming section: the artifacts are renamed according
    // to the crossSuffix selection
    val artifactsMap = preCrossArtifactsMap map {
      case (projName, BuildArtifactsOut(subs)) => (projName, BuildArtifactsOut(
        subs map {
          case BuildSubArtifactsOut(subProjName, artifacts, shas, moduleInfo) =>
            val renamedArtifacts = artifacts map { l =>
              if (isScalaCoreArt(l)) l else l.copy(crossSuffix = patcher.crossSuff)
            }
            // These newSHAs have the new *locations* but still the old sha hash value;
            // those hashes are recomputed at the end, before returning.
            val newSHAs = shas map { sha =>
              val oldLocation = sha.location
              try {
                val OrgNameVerFilenamesuffix(org, oldName, ver, suffix1, suffix2, isMaven, isIvyXml) = oldLocation
                if (isScalaCore(oldName, org)) sha else {
                  val newName = patcher.patchName(oldName)
                  if (newName == oldName) sha else {

                    def fileDir(name: String) = if (isMaven)
                      org.split('.').foldLeft(localRepo)(_ / _) / name / ver
                    else
                      localRepo / org / name / ver / suffix1

                    def fileLoc(name: String) = fileDir(name) / (if (isMaven)
                      (name + suffix1)
                    else if (isIvyXml)
                      suffix2
                    else
                      (name + suffix2))

                    val oldFile = fileLoc(oldName)
                    val newFile = fileLoc(newName)
                    val newLocation = IO.relativize(localRepo, newFile) getOrElse
                      sys.error("Internal error while relativizing " + newFile.getCanonicalPath() + " against " + localRepo.getCanonicalPath())
                    fileDir(newName).mkdirs() // ignore if already present
                    if (!oldFile.renameTo(newFile))
                      sys.error("cannot rename " + oldLocation + " to " + newLocation + ".")
                    sha.copy(location = newLocation)
                  }
                }
              } catch {
                case e: _root_.scala.MatchError =>
                  log.error("Path cannot be parsed: " + oldLocation + ". Continuing...")
                  sha
              }
            }
            BuildSubArtifactsOut(subProjName, renamedArtifacts, newSHAs,
              moduleInfo.copy(attributes = moduleInfo.attributes.copy(scalaVersion =
                if (isScalaCore(moduleInfo.name, moduleInfo.organization)) None else
                  patcher.crossSuff match {
                    case "" => None
                    case s if s.startsWith("_") => Some(s.drop(1))
                    case s => sys.error("Internal Error: crossSuff has unexpected format: \"" + s + "\". Please report.")
                  }
              )))
        }))
    }

    //
    // we have all our artifacts ready. Time to rewrite the POMs and the ivy.xmls!
    // Note that we will also have to recalculate the shas
    //
    // Let's collect the list of available artifacts:
    //
    val allArtifactsOut = artifactsMap.map { _._2 }
    val available = allArtifactsOut.flatMap { _.results }.flatMap { _.artifacts }

    (localRepo.***.get).filter(_.getName.endsWith(".pom")).foreach { patchPomDependencies(_, available) }

    val ivyHome = dir / ".ivy2" / "cache"
    (localRepo.***.get).filter(_.getName == "ivy.xml").foreach { patchIvyDependencies(_, available, ivyHome, localRepo) }

    // dbuild SHAs must be re-computed (since the POM/Ivy files changed)
    // We preserve the list of original subprojects (and consequently modules),
    // where the subproject names may be slightly renamed in order to avoid collisions.

    val out = BuildArtifactsOut(artifactsMap flatMap {
      _._2.results map {
        case sub @ BuildSubArtifactsOut(project, modArtLocs, shas, modInfo) =>
          sub.copy(shas = sub.shas map {
            case oldArtSha @ ArtifactSha(oldSha, location) =>
              val path = IO.pathSplit(location)
              val name = path.last
              if (name.endsWith(".pom") || name == "ivy.xml") {
                // Recalculate the actual sha hash from the new content of the file.
                // (we only need to rescan poms and ivy.xml files, as the content of
                // the other files is still the same)
                val newSha = hashing.files sha1
                  (path.foldLeft(localRepo) { _ / _ })
                ArtifactSha(newSha, location)
              } else oldArtSha
          })
      }
    })
    log.debug("out: " + writeValue(out))
    out
  }

  // This lengthy wrapper is needed due to the peculiar definition style
  // of some Ivy structures. There's no way to clone-and-patch a ModuleDescriptor,
  // in particular removing the existing licenses, so the only alternatives are
  // either to decompose and reconstruct *everything*, or to add a wrapper that
  // forwards to the underlying structure everything, except the license information.
  // This is a "dumb" wrapper that does not change behavior. You will need to create
  // a subclass with the required overrides
  abstract class ModuleDescriptorWrapper(m: ivy.core.module.descriptor.ModuleDescriptor) extends ivy.core.module.descriptor.ModuleDescriptor {
    // Members declared in ivy.plugins.latest.ArtifactInfo
    def getRevision(): String =
      m.getRevision()
    // Members declared in ivy.core.module.descriptor.DependencyDescriptorMediator
    def mediate(x: ivy.core.module.descriptor.DependencyDescriptor): ivy.core.module.descriptor.DependencyDescriptor =
      m.mediate(x)
    // Members declared in ivy.util.extendable.ExtendableItem
    def getAttribute(x: String): String =
      m.getAttribute(x)
    def getAttributes(): java.util.Map[_, _] =
      m.getAttributes()
    def getExtraAttribute(x: String): String =
      m.getExtraAttribute(x)
    def getExtraAttributes(): java.util.Map[_, _] =
      m.getExtraAttributes()
    def getQualifiedExtraAttributes(): java.util.Map[_, _] =
      m.getQualifiedExtraAttributes()
    // Members declared in ivy.core.module.descriptor.ModuleDescriptor
    def canExclude(): Boolean =
      m.canExclude()
    def dependsOn(x: ivy.plugins.version.VersionMatcher, y: ivy.core.module.descriptor.ModuleDescriptor): Boolean =
      m.dependsOn(x, y)
    def doesExclude(x: Array[String], y: ivy.core.module.id.ArtifactId): Boolean =
      m.doesExclude(x, y)
    def getAllArtifacts(): Array[ivy.core.module.descriptor.Artifact] =
      m.getAllArtifacts()
    def getAllDependencyDescriptorMediators(): ivy.core.module.id.ModuleRules =
      m.getAllDependencyDescriptorMediators()
    def getAllExcludeRules(): Array[ivy.core.module.descriptor.ExcludeRule] =
      m.getAllExcludeRules()
    def getArtifacts(x: String): Array[ivy.core.module.descriptor.Artifact] =
      m.getArtifacts(x)
    def getConfiguration(x: String): ivy.core.module.descriptor.Configuration =
      m.getConfiguration(x)
    def getConfigurations(): Array[ivy.core.module.descriptor.Configuration] =
      m.getConfigurations()
    def getConfigurationsNames(): Array[String] =
      m.getConfigurationsNames()
    def getConflictManager(x: ivy.core.module.id.ModuleId): ivy.plugins.conflict.ConflictManager =
      m.getConflictManager(x)
    def getDependencies(): Array[ivy.core.module.descriptor.DependencyDescriptor] =
      m.getDependencies()
    def getDescription(): String =
      m.getDescription()
    def getExtraAttributesNamespaces(): java.util.Map[_, _] =
      m.getExtraAttributesNamespaces()
    def getExtraInfo(): java.util.Map[_, _] =
      m.getExtraInfo()
    def getHomePage(): String =
      m.getHomePage()
    def getInheritedDescriptors(): Array[ivy.core.module.descriptor.ExtendsDescriptor] =
      m.getInheritedDescriptors()
    def getLastModified(): Long =
      m.getLastModified()
    def getLicenses(): Array[ivy.core.module.descriptor.License] =
      m.getLicenses()
    def getMetadataArtifact(): ivy.core.module.descriptor.Artifact =
      m.getMetadataArtifact()
    def getModuleRevisionId(): ivy.core.module.id.ModuleRevisionId =
      m.getModuleRevisionId()
    def getParser(): ivy.plugins.parser.ModuleDescriptorParser =
      m.getParser()
    def getPublicConfigurationsNames(): Array[String] =
      m.getPublicConfigurationsNames()
    def getPublicationDate(): java.util.Date =
      m.getPublicationDate()
    def getResolvedModuleRevisionId(): ivy.core.module.id.ModuleRevisionId =
      m.getResolvedModuleRevisionId()
    def getResolvedPublicationDate(): java.util.Date =
      m.getResolvedPublicationDate()
    def getResource(): ivy.plugins.repository.Resource =
      m.getResource()
    def getStatus(): String =
      m.getStatus()
    def isDefault(): Boolean =
      m.isDefault()
    def setResolvedModuleRevisionId(x: ivy.core.module.id.ModuleRevisionId): Unit =
      m.setResolvedModuleRevisionId(x)
    def setResolvedPublicationDate(x: java.util.Date): Unit =
      m.setResolvedPublicationDate(x)
    def toIvyFile(x: java.io.File): Unit =
      m.toIvyFile(x)
  }

  class NewDescriptorWrapper(m: ivy.core.module.descriptor.ModuleDescriptor,
    newDependencies: Array[ivy.core.module.descriptor.DependencyDescriptor],
    newRevId: ivy.core.module.id.ModuleRevisionId) extends ModuleDescriptorWrapper(m: ivy.core.module.descriptor.ModuleDescriptor) {
    override def getDependencies(): Array[ivy.core.module.descriptor.DependencyDescriptor] = newDependencies
    override def getResolvedModuleRevisionId(): ivy.core.module.id.ModuleRevisionId = newRevId
    override def setResolvedModuleRevisionId(x: ivy.core.module.id.ModuleRevisionId) =
      sys.error("Unexpected invocation of setResolvedModuleRevisionId(). Please report.")
  }

  def patchIvyDependencies(file: File, available: Seq[ArtifactLocation], ivyHome: File, localRepo: File) = {
    import _root_.scala.collection.JavaConversions._

    val settings = new ivy.core.settings.IvySettings()
    settings.setDefaultIvyUserDir(ivyHome)
    val parser = ivy.plugins.parser.xml.XmlModuleDescriptorParser.getInstance()
    val ivyFileRepo = new ivy.plugins.repository.file.FileRepository(localRepo.getAbsoluteFile())
    val rel = IO.relativize(localRepo, file) getOrElse sys.error("Internal error while relativizing")
    val ivyFileResource = ivyFileRepo.getResource(rel)
    val model = parser.parseDescriptor(settings, file.toURL(), ivyFileResource, true) match {
      case m: ivy.core.module.descriptor.DefaultModuleDescriptor => m
      case m => sys.error("Unknown Module Descriptor: " + m)
    }

    val myRevID = model.getModuleRevisionId()
    val NameExtractor = """[^/]*/([^/]*)/([^/]*)/ivys/ivy.xml""".r
    val NameExtractor(newArtifactId, newVersion) = rel
    val newRevID = org.apache.ivy.core.module.id.ModuleRevisionId.newInstance(
      myRevID.getOrganisation(),
      newArtifactId,
      myRevID.getBranch(),
      newVersion,
      myRevID.getExtraAttributes())

    val newDeps = model.getDependencies() map { d =>
      val dep = d match {
        case t: ivy.core.module.descriptor.DefaultDependencyDescriptor => t
        case t => sys.error("Unknown Dependency Descriptor: " + t)
      }
      val rid = dep.getDependencyRevisionId()
      val newDep = available.find { artifact =>
        artifact.info.organization == rid.getOrganisation() &&
          artifact.info.name == fixName(rid.getName())
      } map { art =>
        val transformer = new ivy.plugins.namespace.NamespaceTransformer {
          def transform(revID: ivy.core.module.id.ModuleRevisionId) = {
            ivy.core.module.id.ModuleRevisionId.newInstance(
              revID.getOrganisation(),
              art.info.name + art.crossSuffix,
              revID.getBranch(),
              art.version,
              revID.getExtraAttributes())
          }
          def isIdentity() = false
        }
        val transformMrid = transformer.transform(dep.getDependencyRevisionId())
        val transformDynamicMrid = transformer.transform(dep.getDynamicConstraintDependencyRevisionId())
        val newdd = new ivy.core.module.descriptor.DefaultDependencyDescriptor(
          null, transformMrid, transformDynamicMrid,
          dep.isForce(), dep.isChanging(), dep.isTransitive())
        val moduleConfs = dep.getModuleConfigurations()
        moduleConfs foreach { conf =>
          dep.getDependencyConfigurations(conf).foreach { newdd.addDependencyConfiguration(conf, _) }
          dep.getExcludeRules(conf).foreach { newdd.addExcludeRule(conf, _) }
          dep.getIncludeRules(conf).foreach { newdd.addIncludeRule(conf, _) }
          dep.getDependencyArtifacts(conf).foreach { depArt =>
            val newDepArt = if (art.info.name != fixName(depArt.getName())) depArt else {
              val n = new ivy.core.module.descriptor.DefaultDependencyArtifactDescriptor(depArt.getDependencyDescriptor(),
                art.info.name + art.crossSuffix, depArt.getType(), depArt.getExt(), depArt.getUrl(), depArt.getExtraAttributes())
              depArt.getConfigurations().foreach(n.addConfiguration)
              n
            }
            newdd.addDependencyArtifact(conf, newDepArt)
          }
        }
        newdd
      } getOrElse dep

      newDep: ivy.core.module.descriptor.DependencyDescriptor
    }

    val newModel = new NewDescriptorWrapper(model, newDeps, newRevID)
    ivy.plugins.parser.xml.XmlModuleDescriptorWriter.write(newModel, file)
    updateChecksumFiles(file)
  }

  def patchPomDependencies(pom: File, available: Seq[ArtifactLocation]) = {
    val reader = new MavenXpp3Reader()
    val model = reader.read(new _root_.java.io.FileReader(pom))
    // transform dependencies
    val deps: Seq[Dependency] = model.getDependencies.asScala
    val newDeps: _root_.java.util.List[Dependency] = (deps map { m =>
      available.find { artifact =>
        artifact.info.organization == m.getGroupId &&
          artifact.info.name == fixName(m.getArtifactId)
      } map { art =>
        val m2 = m.clone
        m2.setArtifactId(fixName(m.getArtifactId) + art.crossSuffix)
        m2.setVersion(art.version)
        m2
      } getOrElse m
    }).asJava
    val newModel = model.clone
    // has the artifactId (aka the name) changed? If so, patch that as well.
    val NameExtractor = """.*/([^/]*)/([^/]*)/\1-[^/]*.pom""".r
    val NameExtractor(newArtifactId, newVersion) = pom.getCanonicalPath()
    newModel.setArtifactId(newArtifactId)
    newModel.setVersion(newVersion)
    newModel.setDependencies(newDeps)
    // we overwrite in place, there should be no adverse effect at this point
    val writer = new MavenXpp3Writer
    writer.write(new _root_.java.io.FileWriter(pom), newModel)
    updateChecksumFiles(pom)
  }

  def updateChecksumFiles(base: File) = {
    // We will also have to change the .sha1 and .md5 files
    // corresponding to this pom, if they exist, otherwise artifactory and ivy
    // will refuse to use the pom in question.
    Seq("md5", "sha1") foreach { algorithm =>
      val checksumFile = new File(base.getCanonicalPath + "." + algorithm)
      if (checksumFile.exists) {
        FileUtils.writeStringToFile(checksumFile, ChecksumHelper.computeAsString(base, algorithm))
      }
    }
  }

  // general utilities:

  // In order to detect the artifacts that belong to the scala core (non cross-versioned)
  // we cannot rely on the cross suffix, as the non-scala nested projects might also be published
  // with cross versioning disabled (it's the default in dbuild). Our only option is going after
  // the organization id "org.scala-lang".
  def isScalaCore(name: String, org: String) = {
    val fixedName = fixName(name)
    (org == "org.scala-lang" && fixedName.startsWith("scala")) ||
      (org == "org.scala-lang.plugins" && fixedName == "continuations")
  }

  def isScalaCoreRef(p: ProjectRef) =
    isScalaCore(p.name, p.organization)

  def isScalaCoreArt(l: ArtifactLocation) =
    isScalaCoreRef(l.info)

}

// A helper to detect versions and other info from an arbitrary set of files contained in a
// mixed Maven/Ivy local repository. It will parse a relative path and file name string, decomposing
// it into useful information (see below for details).
object OrgNameVerFilenamesuffix {
  val MavenMatchPattern = """(.*)/([^/]*)/([^/]*)/\2(-[^/]*)""".r
  val IvyXmlMatchPattern = """([^/]*)/([^/]*)/([^/]*)/(ivys)/([^/]*)""".r
  val IvyMatchPattern = """([^/]*)/([^/]*)/([^/]*)/([^/]*)/\2([^/]*)""".r

  // Returns: org, name, ver, suffix1, suffix2, isMaven, isIvyXml
  // where:
  // - for Maven:
  //   isMaven is true
  //   suffix1 is the part after the "name", for example in:
  //     org/scala-lang/modules/scala-xml_2.11.0-M5/1.0-RC4/scala-xml_2.11.0-M5-1.0-RC4-sources.jar
  //   suffix1 is "-1.0-RC4-sources.jar"
  //   suffix2 is ignored
  // - for Ivy, for things that are in an "ivys" directory:
  //   isIvyXml is true
  //   suffix1 is "ivys"
  //   suffix2 is the filename. For example:
  //     org.scala-lang/scala-compiler/2.10.2-dbuildx83bbe18c0407e30bbcf72be0eb1cfc9934528099/ivys/ivy.xml.sha1
  //     -> suffix2 is "ivy.xml.sha1"
  // - for Ivy, for things that are not in an "ivys" directory:
  //   isIvyXml is false
  //   suffix1 is "docs", "jars", etc.
  //   suffix2 is the portion of the filename after "name". For example:
  //     org.scala-lang/scala-compiler/2.10.2-dbuildx83bbe18c0407e30bbcf72be0eb1cfc9934528099/docs/scala-compiler-javadoc.jar
  //     -> suffix1 is "docs"
  //     -> suffix2 is "-javadoc.jar"
  def unapply(s: String): Option[(String, String, String, String, String, Boolean, Boolean)] = {
    try {
      val MavenMatchPattern(org, name, ver, suffix) = s
      Some((org.replace('/', '.'), name, ver, suffix, "", true, false))
    } catch {
      case e: _root_.scala.MatchError => try {
        val IvyXmlMatchPattern(org, name, ver, suffix1, suffix2) = s
        Some((org, name, ver, suffix1, suffix2, false, true))
      } catch {
        case e: _root_.scala.MatchError => try {
          val IvyMatchPattern(org, name, ver, suffix1, suffix2) = s
          Some((org, name, ver, suffix1, suffix2, false, false))
        } catch {
          case e: _root_.scala.MatchError => None
        }
      }
    }
  }
}

class NamePatcher(arts: Seq[ArtifactLocation], config: ProjectBuildConfig) {
  // we also need the new scala version, which we take from the scala-library artifact, among
  // our subprojects. If we cannot find it, then we have none.
  private val scalaVersion = arts.find(l => l.info.organization == "org.scala-lang" && l.info.name == "scala-library").map(_.version)
  private def getScalaVersion(newCrossLevel: String) = scalaVersion getOrElse
    sys.error("The requested cross-version level is " + newCrossLevel + ", but no scala-library was found among the dependencies (maybe you meant \"cross-version: disabled\"?).")
  private val Part = """(\d+\.\d+)(?:\..+)?""".r
  private def binary(s: String) = s match {
    case Part(z) => z
    case _ => sys.error("Fatal: cannot extract Scala binary version from string \"" + s + "\"")
  }
  val crossSuff = config.getCrossVersionHead match {
    case "disabled" => ""
    case l @ "full" => "_" + getScalaVersion(l)
    case l @ "binary" => "_" + binary(getScalaVersion(l))
    case l @ "standard" => sys.error("\"standard\" is not a supported cross-version selection for this build system. " +
      "Please select one of \"disabled\", \"binary\", or \"full\" instead.")
    case cv => sys.error("Fatal: unrecognized cross-version option \"" + cv + "\"")
  }
  def patchName(s: String) = fixName(s) + crossSuff
}

