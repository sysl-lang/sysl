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

var p = Point(6, 7)

print("point:", p.x, p.y)
```

```output
point: 6 7
```

Construction is positional and uses the type's name. There is no `new`, and nothing is allocated:
`p` is a value, it lives in the current frame, and it is exactly the size of its fields.

A struct is copied when it is assigned, like any other value:

```sysl
struct Point
    x: int
    y: int

var a = Point(1, 2)
var b = a

b.x = 99

print("a:", a.x, "b:", b.x)
```

```output
a: 1 b: 99
```

This differs from languages in which every object is a reference: `b = a` copied the point. Two
names for *one* object are something you ask for explicitly, and the
[memory chapter](/tour/memory/) shows how.

A member with a `self` receiver is a method. The sigil on `self` says how the method receives the
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

`self` by value receives a copy, which suits a method that only reads and guarantees it cannot write
to the caller's value. `*self` is the receiver for a mutating method. It uses the same sigil as a raw
pointer everywhere else, because it is one.

There is no `this` and no implicit receiver: `self` is written explicitly, and so is every field
access through it.

## Properties and associated functions

A member with no parameter list is a **property**: it reads like a field but is computed:

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

The call has no parentheses: `r.area`, not `r.area()`. By convention a property is *cheap*; anything
that allocates or loops is written as a method, so the parentheses signal the cost.

A property may also be **assigned**, by declaring a `set` beside it, which lets a value act on writes
to it; [the reference](/reference/declarations/#a-property-may-be-settable) has the form.

A member with no `self` is an **associated function**, called through the type name. This is where
named constructors go, since the positional `Rect(w, h)` covers only one form:

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

Next: [memory](/tour/memory/), the three memory modes, which are what most distinguish sysl from
other languages.
