---
title: Structs and methods
summary: Data with a name, then behaviour beside it — and a receiver that says how the method takes the instance.
weight: 40
---

## A struct is its fields

```sysl
struct Point
    x: int
    y: int
end Point

var p = Point(6, 7)

print("point:", p.x, p.y)
```

```output
point: 6 7
```

Construction is positional and uses the type's own name. There is no `new`, and no allocation has
happened: `p` is a value, it lives in this frame, and it is exactly as big as its fields.

A struct is copied when you assign it, the same as any other value:

```sysl
struct Point
    x: int
    y: int
end Point

var a = Point(1, 2)
var b = a

b.x = 99

print("a:", a.x, "b:", b.x)
```

```output
a: 1 b: 99
```

That is worth pausing on if you are used to a language where every object is a reference. `b = a`
copied the point. If you want two names for *one* object, you ask for it — and that is the
[memory chapter](/tour/memory/).

## Methods

A member with a `self` receiver is a method. The sigil on `self` says how the method takes the
instance, exactly as it would on any other parameter:

```sysl
struct Vec2
    x: int
    y: int

    // A read that does not touch the original takes `self` by value.
    sum(self) -> int = self.x + self.y

    // `*self` is a raw pointer to the instance, so the writes land in the caller's value.
    shift(*self, dx: int, dy: int)
        self.x += dx
        self.y += dy
end Vec2

var here = Vec2(3, 4)

print("before:", here.sum())
here.shift(10, 20)
print("after:", here.sum())
```

```output
before: 7
after: 37
```

`self` by value gets a copy, which is what a read wants and what stops a method that only reads from
being able to write. `*self` is the receiver a mutating method takes, and it is spelled with the
same sigil that means "raw pointer" everywhere else — because that is what it is.

There is no `this` and no implicit receiver: `self` is written, and a field access through it is
written too.

## Properties and associated functions

A member with no parameter list is a **property** — it reads like a field but is computed:

```sysl
struct Rect
    w: int
    h: int

    area -> int = self.w * self.h
    perimeter -> int = 2 * (self.w + self.h)
end Rect

var r = Rect(3, 4)

print("area:", r.area, "perimeter:", r.perimeter)
```

```output
area: 12 perimeter: 14
```

Note the missing parentheses at the call: `r.area`, not `r.area()`. The convention that goes with
that spelling is that a property is *cheap* — anything that allocates or loops is a method, so that
the parentheses warn you.

A member with no `self` at all is an **associated function**, called through the type name. It is
where a named constructor goes, since the positional `Rect(w, h)` covers only one shape:

```sysl
struct Rect
    w: int
    h: int

    area -> int = self.w * self.h

    square(n: int) -> Rect = Rect(n, n)
end Rect

print("square:", Rect.square(5).area)
```

```output
square: 25
```

---

Next: [memory](/tour/memory/) — the three modes, and the chapter that makes sysl a different
language rather than a different syntax.
