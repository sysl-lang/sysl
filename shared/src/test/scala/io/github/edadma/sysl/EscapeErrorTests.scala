package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The escape analysis of `05-escape-analysis.md`: a slice of an array the frame owns has no
 * owner to keep it alive, so the compiler has to find every route by which one could still be
 * reached after the frame returns — and let the rest through untouched.
 */
class EscapeErrorTests extends AnyFreeSpec with CodegenSupport {

  "a view that gets out is rejected when it" - {
    "is returned" in {
      err("""view() -> []int
            |    var buf: [4]int
            |    buf[0..<2]
            |end view
            |print(view().len)
            |""".stripMargin) should include("is returned")
    }

    "is returned from a `return` in the middle" in {
      err("""view(c: bool) -> []int
            |    var buf: [4]int
            |    if c then return buf[..]
            |    buf[0..<0]
            |end view
            |print(view(true).len)
            |""".stripMargin) should include("is returned")
    }

    "is returned inside a struct" in {
      err("""struct Header
            |    body: []u8
            |end Header
            |make() -> Header
            |    var buf: [4]u8
            |    Header(buf[..])
            |end make
            |print(make().body.len)
            |""".stripMargin) should include("is returned")
    }

    "is returned by a function that only passed it along" in {
      err("""pass(s: []int) -> []int = s
            |leak() -> []int
            |    var buf: [4]int
            |    pass(buf[..])
            |end leak
            |print(leak().len)
            |""".stripMargin) should include("is returned")
    }

    "reaches a local that is returned" in {
      err("""leak() -> []int
            |    var buf: [4]int
            |    var s = buf[..]
            |    var t = s
            |    t
            |end leak
            |print(leak().len)
            |""".stripMargin) should include("is returned")
    }

    "goes on the heap" in {
      err("""struct Held
            |    body: []int
            |end Held
            |stash() -> &Held
            |    var buf: [4]int
            |    var h: &Held = Held(buf[..])
            |    h
            |end stash
            |print(stash().body.len)
            |""".stripMargin) should include("is put on the heap")
    }

    "is handed to a callee that holds on to it" in {
      err("""stash(dest: *[]int, s: []int)
            |    *dest = s
            |grab() -> usize
            |    var buf: [4]int
            |    var out: []int
            |    stash(&out, buf[..])
            |    out.len
            |end grab
            |print(grab())
            |""".stripMargin) should include("holds on to it")
    }
  }

  "a view that stays where it was made is allowed when it" - {
    "is only read" in {
      ir("""var buf: [4]u8
           |var s = buf[..]
           |print(s.len, s[0])
           |""".stripMargin) should include("@main")
    }

    "goes to a callee that only reads it" in {
      ir("""peek(s: []u8) -> usize = s.len
           |use() -> usize
           |    var buf: [4]u8
           |    peek(buf[..])
           |end use
           |print(use())
           |""".stripMargin) should include("@peek")
    }

    "is filled by a callee and read back here" in {
      ir("""fill(s: []int, v: int)
           |    for i in 0..<s.len do s[i] = v
           |use() -> int
           |    var buf: [4]int
           |    fill(buf[..], 7)
           |    buf[0]
           |end use
           |print(use())
           |""".stripMargin) should include("@fill")
    }

    // 05's zero-copy parsing pattern: the result views the argument, and both live here.
    "comes back from a callee as part of a value this frame keeps" in {
      ir("""struct Header
           |    body: []u8
           |end Header
           |parse(s: []u8) -> Header = Header(s)
           |use() -> usize
           |    var buf: [4]u8
           |    var h = parse(buf[..])
           |    h.body.len
           |end use
           |print(use())
           |""".stripMargin) should include("@parse")
    }

    "views a buffer on the heap, which has an owner to keep it alive" in {
      ir("""view() -> []int
           |    var buf: &[4]int = [1, 2, 3, 4]
           |    buf[..]
           |end view
           |print(view().len)
           |""".stripMargin) should include("@view")
    }

    "views a `*T` region, which is outside every guarantee anyway" in {
      ir("""view() -> []int
           |    var buf: [4]int
           |    var p = &buf
           |    p[..]
           |end view
           |print(view().len)
           |""".stripMargin) should include("@view")
    }
  }

  "a recursive function converges rather than assuming the worst" in {
    ir("""walk(s: []int, i: usize) -> int
         |    if i >= s.len then 0 else s[i] + walk(s, i + 1)
         |end walk
         |use() -> int
         |    var buf: [4]int
         |    walk(buf[..], 0)
         |end use
         |print(use())
         |""".stripMargin) should include("@walk")
  }
}
