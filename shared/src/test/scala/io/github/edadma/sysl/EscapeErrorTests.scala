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

    // A loop is an expression, so a `break` of a frame-backed slice out of a loop whose value is
    // returned is the same escape as returning the slice directly — the break carries it out.
    "is broken out of a loop that is returned" in {
      err("""leak() -> []int
            |    var buf: [4]int
            |    for i in 0..<4
            |        if i == 2 then break buf[..]
            |    else buf[0..<0]
            |end leak
            |print(leak().len)
            |""".stripMargin) should include("is returned")
    }

    "is a slice of a slice of a frame-local array" in {
      err("""view() -> []int
            |    var buf: [8]int
            |    var s = buf[..]
            |    s[1..<4]
            |end view
            |print(view().len)
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

    // Breaking a slice of a heap buffer out of a loop is fine: the slice retains the buffer's
    // owner, so it keeps its storage alive the same way a returned heap-buffer view does.
    "is broken out of a loop but views a heap buffer" in {
      ir("""view() -> []int
           |    var buf: &[4]int = [1, 2, 3, 4]
           |    for i in 0..<4
           |        if i == 2 then break buf[..]
           |    else buf[0..<0]
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

  // `05` gives the rule for a callee whose body is not here: assume it keeps everything, because
  // nothing can tell whether the foreign side held on to what it was handed. An `extern` is that
  // case made explicit, and it needs no new machinery — a name with no body is already the
  // pessimistic one.
  "an extern is assumed to keep whatever it is handed" in {
    err("""extern take(s: []u8)
          |use()
          |    var buf: [4]u8
          |    take(buf[0..<2])
          |end use
          |use()
          |""".stripMargin) should include("is passed to 'take', which holds on to it")
  }

  "a heap-backed view may still be handed to an extern" in {
    ir("""extern take(s: []u8)
         |use()
         |    var buf: &[4]u8 = [1, 2, 3, 4]
         |    take(buf[0..<2])
         |end use
         |use()
         |""".stripMargin) should include("declare void @take({ ptr, ptr, i64 })")
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
