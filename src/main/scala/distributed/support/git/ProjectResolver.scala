package distributed
package support
package git

import _root_.sbt.Path._
import project.model._
import project.resolve.ProjectResolver

/** This class knows how to resolve Git projects and
 * update the build configuration for repeatable checkouts.
 */
class GitProjectResolver extends ProjectResolver {
  def canResolve(config: BuildConfig): Boolean = {
    val uri = new java.net.URI(config.uri)    
    (uri.getScheme == "git") || (uri.getPath endsWith ".git")
  }
  def resolve(config: BuildConfig, dir: java.io.File): BuildConfig = {
    val uri = new java.net.URI(config.uri)

    // First clone into the directory or fetch
    // TODO - better git checkout detection...
    if(!dir.exists) dir.mkdirs()
    if(!(dir / ".git").exists) Git.clone(uri, dir)
    else Git.fetch("", dir)
    // TODO - Fetch non-standard references?
    // Then checkout desired branch/commit/etc.
    Option(uri.getFragment()) foreach (ref => Git.checkout(dir, ref))
    val sha = Git.revparse(dir, "HEAD")
    val newUri = UriUtil.dropFragment(uri).toASCIIString + "#" + sha
    config.copy(uri = newUri)
  }
}