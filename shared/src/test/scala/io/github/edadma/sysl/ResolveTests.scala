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

      publishedModule(cache, "github.com/e/buf", Version(1, 2, 0), "buf")
      publishedModule(cache, "github.com/e/buf", Version(1, 4, 0), "buf")
      publishedModule(cache, "github.com/e/a", Version(1, 0, 0), "a",
        deps = dep("buf", "github.com/e/buf", "1.2.0"))
      publishedModule(cache, "github.com/e/b", Version(1, 0, 0), "b",
        deps = dep("buf", "github.com/e/buf", "1.4.0"))

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

      publishedModule(cache, "github.com/e/buf", Version(1, 9, 0), "buf")
      publishedModule(cache, "github.com/e/buf", Version(1, 10, 0), "buf")
      publishedModule(cache, "github.com/e/a", Version(1, 0, 0), "a",
        deps = dep("buf", "github.com/e/buf", "1.10.0"))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/a", "1.0.0")}, ${dep("buf", "github.com/e/buf", "1.9.0")}"))

      selected(resolve(root, cache))("github.com.e.buf") shouldBe "1.10.0"
    }

    // The whole point of MVS: adding a dependency that wants nothing new cannot move anything.
    "a dependency that asks for less does not lower a floor" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/buf", Version(1, 2, 0), "buf")
      publishedModule(cache, "github.com/e/buf", Version(1, 4, 0), "buf")
      publishedModule(cache, "github.com/e/a", Version(1, 0, 0), "a",
        deps = dep("buf", "github.com/e/buf", "1.2.0"))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/a", "1.0.0")}, ${dep("buf", "github.com/e/buf", "1.4.0")}"))

      selected(resolve(root, cache))("github.com.e.buf") shouldBe "1.4.0"
    }

    // The lower version's manifest was read on the way to raising the floor; it must not survive
    // into what gets built, and its modules must not be what the import table names.
    "the version that was passed over is not in the graph" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/buf", Version(1, 2, 0), "early")
      publishedModule(cache, "github.com/e/buf", Version(1, 4, 0), "later")
      publishedModule(cache, "github.com/e/a", Version(1, 0, 0), "a",
        deps = dep("buf", "github.com/e/buf", "1.2.0"))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/a", "1.0.0")}, ${dep("buf", "github.com/e/buf", "1.4.0")}"))

      val graph = resolve(root, cache)

      graph.packages.count(_.canonical == "github.com.e.buf") shouldBe 1

      // `a` asked for 1.2.0 and gets the selected 1.4.0, so what its import lines may name is what
      // the built version holds rather than what the version it asked for held.
      val a = graph.packages.find(_.canonical == "github.com.e.a").get

      a.imports shouldBe Map("later" -> "github.com.e.buf.later")
    }
  }

  "what a name at the head of an import line means" - {

    // The library's own documentation says `json.parse`, and `§ 9` rejected mandatory mounting so
    // that a consumer could write exactly that.
    "a package's own modules, under their own names" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/sysl-json", Version(1, 4, 0), "json", name = "json-lib")

      val root = project(manifest("app", "0.1.0", dep("j", "github.com/e/sysl-json", "1.4.0")))

      resolve(root, cache).packages.head.imports shouldBe
        Map("json" -> "github.com.e.sysl-json.json")
    }

    // The package is the unit of distribution and the module is the unit of code: sqlite3's package
    // is `sqlite3` and its module is `sqlite`, and a consumer reaching the second should not have to
    // say the first.
    "and not the name the package calls itself" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/sqlite3", Version(1, 0, 0), "sqlite", name = "sqlite3")

      val root = project(manifest("app", "0.1.0", dep("s", "github.com/e/sqlite3", "1.0.0")))

      resolve(root, cache).packages.head.imports.keys should contain only "sqlite"
    }

    "a mount hangs the whole package under one segment" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/sysl-json", Version(1, 4, 0), "json")

      val root = project(manifest("app", "0.1.0",
        dep("j", "github.com/e/sysl-json", "1.4.0", mount = "ejson")))

      resolve(root, cache).packages.head.imports shouldBe Map("ejson" -> "github.com.e.sysl-json")
    }

    "every module a package has, where there is more than one" in {
      val cache = emptyCache()

      published(cache, "github.com/e/two", Version(1, 0, 0), manifest("two", "1.0.0"),
        "alpha/alpha.sysl" -> "module alpha\n", "beta/beta.sysl" -> "module beta\n")

      val root = project(manifest("app", "0.1.0", dep("t", "github.com/e/two", "1.0.0")))

      resolve(root, cache).packages.head.imports shouldBe Map(
        "alpha" -> "github.com.e.two.alpha",
        "beta" -> "github.com.e.two.beta",
      )
    }

    // Per-consumer, which is the whole of the two-layer identity: two projects may write different
    // words and still link one copy of the package.
    "is per-package, so a dependency's own table is its own" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/buf", Version(1, 0, 0), "buf")
      publishedModule(cache, "github.com/e/a", Version(1, 0, 0), "a",
        deps = dep("b", "github.com/e/buf", "1.0.0"))

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/a", "1.0.0")}, " +
          s"${dep("buf", "github.com/e/buf", "1.0.0", mount = "bytes")}"))

      val graph = resolve(root, cache)

      graph.packages.head.imports shouldBe
        Map("a" -> "github.com.e.a.a", "bytes" -> "github.com.e.buf")
      graph.packages.find(_.canonical == "github.com.e.a").get.imports shouldBe
        Map("buf" -> "github.com.e.buf.buf")
    }

    "a package with no modules at all offers nothing to import" in {
      val cache = emptyCache()

      published(cache, "github.com/e/j", Version(1, 0, 0), manifest("j", "1.0.0"))

      val root = project(manifest("app", "0.1.0", dep("j", "github.com/e/j", "1.0.0")))

      resolveRefused(root, cache) should include("has no modules")
    }
  }

  "a collision is an error and never a silent winner" - {

    "two dependencies whose modules want one name" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/one", Version(1, 0, 0), "json", name = "one")
      publishedModule(cache, "github.com/e/two", Version(1, 0, 0), "json", name = "two")

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/one", "1.0.0")}, ${dep("b", "github.com/e/two", "1.0.0")}"))

      val e = resolveRefused(root, cache)

      e should include("is the root name of two packages")
      e should include("mount")
    }

    "and a mount settles it" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/one", Version(1, 0, 0), "json", name = "one")
      publishedModule(cache, "github.com/e/two", Version(1, 0, 0), "json", name = "two")

      val root = project(manifest("app", "0.1.0",
        s"${dep("a", "github.com/e/one", "1.0.0")}, " +
          s"${dep("b", "github.com/e/two", "1.0.0", mount = "json2")}"))

      resolve(root, cache).packages.head.imports shouldBe
        Map("json" -> "github.com.e.one.json", "json2" -> "github.com.e.two")
    }

    // The common case rather than an exotic one, and the reason the check cannot be only about
    // dependencies disagreeing with each other.
    "a dependency taking a name the project already uses for a module of its own" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/one", Version(1, 0, 0), "json", name = "one")

      val root = project(manifest("app", "0.1.0", dep("a", "github.com/e/one", "1.0.0")), "json")

      resolveRefused(root, cache) should include("is both a directory in this project")
    }

    "and a directory that is not a module does not collide with anything" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/one", Version(1, 0, 0), "json", name = "one")

      val root = project(manifest("app", "0.1.0", dep("a", "github.com/e/one", "1.0.0")), ".git")

      resolve(root, cache).packages.head.imports shouldBe Map("json" -> "github.com.e.one.json")
    }
  }

  "a path dependency" - {

    "is read and named like any other" in {
      val cache = emptyCache()
      val other = project(manifest("helper", "0.1.0"), "helper")
      val root  = project(s"""package { name = "app", version = "0.1.0" }
                             |dependencies { h { path = "$other" } }
                             |""".stripMargin)

      val graph = resolve(root, cache)

      graph.packages.head.imports shouldBe Map("helper" -> "h.helper")
      graph.packages.find(_.canonical == "h").map(_.root) shouldBe Some(other)
    }

    // A relative path in a fetched package is relative to a checkout that exists on one machine.
    "may not itself have one, because a relative path is only meaningful where it was written" in {
      val cache  = emptyCache()
      val bottom = project(manifest("bottom", "0.1.0"), "bottom")
      val middle = project(s"""package { name = "middle", version = "0.1.0" }
                              |dependencies { b { path = "$bottom" } }
                              |""".stripMargin, "middle")
      val root   = project(s"""package { name = "app", version = "0.1.0" }
                              |dependencies { m { path = "$middle" } }
                              |""".stripMargin)

      resolveRefused(root, cache) should include("only means something in the project it was written in")
    }

    // Its git dependencies still take part in selection, so a floor it raises is a floor.
    "raises the floors its own manifest asks for" in {
      val cache = emptyCache()

      publishedModule(cache, "github.com/e/buf", Version(1, 4, 0), "buf")

      val other = project(manifest("helper", "0.1.0", dep("buf", "github.com/e/buf", "1.4.0")), "helper")
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
