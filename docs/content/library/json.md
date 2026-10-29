---
title: The JSON module
summary: "`sysl.encoding.json` — a JSON document read into a value tree that keeps its order and its integers, written back compactly or for a person, and refused with the line and column where it went wrong."
weight: 59
---

**Every declaration in `sysl.encoding.json`, with its signature:** [the generated API page](/api/sysl-encoding-json/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.encoding.json` reads RFC 8259 JSON into a `Json` value and writes one back out. It opens
nothing and reads nothing: `parse` takes text the program already has, and writing goes into any
`Writer`. It needs a heap and nothing else, so it builds for a board that has one.

```sysl
import sysl.encoding.json.parse

val doc = parse("{\"name\": \"sysl\", \"tags\": [\"systems\", \"small\"], \"stars\": 42}").unwrap()

print(doc.get("name").unwrap().as_str().unwrap())
print(doc.get("tags").unwrap().len(), doc.get("stars").unwrap().as_int().unwrap())
print(doc)
```

```output
sysl
2 42
{"name":"sysl","tags":["systems","small"],"stars":42}
```

## The tree

A `Json` is one of `Null`, `Bool`, `Int`, `Big`, `Real`, `Str`, `Arr` and `Obj`. An array holds a
`Buf[Json]` and an object a `Buf[Member]` — a `key` and a `value` — so the tree owns itself and every
walk over it is a plain `match`.

**An object is a sequence, not a map.** Its members keep the order the document wrote them in, a
name written twice keeps both, and `get` answers the first. That is what lets a document be read,
changed and written back without reordering it, and a linear scan is the right cost at the sizes
JSON is used at.

**An integer stays an integer.** A number written with neither a fraction nor an exponent is an
`Int` where it fits a `long`, and a `Big` carrying its digits where it does not; anything written with
a `.` or an `e` is a `Real`. So `1` and `1.0` are different values, an identifier past 2^53 survives
the trip, and `as_real` is there for a caller that does not care which it got:

```sysl
import sysl.encoding.json.{parse, Json}

val doc = parse("[1, 1.0, 12345678901234567890123, 2e3]").unwrap()

for i in 0..<doc.len()
    val label = doc.at(i).unwrap() match
        Json.Int(n) -> s"Int $n"
        Json.Big(d) -> s"Big $d"
        Json.Real(x) -> s"Real $x"
        _ -> "other"

    print(label)
```

```output
Int 1
Real 1
Big 12345678901234567890123
Real 2000
```

The `as_*` accessors never convert: `Str("1").as_int()` is `None`. Strings are decoded in full —
every escape, and a `\u` surrogate pair into the one character it names; half a pair alone is
refused, since a `string` is UTF-8 and has no way to hold it.

## Writing

**The compact form is the `Display`**, so `print(j)`, `j.to_string()` and `s"$j"` all write JSON with no
space anywhere. `pretty(j, step)` is for a person: one element or member to a line, `step` spaces
per level. A `Real` is written as the shortest text that reads back as the same value, with a `.0`
where it would otherwise read back as an `Int`; a NaN or an infinity is written `null`, since JSON has
no spelling for one.

```sysl
import sysl.encoding.json.{parse, pretty, quoted}

val doc = parse("{\"a\":[1,2.0,{}],\"b\":\"tab\\there\"}").unwrap()

print(pretty(doc, 2))
print(quoted("say \"hi\"\n"))
```

```output
{
  "a": [
    1,
    2.0,
    {}
  ],
  "b": "tab\there"
}
"say \"hi\"\n"
```

What is written reads back equal to what was held, for every value but a non-finite `Real`.

## Reading and writing your own types

**`to_json(v)` and `from_json[T](text)` read and write any type that derives
[`Codec`](/library/codec/)**, with no tree in the middle: `from_json` reads the text through
`JsonDecoder`, a pull reader over the bytes, straight into the fields of a `T`. `JsonDecoder` is the
reader `parse` builds its tree with, so the two accept exactly the same texts and refuse a malformed
one in the same words; `from_json_bytes` is the same over bytes that may not be text.

```sysl
import sysl.codec.Codec
import sysl.encoding.json.{to_json, from_json}

struct Server derives Codec
    host: string
    port: u16
    tls: Option[bool]

val s = from_json[Server]("{\"port\": 8443, \"host\": \"example.org\"}").unwrap()

print(s.host, s.port, s.tls)
print(to_json(Server("a\"b", 80, Some(true))).unwrap())
print(from_json[Server]("{\"host\": \"h\", \"port\": 70000}").unwrap_err())
```

```output
example.org 8443 None
{"host":"a\"b","port":80,"tls":true}
port: out of range for ushort (byte 22)
```

`JsonEncoder` writes into any `Writer` as it goes, compactly, with a real in its shortest form. A
NaN or an infinity has no JSON spelling: the encoder writes `null` in its place and latches the fault,
and `to_json` answers it as an error rather than text that would not read back.

## What a refusal says

A malformed document is refused with a `JsonError` (which has `==`, as its `Fault` does): the `line` and `col` (both from one; a column
counts characters, so it is what an editor shows), the byte `offset`, and the `fault`. It prints
the way a compiler's diagnostic starts:

```sysl
import sysl.encoding.json.parse

for text in ["[1, 2,]", "{\"a\": 1\n \"b\": 2}", "\"caf\u{e9} \\x\"", "[01]", "{\"open\": [true"]
    parse(text) match
        Ok(_) -> print("accepted")
        Err(e) -> print(e)
```

```output
1:7: a value expected
2:2: ',' or '}' expected
1:7: not one of JSON's escapes: \" \\ \/ \b \f \n \r \t \uXXXX
1:2: a number may not have a leading zero
1:15: the text ends where ',' or ']' was expected
```

The grammar is RFC 8259's exactly — no comments, no trailing comma, no single quotes, no leading `+`
or leading zero. Arrays and objects may nest `max_depth` (128) deep, which keeps a hostile document
from exhausting the stack. `parse_bytes` takes bytes that may not be text and refuses a string that
is not UTF-8 where its bad byte stands. Every `y_` and `n_` case of
[JSONTestSuite](https://github.com/nst/JSONTestSuite) is in the module's tests.
