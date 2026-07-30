# Design Decisions: Constrained Types and Contracts

**Status:** written against an implementation that already exists — derived types, range and
predicate constraints, struct invariants, and function contracts all compile and run today — and
which had no design document at all. That absence is why this chapter is being written late: two
guide programs produced findings about the feature with nowhere to record them, and one of those
findings is a rule nobody ever decided out loud. The sections marked *open* say which of these are
rulings and which are the state the code happened to reach.

The through-line: sysl has one mechanism for **narrowing what a type's values may be** and one for
**stating what a function promises**, and both are checked *at run time, where the value is
produced*. Neither is a proof system. What they buy is that a violation stops the program at the
line that caused it instead of somewhere downstream, and — the part that matters more — that a
narrowed type is a **type**, so the compiler can tell two of them apart before anything runs.

---

## 1. One declaration form, three independent parts

```
type Name = [new] Base [within lo..hi] [where predicate]
```

`new`, `within` and `where` are **contextual keywords**: ordinary identifiers everywhere else in the
language, recognised only here. A field or function may still be called `where`.

The three parts are orthogonal and each may be left out, with one exception:

| written | means |
|---|---|
| `type Meters = new f64` | a distinct type over `f64`, no constraint |
| `type Age = int within 0..150` | `int` with a range, but the *same* type as `int` |
| `type Even = int where it % 2 == 0` | `int` with a predicate |
| `type Slot = new u8 within 0..<200` | distinct **and** constrained |
| `type Alias = int` | **rejected** — a name for a type that is that type is not a declaration |

The last row is the exception. A transparent alias with no constraint declares nothing: it neither
narrows the values nor makes a new type, so writing it can only be a mistake or an attempt at a
`typedef`, and sysl does not have one. The diagnostic says so at the declaration.

**The base is a scalar** — an integer, a float, or a `char`. A struct, an enum or an array base is
rejected at the declaration: a constraint here is a check on a *value*, and the two mechanisms for
narrowing an aggregate are the struct invariant of §6 and, for an enum, having fewer variants.

**`..` is inclusive and `..<` excludes the upper bound**, matching the range expressions in `00`.
The bounds are literals — an integer, a float, or a character, optionally signed. A bound outside the
base's own range is rejected at the declaration (`type T = byte within 0..300`), as is an inverted
range and an exclusive range with nothing in it.

The predicate is an ordinary boolean expression, and the value it is about is named **`value`**:

```
type Even = int within 0..100 where value % 2 == 0
type HexDigit = char where value >= '0' && value <= '9'
```

`value` is bound only inside the predicate. The predicate may read module constants, which is what
lets a range and a table's size be stated once — as far as a predicate is concerned; a `within`
bound still may not (open **b** below). A non-boolean predicate is reported without leaking the name
of the function the compiler synthesised to hold it.

## 2. `new` is what makes it a type

Without `new` a constrained type is its base with a checked range: `Age` and `int` are the same type,
values flow between them freely, and what the declaration buys is the check.

With `new` it is a **distinct nominal type**. That is the whole of the difference, and it is a large
difference:

- Two derived types over one base do not mix. `Meters + Feet` is "needs matching types".
- A derived type does not mix with its base either. `Meters(3.0) + 1.0` is refused, and
  `var m: Meters = 3.0` is "declared Meters but the value is real".
- Going in either direction is a **written conversion**: `Meters(x)` wraps, `f64(m)` unwraps, and
  the wrap is where the constraint is checked.

The payoff is the one `guide/kernel` was written to measure. A table-driven program has several
small integers that index different things — a task number, a lock number, a priority level — and
the bug such a program actually makes is passing one where another was wanted. Three `new u8`s with
three ranges make that bug a compile error rather than a plausible-looking wrong answer. It is the
strongest argument the language has for the feature, and it is an argument about **types**, not
about checking.

The two kinds behave oppositely here, and keeping them apart is the whole of this section. A
**transparent** subtype *is* its base (§1), so reading one where a base is wanted needs no cast:
`var n: int = a` on an `Age` is one integer flowing into another. A **derived** one needs the cast in
*both* directions, and no position excuses it — an initializer, an argument and a returned value each
refuse a `Meters` where an `f64` is written, and `f64(m)` is what to write. That is the bullet above
rather than an exception to it: a derivation is a distinct type, and reading it as something else is
a conversion whichever way it goes.

## 3. A derivation inherits its base's behaviour and may replace none of it

A `new` type over a scalar arrives with everything the scalar could do — `==`, `<`, `+`, `-`, `*`,
`str` and the rest of the catalog — working at itself and producing itself. And **no `impl` may
replace or extend any of it**: `impl Add[Span] for Stamp` is refused because `add` is how `Add` is
implemented for `Stamp` and the compiler provides that, and `impl Display for Stamp` is refused
because `Stamp` already implements `Display`.

Both halves of that are deliberate, and stating the reason is most of why this section exists.

**Inheriting is right** because a derivation does not change what the values *are*. `Slot` is some of
the `u8`s; it is not a different set of things that happens to be stored in a byte. A derivation that
started with nothing would make every `new u8` cost a dozen `impl` blocks and nobody would use it,
which defeats the point of §2.

**Refusing to replace is the harder call, and it is a ruling.** If `Stamp` could redefine `<`, then
`Stamp` would be a set of `i64`s that do not order the way `i64`s order — and every fact the base
guarantees would hold only until somebody looked. A derivation is a *narrowing*, and a narrowing that
alters behaviour is not a narrowing. So the answer to "I want my own `+`" is: you do not want a
derivation, you want a **struct**.

The cost of that ruling is real and was measured. `guide/datetime` needed a moment and a length of
time with an algebra between them — `Instant + Duration -> Instant`, `Duration + Duration ->
Duration`, and `Instant + Instant` **meaningless** — and a derived scalar gets it exactly backwards:
it arrives with `Instant + Instant`, which is nonsense, and cannot be given `Instant + Duration`,
which is what was wanted. So the program uses one-field structs and writes five `impl` blocks per
type.

**The two mechanisms are therefore complements, not alternatives**, and the choice is not about
taste:

| | a `new` derivation | a one-field struct |
|---|---|---|
| distinct type | yes | yes |
| the base's catalog | free, and unchangeable | nothing, write it all |
| an operation the base does not have | impossible | ordinary |
| an operation the base has that is now nonsense | present anyway | absent |

**Use a derivation for an identity** — a slot number, a handle, a unit-tagged measurement, anything
whose operations are its representation's operations. **Use a struct for a quantity with an algebra
of its own.** What has no answer today is the case in the middle: a type that wants most of its
base's catalog and one row of its own. That is recorded as open below.

### The catalog is the base's, and the range still holds

"Everything the scalar could do" is the whole of `01`'s operator table and not a shortlist: the
arithmetic operators, the remainder, the bitwise operators and the shifts, unary `-` and `~`, the
comparisons, the compound assignments, `++` and `--`, and `str`. **A subtype narrows which values a
type has, never which operations it has** — so membership in an operator's trait is a question about
the *base*, which is how `Eq` and `Ord` were always read and how the rest are read now.

For a **transparent** subtype that means more than convenience, because §1 makes it *the same type as*
its base: `a < n` between an `Age` and an `int` is one comparison of two integers, and a literal
beside one is an ordinary base value. `s * 100` on a `Small = int within 0..10` is 200 — the multiplier
is not a `Small` and does not have to be, since what has to be in range is the product, and the
product is checked where it is stored. A **derived** subtype is its own representation, so it mixes
with the base only through the cast §2 requires, and its results are its own and checked as §4 says.

What the base does not have, the subtype does not either, and the diagnostic names the subtype: unary
`-` over an unsigned base, `~` over a float, `++` over a float or a `char`.

## 4. Where a constraint is checked

At every point a value of the constrained type is **produced**, and nowhere else. A produce site is
not a syntactic form to be listed but a consequence of what the type is: a value comes to have the
type wherever it **flows into a slot the type is written on**, plus wherever an operation of the
type's own **yields one**. So the sites are the slots, and the slots are enumerable:

- a variable's initializer, and every later assignment to it — including one arm of a
  multi-assignment, and a write through a pointer
- an argument at a call, and a function's returned value — a plain function's, a method's, a nested
  function's, or a closure's
- an explicit cast, `T(x)`
- a field written into a struct, at construction and at every later write, however the struct is
  reached
- an element of an array, at a literal and at every later write, through the array or through a view
  of it
- a part of a tuple, and the payload of an enum variant
- an item entering a **generic** container instantiated at the type — the slot is written `T` there,
  so the check follows the type *argument* and not the spelling

And the two sites that are the type's own doing rather than a slot's:

- an **operation on a derived subtype**, which §3 gives the base's catalog *producing itself* — so
  `Slot(199) + Slot(1)` is a produce site and traps, and this is what keeps §3 and this section from
  contradicting each other. A **transparent** subtype has no such site: its arithmetic happens at its
  base and yields a base value, which is checked by the store that gives it the subtype again.
- a **compound assignment** and an **increment**, which compute and store in one step and so are
  checked between the two. `a += e` produces exactly what `a = a + e` produces, and the two agree by
  construction: what the operator is applied to is a base value either way, so `t += 120` on a
  `Temp = int within -100..100` holding `-50` is `70` and not a complaint that 120 is no temperature.

The site that is closed by not existing is a declaration with no value: a constrained subtype has
**no zero value**, whether or not its range contains zero, so `var a: Age` is refused and there is no
unwritten value to check. Making that the type's rule rather than the range's means widening a range
never silently changes whether a declaration compiles somewhere else.

A value that already has the type is not re-checked when it is merely read, passed along, or copied —
it could not have got there unchecked. Passing one to a *different* subtype over the same base **is**
a produce site and is checked again, which is what keeps a wider type from leaking into a narrower
one.

Where both a range and a predicate are written, the range is checked first, so a value the range
rejects never reaches the predicate.

A violated check **traps**: the program stops. It is not an error value, it is not catchable, and
there is deliberately no `try` form that returns an `Option` — a constrained type states something
its values are, and a value that is not one of them is a bug in the code that made it, not a
condition to handle. Code that wants to *ask* whether a number is in range writes `T::Valid(x)`,
the total membership test of §5, and then the ordinary cast.

A simple enum *does* have `Color.try(n)` (`09 §2`), so `Age.try(n)` is the first thing a reader of
this section writes. It is answered by name: the message says there is no `try`, why, and both of
the forms above.

## 5. What the type's own name offers: `::` attributes

A constrained type's name is a type and not a value, so nothing is *read* from it. What it answers
are **attributes**, written with `::` rather than `.` so that they stay out of the member namespace:
`Age::First` cannot be confused with a field, a property or an associated function, and no `impl`
can shadow one by declaring a member of that name.

The set is small and closed, and every member of it is a question about integer bounds — so it is
what a `within`-ranged integer subtype offers:

| written | is | notes |
|---|---|---|
| `T::First` | the lower bound | a constant, no argument |
| `T::Last` | the upper bound | one *below* the written bound where the range is exclusive |
| `T::Valid(x)` | whether `x` is in range | a `bool`, and **total** — it never traps, which is what makes it the question form |
| `T::Succ(x)` | the next value | traps at `T::Last` |
| `T::Pred(x)` | the previous value | traps at `T::First` |
| `T::Range` | the range itself | only as a `for` loop's iterable, `First..Last` inclusive |

**`Valid` is the answer to "how do I ask instead of trapping".** §4 rules that a produce site traps
because a value outside the range is a mistake and not a condition; `Valid` is how a program that
holds a number and does not yet know puts the question, and a cast after a `Valid` that answered
true is the ordinary way in.

`Succ` and `Pred` **trap** rather than saturating or wrapping, on the same argument as the produce
sites: a step past the end is not a value of the type, so making one is the mistake, and no single
answer would be right for every caller.

`Range` is meaningful only where an iterable is, and says so anywhere else. It names nothing a
program can hold, because a range is not yet a type a program can name (`14`).

A subtype over a float or a `char`, and one written with no range, have **no** attributes — each of
these is a question about integer bounds, and there are none to ask about. The diagnostic names the
base rather than the attribute, since the base is the part that would have to change.

## 6. Struct invariants

A struct body may carry `invariant <bool>` clauses among its fields. The clause is an expression over
the struct's own fields, in scope by name:

```
struct Window
    lo: int
    hi: int
    invariant lo <= hi
    invariant hi - lo < 4096
```

Several clauses all have to hold, and each is checked independently so the diagnostic names the one
that failed. An invariant may read a module constant.

`invariant` is contextual here too, and the grammar disambiguates by shape: a member declaration has
a parameter list or a `->`, an invariant clause is the word followed by an expression, and anything
else is a field — so a field may still be named `invariant`, since `invariant: int` matches neither
of the first two.

**Checked at every write, not only at construction.** That is the part worth stating, because the
cheap implementation checks the constructor and calls it done:

- constructing the struct, including one built directly as an argument or as a returned value
- assigning a whole struct over an existing one
- **assigning a single field**, including a compound assignment (`w.hi += 1`) and an increment
  (`w.hi++`), which is a write of one field and owed the same re-check
- a field written through a pointer
- a field written into an array element

The consequence, and it is the intended one: a sequence of writes that ends in a valid state but
passes through an invalid one **traps at the step that broke it**. There is no "I am mid-update"
mode. A struct that cannot be updated one field at a time has to be updated as a whole, which is
what whole-struct assignment is for.

Invariants on a **generic** struct are not supported and say so.

## 7. Contracts on a function

```
half(x: int) -> int
    require x >= 0
    ensure result >= 0
    x / 2
```

**Both kinds form one block at the top of the body.** A clause of either kind after an ordinary
statement is rejected — a precondition that runs after some of the work is not a precondition, and a
postcondition is *written* with them because that is where a reader looks for what the function
promises, not because that is when it runs. Clauses may not be nested inside an inner block either.

`require` is checked on entry. `ensure` is checked **before every return**, including early ones — an
early `return` that violates the postcondition traps exactly as the fall-through path would. Both
take an optional message: `require x >= 0, "a half of a negative is not what this means"`.

Contracts work the same on a **method**, including one with a `*self` receiver, where they are at
their most useful: `ensure result > old(self.n)` on a mutating method says the thing the method is
for.

Two names exist only inside a contract:

- **`result`** is the value being returned, available in an `ensure` only. It is rejected in a
  `require` (there is no value yet), in an ordinary statement, and in the `ensure` of a function that
  returns nothing. A local actually named `result` shadows it, which is the ordinary scoping rule
  and not a special case.
- **`old(expr)`** is `expr` evaluated **on entry**, available in an `ensure` only. It takes exactly
  one argument. Several `old` snapshots in one function are independent of each other. This is what
  lets a postcondition talk about what a mutating function *changed* rather than only about what it
  left behind.

Like a constraint, a violated contract traps. Contracts are checked in every build; there is no
"release mode drops them" switch, and adding one would make a program's meaning depend on how it was
compiled.

## 8. What this is not

It is not verification. Nothing here is proved at compile time: a `require` is a branch and a trap, an
invariant is a call to a synthesised predicate, a `within` is two comparisons. The compiler will not
tell you that a call site cannot satisfy a precondition, and it will not eliminate a check it could
have proved redundant.

That is a scope decision rather than a limitation to be fixed later. Static checking of these
conditions is a different project with a different shape — it needs a specification language, a
notion of framing, and an answer for what happens when the prover times out — and a runtime-checked
version is useful on its own terms and is what the language ships. What the runtime version buys is
**the failure lands where the mistake is**, and, for `new` types, a compile-time guarantee that has
nothing to do with the checking at all.

## Open (not yet decided)

**a. A derivation that adds one operation.** §3 rules that a derivation may not *replace* inherited
behaviour, and the reason given — a narrowing that changes behaviour is not a narrowing — does not
obviously extend to *adding* an operation the base does not have. `impl Add[Duration] for Instant`
where `Instant = new i64` takes something the base could never have taken and produces the base's own
answer; no guarantee of `i64`'s is touched by it. It is refused today because the check is on the
method *name*, and `add` is taken. Whether the rule should be "no member may shadow an inherited one"
rather than "no member at all" is the open question, and it is the difference between the middle
column of §3's table being empty and being usable. Decide with `14 §7`.

**~~b. A `within` bound may not name a `const`.~~** **Closed — it may.** A bound is a **constant
expression**, folded through the same `fold` an array bound and an enum discriminant go through, so the
three positions accept the same expressions and cannot drift apart: `within 0..<max_tasks` beside
`[max_tasks]Task` is one fact written once, and `within lo..hi - 3` is ordinary. Nothing about the rest
of the chapter moved — the range is still checked at every produce site §4 names, and the ordering check
runs on the folded values, so two constants in the wrong order are refused exactly as two literals are.

What kept it at a literal was a **grammar** ambiguity rather than a decision, which is why this was
implementation work: `rangeExpr` is built out of `bitOr`, so a bound parsed at any looser level would
read `0..<max_tasks` as a *range expression* and swallow the operator separating the two bounds. Naming
`bitOr` — the level immediately tighter than a range — is the whole of the fix.

What a bound may still not be is **non-constant**, and the two refusals differ because the mistakes do:
a name that denotes no constant is reported as not being one (a module-level `val` is read-only storage
with an address, not a constant, and is refused here), while a constant of the wrong *kind* is reported
against the base — `needs integer bounds`, not `needs integer-literal bounds`, since a bound need not be
a literal at all.

**c. Invariants on a generic struct.** Refused with a clear message. The question is what a clause
over a field of type `T` could even mean when nothing about `T` is known — most useful invariants
compare, which needs a bound, and a bound the *struct* carries is inherited by every member (`10`),
so the machinery is there. Nobody has needed it.

**d. Whether a constrained type should narrow anything statically.** A `u8 within 0..<200` is known
to fit in a `u8`, so `int(slot)` is a widening the compiler could see is total. Nothing exploits
that today. Related: two subtypes over one base where one range contains the other could convert
without a check, and does not.

**~~e. A produce site the checking does not reach.~~** **Closed.** The derivation is now written into
§4 — a produce site is a slot the type is written on, or an operation of the type's own — and every
site it names is covered twice over in `SubtypeProduceSiteTests`, once with a value the range accepts
and once with the neighbouring value it does not, since a site with no check passes the first.

Deriving it rather than listing it found what the list had missed, and it was not a slot: an
**operation on a derived subtype**. §3 gives a derivation the base's catalog producing itself, which
made every arithmetic operator a produce site nobody had written down, so `Slot(199) + Slot(1)` was a
`Slot` holding 200 — and storing it into another `Slot` did not re-check, because a value that
already has the type is not produced again. `type Slot = new u8 within 0..<200` is §1's own example
row. The two forms that compute and store in one step, `a += e` and `a++`, were the other omission.
