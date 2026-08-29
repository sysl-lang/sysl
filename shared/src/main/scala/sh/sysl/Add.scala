package sh.sysl

import io.github.edadma.cross_platform.*

/** `sysl add` — the write half of the package manager.
 *
 * `sysl deps` could report a dependency graph and `sysl build` could fetch one, and the only way to
 * *add* an entry was to open `package.hocon` and type it. That is the command a package manager is
 * judged by, and its absence is felt every time somebody has to go and look up how a coordinate is
 * spelled and what the newest tag is.
 *
 * ==Where the version comes from==
 *
 * **`git ls-remote --tags`, not a forge's API.** A coordinate here is a git identity and `Fetch`
 * clones it with plain git, so asking git what tags exist is the same question the build already
 * asks and works for any host — a self-hosted server, a mirror, an internal GitLab. Reading tags
 * through GitHub's API would make `sysl add` work for one host and fail for the rest, which is a
 * worse command than none.
 *
 * ==What is written==
 *
 * The manifest is rewritten by `ManifestEdit`, which changes the smallest run of bytes it can. The
 * result is then read back through `PackageConfig` **before** it is written to disk, so a rewrite
 * that produced something the compiler cannot read leaves the file exactly as it was.
 *
 * Nothing is fetched. `sysl add` records what a project takes; the next build is what goes and gets
 * it, and that is where a coordinate that does not exist is reported — by the machinery that already
 * says so well.
 */
object Add {

  /** What a person typed: a coordinate, and a version where they pinned one. */
  private[sysl] case class Asked(coordinate: String, version: Option[String], label: String)

  /** Reads `github.com/owner/repo` or `github.com/owner/repo@1.2.3`.
   *
   * **A leading `v` on the version is taken and dropped.** A repository's tag is `v1.2.3` and a
   * manifest writes `1.2.3`, so both spellings arrive from people reading the same page, and
   * refusing one of them would be refusing a version that is right.
   */
  private[sysl] def read(spec: String): Either[String, Asked] = {
    val parts = spec.split("@", -1).toList

    val (coordinate, wanted) = parts match
      case c :: Nil      => (c, None)
      case c :: v :: Nil => (c, Some(v.stripPrefix("v")))
      case _ => return Left(s"'$spec' writes '@' more than once — a package is " +
        "'<host>/<owner>/<repo>' or '<host>/<owner>/<repo>@<version>'")

    for
      _ <- Either.cond(!coordinate.contains("://"), (),
             s"'$coordinate' is a URL — a coordinate is an identity, so it carries no scheme: " +
               coordinate.dropWhile(_ != '/').dropWhile(_ == '/'))
      _ <- Either.cond(coordinate.count(_ == '/') >= 2, (),
             s"'$coordinate' is not a coordinate — it is a host and a path, as in " +
               "'github.com/sysl-lang/sdl3'")
      _ <- wanted.map(Version.parse).getOrElse(Right(null)).map(_ => ())
    yield Asked(coordinate, wanted, labelOf(coordinate))
  }

  /** What the entry is called, which is what an `import` line will say.
   *
   * The last segment of the coordinate, with a major-version suffix dropped — `.../json/v2` is the
   * `json` package at its second major, and naming it `v2` would name the version rather than the
   * package.
   */
  private[sysl] def labelOf(coordinate: String): String = {
    val bare = Dependency.withoutMajor(coordinate)

    bare.drop(bare.lastIndexOf('/') + 1)
  }

  /** The newest release tag a repository has, asked of git.
   *
   * A tag that is not a version is ignored rather than refused: repositories carry `latest`,
   * `nightly` and release-candidate tags, and none of them is what a manifest can pin.
   */
  private[sysl] def newest(tags: List[String]): Option[Version] =
    tags.flatMap(t => Version.parse(t.stripPrefix("v")).toOption).sorted.lastOption

  private[sysl] def tagsOf(coordinate: String): Either[String, List[String]] = {
    val url    = Dependency.cloneUrl(coordinate)
    val result = exec(Seq("git", "ls-remote", "--tags", "--refs", url))

    if result.exitCode != 0 then
      Left(s"cannot ask $url what versions it has:\n${result.stderr.trim}")
    else
      Right(result.stdout.linesIterator.toList.flatMap { line =>
        val at = line.lastIndexOf("refs/tags/")

        Option.when(at >= 0)(line.substring(at + "refs/tags/".length).trim)
      })
  }

  /** The whole command: work out the version, rewrite the manifest, check it still reads, write it. */
  def run(root: String, spec: String): Either[String, String] = {
    val file = s"$root/${PackageConfig.FileName}"

    for
      _      <- Either.cond(isFile(file), (),
                  s"there is no ${PackageConfig.FileName} here — 'sysl add' adds a dependency to a " +
                    "project, and a project is a directory with a manifest in it")
      asked  <- read(spec)
      pinned <- asked.version match
                  case Some(v) => Right(v)
                  case None =>
                    tagsOf(asked.coordinate).flatMap { tags =>
                      newest(tags).map(_.toString).toRight(
                        s"${asked.coordinate} has no version tag, so there is nothing to pin — a " +
                          "package is published by tagging it 'v<major>.<minor>.<patch>'")
                    }
      text    = readFile(file)
      edited <- ManifestEdit.addDependency(text, asked.label, asked.coordinate, pinned)

      // Read back before writing. The rewrite is textual, so the one thing it cannot know for itself
      // is whether what came out is still a manifest — and a project whose manifest a tool broke is
      // a much worse outcome than a refusal.
      _      <- PackageConfig.read(edited).left.map(e =>
                  s"adding '${asked.label}' would have made ${PackageConfig.FileName} unreadable, so " +
                    s"nothing was written:\n  $e")
    yield
      writeFile(file, edited)
      s"added ${asked.label} ${asked.coordinate} $pinned"
  }
}
