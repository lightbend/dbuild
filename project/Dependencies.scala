import sbt._
import DbuildLauncher._

class CommonDependencies {

  val mvnVersion = "3.5.2"

  val typesafeConfig = "com.typesafe" % "config" % "1.2.1"

  val aetherVersion = "1.1.0"
  val aether         = "org.apache.maven.resolver" % "maven-resolver" % aetherVersion
  val aetherApi      = "org.apache.maven.resolver" % "maven-resolver-api" % aetherVersion
  val aetherSpi      = "org.apache.maven.resolver" % "maven-resolver-spi" % aetherVersion
  val aetherUtil     = "org.apache.maven.resolver" % "maven-resolver-util" % aetherVersion
  val aetherImpl     = "org.apache.maven.resolver" % "maven-resolver-impl" % aetherVersion
  val aetherConnectorBasic = "org.apache.maven.resolver" % "maven-resolver-connector-basic" % aetherVersion
  val aetherFile     = "org.apache.maven.resolver" % "maven-resolver-transport-file" % aetherVersion
  val aetherHttp     = "org.apache.maven.resolver" % "maven-resolver-transport-http" % aetherVersion
  val aetherWagon    = "org.apache.maven.resolver" % "maven-resolver-transport-wagon" % aetherVersion
/*
  val aether         = "org.eclipse.aether" % "aether" % aetherVersion
  val aetherApi      = "org.eclipse.aether" % "aether-api" % aetherVersion
  val aetherSpi      = "org.eclipse.aether" % "aether-spi" % aetherVersion
  val aetherUtil     = "org.eclipse.aether" % "aether-util" % aetherVersion
  val aetherImpl     = "org.eclipse.aether" % "aether-impl" % aetherVersion
  val aetherConnectorBasic = "org.eclipse.aether" % "aether-connector-basic" % aetherVersion
  val aetherFile     = "org.eclipse.aether" % "aether-transport-file" % aetherVersion
  val aetherHttp     = "org.eclipse.aether" % "aether-transport-http" % aetherVersion
  val aetherWagon    = "org.eclipse.aether" % "aether-transport-wagon" % aetherVersion
*/
  val ivy            = "org.scala-sbt.ivy" % "ivy" % "2.3.0-sbt-48dd0744422128446aee9ac31aa356ee203cc9f4"

  val mvnAether      = "org.apache.maven" % "maven-resolver-provider" % mvnVersion
  val mvnWagon       = "org.apache.maven.wagon" % "wagon-http" % "3.0.0"
  val mvnEmbedder    = "org.apache.maven" % "maven-embedder" % mvnVersion

  val jacks          = "com.cunei" %% "jacks" % "2.2.5"
  val jackson        = "com.fasterxml.jackson.core" % "jackson-annotations" % "2.2.3"
  val aws            = "com.amazonaws" % "aws-java-sdk" % "1.3.29"
  val uriutil        = "org.eclipse.equinox" % "org.eclipse.equinox.common" % "3.6.0.v20100503"
  val jline          = "jline" % "jline" % "2.14.2"

  val javaMail       = "javax.mail" % "mail" % "1.4.7"
  val commonsLang    = "commons-lang" % "commons-lang" % "2.6"
  val commonsIO      = "commons-io" % "commons-io" % "2.4"
  val jsch           = "com.jcraft" % "jsch" % "0.1.50"
  val oro            = "org.apache.servicemix.bundles" % "org.apache.servicemix.bundles.oro" % "2.0.8_6"
  val scallop        = "org.rogach" %% "scallop" % "1.0.0"

  val jgit           = "org.eclipse.jgit" % "org.eclipse.jgit" % "3.1.0.201310021548-r"

  val slf4jSimple    = "org.slf4j" % "slf4j-simple" % "1.7.7"

  // We deal with two separate launchers:
  // 1) The "sbt-launch.jar" is the regular sbt launcher. we package it in the "build" subproject
  // as a resource, so that it is available to the running dbuild when it wants to spawn a further sbt.
  // 2) We use a modified, dbuild-specific modified version in order to launch dbuild. This
  // is necessary since the Proguard-optimized sbt launcher is unusable as a library. This is the
  // version herebelow.
  def dbuildLaunchInt(v:String) = launchInt
  def dbuildLauncher(v:String)  = launcher
}
