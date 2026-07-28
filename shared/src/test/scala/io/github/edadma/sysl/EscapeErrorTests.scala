package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The escape analysis of `05-escape-analysis.md`: a slice of an array the frame owns has no
 * owner to keep it alive, so the compiler has to find every route by which one could still be
 * reached after the frame returns — and let the rest through untouched.
 */
class EscapeErrorTests extends AnyFreeSpec with CodegenSupport {

  /** What promotion does **not** reach, and why each is a different question rather than the same
   * one unfinished.
   *
   * `05 § What happens when a slice escapes` moves a local array to the heap when a view of it gets
   * out. Both shapes below get a view out and neither has anywhere to move it to: the storage
   * belongs to something the body did not declare. So the diagnostic that used to cover every
   * escape now covers exactly these, and says which of the two it is.
   */
  "a view that gets out is still refused when the storage is not this body's to move" - {
    // The caller laid the array out and passed a copy of it; moving it would mean copying it into a
    // buffer on entry, which is a promotion of a *parameter* and is not what `05` specifies.
    "the array is a parameter passed by value" in {
      err("""take(a: [4]int) -> []int = a[0..<2]
            |print(take([1, 2, 3, 4]).len)
            |""".stripMargin) should include("not this body's to move")
    }

    // Moving a field means choosing between moving the field alone and moving the struct that holds
    // it, which `05 § Deferred` names as unspecified — so it is left refused rather than guessed at.
    "the array is a field of a local struct" in {
      err("""struct Frame
            |    cells: [4]int
            |end Frame
            |peek() -> []int
            |    var f: Frame
            |    f.cells[0..<2]
            |end peek
            |print(peek().len)
            |""".stripMargin) should include("not this body's to move")
    }

    "the diagnostic points at the slice rather than at the function" in {
      err("""take(a: [4]int) -> []int = a[0..<2]
            |print(take([1, 2, 3, 4]).len)
            |""".stripMargin) should include("1:29")
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

    // The array is a field of somebody else's struct, and the pointer is how this frame reached
    // it — so the storage is no more this frame's than `*p` itself is. The route to the pointer
    // runs through a field access rather than ending at the dereference.
    "views an array field reached through a `*T`" in {
      ir("""struct Box
           |    a: [8]u8
           |    n: usize
           |end Box
           |view(b: *Box) -> []u8 = b.a[0..<b.n]
           |var b = Box([65u8; 8], 3usize)
           |print(view(&b).len)
           |""".stripMargin) should include("@view")
    }

    "views an array field of a `*T` receiver" in {
      ir("""struct Box
           |    a: [8]u8
           |    n: usize
           |
           |    view(*self) -> []u8 = self.a[0..<self.n]
           |end Box
           |var b = Box([65u8; 8], 3usize)
           |print(b.view().len)
           |""".stripMargin) should include("@Box.view")
    }

    // Several steps of a place, not one: a table of structs indexed and then a field of the
    // element. Every step is still somebody else's storage.
    "views an array nested two steps inside a `*T`" in {
      ir("""struct Row
           |    a: [4]u8
           |end Row
           |struct Table
           |    rows: [2]Row
           |end Table
           |view(t: *Table, i: usize) -> []u8 = t.rows[i].a[..]
           |var t = Table([Row([65u8; 4]); 2])
           |print(view(&t, 0usize).len)
           |""".stripMargin) should include("@view")
    }
  }

  // The pointer has to be the route to *this* array. A local array beside a pointer to something
  // else is still a local array, and an element of a local array of arrays still belongs here.
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
