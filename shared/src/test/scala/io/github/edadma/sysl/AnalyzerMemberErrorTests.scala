package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for a type's members and dispatch: inherent methods and properties, members on a
 * generic type, and traits — conformance of an `impl`, and the bounds a generic places on its
 * type parameters.
 */
class AnalyzerMemberErrorTests extends AnyFreeSpec with CodegenSupport {

  "methods" - {
    "a '&self' method rejects a bare stack value" in {
      err(
        """struct C
          |    n: int
          |    bump(&self)
          |        self.n += 1
          |var c = C(0)
          |c.bump()""".stripMargin
      ) should include("'&self' needs a reference")
    }

    "a property is read without parentheses" in {
      err(
        """struct P
          |    x: int
          |    twice -> int = self.x * 2
          |var p = P(1)
          |print(p.twice())""".stripMargin
      ) should include("is a property")
    }

    "a method is called with parentheses" in {
      err(
        """struct P
          |    x: int
          |    twice(self) -> int = self.x * 2
          |var p = P(1)
          |print(p.twice)""".stripMargin
      ) should include("is a method")
    }

    "an unknown method is reported against its type" in {
      err(
        """struct P
          |    x: int
          |var p = P(1)
          |print(p.area())""".stripMargin
      ) should include("no method 'area'")
    }

    "an unknown associated function is reported against its type" in {
      err(
        """struct P
          |    x: int
          |    id(self) -> int = self.x
          |var p = P.make()""".stripMargin
      ) should include("no associated function 'make'")
    }

    "a member may not share a name with a field" in {
      err(
        """struct P
          |    x: int
          |    x(self) -> int = 1""".stripMargin
      ) should include("both a field and a member")
    }

    // An associated function on a generic type has no receiver to read the type arguments from,
    // so it would need them inferred — a separate deferral with its own diagnostic. Methods and
    // properties, whose type arguments come straight off the receiver, are supported.
    "an associated function on a generic type waits on the generics work" in {
      err(
        """struct Box[T]
          |    value: T
          |    empty() -> int = 0""".stripMargin
      ) should include("associated functions on generic types are not supported yet")
    }

    // A method that introduces its own type parameter, even on a non-generic type, is a separate
    // deferral with its own diagnostic.
    "a generic method on a non-generic type waits on the generics work" in {
      err(
        """struct Registry
          |    n: int
          |    store[T](&self, item: T) -> int = self.n""".stripMargin
      ) should include("generic methods are not supported yet")
    }

    // A method with its own type parameter is rejected even on a generic type — the receiver fixes
    // the struct's parameters, but the method's own parameter still has nothing to infer it from.
    "a generic method on a generic type waits on the generics work" in {
      err(
        """struct Box[T]
          |    value: T
          |    cast[U](self, u: U) -> U = u""".stripMargin
      ) should include("generic methods are not supported yet")
    }
  }

  "members on enums" - {
    // A variant is what an enum has instead of fields, so it is a variant a member may not shadow —
    // and the diagnostic says variant, since there is no field to have meant.
    "a member may not share a name with a variant" in {
      err(
        """enum Color
          |    Red
          |    Red(self) -> int = 1""".stripMargin
      ) should include("type 'Color' has both a variant and a member named 'Red'")
    }

    "an unknown method on an enum is reported against its type" in {
      err(
        """enum Color
          |    Red
          |var c = Red
          |print(c.area())""".stripMargin
      ) should include("type 'Color' has no method 'area'")
    }

    // An enum has no fields at all, so an absent name can only have been a property — saying "field
    // or property" here would point at something the type cannot have.
    "an absent name read off an enum value is reported as a property" in {
      err(
        """enum Color
          |    Red
          |var c = Red
          |print(c.nope)""".stripMargin
      ) should include("'Color' has no property 'nope'")
    }

    "a property on an enum is read without parentheses" in {
      err(
        """enum Color
          |    Red
          |    code -> int = 1
          |var c = Red
          |print(c.code())""".stripMargin
      ) should include("is a property")
    }

    "a method on an enum is called with parentheses" in {
      err(
        """enum Color
          |    Red
          |    code(self) -> int = 1
          |var c = Red
          |print(c.code)""".stripMargin
      ) should include("is a method")
    }

    // A `&self` method needs the reference itself; a bare enum on the stack has no refcount to
    // share, exactly as a bare struct has none.
    "a '&self' method on an enum rejects a bare stack value" in {
      err(
        """enum Color
          |    Red
          |    code(&self) -> int = 1
          |var c = Red
          |print(c.code())""".stripMargin
      ) should include("'&self' needs a reference")
    }

    // Reached through the type name rather than a value: an instance member is not a variant, and
    // the diagnostic distinguishes the three member kinds rather than claiming the name is unknown.
    "an instance member reached through the enum name says to use a value" in {
      err(
        """enum Color
          |    Red
          |    code(self) -> int = 1
          |print(Color.code())""".stripMargin
      ) should include("'code' is an instance method of 'Color'")

      err(
        """enum Color
          |    Red
          |    code(self) -> int = 1
          |print(Color.code)""".stripMargin
      ) should include("call it on a value, as 'value.code(…)'")

      err(
        """enum Color
          |    Red
          |    code -> int = 1
          |print(Color.code)""".stripMargin
      ) should include("'code' is a property of 'Color'")
    }

    "an associated function read off the enum name without parentheses is rejected" in {
      err(
        """enum Color
          |    Red
          |    make() -> int = 1
          |print(Color.make)""".stripMargin
      ) should include("'make' is an associated function of 'Color' — call it with 'Color.make(…)'")
    }

    "an unknown associated function on an enum is reported against its type" in {
      err(
        """enum Color
          |    Red
          |    make() -> int = 1
          |print(Color.bogus())""".stripMargin
      ) should include("enum 'Color' has no variant or associated function 'bogus'")
    }

    // The same two deferrals a struct's members meet, met on an enum: a member's own type
    // parameter has nothing to infer it from, and an associated function has no receiver to read
    // the enum's type arguments off.
    "a generic method on an enum waits on the generics work" in {
      err(
        """enum Color
          |    Red
          |    store[T](self, item: T) -> int = 1""".stripMargin
      ) should include("generic methods are not supported yet")
    }

    "an associated function on a generic enum waits on the generics work" in {
      err(
        """enum Maybe[T]
          |    Just(value: T)
          |    make() -> int = 1""".stripMargin
      ) should include("associated functions on generic types are not supported yet")
    }

    "a data enum's method body must still cover every variant" in {
      err(
        """enum Shape
          |    Circle(r: int)
          |    Empty
          |    area(self) -> int = match self
          |        Empty -> 0""".stripMargin
      ) should include("not exhaustive")
    }

    "the prelude's Option members are checked against the element type" in {
      err(
        """var a: Option[int] = Some(1)
          |print(a.unwrap_or("no"))""".stripMargin
      ) should include("is int, but string was given")
    }
  }

  "traits on enums" - {
    "an impl for an enum that omits a trait method is rejected" in {
      err(
        """trait Show
          |    show(self) -> int
          |    label(self) -> int
          |enum Color
          |    Red
          |impl Show for Color
          |    show(self) -> int = 1""".stripMargin
      ) should include("method 'label' is missing")
    }

    "an impl method colliding with a variant is rejected" in {
      err(
        """trait Show
          |    Red(self) -> int
          |enum Color
          |    Red
          |impl Show for Color
          |    Red(self) -> int = 1""".stripMargin
      ) should include("both a variant and a member")
    }

    "implementing a trait for a generic enum is rejected for now" in {
      err(
        """trait Show
          |    show(self) -> int
          |enum Maybe[T]
          |    Just(value: T)
          |impl Show for Maybe
          |    show(self) -> int = 1""".stripMargin
      ) should include("generic type is not supported yet")
    }

    // A trait may be implemented for any type, so what is wrong with `impl Show for Ghost` is not
    // that `Ghost` is the wrong *kind* of type — it is that there is no such type at all, which is
    // what the diagnostic says.
    "implementing a trait for an unknown type says the name is unknown" in {
      err(
        """trait Show
          |    show(self) -> int
          |impl Show for Ghost
          |    show(self) -> int = 1""".stripMargin
      ) should include("unknown type 'Ghost'")
    }

    "a built-in type may carry an impl, and its methods resolve on a value of it" in {
      ir(
        """trait Show
          |    show(self) -> string
          |impl Show for int
          |    show(self) -> string = "i"
          |print(5.show())""".stripMargin
      ) should include("define { ptr, ptr, i64 } @int.show(")
    }

    // The key is the type, not the spelling it was reached by, so two aliases of one type are one
    // implementation and the second is the duplicate it is.
    "two spellings of one built-in type are one implementation" in {
      err(
        """trait Show
          |    show(self) -> string
          |impl Show for int
          |    show(self) -> string = "a"
          |impl Show for i32
          |    show(self) -> string = "b"
          |print(1)""".stripMargin
      ) should include("already implements 'Show'")
    }

    "a type with no values, and one with only one, carry nothing" in {
      val trait_ = "trait Show\n    show(self) -> string\n"

      err(s"${trait_}impl Show for never\n    show(self) -> string = \"n\"\nprint(1)") should
        include("'never' has no values")
      err(s"${trait_}impl Show for unit\n    show(self) -> string = \"u\"\nprint(1)") should
        include("a trait for it would say nothing")
    }

    "an enum that does not implement a bound's trait is rejected at the call" in {
      err(
        """trait Show
          |    show(self) -> int
          |enum Color
          |    Red
          |render[T: Show](x: T) -> int = x.show()
          |print(render(Red))""".stripMargin
      ) should include("requires its type parameter 'T' to implement 'Show', but Color does not")
    }

    // The definition-time check of `14 §4`: the body is walked once with `T` opaque, so a method
    // it did not declare a bound for is reported against the definition that assumed it rather
    // than against whichever caller happened to supply a type without that method.
    "an unbounded generic may not call a method its parameter does not promise" in {
      err(
        """trait Show
          |    show(self) -> int
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> int = self.v
          |loose[T](x: T) -> int = x.show()
          |print(loose(P(7)))""".stripMargin
      ) should include("'show' needs 'T: Show'")
    }
  }

  /** `14 §4` — a generic body is checked once, at its definition, with each type parameter opaque
   * except for what its bounds promise. What separates this from the template model is that the
   * body is wrong on its own, so the diagnostic does not wait for a call site to expose it.
   */
  "definition-checked bounds" - {
    val show = "trait Show\n    show(self) -> int\n"

    "a generic nothing instantiates is still checked" in {
      err(s"${show}loose[T](x: T) -> int = x.show()\nprint(1)") should
        include("'show' needs 'T: Show'")
    }

    "the bound is what licenses the call" in {
      ir(
        s"""${show}struct P
           |    v: int
           |impl Show for P
           |    show(self) -> int = self.v
           |render[T: Show](x: T) -> int = x.show()
           |print(render(P(7)))""".stripMargin
      ) should include("@P.show")
    }

    "the union of several bounds is available" in {
      ir(
        s"""${show}trait Size
           |    size(self) -> int
           |struct P
           |    v: int
           |impl Show for P
           |    show(self) -> int = self.v
           |impl Size for P
           |    size(self) -> int = 1
           |both[T: Show + Size](x: T) -> int = x.show() + x.size()
           |print(both(P(7)))""".stripMargin
      ) should include("@P.size")
    }

    // Nothing else will check these: an instantiation resolves the same call against a concrete
    // implementation, so a call that disagrees with the *trait* is caught here or nowhere.
    "the call is checked against the trait's signature, not an implementation's" in {
      err(s"${show}render[T: Show](x: T) -> int = x.show(1)\nprint(1)") should
        include("method 'Show.show' takes 0 arguments, but 1 argument was given")

      err(
        """trait Scale
          |    by(self, n: int) -> int
          |render[T: Scale](x: T) -> int = x.by("a")
          |print(1)""".stripMargin
      ) should include("'n' of 'Scale.by' is int, but string was given")
    }

    "a method no trait declares names no bound to add" in {
      err("loose[T](x: T) -> int = x.nope()\nprint(1)") should
        include("no trait declares a method 'nope'")
    }

    "a method two traits declare offers both bounds" in {
      err(
        s"""${show}trait Render
           |    show(self) -> int
           |loose[T](x: T) -> int = x.show()
           |print(1)""".stripMargin
      ) should include("it is declared by 'Show', 'Render'")
    }

    // A parameter's bounds are what it can promise a callee, so forwarding one to a bounded
    // parameter needs the bound written on both.
    "an unbounded parameter cannot satisfy a callee's bound" in {
      err(
        s"""${show}render[T: Show](x: T) -> int = x.show()
           |outer[U](x: U) -> int = render(x)
           |print(1)""".stripMargin
      ) should include("requires its type parameter 'T' to implement 'Show', but 'U' is not bounded by it")
    }

    "a bound satisfies the same bound" in {
      ir(
        s"""${show}struct P
           |    v: int
           |impl Show for P
           |    show(self) -> int = self.v
           |render[T: Show](x: T) -> int = x.show()
           |outer[U: Show](x: U) -> int = render(x)
           |print(outer(P(7)))""".stripMargin
      ) should include("@P.show")
    }

    // The operations every sysl value has need no bound, so a generic that only moves its
    // parameter around stays legal with nothing declared about it.
    "moving a value around needs no bound" in {
      irMain(
        """id[T](x: T) -> T = x
          |keep[T](x: T) -> T
          |    var y = x
          |    y
          |print(id(3))
          |print(keep(4))""".stripMargin
      ) should include("@keep.int")
    }

    // The pass walks the body exactly as an ordinary one is walked, so it registers `Box[T]` on
    // the way through. A type parameter is not something anything can be laid out at, and nothing
    // at run time reaches it, so what the pass registered must not survive into the module.
    "the pass leaves no type parameter in the emitted module" in {
      val out = ir(
        """struct Box[T]
          |    value: T
          |wrap[T](x: T) -> Box[T] = Box(x)
          |var b = wrap(3)
          |print(b.value)""".stripMargin
      )

      out should include("%struct.Box.int")
      out should not include "Box.T"
    }
  }

  "members on generic types" - {
    "a method call with the wrong arity is reported" in {
      err(
        """struct Box[T]
          |    value: T
          |    plus(self, other: T) -> T = self.value + other
          |var a = Box(1)
          |print(a.plus(2, 3))""".stripMargin
      ) should include("takes 1 argument, but 2 arguments were given")
    }

    "a property on a generic type is read without parentheses" in {
      err(
        """struct Box[T]
          |    value: T
          |    doubled -> T = self.value + self.value
          |var a = Box(1)
          |print(a.doubled())""".stripMargin
      ) should include("is a property")
    }

    "a method on a generic type is called with parentheses" in {
      err(
        """struct Box[T]
          |    value: T
          |    get(self) -> T = self.value
          |var a = Box(1)
          |print(a.get)""".stripMargin
      ) should include("is a method")
    }

    "an unknown method on a generic type is reported against its type" in {
      err(
        """struct Box[T]
          |    value: T
          |var a = Box(1)
          |print(a.area())""".stripMargin
      ) should include("no method 'area'")
    }

    "a member may not share a name with a field on a generic type" in {
      err(
        """struct Box[T]
          |    value: T
          |    value(self) -> T = self.value""".stripMargin
      ) should include("both a field and a member")
    }

    // The unbounded model checks a member's body per instantiation, so a numeric operation on the
    // element is only rejected once a non-numeric element reaches it — at the call, not the
    // definition. total's type mismatch would never surface without instantiating at a string.
    "a method body that needs a numeric element is rejected at a non-numeric instantiation" in {
      err(
        """struct Box[T]
          |    value: T
          |    inc(self) -> T = self.value + 1
          |var a = Box("s")
          |print(a.inc())""".stripMargin
      ) should include("int")
    }
  }

  "traits" - {
    "an impl that omits a trait method is rejected" in {
      err(
        """trait Show
          |    show(self) -> string
          |    label(self) -> string
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = "p"""".stripMargin
      ) should include("method 'label' is missing")
    }

    "an impl method the trait does not declare is rejected" in {
      err(
        """trait Show
          |    show(self) -> string
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = "p"
          |    extra(self) -> int = 1""".stripMargin
      ) should include("declares no method 'extra'")
    }

    "an impl method whose parameter type differs from the trait is rejected" in {
      err(
        """trait Plus
          |    plus(self, x: int) -> int
          |struct P
          |    v: int
          |impl Plus for P
          |    plus(self, x: string) -> int = self.v""".stripMargin
      ) should include("parameter 'x'")
    }

    "an impl method whose result type differs from the trait is rejected" in {
      err(
        """trait Show
          |    show(self) -> string
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> int = self.v""".stripMargin
      ) should include("but trait 'Show' declares")
    }

    "an impl method whose receiver mode differs from the trait is rejected" in {
      err(
        """trait Bump
          |    bump(*self)
          |struct P
          |    v: int
          |impl Bump for P
          |    bump(self)
          |        self.v""".stripMargin
      ) should include("different receiver")
    }

    "implementing an unknown trait is rejected" in {
      err(
        """struct P
          |    v: int
          |impl Nope for P
          |    go(self) -> int = self.v""".stripMargin
      ) should include("unknown trait 'Nope'")
    }

    "implementing a trait for an unknown type is rejected" in {
      err(
        """trait Show
          |    show(self) -> string
          |impl Show for Ghost
          |    show(self) -> string = "g"""".stripMargin
      ) should include("unknown type 'Ghost'")
    }

    "two impls of the same trait for one type are rejected" in {
      err(
        """trait Show
          |    show(self) -> string
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = "a"
          |impl Show for P
          |    show(self) -> string = "b"""".stripMargin
      ) should include("already implements 'Show'")
    }

    // A trait method dispatches through a member that concretely exists; on a type with no impl
    // there is nothing to resolve to, and the diagnostic is the ordinary "no method" one.
    "calling a trait method on a type with no impl is rejected" in {
      err(
        """trait Show
          |    show(self) -> string
          |struct P
          |    v: int
          |var p = P(1)
          |print(p.show())""".stripMargin
      ) should include("has no method 'show'")
    }

    "a generic trait is rejected for now" in {
      err(
        """trait Into[T]
          |    into(self) -> T""".stripMargin
      ) should include("generic traits are not supported yet")
    }

    "implementing a trait for a generic struct is rejected for now" in {
      err(
        """trait Show
          |    show(self) -> int
          |struct Box[T]
          |    v: T
          |impl Show for Box
          |    show(self) -> int = 1""".stripMargin
      ) should include("generic type is not supported yet")
    }

    // A field and an impl method sharing a name collide the same way a field and an inherent
    // method do, since both land in the one member table.
    "an impl method colliding with a field is rejected" in {
      err(
        """trait Show
          |    v(self) -> int
          |struct P
          |    v: int
          |impl Show for P
          |    v(self) -> int = 1""".stripMargin
      ) should include("both a field and a member")
    }

    // A bound is a promise the caller must keep: the concrete type it supplies must implement the
    // trait. A struct with no such impl fails the bound at the call, naming the parameter and trait
    // rather than surfacing a missing-method error from inside the monomorphized body.
    "calling a bounded generic with a type that lacks the impl is rejected" in {
      err(
        """trait Show
          |    show(self) -> string
          |struct P
          |    v: int
          |struct Q
          |    w: int
          |impl Show for P
          |    show(self) -> string = "p"
          |render[T: Show](x: T) -> string = x.show()
          |print(render(Q(1)))""".stripMargin
      ) should include("requires its type parameter 'T' to implement 'Show', but Q does not")
    }

    // A scalar carries no impl, so it fails a trait bound the same way an unimplementing struct
    // does — there is no structural conformance to fall back on.
    "calling a bounded generic with a scalar type is rejected" in {
      err(
        """trait Show
          |    show(self) -> string
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = "p"
          |render[T: Show](x: T) -> string = x.show()
          |print(render(7))""".stripMargin
      ) should include("but int does not")
    }

    // Each bound in a multi-bound parameter is a separate requirement; supplying a type that
    // implements one but not the other fails on the missing one.
    "a multi-bound parameter rejects a type missing one of its bounds" in {
      err(
        """trait Named
          |    name(self) -> string
          |trait Aged
          |    age(self) -> int
          |struct P
          |    v: int
          |impl Named for P
          |    name(self) -> string = "p"
          |describe[T: Named + Aged](x: T) -> string = x.name()
          |print(describe(P(1)))""".stripMargin
      ) should include("to implement 'Aged', but P does not")
    }

    "a bound naming something that is not a trait is rejected" in {
      err(
        """struct Widget
          |    v: int
          |consume[T: Widget](x: T) -> int = 1""".stripMargin
      ) should include("names 'Widget', which is not a trait")
    }
  }

  "the core trait catalog" - {
    "'Self' outside a trait or an impl names nothing" in {
      err("var x: Self = 1") should include("only meaningful inside a 'trait' or an 'impl'")
    }

    // A member of a generic type has no one meaning for `Self` — it would be `Box[T]`, which waits
    // for an instantiation — so it is left unbound rather than bound to something convenient.
    "'Self' in a member of a generic type names nothing" in {
      err(
        """struct Box[T]
          |    v: T
          |    same(self) -> Self = self
          |var b = Box(1)
          |print(b.same().v)""".stripMargin
      ) should include("only meaningful inside a 'trait' or an 'impl'")
    }

    "a type may not be declared with the name 'Self'" in {
      err(
        """struct Self
          |    v: int""".stripMargin
      ) should include("already declared")
    }

    // The catalog traits are prelude declarations, so their names are taken exactly as `Option`'s
    // is — a program that wants its own addition trait picks another name.
    "a program cannot redeclare a catalog trait" in {
      err(
        """trait Ord
          |    lt(self, rhs: Self) -> bool""".stripMargin
      ) should include("already declared")
    }

    "a built-in's membership cannot be overridden by an impl" in {
      err(
        """impl Add for int
          |    add(self, rhs: Self) -> Self = self""".stripMargin
      ) should include("the compiler provides it")
    }

    // `char` has equality and ordering and no arithmetic at all (`01`), so the membership stops
    // where the operator does — and the diagnostic is the ordinary missing-method one.
    "a scalar outside a membership has no such method" in {
      err("print('a'.add('b'))") should include("type 'char' has no method 'add'")
    }

    "a bool is equatable and not ordered" in {
      err("print(true.lt(false))") should include("type 'bool' has no method 'lt'")
    }

    "a float has no remainder" in {
      err("print(2.5.rem(1.0))") should include("type 'real' has no method 'rem'")
    }

    "an unsigned integer cannot be negated" in {
      err("print(7u32.neg())") should include("type 'uint' has no method 'neg'")
    }

    // The call is checked against the trait's signature, where the argument type is `Self` — which
    // on this receiver is `int`, so a bool is the wrong thing to hand it.
    "a built-in trait method checks its argument against 'Self'" in {
      err("print(3.add(true))") should include("'rhs' of 'Add.add' is int, but bool was given")
    }

    "a built-in trait method checks its arity" in {
      err("print(3.add(1, 2))") should include("method 'Add.add' takes 1 argument")
    }

    "a type outside the bound's membership is rejected at the call" in {
      err(
        """sum[T: Add](a: T, b: T) -> T = a.add(b)
          |print(sum(true, false))""".stripMargin
      ) should include("requires its type parameter 'T' to implement 'Add', but bool does not")
    }

    // The payoff of the definition-time pass over the catalog: the body is wrong on its own line,
    // and the diagnostic names the bound that would license it.
    "an unbounded parameter cannot call a catalog method" in {
      err("sum[T](a: T, b: T) -> T = a.add(b)") should include("'add' needs 'T: Add'")
    }

    "a bound licenses only its own trait's method" in {
      err("mix[T: Add](a: T, b: T) -> bool = a.lt(b)") should include("'lt' needs 'T: Ord'")
    }

    // The whole point of definition-checked bounds, in its operator spelling: the body is wrong on
    // its own line, and the diagnostic names the bound that would make it right.
    "an operator on an unbounded parameter names the bound it needs" in {
      err("sum[T](a: T, b: T) -> T = a + b") should include("'+' needs 'T: Add'")
    }

    "an ordering operator on an unbounded parameter names 'Ord'" in {
      err("less[T](a: T, b: T) -> bool = a < b") should include("'<' needs 'T: Ord'")
    }

    "a bound licenses only its own operator" in {
      err("less[T: Eq](a: T, b: T) -> bool = a < b") should include("'<' needs 'T: Ord'")
    }

    "a prefix operator on an unbounded parameter names 'Neg'" in {
      err("flip[T](a: T) -> T = -a") should include("'-' needs 'T: Neg'")
    }

    "an equality operator on an unbounded parameter names 'Eq'" in {
      err("same[T](a: T, b: T) -> bool = a == b") should include("'==' needs 'T: Eq'")
    }

    // Every other unlicensed use names a bound that would allow it. This one names none — nothing
    // declares a property `v`, and a *field* is layout, which no bound reaches. So it is settled at
    // the definition outright rather than deferred to whatever types turn up (`10 §5`).
    "a field read off a type parameter is refused outright, with no bound to suggest" in {
      val out = err("first[T](x: T) -> int = x.v")

      out should include("has no fields to read")
      out should include("no trait declares a property 'v'")
    }

    "a field read is refused even where every instantiation would have had the field" in {
      err(
        """struct P
          |    v: int
          |first[T](x: T) -> int = x.v
          |print(first(P(7)))""".stripMargin
      ) should include("has no fields to read")
    }

    "a chained comparison still needs its operands to agree" in {
      err(
        """struct M
          |    v: int
          |impl Ord for M
          |    lt(self, rhs: Self) -> bool = self.v < rhs.v
          |var a = M(1)
          |print(a < a < 3)""".stripMargin
      ) should include("'<' needs matching types, got M and int")
    }

    "compound assignment still needs the right operand to agree" in {
      err(
        """struct M
          |    v: int
          |impl Add for M
          |    add(self, rhs: Self) -> Self = M(self.v + rhs.v)
          |var a = M(1)
          |a += 2""".stripMargin
      ) should include("'+' needs matching types, got M and int")
    }

    "an operator on a user type with no impl is not defined" in {
      err(
        """struct M
          |    v: int
          |var a = M(1)
          |var b = a + a""".stripMargin
      ) should include("'+' is not defined for M")
    }

    "an operator's operands must be the same type" in {
      err(
        """struct M
          |    v: int
          |impl Add for M
          |    add(self, rhs: Self) -> Self = M(self.v + rhs.v)
          |var a = M(1) + 2""".stripMargin
      ) should include("'+' needs matching types")
    }

    // Member lookup finds an inherent member before it asks about a membership, so an `impl` of
    // some other trait could otherwise take `5.add` over from the `Add` `int` already implements.
    "a member of a built-in may not hide one of its catalog methods" in {
      err(
        """trait Tally
          |    add(self, k: int) -> int
          |impl Tally for int
          |    add(self, k: int) -> int = self + k""".stripMargin
      ) should include("a member of this name would hide it")
    }

    // The trait writes `Self` and the impl writes a type that is not the implementing one, so the
    // two signatures differ — which is the check `Self` exists to make possible.
    "an impl whose result differs from the trait's 'Self' is rejected" in {
      err(
        """trait Doubler
          |    twice(self) -> Self
          |struct M
          |    v: int
          |impl Doubler for M
          |    twice(self) -> int = self.v""".stripMargin
      ) should include("but trait 'Doubler' declares")
    }
  }
}
