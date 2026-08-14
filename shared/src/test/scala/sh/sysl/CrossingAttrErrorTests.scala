package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@crossing(p)` — the annotation that says a parameter hands a value to another concurrency
 * domain, and what it refuses (`06 § Marking a domain boundary`).
 *
 * The point of the annotation is that the rule reaches a scheduler sysl did not write: the check is
 * made at the call, off the signature, so a package binding an RTOS gets it by writing one line
 * above the wrapper it already has. What is checked is the **shareable** walk a `&sync T`'s pointee
 * goes through — a parameter copies nothing, so a view's non-atomic buffer count is the same race
 * one level down, and a `*T`'s pointee is examined rather than waved through.
 *
 * What it *accepts* is `CrossingAttrRunTests`, which runs the programs rather than compiling them.
 */
class CrossingAttrErrorTests extends AnyFreeSpec with CodegenSupport {

  private val node = "struct Node\n    v: int\n"
  private val job  = "struct Job\n    node: &Node\n"

  "the annotation names parameters, in parentheses" - {

    "there is no bare form" in {
      err("@crossing\nf(x: int) -> int = x\nprint(f(1))") should
        include("names the parameters a value reaches another concurrency domain through")
    }

    // `@reads()` is a real claim — the function reads no module storage — and this one would not be:
    // a function that hands nothing across a boundary has said so by not writing the line.
    "nor an empty pair of parentheses" in {
      err("@crossing()\nf(x: int) -> int = x\nprint(f(1))") should include("There is no empty form")
    }

    "what stands in them is a name, not an expression" in {
      err("@crossing(1)\nf(x: int) -> int = x\nprint(f(1))") should
        include("names the parameters a value reaches another concurrency domain through")
    }
  }

  "what it may name is settled at the declaration" - {

    "a word that is not a parameter is refused by name" in {
      val e = err("@crossing(stat)\nf(state: int) -> int = state\nprint(f(1))")

      e should include("'@crossing' names 'stat', which is not a parameter of 'f'")
      e should include("its parameters are 'state'")
    }

    "and a function with no parameters says so" in {
      err("@crossing(state)\nf() -> int = 1\nprint(f())") should include("it takes none")
    }

    "one named twice says nothing the once does not" in {
      err("@crossing(a, a)\nf(a: int) -> int = a\nprint(f(1))") should include("names 'a' twice")
    }
  }

  "a raw pointer parameter is looked through, which is what the annotation buys" - {

    // `*T` is on the crossable list because it carries no count — a fact about the pointer and not
    // about the object at the far end, which is the thing that crossed.
    "an object holding a plain reference may not be handed over" in {
      val e = err(node + job +
        "@crossing(state)\nstart(state: *Job) -> int = state[0].node.v\n" +
        "var n: &Node = Node(1)\nvar j = Job(n)\nprint(start(&j))")

      e should include("what 'state' of 'start' points at reaches another concurrency domain")
      e should include("its 'node' reaches a '&Node'")
      e should include("Hold it as a '&sync Node'")
    }

    "nor one holding a string, whose buffer count is not atomic either" in {
      val e = err(
        "struct Job\n    label: string\n" +
        "@crossing(state)\nstart(state: *Job) -> int = 1\n" +
        "var j = Job(\"hi\")\nprint(start(&j))")

      e should include("its 'label' reaches a 'string'")
      e should include("a shared object holds no view at all")
    }
  }

  "a parameter that is not a pointer is asked about its own type" - {

    "a plain reference is the thing in the way" in {
      val e = err(node +
        "@crossing(state)\nstart(state: &Node) -> int = state.v\n" +
        "var n: &Node = Node(1)\nprint(start(n))")

      e should include("'state' of 'start' reaches another concurrency domain")
      e should include("but it is a '&Node'")
    }
  }

  /** The question is about the **argument**, so a generic facility is asked afresh per
   * instantiation — the rule a `&sync Box[T]` is already held to.
   */
  "a generic parameter is asked per instantiation" in {
    val e = err(node + job + "@crossing(state)\nstart[T](state: *T) -> unit = ()\n" +
      "var n: &Node = Node(1)\nvar j = Job(n)\nstart(&j)")

    e should include("what 'state' of 'start' points at reaches another concurrency domain")
    e should include("its 'node' reaches a '&Node'")
  }
}
