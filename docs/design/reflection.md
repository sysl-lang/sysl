# Reflection — design

sysl gets **compile-time type reflection** in 0.3.0. A generic function can ask what a type is made
of — its kind, its fields, its variants, their names and annotations — and write one line that is
checked once per part. With that, serializers, `Debug`/`Eq`-style derivation, format helpers and the
compiler's own AST codec (0.3.0 item 7) can be ordinary library code instead of forms the compiler
writes. It builds on [comptime](comptime.md), and it is designed before comptime is built so the two
land together (user, 2026-10-18).

Nothing here is built yet. The note settles what the language says and lists what the user decides.

## 1. What exists, and the gaps (0.2.4)

| exists | where |
|---|---|
| `T::First`, `T::Image(v)`, `T::Value(s)`… on a simple enum; `T::Min`/`Max` on integers; the subtype set | attributes.md § `::`. **A fixed, closed set**, with no data enum and no struct |
| `..A` packs walked by `for const i in 0..<A.len`, with `t.i` per copy | generics.md § packs. **Tuples only**: `p.0` on a struct → *its fields are reached by name* |
| `derives Eq, Ord, Hash, Display`, plus a `Debug` for every type | traits.md. **Compiler-written, and closed**: *there is no way to teach the clause a fifth name* |
| `var p: P` followed by `p.x = …` field by field | probed: prints `1 a` |
| annotations | **a closed list** in `parse_attr.sysl`; a program cannot declare one |

So a program can walk a tuple's parts but not a struct's fields or an enum's variants. It cannot
learn a field's name, cannot carry metadata on a field, and cannot add a derivation.

## 2. Decisions

| # | question | recommendation | why, in one line |
|---|---|---|---|
| 1 | the shape of a query | **More `::` attributes on any type**, answered while compiling: `T::Kind`, `T::Name`, `T::Fields`, `T::Variants`. `T::Kind` is a std enum (`Struct`, `Tuple`, `SimpleEnum`, `DataEnum`, `Union`, `Array`, `Slice`, `Int`, `Float`, `Fixed`, `Bool`, `Char`, `String`, `Ref`, `Pointer`, `Fn`, `Opaque`…) | `::` is already "what a type's name answers", outside the member namespace. Zig's `@typeInfo(T)` returns one big union; separate attributes keep each answer a plain value |
| 2 | walking fields | **`for const f in T::Fields`**. `f` is a compile-time field descriptor (`f.name: string`, `f.index`, `f.offset`, `f.public`), and **`v.(f)`** selects that field of a `T` in each unrolled copy, typed as the field. It is readable and assignable, as `v.x` is | extends the existing `t.i`-per-copy rule from a position to a descriptor; each copy is checked on its own, as today |
| 2b | a field's type, where a type is wanted | **`f::Type`** stands for the field's type in a type position: `var tmp: f::Type`, `decode[f::Type](r)` | needed to call a generic per field; no type-as-value is introduced (comptime decision 8) |
| 3 | asking what a type can do | **`if const T: Trait`** is true when `T` implements `Trait`, written as a bound is (`T: Display + Hash`, `T: Conv[int]`, `f::Type: Display`), and asked as a bound asks it. It is an operand of an `if const` or `elif const` condition, so it joins `&&`, `\|\|`, `!` and ordinary constants. Pairs with comptime's `if const` | a serializer chooses "use its own impl" or "walk its fields" per type |
| 4 | walking variants of a data enum | **`for const c in T::Variants`**. `c.name`, `c.index`, `c::Fields` (walked as in 2). **`x is c`** tests a value's variant; **`x.(c)`** is the payload as a tuple, trapping on another variant; **`T::Make(c, tuple)`** builds one | the three operations an encoder and decoder need, with no generated `match` |
| 4b | a variant's payload, where a type is wanted | **`c::Payload`** stands for the payload's type in a type position: `unit` for a variant with no fields, the field's own type for one, the tuple of them for more — the shape `x.(c)` hands back and `T::Make(c, p)` takes. A decoder writes `var p: c::Payload`, then `p.(f) = decode[f::Type](r)` for each `f` in `c::Fields`, then `T::Make(c, p)`; over a payload of one field `p.(f)` is `p` itself, and assignable | builds any variant with one generic body, as 5 builds a struct; `f::Payload` and `c::Type` are refused, naming what each descriptor answers |
| 5 | building a struct generically | **`var out: T` then `out.(f) = …` for every field**, which works field by field. `out` starts at the type's zero, as any `var` does, so a field not assigned on some path keeps its zero; a type with no zero is refused at the `var` | Zig does the same with `undefined`; no new construct |
| 6 | user annotations | **A program may declare one**: `@annotation struct rename` with `name: string` makes `@rename("id")` legal on a field, a variant or a type. Reflection reads it: `f::Annotation[rename]` is an `Option[rename]` | the one thing every serializer needs (Rust's serde attributes, Go's struct tags). The built-in list stays reserved |
| 7 | private fields | **A query lists every field, and `f.public` says which are public. `v.(f)` on a private field is allowed only in the type's own file, or in a derivation the type asked for (8)** | the type's author opts in by writing `derives`; anyone else sees only the public surface |
| 8 | derivation from a library | **A trait may carry a `derive` body**: `trait Json` with a `derive[T] encode(self: T, w: Writer)` member written with reflection. `struct S derives Json` instantiates it as the `impl` a person would have written. The closed list of four goes away | the memo's goal: derivation becomes library code |
| 8b | the compiler-written four and `Debug` | **Rewritten as std `derive` bodies**, as the last step, with byte-identical output (the suite and the docs pages prove it). `Display`'s private renderer stays a std detail | proves the machinery on the code with the most tests, and removes `deriving.sysl` |
| 9 | run-time reflection | **None.** Every query is answered while compiling. A descriptor is plain data, so a program may lay `T::Name` or a field-name table into the object file (comptime decision 2), but there is no `any` and no RTTI on trait objects | Zig and D take this line too; it costs nothing in programs that do not use it |
| 10 | functions and methods (Zig's `decls`) | **Out of scope for 0.3.0.** `T::Fields` and `T::Variants` cover serializers, derivation and the AST codec | a type's methods vary with what is imported; that needs its own note |
| 11 | generic types | `Box[int]::Fields` reflects the instance; `Box::Fields` with no arguments is refused, like `Box::First` | consistent with the existing attributes |
| 12 | every stored member | **`T::Members`** lists what a struct, tuple or union stores, in declaration order: its named fields, each anonymous `struct`/`union` block and each unnamed `_` bitfield (a zero-width `_` stores nothing and is not listed). Its descriptors are `sysl.reflect.Member`s — a `Field`'s four plus `anonymous: bool`; an anonymous block's `name` is `""`, an unnamed bitfield's `"_"`. `v.(f)` reads and assigns each, an anonymous block as its own struct or union (`f::Type::Kind` says which). Privacy is 7's; a type with no members is refused as for `T::Fields` | `Debug` shows the bytes a value holds, which `T::Fields` alone cannot reach |
| 13 | `T::Fields` and `_` | **Named fields only**: an unnamed `_` bitfield is not listed, as an anonymous block is not | it only moves the fields after it, and a derived `Eq`/`Hash` must not read it |
| 14 | the declared name | **`T::BaseName`** is the name without module or arguments, as `Debug`/`Display` print it: `Box` for `Box[int]`, `Q` for `demo.Q`; an anonymous `union`/`struct` block answers that word; a type no declaration names (`int`, a tuple) answers as `T::Name` does, and it is refused where `T::Name` is | a derived renderer prints `Box(…)`, not `Box[int](…)` |
| 15 | an `opaque struct` with fields | **A struct in its own module**: there (and in a derivation it asked for, as 7) `T::Kind` is `Struct` and `T::Fields`/`T::Members` list its fields; elsewhere it is `Opaque` with none, and one with no body is `Opaque` everywhere | the layout is visible exactly where the module may build and take one apart |

## 3. How it reads

```sysl
@annotation
struct rename
    name: string

trait Json
    encode(self, w: *Writer)

    derive[T] encode(self: T, w: *Writer)
        if const T::Kind == Kind.Struct
            w.write("{".bytes)

            for const f in T::Fields
                if f.index > 0 then w.write(",".bytes)

                val key = f::Annotation[rename].map(r -> r.name).unwrap_or(f.name)

                w.write(s"\"$key\":".bytes)
                self.(f).encode(w)

            w.write("}".bytes)
        else
            @assert(false, s"Json cannot derive for ${T::Name}")

struct User derives Json
    @rename("id") user_id: int
    name: string
```

`User` gets an `impl Json for User` whose body is two unrolled field writes. `self.(f).encode(w)`
is checked for each field, so a field whose type is not `Json` is refused at the `derives` with the
ordinary *does not implement* wording, naming the field.

## 4. The analogy checks

| | query | walk fields | field metadata | derivation | run time |
|---|---|---|---|---|---|
| Zig | `@typeInfo(T)` union | `inline for` + `@field(v, name)` | none (decls by convention) | ordinary `comptime` code | none |
| D | `__traits`, `.tupleof` | `static foreach` | UDAs (`@Name("x")`) | mixin templates | `TypeInfo` |
| Rust | none in the language | proc macros | `#[serde(...)]` | `#[derive]` proc macros | `Any` (type id only) |
| C++26 | `^^T` + `std::meta` | `template for` | annotations (P3394) | ordinary consteval code | none |
| **sysl** | `T::` attributes | `for const f in T::Fields` + `v.(f)` | `@annotation` structs | `derive` member of a trait | none |

D is the closest, through user-defined attributes and `static foreach` over `.tupleof`. C++26 lands
the same shape: reflection values, `template for` and annotations.

## 5. Decided (user, 2026-10-18)

All four recommendations were taken, along with every row of §2 as recommended:

1. **User annotations.** A program declares one as an `@annotation` struct, and reflection reads it as `f::Annotation[A]`.
2. **The compiler-written derivations move to std in 0.3.0.** `Eq`, `Ord`, `Hash`, `Display` and `Debug` become std `derive` bodies with byte-identical output, and `deriving.sysl` is removed.
3. **Private fields.** They are reachable in the type's own file and in a derivation the type asked for with `derives`.
4. **The spelling `v.(f)`.**

## 6. Implementation plan

Prefix `rfl_`. These steps come after comptime's steps 1–6.

1. **Queries (1, 2b, 11).** `T::Kind`, `T::Name`, `T::Fields`, `T::Variants` and the descriptors, in
   `type_attr.sysl`. `Kind` goes in std's root module.
2. **`for const` over descriptors, and `v.(f)` / `x.(c)` / `x is c` / `T::Make` (2, 4).** These
   extend `for const`'s per-copy checking.
3. **`if const T: Trait` (3).**
4. **`@annotation` (6)**: the parser, the declaration table, and `f::Annotation[A]`.
5. **`derive` members and the open `derives` clause (8), with the privacy rule (7).**
6. **Std derivations of `Eq`, `Ord`, `Hash`, `Display` and `Debug` (8b)**, then remove `deriving.sysl`.
7. **Pages**: a new reference page `reflection.md`, and updates to traits.md § derives, attributes.md
   § `::`, and generics.md § `for const`.
8. **Item 7's codec** is then written against this, as its own item.
