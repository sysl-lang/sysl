---
title: Compact reference
summary: The whole language on one page, for a reader who already programs — tables and one-line rules instead of explanation, with the idioms that keep new code in style.
weight: 140
---

Every other page in this section explains. This one does not: it is the language written down as
densely as it can be, for a reader who already programs in something and wants the rules rather than
the argument. Each heading below has a full page behind it, linked, and the full page is where an
edge case is settled.

Two things this page is not. It is not a tutorial — the [tour](/tour/) is. And it is not the
specification: where this page and a full page disagree, the full page is right, because that is the
one whose every claim is a compiled program.

## Lexical and layout

[Full page](/reference/lexical/).

| | |
|---|---|
| line comment | `// …` |
| block comment | `/* … */`, and it **nests** |
| doc comment | `/** … */` above a declaration, with `@param` / `@return` tags |
| **not** a comment | `--`. It lexes as two minus signs and the error lands further along the line |

**Layout.** Indenting opens a block, dedenting closes it, a newline ends a statement. No braces
around statement blocks. `;` is a separator only inside a three-clause `for` header.

- `(`, `[` and `{` **suspend** the off-side rule until they close — with two exceptions, `match` and
  `->`, which open an indented block wherever they are written, brackets included.
- A line ending in an operator that **cannot finish an expression** (`+`, `&&`, `==`, `<<`, prefix
  `!`) continues onto the next. Excluded: `=` and `->` (they open a block instead), the postfix `++`,
  `--`, `?`, and `..`, `..<`, `...`, which can be complete.
- A line **beginning** with `.name` continues the line above it — the one way to break a call chain.
- `end Name` may close any block-shaped declaration; it is optional, checked when written, and
  **required only on a struct with no fields**.

**Literals.**

| | |
|---|---|
| integers | `42`, `0xFF`, `0b1010_1010`, `0o755`; `_` between digits; a canonical type name as a suffix |
| floats | `3.14`, `2.5e3`; default `real` (`f64`); a width as a suffix. No hex/binary/octal float |
| `char` | `'a'`, `'\n'`, `'\u{1F600}'` — one Unicode scalar value |
| `string` | `"…"` · `c"…"` a C string as `*u8` · `s"…${e}…"` interpolated · `f"…${e}%4d…"` with a specifier · `raw"…"` left exactly alone |
| text block | any of those tripled — `"""…"""` — spanning lines, common indentation stripped |
| labels | `'name` — told from a `char` by having no closing quote |
| words | `true`, `false`, `null`. `null` exists for `*T` and only for `*T` |

**Forty reserved words**: `alignof as break const continue defer do elif else ensure enum extern
false for if impl import in loop match module null offsetof override private ref require return self
sizeof static struct then trait true type val var weak while`.

**Contextual** — keywords only where the grammar expects one, ordinary names everywhere else: `is`,
`not`, `end`, `become`, `opaque`, `derives`, `invariant`, `new`, `set`, `some`, `with`, `within`,
`where`, and the `c` of a `c const` / `c type` block. Type names (`int`, `usize`, `f32`, `string`, …)
are predeclared identifiers, not reserved.

**`__…__`** — leading and trailing `__` with capitals between — is reserved to the language;
nothing may declare one. The built-ins are `__FILE__`, `__LINE__`, `__COLUMN__`, `__FUNCTION__`,
`__DATE__`, `__TIME__`, `__NAME__`, `__VERSION__`. `__NAME__` and `__VERSION__` are the
`package.hocon` name and version of the package whose file they are written in, and are refused in a
file that belongs to no package. Written as a **default argument** the location ones, `__NAME__` and
`__VERSION__` report the caller — its file, its line, its package's name and version.

**Operators**, closed set, longest match, no user-defined symbols:

```
=  +=  -=  *=  /=  %=  &=  |=  ^=  <<=  >>=      ||  &&  !
==  !=  <  >  <=  >=      ..  ..<  ...      |  ^  &  ~
+  -  *  /  %  <<  >>      ++  --      (  )  [  ]  {  }  .  ?  .*
,  ::  :  ->      #  ;      @
```

## Types

[Full page](/reference/types/).

| | |
|---|---|
| `iN` / `uN` | an **open** family — `i5`, `u3`, `u12`, `i128` all exist without the compiler knowing them. Arithmetic wraps at the declared width |
| aliases | `byte`=`u8`, `ushort`=`u16`, `uint`=`u32`, `ulong`=`u64`, `short`=`i16`, `int`=`i32`, `long`=`i64`, `real`=`f64`. Pinned by definition on every target; prefer explicit widths at a C boundary |
| `fN` | **closed** — `f32` and `f64` only |
| `usize` / `isize` | pointer-width, and **distinct types** from every `uN`. Lengths, indices and `sizeof` are `usize` |
| `bool` | nothing coerces to it |
| `char` | one Unicode scalar value; neither `u8` nor `u32` |
| `string` | validated UTF-8, immutable, three words. Indexes by byte, iterates by `char` |
| `unit` / `never` | one value / no values. `never` is a subtype of everything |
| `[N]T` | fixed array — **is** its elements, so copying copies them all |
| `[]T`, `[]const T` | slice — `{owner, pointer, length}`, a **view** of someone else's elements |
| `<N>T` | vector — N lanes in a register; operators work lane-wise; a lane index must be constant |
| `(A, B)` | tuple, read `t.0`, `t.1` |
| `Fn(A) -> R` | callable type. `f: A -> B` is sugar for a **type parameter** (monomorphized, no box); `f: &Fn(A) -> B` is a boxed trait object |
| `*extern(A) -> R` | a raw C function pointer |
| `type N = T` | alias — a second spelling, no new type, no checking |

**Every index is checked** on arrays and slices, and out of bounds traps.

```sysl
struct Point
    x: int
    y: int

enum Shape
    Circle(r: real)
    Rect(w: real, h: real)

area(s: Shape) -> real = s match
    Circle(r)  -> 3.0 * r * r
    Rect(w, h) -> w * h

var a = [1, 2, 3]
var b = a
var v: []int = a[..]

b[0] = 99
v[1] = 88

print(a[0], a[1], b[0], Point(1, 2).y, area(Rect(2.0, 3.0)))
```

```output
1 88 99 2 6
```

`Option[T]` and `Result[T, E]` are ordinary library enums with no compiler privileges.

**A simple enum** may fix its representation and its discriminants — `enum Colour: u8` with
`Red = 2` — and answers a closed set of `::` attributes: `T::First`, `T::Last`, `T::Pos(v)`,
`T::Val(i)`, `T::Succ(v)`, `T::Pred(v)`, `T::Image(v)`, `T::Value("Name")`.

## Declarations

[Full page](/reference/declarations/).

| form | mutable | value | type |
|---|---|---|---|
| `var n = v` | yes | optional | optional |
| `val n = v` | written once | required | optional |
| `const N: T = v` | no, folded while compiling | required | **required** |

At module level, `val` is read-only storage that outlives the run and `const` is folded into every
use — *if it has to be indexed or pointed at, it is a `val`*. In the **entry file**, whose top level
is a body, the module member is asked for with `static val` / `static var`.

**A function is a name, a parameter list, an optional `-> result`, and a body** — no keyword. The
body is `= expr` or an indented block whose **trailing expression** is the result; `=` may also open
a block. An absent result type means `unit`.

```sysl
double(n: int) -> int = n * 2

sum(a: int, b: int) -> int
    val t = a + b

    t

box(w: int, h: int = 1, fill: string = "*") -> string
    var s = ""

    for i in 0..<w * h
        s += fill

    s

print(double(21), sum(40, 2), box(3), box(2, fill = "#"))
```

```output
42 42 *** ##
```

- **Defaults** are full expressions evaluated at the call, read at the parameter's declared type.
  **Named arguments** are written `name = value`.
- **Overloading** is by parameter types. A function whose last act is a call to itself reuses the
  frame; `@tailrec` asserts that it did, and `become f(…)` demands it of any call.
- **`...`** collects the rest of the call. Several results go back as a tuple.

**A struct's members** — fields, methods, properties, `invariant` — go in the body in any order. The
constructor is the struct's name applied to its fields in declaration order; a field declares no
default.

**The three receivers, and the difference is what survives the call:**

| | |
|---|---|
| `self` | a **copy**. Cannot be mutated, cannot be referred back to |
| `*self` | the caller's **address** — good for the call's length, and may not be kept |
| `&self` | a **share of the box** — what the method builds may hold the receiver and outlive the call. Refused on a stack value |

**A property is a method with the parameter list left off** — `perimeter -> int`, read as
`r.perimeter`. A setter beside it is `set f(v)`, whose parameter carries no type.

```sysl
struct Rect
    w: int
    h: int

    invariant w > 0 && h > 0

    area(self) -> int = self.w * self.h

    perimeter -> int = 2 * (self.w + self.h)

    scale(*self, k: int)
        self.w *= k
        self.h *= k
end Rect

var r = Rect(3, 4)

print(r.area(), r.perimeter)

r.scale(2)

print(r.area())
```

```output
12 14
48
```

**Visibility** is public unless said otherwise: `private` is *this file*, `private[mod]` is the named
enclosing module and everything under it. The bracket takes a **simple name, not a path** —
`private[matrix]`, never `private[sysl.math.matrix]`.

## Expressions and statements

[Expressions](/reference/expressions/) · [statements](/reference/statements/) ·
[patterns](/reference/patterns/).

- **Conversions are calls and none is inferred**: `u16(n)`, `real(n)`, `int(x)`, `u32(c)`, `char(u)`
  (traps), `string(c)`, `usize(p)`. There is **no** conversion to or from `bool`, and none between a
  number and a `string` — `str(x)` renders, `sysl.text` parses. `ptr_cast` makes a pointer from an
  address.
- **Comparison chains**: `0 <= i < n` is one expression, each operand evaluated once.
- **`x is Pat`** and `x is not Pat` test against the full pattern grammar, alternatives included, and
  stand **in the condition of an `if` or a `while` and nowhere else** — chain them with `&&`, or use a
  `match`.
- **`?`** is postfix and tightest: on `Ok(v)`/`Some(v)` it is `v`, otherwise it returns the failure
  from the enclosing function — which must return the same channel. It converts the error through
  `From`.
- **`base with { bg = 9 }`** is `base` again with those fields changed; the left side is untouched.
- **`.Green`** is the variant with the qualifier the context already supplies left off.
- **A call's last argument may be an indented block** after a `:`; what the block becomes is decided
  by the parameter it fills, and the one value it is passed is `it`.
- `sizeof(T)`, `alignof(T)`, `offsetof(T, f)` fold while compiling.

**Loops — five forms, each an expression.**

| | |
|---|---|
| `while c` / `do … while c` | test before / after |
| `loop` | no test; something inside leaves it. **Never write `while true`** |
| `for x in seq` | a range, an array, a slice, or an `Iterate`; `x` may be a full pattern in parentheses |
| `for init; cond; step` | a stride, a descent, several variables |
| `for const i in 0..<N` | **unrolled** while compiling — for walking a type pack; no label, no `break`, no `else` |

`break expr` makes the loop's value; an `else` block after the body supplies the value when the loop
finishes normally, and a value-carrying `break` without one is refused. Loops take `'label`s.

**`defer <statement>` runs on the way out of its block** by any ordinary route — falling off the end,
`return`, `break`, `continue`, or a `?` taking its failure arm — in reverse order. A **trap runs no
deferred statement**.

**Patterns**, one table:

| | |
|---|---|
| `_` | anything, binds nothing |
| `else …` | the catch-all arm, in tail position |
| `0`, `'a'`, `"hi"`, `true` | a literal, on any type with equality |
| `3..7`, `0..<10`, `'a'..'z'` | a range, over the numeric types and `char` |
| `r` | binds. A **bare lowercase name binds**; a backticked `` `limit` `` references instead |
| `Circle(r)`, `.Empty` | a variant, binding sub-patterns |
| `Point(a, b)`, `Point{x, y}`, `Point{x: a}` | a struct, positionally or by name |
| `(a, _)` | a tuple |
| `c @ Circle(r)` | matches, and binds the whole value too |
| `A \| B` | alternatives, which may **not** bind |

A `match` on a data enum must be exhaustive or carry an unguarded `else`; **a guarded arm never
counts toward exhaustiveness**, and a failed guard falls through to a later arm. A pattern may also
stand at a binding and in a `for` header, where only an irrefutable one is allowed.

```sysl
band(n: int) -> string
    n match
        1..10 if n > 5 -> "high"
        1..10          -> "low"
        else              "out"

var xs = [1, 3, 4, 5]

val first_even = for x in xs
    if x % 2 == 0 then break x
else -1

print(band(7), band(2), band(50), first_even)
```

```output
high low out 4
```

## Ownership and memory

[Full page](/reference/memory/).

| mode | what it is | freed by | needs an allocator |
|---|---|---|---|
| `T` | a value — stack, register, or inline in something bigger | nobody | no |
| `&T` | a counted reference to a heap object | the compiler, when the last one goes | to **make** one |
| `weak T` | a reference that does not keep its object alive; `.get()` answers `Option[&T]` | — | no |
| `&sync T` | `&T` with an atomic count — a **distinct type**, no conversion either way | the compiler | yes |
| `*T` | a raw machine pointer | you | no |

**The choice is per declaration, not per type**, and value is the unmarked default. Assignment
copies; copying a value holding a `&T` retains it, so **there is no `Copy` bound to write, ever**.
References are non-null — nullable is `Option`. A slice's `owner` word keeps its backing alive, and a
local that escapes is promoted rather than dangling.

**`ref t = self.tasks[i]`** binds a name to a *place*, keeping the bounds, `within` and invariant
checks that `&self.tasks[i]` would have given up.

**A `Drop` fires when a box's count reaches zero and for nothing else** — see the tips below.

**Crossing a concurrency domain** copies by default; a `&T` may not cross, which is checked at a
`@crossing` parameter. `&sync T` makes the *reference* safe to share, not the object safe to mutate.

## Generics and traits

[Generics](/reference/generics/) · [traits](/reference/traits/).

- `[T]`, `[T: Bound]`, `[T: A + B]`; `[const N: usize]` stands for a **value**; `[..A]` for a **list
  of types**. Inference is bidirectional, and a type argument may be written explicitly as `f[int](x)`.
- **An unbounded `T` may be copied, assigned, passed, returned and stored, and nothing else.** Every
  operator, method, index and field access needs a bound — and the diagnostic names the bound to
  write, except for a field, which no trait could supply.
- **Conformance is explicit**: an `impl Trait for Type` block, which may live with either.
- A trait may take type parameters, declare an **associated type** — `type Item` in the trait, fixed
  by the `impl`, read back as `Self::Item` inside and `T::Item` from a bound — and **require** another
  trait.
- **A blanket `impl[T: Bound]` is allowed only over a family the compiler closes** — `Integer` is
  one; `Float` is an ordinary trait, so its two widths need two blocks.

**Trait objects** are fat pointers: `*Trait` points at the value, `&Trait` at the box. A trait is
erasable when it declares no associated type and every member has a receiver, mentions `Self` nowhere
else, takes no `...`, and — for `*Trait` — does not take `&self`. That excludes the whole operator
catalogue, deliberately.

**The compiler writes four**, and the list is closed: `derives Eq, Ord, Hash, Display` after the name
of a `struct` or `enum`.

| trait | for |
|---|---|
| `Eq`, `Ord` | `==`, `<` … — and **a bare enum already has `Eq`**; writing one is refused |
| `Add` `Sub` `Mul` `Div` `Rem` `Neg` | arithmetic |
| `BitAnd` `BitOr` `BitXor` `Shl` `Shr` `Not` | bitwise |
| `Zero`, `One` | the identities |
| `Display` | rendering to a sink; `str(x)` and `print` go through it |
| `Hash` | hashing |
| `Index`, `IndexSet` | `x[i]` and `x[i] = v` |
| `Iterate` | what `for x in` walks |
| `From` | `T.from(u)`, and what `?` converts an error through |
| `Drop` | destruction |
| `Fn0` … `Fn4` | calling a value |
| `Sequence[T]` | `fold` `any` `all` `find` `position` `count_where` `each` `map` `filter` `flat_map`, on slices and `Buf` |

## Modules and packages

[Modules](/reference/modules/) · [packages](/reference/packages/).

**A module is a directory**, and every file in it declares the same `module` path. The module graph
is acyclic.

```sysl
import sysl.math.max              // one member, unqualified
import sysl.math.{max, min}       // several
import sysl.math.*                // every public member
import sysl.math.{max as bigger}  // renamed
import sysl.math                  // the module: math.max(a, b)
import sysl.math as m             // the module, renamed
```

A public member is **always reachable fully qualified**; an import only shortens. Resolution is the
module the name is written in, then the file's imports, then the library — innermost first.

**A program starts in one place**: either top-level statements in one file, or a `main(args:
[]string)`, never both. That file is the **entry file** and its top level is a body, so its top-level
`var`s are locals unless written `static`.

**Capabilities are a module property**, written in the file header under `module` and consistently in
every file: `@no_alloc` (a promise this module does not allocate) and `@requires(heap, os, posix)` (a
demand). `@needs(...)` is the per-declaration form, and it is the one an `extern` takes.

**`package.hocon`** — a project is a directory with `.sysl` files in it; the manifest is optional.

```hocon
package {
  name    = "tool"
  version = "0.1.0"
  sysl    = "0.0.113"
}

capabilities { heap = false }

dependencies {
  json   { git = "github.com/edadma/sysl-json", version = "1.4.0", mount = "j" }
  helper { path = "../helper" }
}

requires {
  pkg_config { "yaml-0.1" = "libyaml — brew install libyaml" }
  headers    { lwip = "the pico-sdk carries them" }
}
```

A dependency is a git coordinate and a version — no registry, and `https://` on the front is refused.
The major version rides in the coordinate from v2 on. **Imports are transitive**, so a manifest names
only what the project's own files import. `package.sysl` is the oldest compiler the project builds
with; a `capabilities` block says what the machine **cannot** do, since everything is provided by
default; a `requires` block names what the consumer must supply a path or a `.pc` file for.

`sysl run · build · build-lib · build-c · test · add · deps · vendor · emit-llvm · emit-ast ·
emit-typed · emit-header · weave · tangle · targets · prove`, and an unknown word runs `sysl-<word>`
off the `PATH`.

## C interop

[Full page](/reference/ffi/).

```sysl
@link("m")
@include("<limits.h>")
@include("<stddef.h>")

extern "strnlen" bounded_len(s: *u8, n: usize) -> usize
extern pow(x: real, y: real) -> real

c const
    BITS: u32 = "CHAR_BIT"
    WIDEST: usize = "sizeof(long long)"

c type
    Size = "size_t"

@export("mylib_add")
add(a: i32, b: i32) -> i32 = a + b
```

- An `extern` is a header with **no body**, and that is the whole difference. Externs share the one
  function namespace, are never generic, and one nothing calls is not emitted at all. The escape
  analysis assumes the worst of every argument.
- **`c const` is a value the C compiler works out** for the target — it may size an array and stand
  in an `@assert`, which a call never can. **`c type` is a width it works out**, for a typedef like
  `size_t` or `TickType_t`.
- `@include` and `@link` are **file** clauses, so a module's `c const` blocks belong in one file.
- `@export` publishes an unmangled symbol; `@export("name")` chooses it. A callback taking or
  returning an **aggregate by value** must be `@export`ed, because `&f` on it has no C-callable
  address — so such a binding uses one exported trampoline and dispatches through it.
- `opaque struct T` withholds a layout; `*extern(A) -> R` is a function pointer; `interrupt` is a
  definition the processor enters.
- Only three shapes genuinely need a line of C: a caller-allocated opaque type, a macro (a
  `#define`, and `stdout` is one on Darwin), and a shape with no sysl spelling — an untagged union, a
  bitfield, a `static inline` function. **A struct by value is not one of them**, in either direction.

## Contracts and attributes

[Errors](/reference/errors/) · [attributes](/reference/attributes/) ·
[verification](/reference/verification/).

```sysl
half(x: int) -> int
    require x >= 0, "a half of a negative is not what this means"
    ensure result >= 0

    x / 2

@assert(sizeof(int) == 4, "the protocol fixes this")

@test
halves()
    assert(half(10) == 5, "ten")

print(half(10))
```

```output
5
```

- `require` is checked on entry, `ensure` before every return. Both form **one block at the top of
  the body**, before any other statement.
- `invariant <bool>` clauses sit among a struct's fields and are re-checked on every write.
- **Constrained types**: `type Name = [new] Base [within lo..hi] [where predicate]`. `new` makes a
  distinct type; `within` and `where` constrain; all three omitted is a plain alias.
- **A trap aborts** — no unwinding, no `catch`, no destructors, no `defer`. It comes from an
  out-of-bounds index, an inverted or out-of-range slice, a failed checked cast, a divide by zero, a
  broken `require` / `ensure` / `invariant`, or `panic`.

| | |
|---|---|
| on a function | `@test` `@tailrec` `@pure` `@ghost` `@reads` `@writes` `@crossing` `@export` `@needs` `@setup` `@teardown` `@setup_all` `@teardown_all` |
| on a type or storage | `@packed` `@align(n)` `@section("…")` `@export` |
| on the file | `@no_alloc` `@requires(…)` `@link("…")` `@include("…")` `@tests` |
| on nothing | `@assert(cond, "why")` |
| a directive | `#if posix` / `#elif` / `#else` / `#endif`, read before the lexer, at column 1 |

`asm` takes one arm per architecture — `[x86_64]`, `[aarch64]`, `[riscv64, riscv32]`, `[thumb]`,
`[x86]`, `[wasm32]`, `[craft]` — and **every architecture needs an answer**, `unavailable "why"`
included.

## Idioms, and the tips that keep code in style

Everything here is a house rule the compiler will not enforce. They are what separates sysl that
reads like sysl from sysl that reads like C with different punctuation.

**Say nothing the context already says.**

- **No literal suffix where the position gives the type.** `if xs.len > 1`, not `1usize`; `b[0]`, not
  `b[0usize]`. Arithmetic propagates it, and a `match` or an `if` takes it from the sibling arm.
- The one place to say it is a `var` starting from nothing: `var i: usize = 0`, because a bare
  integer literal alone infers `int`. Better still, reach for `for i in 0..<xs.len`, which needs
  neither.
- **A suffix is load-bearing in six places**, and stays: an array literal's element type
  where **every** element is literal (`[104u8, 105, …]` — one suffix settles the rest, or annotate the binding instead), a range with **both** bounds literal, an `if` or
  `match` where **both** sides are literals, a page whose subject is the type, a float width, and an
  integer outside `int`'s range.
- For a byte constant write `u8(';')`, never `59u8` — a `char` will not compare to a byte, and there
  is no `b'…'` form.
- Keep an annotation the compiler asks for: `var head: Buf[&Display] = buf()` has nothing to infer
  from. The test is whether removing it still compiles.

**Reach for the form the language names.**

- `loop`, never `while true`. An unconditional loop is one whose exit is inside it, and `loop` says
  so at the head.
- `for x in xs` and `for i in 0..<xs.len` over a `while` with a hand-rolled index.
- `xs.map(…)`, `.filter(…)`, `.each(…)`, `.find(…)`, `.fold(…)` over a hand-rolled push loop —
  `import sysl.seq.Sequence` is what puts them on a slice or a `Buf`.
- `for (k, v) in pairs` — a pattern in the header, not a destructure in the body.
- `e == Singular` — a bare enum already compares.
- A `match` or a closure body may be written **as an argument**, so the `val` that exists only to be
  used once on the line above the call is noise.
- `break x` with an `else`, rather than a found-flag.

**`end` markers are for long blocks.** The convention is seven lines head-to-marker inclusive: below
that the marker is noise, and on a four-line struct it is the reader's first impression of how much
ceremony the language charges. A struct with no fields is the exception and **must** carry one.

**Memory.**

- **A constructor for a type with a destructor returns `&T`, or the destructor never runs.** A plain
  value is copied rather than owned, so an `impl Drop` on a type nobody boxes is dead code that looks
  exactly like resource management. It is the type at **each** level, not the outermost:
  `Buf[&Thing]` and a `&Thing` field run one; `Buf[Thing]` and a `Thing` field do not, and boxing the
  holder does not save a by-value field. On an **error** path no value owns the handle and no box is
  made, so read what you need and close it where it stands.
- A method that hands C the address of caller-placed storage takes `*self`; one that must outlive
  the call in something it built takes `&self`; a read-only accessor may take `self`.
- Use a `weak T` for the edge back to a parent, or counting cannot collect the cycle.
- A closure crossing a concurrency domain is a `&sync Fn`, and what it may capture is checked: a
  scalar, an array, a `&sync T` and a `*T` yes; a `string`, a slice or an ordinary `&T` no. A named
  function reads into one **bare** — `Job(no_job)`, never `&no_job`.

**Traps to know before they bite.**

- **`raw"""` strips the indentation of its *closing* delimiter**, not of its content. Indent the
  `"""` to match the body, or every line of a PEM or a JSON fixture carries four spaces.
- **A module-level `val` whose value needs a loop to build shuts the package out of a bare-metal
  target** — there is no entry point and no loader to run the initializer. A scalar, a `ptr_cast`, a
  struct of literals and even a large array literal all fold; something like `byte_set_of("()[]")`
  does not. Put it in a local, or on a struct built once. A host `sysl test .` cannot see this;
  `sysl build-c <dir> --target thumb-freestanding-softfp` can.
- A **trait member may not collide with an inherent one**, and the first diagnostic is the real one —
  the ones pointing into the dependency's own file are the wreckage of the refused `impl`.
- **An associated type does *not* shadow a module type of the same name**, so `type Token =
  Spanned[Token]` is legal and means what it looks like.
- `private[m]` takes a **simple name**; a dotted path is refused at the first dot, with the rule and
  the simple name to write instead.
- `ref` is reserved, so a binding of `uv_ref` needs another name.
- A `val`-bound slice read out of a field is `[]const T`; `var` gives `[]T`.

**Probe before believing a limit.** A comment in a package is evidence about the day it was written,
and this org has shipped three separate claims about things the language could not do that it could
do all along. One program and one `sysl build` settle it.

## Diagnostics you will meet

| it says | change |
|---|---|
| `'+' needs 'T: sysl.Add'` | add the bound the message names to the type parameter |
| `'T' is a type parameter, so it has no fields to read` | no bound can supply a field; take a property through a trait instead |
| `'?' may only be used in a function returning sysl.Result, not int` | give the enclosing function the channel it early-returns into |
| `this loop breaks with a int but has no 'else'` | add the `else` block that says what a normal finish is worth |
| `a 'for' names one thing, and a pattern is what takes it apart` | `for (k, v) in …`, with the parentheses |
| `'is' tests a pattern in the condition of an 'if' or a 'while', and nowhere else` | chain with `&&` inside the condition, or write a `match` |
| `already implements 'sysl.Eq' — no variant of it carries anything` | delete the block; a bare enum compares already |
| `'&Cell' and '&sync Cell' are distinct types` | allocate it as the one you need where it is constructed |
| `'require'/'ensure' clauses must come before any other statement` | move the whole contract block to the top of the body |
| `a program starts in one place, and this 'main' is a second` | put the top-level statements inside `main` |
| `is exported and reaches … module storage an initializer fills` | the bare-metal `val` above: make it a local or a field |
| `'private[…]' names one enclosing module by its simple name rather than by a path` | `private[b]` — the bracket takes a simple name |
| `Undefined symbols … _stdout` | a macro, not a symbol; put a line of C beside the module |

---

Next: back to [the reference index](/reference/), or the [tour](/tour/) for the same language in the
order it makes sense to learn.
