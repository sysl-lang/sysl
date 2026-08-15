package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `TreeWalk.children` — the one descent the analysis passes share.
 *
 * **The match ends in `case _ => Nil`, which is what makes this suite necessary.** A node missing
 * from it is not a compile error and not a wrong answer anywhere obvious: it is a node whose
 * *operands* are invisible, silently, to every pass built on this — the escape analysis, the ARC
 * insertion, `SelfAlias`, and `blocks`, which is how a statement nested inside an expression is
 * reached at all. Nothing else in the tree fails when an entry is forgotten.
 *
 * So the assertions are made against the nodes directly rather than through a program. A test that
 * went through the compiler would be asserting on whichever pass happened to notice, and the
 * passes that use this walk mostly notice nothing at all — the failure is a check that does not
 * run, not a check that gives the wrong answer.
 *
 * The vector family is what this covers, because it is where the omission has actually happened:
 * `TLane` was absent, so the call in `g()[0]` was invisible to all four.
 */
class TreeWalkTests extends AnyFreeSpec with Matchers {

  private given Word = Word(64)

  private val f32  = Type.Floating(32)
  private val v4   = Type.Vector(4, f32)
  private val mask = Type.Vector(4, Type.Bool)
  private val xs   = Type.Slice(f32)

  /** A stand-in operand that is distinguishable from every other one in a test. */
  private def mark(n: Int): TExpr = TIntLit(BigInt(n), Type.usize)

  private def va = TLoad("a", v4)
  private def vb = TLoad("b", v4)
  private def vm = TLoad("m", mask)
  private def sl = TLoad("s", xs)

  "every operand of a vector literal is reached" in {
    TreeWalk.children(TVectorLit(List(mark(1), mark(2)), v4)) shouldBe List(mark(1), mark(2))
  }

  "a splat's value is reached" in {
    TreeWalk.children(TSplat(mark(1), v4)) shouldBe List(mark(1))
  }

  "both sides of a lane-wise comparison are reached" in {
    TreeWalk.children(TVecCompare("<", va, vb, mask)) shouldBe List(va, vb)
  }

  "a select reaches its mask and both arms" in {
    TreeWalk.children(TSelect(vm, va, vb, v4)) shouldBe List(vm, va, vb)
  }

  "a reduction reaches what it reduces" in {
    TreeWalk.children(TReduce("fadd", va, f32)) shouldBe List(va)
  }

  // The one that was missing. `g()[0]` is a lane read over a call, and until this entry existed the
  // call was reachable by no walk built on `children`.
  "a lane read reaches the vector it reads from" in {
    TreeWalk.children(TLane(va, 0, f32)) shouldBe List(va)
  }

  // These two carry a *slice*, which is the first vector node whose operand can hold an owner —
  // so an omission here would hide something from the escape and ARC walks rather than only from
  // the ones that look for calls.
  "a load reaches the elements it reads and the index it starts at" in {
    TreeWalk.children(TVecLoad(sl, mark(3), v4)) shouldBe List(sl, mark(3))
  }

  "a store reaches the elements, the index and the value" in {
    TreeWalk.children(TVecStore(sl, mark(3), va)) shouldBe List(sl, mark(3), va)
  }

  // `blocks` is `children` applied until a block turns up, so an entry missing above also loses
  // every statement written inside an expression the node holds. That is the consequence with the
  // widest reach and it is worth one assertion of its own.
  "a block nested inside a load's index is reached" in {
    val block  = TBlock(List(TExprStmt(mark(7))), None, Type.usize)
    val inside = TIf(List(TCondTest(TBoolLit(true))), block, None, Type.usize)

    TreeWalk.blocks(TVecLoad(sl, inside, v4)) shouldBe List(block)
  }
}
