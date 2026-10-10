---
title: sysl.signal.sys
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.signal.sys
requires: "requires { os }"
---

## Index

[`set_default`](#set_default) [`set_ignored`](#set_ignored) [`set_restored`](#set_restored)

## Constants

### `set_default`

```sysl
const set_default: int = 0
```

What `disposition` sets a signal to: the action the system takes for it when nobody has said
otherwise -- for most signals, ending the program.

### `set_ignored`

```sysl
const set_ignored: int = 1
```

What `disposition` sets a signal to: nothing at all. The signal is discarded where it arrives.

### `set_restored`

```sysl
const set_restored: int = 2
```

What `disposition` sets a signal to: whatever it was before `watch` was first asked for it, which
the supplier kept. A signal `watch` never touched is left as it is, and that is success.
