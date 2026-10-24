---
title: sysl.c
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.c
summary: "The C variadic forms: `va_start`, `va_arg`, `va_copy` and `va_end`."
---

A function with a C variadic tail (`sum(n: int, ...)`) reads that tail through these, exactly as a
C function reads one through `<stdarg.h>`'s macros. The compiler answers them itself -- `va_arg`
takes the type it reads from the position its value fills -- so nothing here is a declaration.
What the module gives them is a home: **a file names the ones it uses**, the way it names anything
else it did not declare.

```
import sysl.c.{va_start, va_arg, va_end}
```

A file that declares a `va_arg` of its own calls that one, imported or not. The forms are
specified in `reference/ffi.md`.
