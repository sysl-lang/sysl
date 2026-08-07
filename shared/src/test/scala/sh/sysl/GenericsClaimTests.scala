package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `10-generics.md` claims, run rather than read.
 *
 * The chapter's ordinary surface has suites of its own — `GenericTests`, `TypeParamDefaultTests`,
 * `BoundTests`, `InferenceTests`. What this one covers is the sentence that turned out not to be
 * true of the compiler, and the probes that confirmed the rest.
 *
 * The sentence is `§5`'s list of what an unbounded parameter may not do: *"no `+`, `<`, `==`, or
 * other operator; no method call; no field access; no index"*. Six of those were refused at the
 * definition, naming the bound to write. **A subscript was not checked there at all** — it compiled
 * silently and surfaced only if something instantiated the body, against the instantiating type,
 * which is the "error lands on some caller three files away" outcome the section exists to prevent
 * and contrasts with C++.
 *
 * The cause was structural: the definition-time pass keeps only what is raised through `boundErr`
 * and drops the rest, and the subscript's complaint went through the ordinary `err`. It now asks the
 * bounds the way a dot call does, since a subscript **is** `Index`'s one method (`14 §3`) — which
 * also fixed a case that had only ever worked by accident, a subscript on a parameter that really
 * was bounded by `Index`.
 */
class GenericsClaimTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "everything an unbounded parameter may not do is refused at its own definition" - {

    "an operator names the bound that supplies it" in {
      err("plus[T](a: T, b: T) -> T = a + b") should include(s"'+' needs 'T: ${lib("Add")}'")
      err("less[T](a: T, b: T) -> bool = a < b") should include(s"'<' needs 'T: ${lib("Ord")}'")
      err("same[T](a: T, b: T) -> bool = a == b") should include(s"'==' needs 'T: ${lib("Eq")}'")
    }

    "a method call names it, or says no trait declares one" in {
      err("meth[T](x: T) -> int = x.go()") should
        include("no trait declares a method 'go', so no bound on 'T' could license this call")
    }

    """a field names none, because a trait promises behaviour and a field is layout — and the
      |diagnostic is reached only after looking for a property of that name""".stripMargin in {
      err("fld[T](x: T) -> int = x.v") should
        include("a field is layout, and no trait declares a property 'v' that a bound could promise")
    }

    "a rendering names 'Display'" in {
      // The trait is read off the seam rather than spelled: it is in the standard module now, so the
      // advice names it `sysl.Display`, and a spelling here would go stale on the next declaration
      // to move. What the bound has to be is the path that actually reaches the trait.
      err("shown[T](x: T) = print(x)") should
        include(s"'print' needs 'T: ${lib("Display")}'")
    }

    """A SUBSCRIPT — which went unchecked, so the body compiled and the reader met the mistake at
      |whatever first instantiated it, against a type the definition never named""".stripMargin in {
      err("idx[T](x: T) -> int = x[0]") should include(s"'index' needs 'T: ${lib("Index")}'")
    }

    "and none of it depends on anything instantiating the body" in {
      // Every case above is a whole program: no call, and the complaint still lands.
      err("idx[T](x: T) -> int = x[0]") shouldNot be(empty)
    }
  }

  /** The definition-time pass is not only about **bounds**. A body is analyzed there, so everything
   * analyzing a body would say belongs there too — and a name that names nothing is the plainest of
   * those.
   *
   * The control is what makes each of these a defect rather than a design choice: the same mistake
   * one word away from being generic is caught, so genericity is what turns the check off.
   */
  "an uncalled generic is checked for more than its bounds" - {

    "a name in the body that names nothing is refused" in {
      err("f[T](y: T) -> usize = nosuchthing") should include("nosuchthing")
    }

    "and so is the same name in a body nothing makes generic" in {
      err("g(y: int) -> usize = nosuchthing") should include("nosuchthing")
    }

    "a type in the signature that names nothing is refused" in {
      err("f[T](xs: []Nope, y: T) -> usize = 0") should include("Nope")
    }

    "and so is the same type where there is no parameter to make it generic" in {
      err("g(xs: []Nope) -> usize = 0") should include("Nope")
    }
  }

  "a bound licenses the subscript, which is what makes the refusal a rule rather than a ban" - {

    """a parameter bounded by 'Index' is subscripted and dispatches through it — this only ever
      |worked because the definition-time pass was dropping the complaint""".stripMargin in {
      run("import sysl.buf.*\n\nidx[T: Index[usize, int]](x: T) -> int = x[0usize]\nvar b: Buf[int] = buf()\n" +
        "b.push(42)\nprint(idx(b))") shouldBe "42\n"
    }

    "while a concrete type that indexes nothing keeps the complaint it always had" in {
      err("struct S\n    n: int\nvar s = S(1)\nprint(s[0])") should include("cannot index S")
    }
  }

  "a conversion out of a parameter is NOT refused, which '§ Open f' settles against '§5'" in {
    // `u8(x)` where `x: T` is called ordinary code by `§ Open f`, and `guide/sha2` is written on it.
    // There is no trait that promises convertibility, so there would be no bound to name — the one
    // capability in the section's list with nothing to license it. Refusing it at the definition
    // breaks a shipped guide program, which is what caught this.
    run("conv[T: Ord](x: T) -> int = 1\nwiden[T](x: T) -> T = x\nprint(conv(5), widen(7))")
      .shouldBe("1 7\n")
    run("shrink(x: u32) -> u8 = u8(x)\nprint(shrink(300u32))") shouldBe "44\n"
  }

  "a type parameter's default" - {

    "fills where a use leaves it out, and the filled form is the written one" in {
      run("struct Pair[A, B = A]\n    x: A\n    y: B\nvar same: Pair[int] = Pair(1, 2)\n" +
        "var both: Pair[int, int] = Pair(3, 4)\nprint(same.x, same.y, both.x, both.y)")
        .shouldBe("1 2 3 4\n")
    }

    "and 'Self' in a trait's default means the implementing type" in {
      run("trait Scale[R = Self]\n    scale(self, k: R) -> Self\nstruct P\n    n: int\n" +
        "impl Scale for P\n    scale(self, k: P) -> P = P(self.n * k.n)\n" +
        "doubled[T: Scale](v: T, k: T) -> T = v.scale(k)\nprint(doubled(P(6), P(7)).n)") shouldBe "42\n"
    }

    "a parameter with no default may not follow one that has" in {
      err("struct Suffix[A = int, B]\n    x: A\n    y: B") should
        include("'B' has no default and comes after 'A', which has one")
    }

    "a default may name only the parameters written before it" in {
      err("struct Fwd[A = B, B = int]\n    x: A\n    y: B") should
        include("the default for 'A' names 'B', which is fixed after it")
    }

    "'Self' in a struct's default has nothing to name" in {
      err("struct SelfDef[A = Self]\n    x: A") should
        include("'Self' is the type implementing a trait, and struct 'SelfDef' is not a trait")
    }

    "a default may not lead back to its own declaration" in {
      err("struct Cyc[A = Cyc]\n    x: int") should
        include("filling a type argument of 'Cyc' from its default leads back to 'Cyc'")
    }

    "nor through another's" in {
      err("struct Aa[X = Bb]\n    x: int\nstruct Bb[Y = Aa]\n    y: int") should
        include("leads back to")
    }

    """only a trait, a struct and an enum may carry one — the other three lists are SOLVED from what
      |they are given, so there is no gap for a default to fill""".stripMargin in {
      err("f[T = int](x: T) -> T = x") should
        include("'T' is a type parameter of the function 'f', whose type parameters are solved")
      err("struct Holder\n    n: int\n\n    with[U = int](self, x: U) -> int = self.n") should
        include("'U' is a type parameter of the method 'Holder.with', whose type parameters are solved")
    }

    """a trait object writes the argument out, because an erased value has forgotten which type
      |'Self' was""".stripMargin in {
      val e = err("trait Sink[T = Self]\n    put(self, x: T) -> int\nstruct Q\n    n: int\n" +
        "impl Sink[int] for Q\n    put(self, x: int) -> int = self.n + x\nvar q = Q(6)\nvar bad: &Sink = q")

      e should include("'T' defaults to 'Self', which names the type implementing 'Sink'")
      e should include("write the argument")
    }

    "while the written-out form forms an object and dispatches" in {
      run("trait Sink[T = Self]\n    put(self, x: T) -> int\nstruct Q\n    n: int\n" +
        "impl Sink[int] for Q\n    put(self, x: int) -> int = self.n + x\nvar q = Q(6)\n" +
        "var obj: &Sink[int] = q\nprint(obj.put(7))") shouldBe "13\n"
    }
  }

  "inference runs in both directions" - {

    "from the arguments, and through a nested construction" in {
      run("struct Box[T]\n    v: T\n\n    get(self) -> T = self.v\nid[T](x: T) -> T = x\n" +
        "print(id(7), id(Box(id(5))).get())") shouldBe "7 5\n"
    }

    "from the expected type, where the arguments cannot reach a parameter" in {
      run("empty[T]() -> Option[T] = None\nvar e: Option[real] = empty()\n" +
        "e match\n    Some(v) -> print(v)\n    None -> print(\"none\")") shouldBe "none\n"
    }

    "and a parameter neither direction reaches asks for an annotation, never a silent default" in {
      err("empty[T]() -> Option[T] = None\nvar stuck = empty()") should
        include("cannot infer the type argument 'T' of 'empty' here — annotate the expected type")
    }

    // A parameter left unsolved because the expected type could not itself be worked out is a
    // consequence of a mistake already reported, and saying so twice sends the reader to the wrong
    // line. Here `IoError` was never imported: the honest answer is that the name is unknown, with
    // the caret under the name, and asking for an annotation on a return type that is already as
    // annotated as it can be is not a second answer but a wrong one.
    "while one left unsolved by a type that is not there says only that the type is not there" in {
      val rendered = err("f() -> Result[unit, IoError] = Ok(())")

      rendered should include("unknown type 'IoError'")
      rendered should not include "cannot infer the type argument"
    }

    // The same suppression through an argument rather than through the expected type, which reaches
    // the unsolved parameter by the other route.
    "and one left unsolved by an argument that is not a type says the same" in {
      val rendered = err("id[T](x: T) -> T = x\n\nval v: IoError = ()\n\nprint(id(v))")

      rendered should include("unknown type 'IoError'")
      rendered should not include "cannot infer the type argument"
    }

    """a LITERAL is consulted last, so an argument that knows its type settles the parameter and the
      |literals are then read against it""".stripMargin in {
      run("pick[T](a: T, b: T, c: T) -> T = c\nprint(pick(1, 2, 250u8))") shouldBe "250\n"
      err("pick[T](a: T, b: T, c: T) -> T = c\nprint(pick(300, 2, 250u8))") should
        include("the literal 300 does not fit")
    }

    "while a call where none of them knows falls to the literal's own default" in {
      // `id(7)` is an `int`, which the wrap at 32 bits is what shows.
      run("id[T](x: T) -> T = x\nprint(id(7) + 2147483647)") shouldBe "-2147483642\n"
    }

    "a parameter naming no type parameter is not part of the question" in {
      run("with_n[T](x: T, n: usize) -> T = x\nvar v = 9\nprint(with_n(v, 7))") shouldBe "9\n"
    }

    "an associated function on a generic type infers by that same rule" in {
      run("struct Box[T]\n    v: T\n\n    of(x: T) -> Box[T] = Box(x)\n\n    get(self) -> T = self.v\n" +
        "var b = Box.of(41)\nprint(b.get())") shouldBe "41\n"
    }
  }

  "a parameter may stand wherever a type may" in {
    run("local[T](x: T) -> T\n    var here: T = x\n    here\nprint(local(\"s\"), local(9))") shouldBe "s 9\n"
  }

  /** What a bound over the **open** integer family can and cannot do.
   *
   * `guide/fft` reported that no trait sysl ships declares a zero, so a generic sum had to seed from
   * `xs[0]`. `Integer` is the membership that answers it for the built-in widths — and the shape of
   * what it does *not* answer is exactly what keeps that program's own identity trait, so both
   * halves are pinned here rather than left to the prose that now claims them.
   */
  "a bound over the open integer family" - {

    "seeds an accumulator from a bare literal, at every width, with no member promising a zero" in {
      val total =
        """total[T: Integer + Add](xs: []T) -> T
          |    var acc: T = 0
          |
          |    for i in 0..<xs.len do acc = acc + xs[i]
          |
          |    acc
          |""".stripMargin

      run(total + "print(total([1, 2, 3]))") shouldBe "6\n"
      run(total + "print(total([1u8, 2u8, 3u8]))") shouldBe "6\n"
    }

    "carries a blanket implementation's method onto every one of them" in {
      run("""trait Show
            |    tag(self) -> int
            |
            |impl[T: Integer] Show for T
            |    tag(self) -> int = 7
            |
            |print(5.tag(), 5u8.tag())""".stripMargin) shouldBe "7 7\n"
    }

    /** The asymmetry that keeps a program's own identity trait in the picture: a blanket subject is a
     * parameter, and a receiverless member has no name to be reached through. Refused where it is
     * written rather than at some instantiation, which is `§5`'s rule holding here too.
     */
    "but not a receiverless one, there being no name a call could reach it through" in {
      err("""trait Additive
            |    identity() -> Self
            |
            |impl[T: Integer] Additive for T
            |    identity() -> T = 0
            |
            |print(int.identity())""".stripMargin) should
        include("'T' is not a name a call could reach it through")
    }

    "and nothing may join the family, since the compiler settles who is in it" in {
      err("""struct Mine
            |    v: int
            |
            |impl Integer for Mine""".stripMargin) should
        include("names a family of types the compiler settles")
    }
  }
}
