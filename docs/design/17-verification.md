# Design Decisions: Verification

`16` builds a language of conditions — `require`, `ensure`, `invariant`, `within` — and then says, in
its §8, that none of it is verification: *"Nothing here is proved at compile time."* That was a scope
decision and it is now spent. This chapter is the other half. It adds the vocabulary a specification
needs that an executable condition does not supply on its own — quantifiers, loop invariants,
termination witnesses, a frame, and state that exists only to be reasoned about — and it says how a
prover reads the result.

The chapter is written against `16` rather than beside it. Everything `16` decided still holds; in
particular a contract still traps, still runs in every build, and still has no switch that turns it
off. What changes is that a second reader now exists for the same clause.

## 1. One clause, two readers

**A clause means one thing. The prover and the running program read the same sentence, and the
language does not offer a way for them to disagree.**

This is the load-bearing decision of the chapter and every other one falls out of it. Three things it
rules out, each of which some other language takes:

- **It is not a separate specification language.** SPARK writes its contracts in a subset of Ada that
  the compiler does not execute; a `Post` aspect is discharged or it is nothing. Here `ensure result
  >= 0` is a branch and a trap first, and a proof obligation second, and it was already the first of
  those before this chapter existed.
- **There is no proof-only build and no checked-only build.** `16 §7` already refused a release mode
  that drops contracts, on the grounds that it would make a program's meaning depend on how it was
  compiled. A proof mode that *added* clauses would be the same mistake from the other end.
- **Proving a check redundant does not remove it.** If the prover discharges every path into
  `half(-1)`, `half`'s `require x >= 0` is still compiled, still branches, and still traps. The
  compiler does not have a way to know that the prover it was not run with would have succeeded, and
  a program whose emitted code depends on whether a prover was available — and on how much time it
  was given, since a prover that times out proves nothing — is a program nobody can reason about.
  Elimination is a real optimization and it is refused here for the same reason the release-mode
  switch was.

The one place a clause is *not* executed is `§8`'s ghost state, and the point of that section is that
the exception is legible in the source rather than in a flag.

## 2. `for all` and `for some`

A quantifier over an integer range, universal and existential:

```
for all i in 0..<n do a[i] > 0
for some k in 0..<n do a[k] == target
```

It is an ordinary `bool`-typed expression — a `require`, an `ensure`, a loop `invariant`, an `if`
condition, the right side of a binding — and not a contract construct that happens to be usable
elsewhere. There is nothing to be gained by restricting it: it computes a boolean from a range and a
predicate, which is a thing programs want outside contracts too, and a rule confining it to clauses
would have to be remembered by every reader for no benefit.

**It sits at the top of the expression grammar, where a closure literal sits, and for the same
reason**: its body extends as far to the right as an expression can, so it cannot also be an operand
of something looser. `for all i in r do P(i) && Q(i)` quantifies over the conjunction. Written the
other way round — a quantifier as the second arm of a chain — it is parenthesized, `ok && (for all i
in r do P(i))`, exactly as a closure in that position would be.

**Spelling.** `all` and `some` are contextual and stay ordinary identifiers everywhere else, which
costs the program's namespace nothing. The separator is `do`, the word a `for` loop already uses to
introduce what it does with each element — so the form is read exactly as the loop above it is, and
the language spends no token on it. The alternative was Ada's `=>`, which is not in sysl's operator
set and would have had to be added for this alone.

**Telling it from a loop takes one token.** A quantifier is `for` `all`/`some` `name` `in`; a loop is
`for` `name` `in`. So `for all in 0..<n do …` is still a loop over a variable named `all`, and
nothing a program already writes changes meaning.

**The rules.**

- The bound name is visible only inside the predicate and shadows an outer name of the same spelling,
  as any other binding does.
- The bounds are integral, evaluated once, in the range forms `00` already has: `..<` excludes the
  upper bound, `..` includes it.
- **An empty range makes `for all` true and `for some` false.** These are the identities of the two
  operations, not a convention — a conjunction over nothing is true and a disjunction over nothing is
  false — and getting them the other way round breaks every proof that reasons about the first
  iteration of anything.
- Both short-circuit: `for all` stops at the first counterexample, `for some` at the first witness.
  This is observable, because a predicate may trap, so it is specified rather than left to the
  emitter.

**A quantifier is a counted loop and it costs what one costs.** `for all i in 0..<n do a[i] > 0`
written in a `require` is `n` comparisons on every call. That is the same trade `require` already
made — a condition is what it says it is — and where it is the wrong trade the answer is `§8`, not a
compiler flag.

## 3. `invariant` and `variant` on a loop

Both are written as the leading statements of a loop's body, in the position and with the shape
`require` and `ensure` take at the top of a function:

```
var i = 0
var sum = 0
while i < n
    invariant i >= 0 && i <= n
    variant n - i
    sum += a[i]
    i += 1
```

Writing them as statements rather than as clauses in each loop's header is what lets one rule serve
all five loop forms — `while`, `do while`, `loop`, `for … in`, and the three-clause `for` — instead
of five headers each growing the same two slots. A clause after an ordinary statement, or in a block
that is not a loop body, is refused with the sentence `16 §7` gives the function-level case: a
condition that runs after some of the work is not an invariant.

**`invariant` is checked on every entry to the body**, which is on arrival at the loop and again
before each subsequent iteration. It is not checked on the way out. That is where the clause is
written and it is what a reader of the line expects; a clause that also ran on exit would be checking
something the loop is no longer doing.

**`variant` is a `variant` in the ordinary sense: an integer expression that must strictly decrease.**
The check is real and it runs: the value is taken at the top of each iteration and compared against
the previous iteration's, and a run that does not decrease traps. So a `variant` on a loop is a live
termination check, not only a proof obligation — an infinite loop with a `variant` stops at the
iteration that failed to make progress, and says which expression failed to decrease.

The expression is not required to be non-negative. A strictly decreasing integer that is unbounded
below still fails to prove termination, and that failure belongs to the prover, which will report it;
making it a runtime trap would refuse programs that terminate for a reason the clause does not
capture.

## 4. `variant` on a function

The same word on a function's contract block declares what decreases at a recursive call:

```
gcd(a: int, b: int) -> int
    require b >= 0
    variant b
    if b == 0 then a
    else gcd(b, a % b)
```

**A function's `variant` may read only its parameters.** That restriction is what makes the check
local, and it is worth the sentence it costs. At a direct self-call the compiler knows both the
current arguments and the ones about to be passed, so it can evaluate the variant expression twice —
once as written, once with the parameters bound to the call's arguments — and trap when the second is
not less than the first. No hidden parameter is threaded through, no state is kept between calls,
and a recursion that stops making progress traps at the call that failed to, naming the expression.

A variant that could read module state would have neither property: the "next" value would not be
computable at the call site, and the check would need a snapshot travelling with the call. The
restriction also matches what a variant is *for* — a well-founded measure on the argument that gets
smaller — so it costs nothing anyone wanted.

**The check reaches direct self-calls only.** Mutual recursion between two functions with variants is
a proof obligation the backend of `§9` discharges and is not checked at runtime; there is no call
site at which both halves of the measure are in hand. That is a real gap and it is stated rather than
papered over.

**A `variant` does not interact with the tail-call rule the way `ensure` does.** `16 §7` explains
that a function with an `ensure` is not tail-call transformed, because a postcondition is checked
when a call returns and a tail call never returns. A variant's check happens *before* the call, so
it survives the jump intact and a `@tailrec` function may carry one.

## 5. Module invariants

**This section and `§7` are specified and not built, and they wait on the same thing: `13 § Why
there is no module-level var` — sysl has no mutable module state for either of them to be about.** A
top-level `var` is a local of the entry point that no function can see, a module-level `val` is
written once, and `13` records the spelling as open with three candidate forms and one named
customer (`guide/slab`'s arena). An invariant over a module's variables and a frame naming which of
them a function touches are both predicates over a set that is empty today, so building either would
be building a check with no subject. They are written out here so that the design lands with the
decision rather than after it — when `13`'s word is chosen, this is what attaches to it. `§ Open f`
carries the dependency.

An `invariant` written at the top level of a file is a predicate over the module's own state:

```
module counter

var count: int = 0
var limit: int = 100

invariant count >= 0 && count <= limit
```

No new word: this is the word `16 §6` already gives a struct, meaning the same thing one level out. A
struct invariant is a property of a struct's fields that every operation on the struct preserves; a
module invariant is a property of a module's variables that every operation on the module preserves.
Several are conjoined.

**Where it is checked is the whole of the design.** A predicate over mutable module state cannot be
checked continuously — it is false in the middle of any function that updates two variables together,
which is exactly the case worth having it for. So it is checked at the module's **surface**: on
return from every public function that writes any variable the invariant reads.

That definition earns each of its three qualifications:

- **Public**, because a private function is an implementation step and may leave the module between
  states; its callers inside the module are what restore the invariant, and one of them is public.
- **That writes**, because a function that only reads cannot have broken it, and checking there would
  be pure cost.
- **Any variable the invariant reads**, because a public function that writes some unrelated module
  variable has not touched the predicate. The clause names what it reads, which is statically
  knowable — the same fact `16 §6`'s aliasing rule leans on.

It is not checked on *entry*. A public function may be called from outside, where the invariant
holds by induction, or from inside, where it may not, and a check on entry would trap on the second
for a state the module itself created. The prover, which reasons about the two call kinds separately,
assumes it on entry to a public function; the runtime cannot tell them apart and does not try.

**This is a real check, not a specification-only clause.** Old sysl made `module_invariant` emit no
code in any backend, so it said nothing until a prover was run. Here it costs one predicate call at
each public write-bearing exit, and it catches the mistake without a prover — which is the shape
every other condition in `16` already has.

## 6. `@pure`

An annotation on a function, checked by the compiler, refused on violation:

```
@pure
square(x: int) -> int = x * x
```

**What a pure function may do:** read its parameters and any `const` or `val`; declare and mutate its
own locals; call other pure functions; recurse; use every control-flow form; trap.

**What it may not do:** call a function that is not pure, including any `extern`; write through a
`*T`, into a `&T`'s field, or into any storage it did not create; perform I/O; contain an `asm`
block; call through a value rather than a name, or dispatch through a trait object.

The one ban this list does *not* carry is the one every other language's purity check leads with —
no writing a global — and its absence is `§5`'s again rather than a decision: there is nothing to
write. It arrives with `13`'s module state and needs no further thought when it does, since a write
to a module variable is a write to storage the function did not create, which the list already says.

The last one is the rule the others rest on. Purity is a property of a *named* callee that the
compiler can look up. A call through a `&Fn` or a trait object names nothing at compile time, so
there is no declaration to consult, and a pure function may not make one. `§ Open a` records what it
would take to lift that.

**A pure function may allocate, and this is a deliberate departure from old sysl, which banned it.**
Purity is about what a *caller* can observe, and a caller cannot observe an object that did not exist
when the call began: a freshly allocated value is either dropped before the function returns or is
the function's result. Banning allocation would put every string operation out of reach and leave
`@pure` useful for arithmetic and nothing else. The question allocation raises is a real one and sysl
already has a better mechanism for it — `13 §4`'s `no alloc` clause, which answers it for a whole
module and is checked at the point of allocation. Two annotations for two questions beats one
annotation answering a question nobody asked it.

**A pure function may trap.** Termination is the one effect a caller can observe that is not worth
the cost of excluding: every arithmetic operation on a constrained type can trap, so a purity that
excluded trapping would exclude `16`'s types wholesale. This is Ada's policy for `pragma Assert` and
it is taken for Ada's reason.

**Purity is not inferred.** A function is pure because it says so. Inference would make an unrelated
edit to a leaf function break a caller three levels up with no annotation anywhere naming the
promise that was broken, which is the failure mode that makes inferred effect systems unpleasant to
live with. The annotation is where the promise is written down and where the error is reported.

**Nothing in the library is annotated yet, so a pure function today reaches only the language and
other pure functions the program wrote.** That is adoption from the leaves up working exactly as
`§7` describes it, and it is not a defect — but it does mean the annotation is narrower in practice
than the list above suggests, until somebody works up `lib/sysl/math`. `§ Open h` records what makes
that less mechanical than it looks.

## 7. `@reads` and `@writes`

**Specified and not built, for `§5`'s reason and by the same dependency** — a frame names module-level
mutable variables and sysl has none. The one thing that comes close is an `extern` variable (`12 §1`),
which really is module-level storage a function can reach; a frame over `errno` and `optind` alone
would be a real feature and a very small one, and it would not be the feature this section describes,
because what a prover needs a frame *for* is reasoning about the module's own state across a call.
So this waits with `§5`.

The looser sibling: a function that is not pure, but that says which module-level variables it
touches.

```
var buffer: [256]u8
var pos = 0

@reads(buffer)
@writes(pos)
write_byte(b: u8)
    buffer[pos] = b
    pos += 1
```

This is SPARK's `Global` aspect with its three modes collapsed to two, and it exists because a prover
cannot reason about a call without it. Given `f()` with no frame, the weakest precondition of
anything after the call is `true` — every module variable might have changed. The annotation is what
makes a call something other than an eraser.

**Three rules, and the compiler enforces all three.**

1. **Body conformance.** In a function declaring `@reads(R)` and `@writes(W)`: every read of a module
   variable `V` requires `V ∈ R ∪ W`; every write requires `V ∈ W`. Reads inside the function's own
   contract clauses count as reads, since they run.
2. **Call-site subset.** Calling a function declaring `@reads(R')` `@writes(W')` requires `R' ⊆ R ∪ W`
   and `W' ⊆ W`. The offending name is what the diagnostic reports, not the fact of the mismatch.
3. **Strict closure.** An annotated function may call only annotated or `@pure` functions. It may not
   call through a value, dispatch through a trait object, or contain an `asm` block.

`W` is included in the read set of rule 1 on purpose: `count += 1` is a read and a write of `count`,
and a form this common should not require both annotations to say one thing. SPARK's `Output` versus
`In_Out` distinction — which would catch a variable declared written and then read before it is
written — is not modelled, and `§ Open b` says what it would buy.

**`@pure` is `@reads() @writes()` plus §6's further bans**, and writing them together is refused as
saying one thing twice.

**Adoption is from the leaves up**, which is why the unannotated case is unchanged rather than
defaulted to anything. A function with no annotation has effects nobody has written down; it may call
and be called by anything, exactly as today. The first leaf that gets a frame forces its annotated
callers to gain one, and the discipline climbs at whatever pace its author sets.

**Only a function declaration carries a frame.** A function *type*, a trait member, and a closure do
not, which means an annotated function cannot take a callback at all — that is rule 3, and it is the
sharpest limitation in this chapter. `§ Open a` holds the design for lifting it; it is deferred rather
than unconsidered, because effects on a function type change what type equality means and that is a
larger question than the one this chapter is answering.

## 8. `@ghost`, and the one thing that does not run

Everything above executes. That is `§1`, and it has a cost that shows up in exactly one place: a
specification is often asymptotically more expensive than the code it specifies. `invariant for all
j in 0..<i do a[j] <= a[j+1]` is the right invariant for an insertion sort's outer loop, it is O(n)
where the loop body is O(n), and checking it on every iteration turns an O(n²) sort into O(n³).

**`@ghost` is the answer, and what it marks is state and code that exists for the specification
alone.** A ghost declaration is erased before codegen and costs nothing at runtime.

```
@ghost
is_sorted(a: []int, n: int) -> bool = for all i in 0..<n - 1 do a[i] <= a[i + 1]

@ghost
var pushes: int = 0
```

Three rules give it a meaning that does not break `§1`:

1. **Executable code may not read or write ghost state**, and may not call a ghost function. If it
   could, erasing the declaration would change what the program computes.
2. **A ghost place may be assigned from executable code**, and that assignment is itself ghost and is
   erased with the rest. This is how a ghost counter tracks a real one. The right-hand side may read
   real state; nothing flows the other way.
3. **A clause that mentions a ghost name is a clause that does not run.** It is a proof obligation and
   nothing else.

**Of the two declarations `@ghost` may mark, the function is built and the variable is not.** Rules 1
and 3 hold as written for a ghost *function*: it is called from a clause or from other ghost code and
from nowhere else, it is not emitted, and a clause that calls one is not laid down. Rule 2 is entirely
about a ghost *variable*, which is `§ Open i`. The function is the half that carries the motivating
case — `is_sorted` in a loop invariant, which is where the asymptotic cost is — and the variable is
the half that lets a specification talk about history.

Rule 3 is the exception to `§1`, and the reason it is an acceptable one is that **it is visible in the
source**. A reader asking whether a clause executes reads the names in it; they do not consult a build
flag, a command line, or a compilation mode. The distinction sysl refuses to make is the one a
*switch* makes, where one program has two meanings. Here two clauses have two meanings and each says
which it is by what it mentions.

The rule is also forced rather than chosen. Given rule 1, a clause mentioning ghost state cannot be
executed — the state is not there — so the only question was whether to allow such clauses at all,
and refusing them would leave `@ghost` marking things nothing may mention.

**A ghost function's body is ordinary code** and may read real state freely; that is the whole point
of `is_sorted` above. What it may not do is write real state, which would be an erased write.

## 9. The proof backend

`sysl prove <file>` translates a module to **WhyML**, the input language of the Why3 platform, and
discharges the resulting goals with whichever provers Why3 is configured with. Why3 was chosen over
emitting SMT-LIB directly because the goals a program generates are not one prover's shape: Why3
splits a verification condition into goals, transforms them, and tries several provers on each, and
reproducing that is a project rather than a backend.

**What is translated is the scalar fragment**: functions whose parameters, locals and result are
integers and booleans; arithmetic and comparison; `if`; `while`; local variables and assignment;
`require`, `ensure`, `result`, `old`, both quantifiers, loop `invariant` and `variant`, function
`variant`, and `@ghost` declarations. Anything else is refused by name — *"the proof backend does not
translate X"* — so that a gap in the translator reads as a gap in the translator, and not as a program
the prover disliked. Arrays are the big absence and are `§ Open j`.

**WhyML separates terms from programs, and `@ghost` is what decides which one a function lands in.**
A term is mathematics and may appear in a `requires`; a program has state and may not. So a `@ghost`
function becomes a `predicate` (or a `function`, where it answers something other than a `bool`) and
every other function becomes a `let`, and a contract that calls a non-ghost function is refused with
the sentence that says to mark it.

That rule replaced a first cut that asked whether a body *could* be read as a term — one expression,
nothing declared — and the way it was wrong is the part worth keeping. It pulled ordinary code into
the term world, and a term keeps plain arithmetic, so `gcd` written as one expression was proved
against unbounded integers while the same function written with a local got its overflow obligations.
**Two spellings of one function, two models.** The mark decides now, which is what `§8` was already
saying: a specification is what `@ghost` marks, and mathematics is what a specification is written in.

**A `@ghost` function that answers `bool` is a `predicate` and not a `bool`-valued function**, and the
case that forces it is the one this chapter cares most about: `forall i. …` is a *formula*, which has
no type at all, so `let function small (n: int) : bool = forall …` is a syntax error rather than a
translation that proves something else. `predicate` is the form whose body is a formula.

**Integer overflow is a proof obligation, and this is the decision with the most consequence in the
section.** `01` defines sysl's plain integer arithmetic to wrap. WhyML's `int` is the mathematical
integers, which do not. Translating `a + b` to `a + b` would therefore prove theorems about a
language sysl is not — and the failure would be silent, which is the worst kind.

So each operation goes through a checked wrapper whose precondition is that the true result is
representable:

```
let add64 (a b: int) : int
  requires { min64 <= a + b <= max64 }
  ensures  { result = a + b }
```

A program that stays in range gets the mathematical model, which is exact for it, and a program that
might not gets a failed goal naming the operation. `--overflow ignore` drops the preconditions for
someone who wants to reason about the rest of a function first; it is off by default, because the
honest reading of "this program is proved" should not quietly exclude the most common way integer
code is wrong.

**Every integer parameter carries its own range as a precondition, and this is not an extra demand on
the caller** — it is the fact that the argument had the type it was declared with. Without it, WhyML's
unbounded `int` makes even `half(x) = x / 2` with `x >= 0` fail to prove that its own division stays
in range, which is not a fact about `half`. The result carries the same range as a postcondition,
which is what a *call* needs. Both go when `--overflow ignore` does, and have to: keeping the ranges
while dropping the obligations would leave a function promising a result nothing makes it stay inside.

**A term keeps plain arithmetic.** A term has nowhere to discharge an obligation, and the
specification is the mathematics the code is measured against rather than a second account of what the
machine does — `ensure result == old(n) * 2` says what doubling means.

**Module invariants** become a `predicate` over the module's variables, with `requires` and `ensures`
attached to every public function — which is where `§5`'s "assumed on entry, established on exit"
becomes something a prover can use, and where it does more than the runtime check can. This arrives
with `§5` and not before.

**What the backend can be honest about today is therefore the function-local scalar fragment**, and
that is less of a consolation than it sounds: `gcd`'s termination and its postcondition, a counting
loop's invariant, a division's divisor, and every arithmetic operation's range are all discharged, and
each of them is a thing that goes wrong in real code. The frame condition is what a *call* needs, and
a translated call into a function whose body is also translated needs no frame — the translation has
the body.

**A proof is not a build.** `sysl prove` neither emits code nor changes what `sysl build` emits, per
`§1`. A module that fails to prove still compiles and still runs, with every check `16` and this
chapter describe. What the prover buys is finding out before the program runs, rather than at the
trap.

## 10. What this is not

It is not a proof of the compiler, and it is not a proof of anything the translator does not
translate. A discharged goal says the source says what it says under the model of `§9` — integers
that wrap only where the obligations allow, no aliasing but what the program writes, a heap the
translation does not model at all. Pointer programs, concurrency, and the ARC runtime are outside it,
and a `@pure` function over integers is the shape this backend is honest about.

It is also not a replacement for the checks. Every clause still runs. Verification here is a way of
finding out that a clause can fail without waiting for it to.

## Open (not yet decided)

**a. Effects on a function type, a trait member, and a closure.** `§7`'s rule 3 refuses an indirect
call from an annotated function, which means an annotated function cannot take a callback — a real
restriction, and the one most likely to be hit. Lifting it means a function type carries an effect
signature (`(int) -> bool @pure`), which makes effects part of type equality, which makes them part
of generic unification, which needs a lattice and a least-upper-bound rule where a type variable is
observed at two effect signatures. Old sysl built all of that and the design is worth reading before
this is started. Deferred because the question it answers — how does a combinator library type its
callbacks — is not the question `§7` was added for, and because binding effects into type equality is
hard to take back.

**b. `Output` versus `In_Out`.** `§7` folds them together, so a variable declared `@writes` may be
read before it is written. SPARK separates them and catches a real class of mistake — reading a
variable this call has not yet given a value. It needs flow analysis rather than a set membership
test, which is why it is not in the first cut.

**c. Whether a proved obligation may be recorded.** `§1` refuses to *remove* a check that was proved
redundant, and the reason is sound. It does not obviously refuse *recording* the proof — a build
artifact naming which obligations were discharged, against which source, so a later build can say
that a file whose hash has not changed was proved on some date. That is a provenance question, not a
codegen one, and it is the shape `15 §6`'s incremental build would want.

**d. Ghost code and the capability clauses.** A ghost function is erased, so a ghost function that
allocates allocates nothing in the emitted program. Whether `no alloc` should therefore ignore it is
undecided, and the two answers are defensible: ignoring it is what erasure implies, and refusing it
keeps one rule about what a module's source may contain.

**j. Arrays, and therefore most of what a specification wants to say.** The fragment `§9` translates
is scalars, so `is_sorted(a, n)` — the example `§8` is written around — is refused at the signature.
Modelling a sysl slice needs a map theory, a length, and an answer for aliasing between two views of
one buffer, and the last of those is the one that makes it a project: `05`'s escape analysis knows
what a view roots at, and whether that is enough to give the prover a frame is not obvious. It is the
single largest thing between this backend and being useful on the programs `guide/` holds.

**i. A ghost *variable*, and therefore `§8`'s rule 2.** A ghost function needs no new position for the
annotation — `@ghost` sits above a declaration exactly as `@test` does — and a ghost local needs
`@ghost` above a `var`, which is a place the grammar does not currently admit an annotation. Beyond
the spelling, the erasure is wider: a declaration to drop, every assignment into it to drop, and a
read from executable code to refuse. What it buys is a specification that can talk about *history* —
how many times something happened, what the input looked like before the loop started — which no
predicate over the current state can say. A module-level ghost variable additionally waits on
`§ Open f` like everything else module-scoped.

**h. Annotating the library, and what a generic function's purity even means.** `pow[T: Mul]` is pure
at `int`, where `Mul` is an instruction, and its purity at some other `T` is a question about that
`T`'s `mul` — so `@pure` on a generic declaration is either a promise about every instantiation
(which needs the bound to carry it) or a claim checked per instantiation (which reports a mistake at
whoever wrote the call rather than at whoever wrote the function). Neither is obviously right, and it
is why `lib/sysl/math`'s generic functions are unannotated rather than annotated in passing. Related
to `§ Open a`, which is the same question asked of a callback.

**g. A `variant` on a nested function.** `§4`'s check is made at the call, and the two callable forms
that are not top-level functions are not reached by a call of that shape: a closure goes through `Fn`,
so nothing at the call site says which body, and a nested function's calls carry its captured
environment as a receiver. The closure half is a real exclusion and is likely to stay one — there is
nothing at the site to check *against*. The nested half is only unbuilt: `12 §5a` gives a nested
function a name and recursion, which is exactly what this clause is for, and what it needs is for the
environment argument to be skipped when the measure's parameters are matched to the call's arguments.
Both are refused today with one message that names both.

**f. `§5` and `§7` wait on `13`'s module-state spelling, and that is the biggest single dependency in
this chapter.** Both are predicates over a set the language cannot yet name. It is worth saying which
way the dependency runs: this chapter does not need a *particular* spelling, only that one exists —
whichever of `13`'s three candidates is taken, an invariant reads the names and a frame lists them.
So nothing here should be used as an argument for one form over another. What this chapter does add
to `13`'s case is a second customer beside `guide/slab`: an allocator wants module state, and a
verifier wants to say what a function does to it.

**e. A quantifier over something other than an integer range.** `for all x in a do P(x)` over a `[]T`
is the form most specifications want, and it is not offered — the range form is, and an index has to
be introduced by hand. The reason is that `14`'s iteration protocol is a trait, so quantifying over
one means calling into user code from a clause, and what that costs a prover has not been worked out.
