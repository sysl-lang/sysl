package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@export` — a definition C can call, and the C header describing it (`15 §12`).
 *
 * **The assertions are on the emitted IR and on the generated header rather than on running
 * anything**, for `CallingConventionTests`' reason: what this feature produces is an artifact for a
 * *different* toolchain to consume, and there is no C project in this suite to consume it. What can
 * be checked here is the whole of what sysl decides — which symbol the definition is emitted under,
 * that the mangling is gone, that a build with no entry point keeps the exports alive, and every
 * refusal.
 */
class ExportTests extends AnyFreeSpec with CodegenSupport with TestFrameworkSupport {

  /** The exports of a compilation, which is the list a header is written from. */
  private def exportsOf(src: String): List[TFunc] =
    Compiler.compiled(List(Source("<input>", src))) match
      case Right(c) => c.exports
      case Left(e)  => fail(e)

  /** The header a module's exports produce. */
  private def headerFor(src: String): String = CHeader.render(exportsOf(src), "demo")

  "the symbol" - {

    "an exported function is emitted under its bare name, with no module path" in {
      val out = ir("module demo\n\n@export\nadd(a: i32, b: i32) -> i32 = a + b\n")

      out should include("define i32 @add(")
      out should not include "@demo$add("
    }

    "and under the symbol it names, where it names one" in {
      val out = ir("module demo\n\n@export(\"mylib_add\")\nadd(a: i32, b: i32) -> i32 = a + b\n")

      out should include("define i32 @mylib_add(")
      out should not include "@demo$add("
    }

    // The rename is what a real C API needs, since its symbols carry a prefix the sysl side gets
    // from its module path — so the function stays `add` to everything inside sysl.
    "a sysl caller still reaches it by its own name, and arrives at the C symbol" in {
      val out = ir("module demo\n\n@export(\"mylib_add\")\nadd(a: i32, b: i32) -> i32 = a + b\n\n" +
        "print(demo.add(1, 2))\n")

      out should include("call i32 @mylib_add(")
    }

    "an unmangled export is not 'internal', so a linker outside can resolve it" in {
      ir("module demo\n\n@export\nadd(a: i32, b: i32) -> i32 = a + b\n") should
        include("define i32 @add(")
    }
  }

  "an export is a root for reachability, exactly as an interrupt handler is" - {

    // The load-bearing case: nothing in the program calls it, so a walk from what the program *runs*
    // cannot reach it, and a build with no entry point has no such walk to start.
    "a function nothing calls survives because it is exported" in {
      ir("module demo\n\n@export\nunused(a: i32) -> i32 = a + 1\n\nprint(1)\n") should
        include("define i32 @unused(")
    }

    "and so does what it calls" in {
      val out = ir("module demo\n\nhelper(a: i32) -> i32 = a * 2\n\n@export\n" +
        "doubled(a: i32) -> i32 = helper(a)\n\nprint(1)\n")

      out should include("define i32 @doubled(")
      out should include("@demo$helper(")
    }

    // A test build replaces the roots with the tests, so an export — which nothing in the program
    // names, that being the point of one — was reachable from nothing and went. It goes *quietly*,
    // unlike a destructor: there is no generated call site to be left dangling, so the suite links
    // and the package's own C is what finds out, which is the whole surface a `build-c` package has.
    "and a test build keeps it, though no test names it" in {
      testIr("module demo\n\n@export\nunused(a: i32) -> i32 = a + 1\n\n" +
        "@test(\"something else\")\nother() = assert_eq(1 + 1, 2)\n") should
        include("define i32 @unused(")
    }
  }

  "what C cannot spell is refused" - {

    "a slice parameter" in {
      val e = err("module demo\n\n@export\nsum(xs: []i32) -> i32 = 0\n")

      e should include("which C has no way to spell")
      e should include("a pointer and a 'usize'")
    }

    "a string parameter" in {
      err("module demo\n\n@export\nlen_of(s: string) -> i32 = 0\n") should
        include("which C has no way to spell")
    }

    "a struct by value" in {
      err("module demo\n\nstruct P\n    x: i32\n    y: i32\n@export\nf(p: P) -> i32 = p.x\n") should
        include("take a pointer to it instead")
    }

    "a struct returned by value" in {
      err("module demo\n\nstruct P\n    x: i32\n@export\nf() -> P = P(1)\n") should
        include("which C has no way to spell")
    }

    // A pointer to a struct is fine, and this is the case the refusal above tells people to write.
    // It is also the check that the refusal is about the *shape* rather than about the type being
    // one the module declared, which a rule keyed on "is it named" would have got wrong.
    "but a pointer to one is not refused" in {
      ir("module demo\n\nstruct P\n    x: i32\n@export\nf(p: *P) -> i32 = p.x\n") should
        include("define i32 @f(")
    }
  }

  "what a definition cannot be and stay C-callable" - {

    "generic" in {
      err("module demo\n\n@export\nid[T](x: T) -> T = x\n") should
        include("cannot be generic")
    }

    // Not an export rule at all: `SyslParser.attributedDecl` reads attributes at statement position
    // only, so no attribute reaches a member — `@test` and `@pure` are as unavailable there. Pinned
    // because it decides the shape a binding is written in: the boundary layer is free functions,
    // and a method is reached by exporting one that takes the receiver.
    "a member, which the grammar refuses before any rule here is reached" in {
      val src = "module demo\n\nstruct P\n    x: i32\n    @export\n    get(self) -> i32 = self.x\n"

      err(src) should not be empty
    }

    "private" in {
      err("module demo\n\n@export\nprivate add(a: i32, b: i32) -> i32 = a + b\n") should
        include("cannot make both claims")
    }

    "variadic" in {
      err("module demo\n\n@export\nf(n: i32, ...) -> i32 = n\n") should
        include("take a 'va_list' parameter instead")
    }

    "'@ghost', which is erased before there is a symbol at all" in {
      err("module demo\n\n@export\n@ghost\nf(a: i32) -> bool = a > 0\n") should
        include("erased before codegen")
    }

    "a symbol C could not name" in {
      err("module demo\n\n@export(\"my lib\")\nf(a: i32) -> i32 = a\n") should
        include("is not a name C can call")
    }

    "and a backticked name exported under itself, which is the same rule from the other end" in {
      err("module demo\n\n@export\n`add two`(a: i32) -> i32 = a + 2\n") should
        include("name the symbol instead")
    }

    "two definitions claiming one symbol" in {
      val src = "module demo\n\n@export(\"f\")\na(x: i32) -> i32 = x\n\n@export(\"f\")\nb(x: i32) -> i32 = x\n"

      err(src) should include("one symbol is one definition")
    }
  }

  "module storage" - {

    // The ruling: a C project supplies its own `main`, so nothing runs to fill storage a computed
    // initializer would have written.
    "a computed 'val' an export reaches is refused" in {
      val src = "module demo\n\ncounter() -> i32 = 7\n\nval start: i32 = counter()\n\n" +
        "@export\nbegin() -> i32 = start\n"

      err(src) should include("module storage an initializer fills")
    }

    "reached transitively, which is what the walk is for" in {
      val src = "module demo\n\ncounter() -> i32 = 7\n\nval start: i32 = counter()\n\n" +
        "inner() -> i32 = start\n\n@export\nbegin() -> i32 = inner()\n"

      err(src) should include("module storage an initializer fills")
    }

    // The other half of the ruling, and the reason it is not simply "no module storage": a constant
    // initializer is laid straight into the object file and nothing runs to fill it, which is the
    // rule C already has for static storage.
    "a 'val' whose initializer is constant data is fine" in {
      val src = "module demo\n\nval start: i32 = 7\n\n@export\nbegin() -> i32 = start\n"

      ir(src) should include("define i32 @begin(")
    }

    "and a 'const', which has no storage at all" in {
      val src = "module demo\n\nconst start: i32 = 7\n\n@export\nbegin() -> i32 = start\n"

      ir(src) should include("define i32 @begin(")
    }
  }

  "the generated header" - {

    "declares each export once, in a guard, with the C fixed-width names" in {
      val h = headerFor("module demo\n\n@export\nadd(a: i32, b: u8) -> i64 = 0i64\n")

      h should include("#ifndef SYSL_DEMO_H")
      h should include("#include <stdint.h>")
      h should include("int64_t add(int32_t a, uint8_t b);")
      h should include("#endif /* SYSL_DEMO_H */")
    }

    "spells a function taking nothing as C does, which is 'void' and not an empty list" in {
      headerFor("module demo\n\n@export\ntick() -> i32 = 0\n") should include("int32_t tick(void);")
    }

    "spells a function yielding nothing as 'void'" in {
      headerFor("module demo\n\n@export\ntick(a: i32)\n    print(a)\n") should include("void tick(int32_t a);")
    }

    // `never` and `unit` both spell as `void`, so the annotation is the only thing carrying the
    // difference across — that control does not come back, rather than merely that no value does.
    "annotates a function that does not return, which a bare 'void' would not say" in {
      val h = headerFor("module demo\n\n@export\nspin() -> never =\n    loop\n        print(1)\n")

      h should include("SYSL_NORETURN void spin(void);")
      h should include("#define SYSL_NORETURN _Noreturn")
      h should include("[[noreturn]]")
    }

    "and the macro is absent from a header with nothing that diverges" in {
      headerFor("module demo\n\n@export\ntick(a: i32)\n    print(a)\n") should not include "SYSL_NORETURN"
    }

    "spells a pointer as a pointer" in {
      headerFor("module demo\n\n@export\nhead(p: *u8) -> *u8 = p\n") should
        include("uint8_t * head(uint8_t * p);")
    }

    "spells a 'bool' as C's, which is why <stdbool.h> is included" in {
      headerFor("module demo\n\n@export\npositive(a: i32) -> bool = a > 0\n") should
        include("bool positive(int32_t a);")
    }

    "spells the two float widths as C's own names" in {
      val h = headerFor("module demo\n\n@export\nf(a: f32) -> f64 = f64(a)\n")

      h should include("double f(float a);")
    }

    // A `char` is a Unicode scalar value and four bytes wide, so C's `char` would be wrong by a
    // factor of four — which is a silently corrupt call rather than a compile error.
    "spells a 'char' as the 32-bit integer it is, never as C's 'char'" in {
      headerFor("module demo\n\n@export\nf(c: char) -> i32 = 0\n") should include("uint32_t c")
    }

    "names them in symbol order rather than declaration order, so the file is stable" in {
      val h = headerFor("module demo\n\n@export\nzeta() -> i32 = 0\n\n@export\nalpha() -> i32 = 1\n")

      h.indexOf("alpha") should be < h.indexOf("zeta")
    }

    "and a module exporting nothing says so, rather than being an empty file" in {
      headerFor("module demo\n\nadd(a: i32) -> i32 = a\n\nprint(1)\n") should
        include("exports nothing")
    }

    "wraps the declarations for C++, which is what a C header is expected to do" in {
      val h = headerFor("module demo\n\n@export\nf() -> i32 = 0\n")

      h should include("extern \"C\" {")
    }
  }

  "the attribute itself" - {

    "a malformed argument is told what the form is" in {
      err("module demo\n\n@export(mylib_add)\nadd(a: i32) -> i32 = a\n") should
        include("names the C symbol as a string")
    }

    "and the unknown-annotation list mentions it, so it is discoverable" in {
      err("module demo\n\n@exprot\nadd(a: i32) -> i32 = a\n") should include("'@export'")
    }
  }
}
