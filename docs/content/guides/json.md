---
title: json
summary: Recursive ownership — a value that contains itself through `&T`, and the program that found the language could not build a string.
weight: 10
---

A JSON reader and writer, checked by round-tripping: parse a document, render it, parse the result,
and assert the two trees agree.

**The axis: recursive ownership.** A JSON document is the smallest honest example of a value that
contains itself — an array holds values, and a value may be an array — so the type cannot be written
at all without an [indirection](/reference/memory/), and every walk over it has to take the reference
apart:

```sysl
enum Json
    Null
    Bool(b: bool)
    Num(n: long)
    Str(s: string)
    Arr(items: &Buf[&Json])
    Obj(fields: &Buf[Field])
end Json
```

`Field` is a struct holding a name and a `&Json`, and the `&` on that value is the edge that makes
the cycle finite.

Numbers are integers, and that is a deliberate narrowing rather than a shortcut. A round trip is the
assertion the program is checked by, and a float would make the check about the renderer's digits
instead of about the tree. Floats are [another program's axis](/guides/fft/).

## What it found

**A pattern does not reach through a reference.** Field selection does — `p.x` works on a `&T` — so
the asymmetry is a thing to learn rather than to guess. Every walk in the program starts `*j match`,
which is [the rule the reference now states outright](/reference/patterns/): selection dereferences
one level on its own and `match` does not, because "am I matching the reference or the thing" should
stay a visible question.

**There was no way to build a `string` out of bytes a program computed.** This is the big one. The
only sources of a string were a literal, a slice of an existing string, concatenation, and `str` — so
unescaping was written as *"copy the source between the escapes, and append a literal for each"*, and
`\uXXXX` reached text only through `str(char(n))`, a **rendering** function standing in for a
constructor.

Answered by [`from_utf8`](/library/text/), which validates a `[]u8` into a `string`. `parse_string`
now gathers into one sink and converts once at the end, and an escape writes the bytes it encodes
rather than being rendered into a string that is immediately concatenated away. *The structure that
had been forced is now the structure that is wanted* — which is the best outcome a finding has.

**There were no constants.** A value shared by several functions had to be a nullary function. `const`
was added afterwards, for a reason this program did not turn up on its own: a nullary function reads
perfectly well *in an expression*, and what it cannot do is be an array bound.

**There was no growable collection.** An array and an object were each a linked list built by
appending through the tail reference — honest for a tree of references, and not what the data is.
Answered by [`Buf[T]`](/library/buf/) reaching the standard library — `sysl.buf`, which a program
asks for by name rather than getting for nothing: an array became a `&Buf[&Json]` and an
object a `&Buf[Field]`. That deleted two list-cell structs, both tail-threading loops, and the two
recursive renderers.

## Worth noticing

The depth limit is `private const max_depth: int = 64`, and the finding above is why it can be. What
a nullary function could never be is an **array bound**, which is the case that motivated the form:
as [declarations](/reference/declarations/) puts it, a `const` is folded into every use and has no
address, and being usable where a constant is *demanded* is exactly what that buys.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/json) ·
Next: [hashmap](/guides/hashmap/) — the trait system under load.
