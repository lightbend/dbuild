package distributed
package project
package dependencies

import sbt.IO
import java.io.File

import resolve.ProjectResolver
import model.{Build,BuildConfig}

abstract class Extractor(
    resolver: ProjectResolver, 
    dependencyExtractor: BuildDependencyExtractor) {
  
  /** Given an initial build configuraiton, extract *ALL* information needed for a full build. */
  def extract(build: BuildConfig): Build = 
    useDirFor(build) { dir =>
      val config = ProjectResolver.resolve(build, dir)
      val deps = BuildDependencyExtractor.extract(build, dir)
      Build(config,deps)
    }
  
  
  // TODO - Configure how/where these projects go....
  private def useDirFor[A](build: BuildConfig)(f: File => A) = {
    val dir = new File(".localprojects")
    val projdir = new File(dir, hashing.sha1Sum(build))
    projdir.mkdirs()
    f(projdir)
  }
}

/** Given intiial configuration, this will extract information to do a distributed build.
 * 
 * 
 * Note: This needs huge cleanup and speed fixing.  Right now it just does what the script did.
 * We should probably cache directories and other kinds of niceties.
 */
object Extractor extends Extractor(ProjectResolver, BuildDependencyExtractor)