package io.github.edadma.sysl

/** Minimal Version Selection and the naming rules over it (`packages.md § 5`, `§ 9`).
 *
 * Every graph here is built in a cache of its own, so the answers are about the resolver rather than
 * about what this machine happens to have fetched before.
 */
class ResolveTests extends PackageCacheSupport {

  private def dep(label: String, coordinate: String, version: String, mount: String = ""): String =
    s"""$label { git = "$coordinate", version = "$version"${if mount.isEmpty then "" else s""", mount = "$mount""""} }"""

  "the highest minimum wins, not the newest that exists" - {

    // your project depends on a 1.0.0 and b 1.0.0
    //          a 1.0.0 depends on buf 1.2.0
    //          b 1.0.0 depends on buf 1.4.0
    //                                  -----
    //                    buf resolves to 1.4.0
    "a floor raised from further down the graph" in {
      val cache = emptyCache()

      published(cache, "github.com/e/buf", Version(1, 2, 0), manifest("buf", "1.2.0"))
      published(cache, "github.com/e/buf", Version(1, 4, 0), manifest("buf", "1.4.0"))
      published(cache, "github.com/e/a", Version(1, 0, 0),
        manifest("a", "1.0.0", dep("buf", "github.com/e/buf", "1.2.0")))
      published(cache, "github.com/e/b", Version(1, 0, 0),
        manifest("b", "1.0.0", dep("buf", "github.com/e/buf", "1.4.0")))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/a", "1.0.0")}, ${dep("b", "github.com/e/b", "1.0.0")}"))

      selected(resolve(root, cache)) shouldBe Map(
        "github.com.e.a" -> "1.0.0",
        "github.com.e.b" -> "1.0.0",
        "github.com.e.buf" -> "1.4.0",
      )
    }

    // 1.10.0 is higher than 1.9.0, which is the case a comparison done on the text gets wrong.
    "versions are compared as numbers" in {
      val cache = emptyCache()

      published(cache, "github.com/e/buf", Version(1, 9, 0), manifest("buf", "1.9.0"))
      published(cache, "github.com/e/buf", Version(1, 10, 0), manifest("buf", "1.10.0"))
      published(cache, "github.com/e/a", Version(1, 0, 0),
        manifest("a", "1.0.0", dep("buf", "github.com/e/buf", "1.10.0")))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/a", "1.0.0")}, ${dep("buf", "github.com/e/buf", "1.9.0")}"))

      selected(resolve(root, cache))("github.com.e.buf") shouldBe "1.10.0"
    }

    // The whole point of MVS: adding a dependency that wants nothing new cannot move anything.
    "a dependency that asks for less does not lower a floor" in {
      val cache = emptyCache()

      published(cache, "github.com/e/buf", Version(1, 2, 0), manifest("buf", "1.2.0"))
      published(cache, "github.com/e/buf", Version(1, 4, 0), manifest("buf", "1.4.0"))
      published(cache, "github.com/e/a", Version(1, 0, 0),
        manifest("a", "1.0.0", dep("buf", "github.com/e/buf", "1.2.0")))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/a", "1.0.0")}, ${dep("buf", "github.com/e/buf", "1.4.0")}"))

      selected(resolve(root, cache))("github.com.e.buf") shouldBe "1.4.0"
    }

    // The lower version's manifest was read on the way to raising the floor; it must not survive
    // into what gets built, and its name must not be what the import table uses.
    "the version that was passed over is not in the graph" in {
      val cache = emptyCache()

      published(cache, "github.com/e/buf", Version(1, 2, 0), manifest("early", "1.2.0"))
      published(cache, "github.com/e/buf", Version(1, 4, 0), manifest("later", "1.4.0"))
      published(cache, "github.com/e/a", Version(1, 0, 0),
        manifest("a", "1.0.0", dep("buf", "github.com/e/buf", "1.2.0")))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/a", "1.0.0")}, ${dep("buf", "github.com/e/buf", "1.4.0")}"))

      val graph = resolve(root, cache)

      graph.packages.count(_.canonical == "github.com.e.buf") shouldBe 1

      // `a` asked for 1.2.0 and gets the selected 1.4.0, so the name in its import table is the
      // name the built version calls itself.
      val a = graph.packages.find(_.canonical == "github.com.e.a").get

      a.imports shouldBe Map("later" -> "github.com.e.buf")
    }
  }

  "what a name at the head of an import line means" - {

    "the package's own name, where nothing overrides it" in {
      val cache = emptyCache()

      published(cache, "github.com/e/sysl-json", Version(1, 4, 0), manifest("json", "1.4.0"))

      val root = project(manifest("app", "0.1.0", dep("j", "github.com/e/sysl-json", "1.4.0")))

      resolve(root, cache).packages.head.imports shouldBe Map("json" -> "github.com.e.sysl-json")
    }

    "a mount, which is what a consumer writes when two packages want one word" in {
      val cache = emptyCache()

      published(cache, "github.com/e/sysl-json", Version(1, 4, 0), manifest("json", "1.4.0"))

      val root = project(manifest("app", "0.1.0",
        dep("j", "github.com/e/sysl-json", "1.4.0", mount = "ejson")))

      resolve(root, cache).packages.head.imports shouldBe Map("ejson" -> "github.com.e.sysl-json")
    }

    // Per-consumer, which is the whole of the two-layer identity: two projects may write different
    // words and still link one copy of the package.
    "is per-package, so a dependency's own table is its own" in {
      val cache = emptyCache()

      published(cache, "github.com/e/buf", Version(1, 0, 0), manifest("buf", "1.0.0"))
      published(cache, "github.com/e/a", Version(1, 0, 0),
        manifest("a", "1.0.0", dep("b", "github.com/e/buf", "1.0.0")))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/a", "1.0.0")}, " +
          s"${dep("buf", "github.com/e/buf", "1.0.0", mount = "bytes")}"))

      val graph = resolve(root, cache)

      graph.packages.head.imports shouldBe
        Map("a" -> "github.com.e.a", "bytes" -> "github.com.e.buf")
      graph.packages.find(_.canonical == "github.com.e.a").get.imports shouldBe
        Map("buf" -> "github.com.e.buf")
    }

    // A directory name is where a checkout happened to land rather than a decision anybody made.
    "a package that names itself nothing, and is given no mount" in {
      val cache = emptyCache()

      published(cache, "github.com/e/j", Version(1, 0, 0), "")

      val root = project(manifest("app", "0.1.0", dep("j", "github.com/e/j", "1.0.0")))

      resolveRefused(root, cache) should include("there is nothing to call it here")
    }
  }

  "a collision is an error and never a silent winner" - {

    "two dependencies preferring one root name" in {
      val cache = emptyCache()

      published(cache, "github.com/e/one", Version(1, 0, 0), manifest("json", "1.0.0"))
      published(cache, "github.com/e/two", Version(1, 0, 0), manifest("json", "1.0.0"))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/one", "1.0.0")}, ${dep("b", "github.com/e/two", "1.0.0")}"))

      val e = resolveRefused(root, cache)

      e should include("is the root name of two packages")
      e should include("mount")
    }

    "and a mount settles it" in {
      val cache = emptyCache()

      published(cache, "github.com/e/one", Version(1, 0, 0), manifest("json", "1.0.0"))
      published(cache, "github.com/e/two", Version(1, 0, 0), manifest("json", "1.0.0"))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/one", "1.0.0")}, " +
          s"${dep("b", "github.com/e/two", "1.0.0", mount = "json2")}"))

      resolve(root, cache).packages.head.imports shouldBe
        Map("json" -> "github.com.e.one", "json2" -> "github.com.e.two")
    }

    // The common case rather than an exotic one, and the reason the check cannot be only about
    // dependencies disagreeing with each other.
    "a dependency taking a name the project already uses for a module of its own" in {
      val cache = emptyCache()

      published(cache, "github.com/e/one", Version(1, 0, 0), manifest("json", "1.0.0"))

      val root = project(manifest("app", "0.1.0", dep("a", "github.com/e/one", "1.0.0")), "json")

      resolveRefused(root, cache) should include("is both a directory in this project")
    }

    "and a directory that is not a module does not collide with anything" in {
      val cache = emptyCache()

      published(cache, "github.com/e/one", Version(1, 0, 0), manifest("json", "1.0.0"))

      val root = project(manifest("app", "0.1.0", dep("a", "github.com/e/one", "1.0.0")), ".git")

      resolve(root, cache).packages.head.imports shouldBe Map("json" -> "github.com.e.one")
    }
  }

  "a path dependency" - {

    "is read and named like any other" in {
      val cache = emptyCache()
      val other = project(manifest("helper", "0.1.0"))
      val root  = project(s"""package { name = "app", version = "0.1.0" }
                             |dependencies { h { path = "$other" } }
                             |""".stripMargin)

      val graph = resolve(root, cache)

      graph.packages.head.imports shouldBe Map("helper" -> "h")
      graph.packages.find(_.canonical == "h").map(_.root) shouldBe Some(other)
    }

    // A relative path in a fetched package is relative to a checkout that exists on one machine.
    "may not itself have one, because a relative path is only meaningful where it was written" in {
      val cache  = emptyCache()
      val bottom = project(manifest("bottom", "0.1.0"))
      val middle = project(s"""package { name = "middle", version = "0.1.0" }
                              |dependencies { b { path = "$bottom" } }
                              |""".stripMargin)
      val root   = project(s"""package { name = "app", version = "0.1.0" }
                              |dependencies { m { path = "$middle" } }
                              |""".stripMargin)

      resolveRefused(root, cache) should include("only means something in the project it was written in")
    }

    // Its git dependencies still take part in selection, so a floor it raises is a floor.
    "raises the floors its own manifest asks for" in {
      val cache = emptyCache()

      published(cache, "github.com/e/buf", Version(1, 4, 0), manifest("buf", "1.4.0"))

      val other = project(manifest("helper", "0.1.0", dep("buf", "github.com/e/buf", "1.4.0")))
      val root  = project(s"""package { name = "app", version = "0.1.0" }
                             |dependencies { h { path = "$other" } }
                             |""".stripMargin)

      selected(resolve(root, cache)) shouldBe Map("github.com.e.buf" -> "1.4.0")
    }
  }

  "a project with no dependencies resolves to itself" in {
    val root  = project(manifest("app", "0.1.0"))
    val graph = resolve(root, emptyCache())

    graph.packages.map(_.canonical) shouldBe List("")
    graph.packages.head.isRoot shouldBe true
    graph.sumsChanged shouldBe false
  }

  // The one path that reaches `git`, pointed at a host that cannot exist: `.invalid` is reserved by
  // RFC 2606 precisely so that it never resolves, which makes this a refusal rather than a network
  // round trip — a unit suite should not be able to tell whether the machine is online.
  "a package the cache has not got, and nothing can fetch" in {
    val cache = emptyCache()
    val root  = project(manifest("app", "0.1.0", dep("j", "sysl.invalid/e/nothing-here", "1.0.0")))

    resolveRefused(root, cache) should include("cannot fetch sysl.invalid/e/nothing-here")
  }
}
