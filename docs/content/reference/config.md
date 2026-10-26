---
title: sysl config
summary: The commands that edit sysl.hocon without disturbing it, and the one that shows what a project's manifests resolve to.
weight: 106
---

`sysl.hocon` is a file you may write by hand, and most of it is reasoning: the manifests in the
sysl-lang organization are mostly comment. **`sysl config` edits it the way a person would**,
changing only the bytes an edit has to change. A new entry takes the indent and alignment of the
entries beside it, a comment stays where it was, and nothing already in the file moves. The one
exception is a one-line block that a new entry would push past 100 columns, which is reflowed into
a block of its own, and only that one.

```bash
sysl config dep add <coordinate>[@<version>] [--optional] [--if <cond>] [--label <l>]
sysl config dep remove <label>
sysl config dep <label> [--features +a,-b] [--set <option>=<value>]...
sysl config feature add <f> [--doc <text>] [--default] [--enables a,b] [--if <cond>]
sysl config feature remove <f>
sysl config option add <o> --type <type> --default <value> [--range <low>,<high>] [--values a,b] [--macro <name>] [--doc <text>] [--feature <f>]
sysl config target add <t> [--triple <triple>] [--linker <linker>] [--linker-script <path>] [--entry <symbol>]
sysl config c add <lib> [--root <dir>] [--sources a,b] [--include a,b] [--feature <f>] [--target <t>]
sysl config get <path>
sysl config set <path> <value>
sysl config unset <path>
sysl config fix
sysl config show [--target <t>] [--features <list>] [--no-default-features] [--all-features]
```

Every command works on the manifest of the current directory; `-C <dir>` names another project.
`sysl config` with no command opens [the menu](#the-menu-sysl-config-on-a-terminal) on a terminal,
and prints the list above anywhere else.

## Every edit is read back before it is written

A writing command works out the new file, reads it through the same manifest reader a build uses,
and only then writes it. **An edit the reader would refuse leaves the file untouched**, and the
reader's refusal is what you are told. Here a feature is told to enable a dependency that is not
optional, which a build would refuse:

```hocon
package { name = "logger", version = "0.1.0" }

dependencies {
  fat { git = "github.com/sysl-lang/fat", version = "0.1.0" }
}
```

```bash
sysl config feature add sd --enables fat
```

```text
sysl: error: adding 'sd' would have made sysl.hocon unreadable, so nothing was written:
  'features.sd' names the dependency 'fat', which is not optional — a feature turns an optional dependency on, and this one is taken whatever is asked for. Write 'optional = true' in that dependency's entry, or drop it from the feature
```

**`--dry-run` prints the change instead of making it**, as a diff, on every writing command:

```hocon
package { name = "console", version = "0.1.0" }

dependencies {
  usb { path = "../usb" }
}
```

```bash
sysl config dep usb --set cdc_rx_buffer=512 --dry-run
```

```text
--- sysl.hocon
+++ sysl.hocon
@@ -2,4 +2,4 @@
 
 dependencies {
-  usb { path = "../usb" }
+  usb { path = "../usb", options { cdc_rx_buffer = 512 } }
 }
dry run: nothing was written
```

**A path written in two places is refused rather than guessed at.** HOCON lets a file set
`dependencies.usb.path` as a dotted key and again inside a block, and an edit cannot know which one
was meant:

```hocon
dependencies.usb.path = "../usb"

dependencies {
  usb { path = "../usb2" }
}
```

```bash
sysl config set dependencies.usb.path "../usb3"
```

```text
sysl: error: `dependencies.usb.path` is written in two places, lines 1 and 4, and an edit would have to guess which one is meant
```

**A command that writes a construct of 0.5.0 raises `package.sysl` to `"0.5.0"`** where the file
names an older floor, because a 0.4 compiler would mis-read a feature block as a malformed list. A
feature written with `--doc`, `--default` or `--if` is a block; one written with `--enables` alone
takes the list form every release reads:

```hocon
package {
  name = "logger"
  sysl = "0.4.0"
}

dependencies {
  fat { git = "github.com/sysl-lang/fat", version = "0.1.0", optional = true }
}
```

```bash
sysl config feature add sd --doc "log to an SD card" --default --enables fat
```

```hocon
package {
  name = "logger"
  sysl = "0.5.0"
}

dependencies {
  fat { git = "github.com/sysl-lang/fat", version = "0.1.0", optional = true }
}

features {
  sd { doc = "log to an SD card", default = true, enables = [fat] }
}
```

## Dependencies

`sysl config dep add` is `sysl add` under its other name: the version is the repository's newest
release tag unless the coordinate pins one, the entry is named for the repository's last segment
unless `--label` names it, and nothing is fetched — the next build does that.

```hocon
package {
  name    = "blink"
  version = "0.1.0"
}

dependencies {
  // The board's pins and clocks.
  pico2 { git = "github.com/sysl-lang/pico2", version = "0.4.0" }
}
```

```bash
sysl config dep add github.com/sysl-lang/fat@0.1.0
```

```hocon
package {
  name    = "blink"
  version = "0.1.0"
}

dependencies {
  // The board's pins and clocks.
  pico2 { git = "github.com/sysl-lang/pico2", version = "0.4.0" }
  fat { git = "github.com/sysl-lang/fat", version = "0.1.0" }
}
```

`--optional` writes `optional = true` and `--if` the machines the dependency is taken on, as the
[packages page](/reference/packages/#a-dependency-only-some-machines-take) describes. An optional
dependency no feature names is refused, since nothing would ever build it: add the feature that
turns it on in the same breath, or add the dependency first and the feature after.

`sysl config dep remove <label>` takes the entry out, with the comment lines directly above it.

**`sysl config dep <label>` chooses what a dependency is built with.** `--features` adds a feature
to the entry's list with `+name` (or a bare name) and takes one out with `-name`; a list left empty
is taken out of the entry. `--set` sets one of the dependency's options, a feature's option by its
dotted name. Both may be given together, and `--set` more than once:

```hocon
package { name = "console", version = "0.1.0" }

dependencies {
  usb { path = "../usb", features = [msc] }
}
```

```bash
sysl config dep usb --features +cdc,-msc --set cdc.rx_buffer=512
```

```hocon
package { name = "console", version = "0.1.0" }

dependencies {
  usb { path = "../usb", features = [cdc], options { cdc { rx_buffer = 512 } } }
}
```

`-name` takes out only what the entry itself asks for. A feature the dependency turns on by
default is turned off with `default_features = false`, written with `sysl config set`. Whether a
feature or an option is one the dependency declares is the build's question, asked of the
dependency's own manifest when it is fetched.

## Features, options, targets and C

`sysl config feature add` and `feature remove` declare and drop a feature of this package.

**An option is declared with its type and its default**, and `--feature` declares it inside a
feature, so it exists only while the feature is on. A feature written in the list form is first
rewritten as the block it stands for:

```hocon
package { name = "usb", version = "0.1.0" }

features {
  // A serial port over USB.
  cdc = []
}
```

```bash
sysl config option add rx_buffer --type u16 --default 256 --range 64,4096 --macro CFG_TUD_CDC_RX_BUFSIZE --feature cdc
```

```hocon
package { name = "usb", version = "0.1.0" }

features {
  // A serial port over USB.
  cdc = {
    options {
      rx_buffer { type = u16, default = 256, range = [64, 4096], macro = "CFG_TUD_CDC_RX_BUFSIZE" }
    }
  }
}
```

A type the reader does not have is its refusal, said before anything is written:

```hocon
package { name = "kernel", version = "0.1.0" }
```

```bash
sysl config option add slots --type u7x --default 4
```

```text
sysl: error: adding the option 'slots' would have made sysl.hocon unreadable, so nothing was written:
  'options.slots.type' is 'u7x', which an option cannot be — an option is an integer type ('usize', 'u8', 'int', 'i64', …), 'bool' or 'string'
```

`sysl config target add` declares a machine, and `sysl config c add` a vendored C library — the
package's own, a target's (`--target`), a feature's (`--feature`), or a feature's on one target
(both), as [C under targets and features](/reference/packages/#c-under-targets-and-features)
describes:

```hocon
package { name = "usb", version = "0.1.0" }
```

```bash
sysl config c add tinyusb --root c/tinyusb/src --include .,../../config --sources tusb.c,device/usbd.c
```

```hocon
package { name = "usb", version = "0.1.0" }

c {
  tinyusb { root = "c/tinyusb/src", include = [".", "../../config"], sources = ["tusb.c", "device/usbd.c"] }
}
```

## `get`, `set` and `unset`: any key, by its path

The commands above know what a dependency, a feature or a C library is. **`get`, `set` and `unset`
know only HOCON**, and reach any key by its path, a segment holding a dot quoted
(`targets."rp2350.s"`). `set` takes the value as HOCON text, so a string is quoted for the shell to
pass the quotes through; it replaces the value where the key is set and adds it where it is not:

```hocon
package {
  name    = "blink"
  version = "0.1.0"
}
```

```bash
sysl config set package.version '"0.2.0"'
```

```hocon
package {
  name    = "blink"
  version = "0.2.0"
}
```

`get` prints the value as the file writes it, and `unset` takes the entry out. Every one of them is
read back as the other commands are.

## `fix`: the old spellings, rewritten

Where the reader refuses an old spelling it says how to write it now. **`sysl config fix` makes
those rewrites**, by the same editor, so the comments survive:

- `features { default = [a, b] }`, the 0.4.0 list of the features on by default, becomes
  `default = true` inside each feature it named;
- `targets.<t>.features.<f> { … }`, the 0.4.0 target block listing the features that change it,
  becomes each feature's own `targets.<t>` block;
- `package.sysl` is raised to `"0.5.0"` where the rewritten file needs it;
- a manifest still named `package.hocon` is renamed `sysl.hocon`.

```hocon
package {
  name = "app"
  sysl = "0.4.0"
}

features {
  default = [fast]
  fast    = []
}

targets {
  pico {
    triple = "thumbv8m.main-none-eabihf"
    features {
      fast { linker_script = "fast.ld" }
    }
  }
}
```

```bash
sysl config fix
```

```hocon
package {
  name = "app"
  sysl = "0.5.0"
}

features {
  fast    = { default = true, targets { pico { linker_script = "fast.ld" } } }
}

targets {
  pico {
    triple = "thumbv8m.main-none-eabihf"
  }
}
```

A file with nothing to fix is left alone. A file that does not read even with every rewrite made is
left alone too, and the reader's refusal says what is left.

## `show`: what the manifests resolve to

`sysl config show` resolves the project's graph for one machine and one feature set — the build's
own `--target`, `--features`, `--no-default-features` and `--all-features` — and prints, package
by package, what that resolution settles:

- the features each package has on, and the member each of its choices picked;
- every option with its value and **who set it**: `this project`, the package whose entry set it
  where this project's did not, or `default`;
- each C library's include roots, in the order they are searched, and the files it compiles.

```text
console 0.1.0 (this project)
  features  none

usb (/home/me/usb)
  features  cdc
  options
    cdc.rx_buffer  512  this project
    ep0_size       64   default
  c
    tinyusb
      include  c/tinyusb/src, config
      sources  c/tinyusb/src/tusb.c, c/tinyusb/src/device/usbd.c
```

It writes nothing. A fetched package is headed by its name, its version and its coordinate; one
reached by a path, by its directory.

## The menu: `sysl config` on a terminal

With no command, and with both its input and its output a terminal, `sysl config` opens a
full-screen tree over **this project's view** of its dependencies: what the graph turns on for each,
and every choice, feature and option it declares.

```text
 app 0.1.0 — aarch64-macos                                      [ / ] search  [ ? ] doc  [ q ] quit
 └─ usb 0.1.0
    ├─ port   (•) rp2350  ( ) rp2040  ( ) stm32h7 — stm32h7 not this machine's
    ├─ ports  3 (1..4) — set by this project, default 1
    ├─ trace  false
    ├─ [x] cdc
    │   └─ rx_buffer  256 (64..4096)
    └─ [ ] msc
        └─ block  512 (512 | 4096) — off with 'msc'
```

- **A choice is a row of radio buttons.** Left and Right move along it, and Enter or Space picks the
  member the cursor is on.
- **A feature is a check box**, and its options are indented under it, greyed while it is off.
- **An option shows its value and its range or its values**, and who set it where this project did.
  Enter edits the value on the bottom line; Enter again sets it, and Escape leaves it as it was. A
  `bool` is flipped by Enter alone.
- **A member or a feature whose `if` fails on the machine is shown and greyed**, so the menu reads
  the same everywhere, and choosing it is refused with the reason. `--target <t>` names the machine
  the `if`s are read against; the default is the manifest's `targets.default`, then the host.
- `/` searches the rows' names and `n` finds the next match; `?` shows the row's `doc`; `q`, Escape
  or Ctrl-C closes the menu.

**Every change the menu makes is one of the commands above**, carried out by the same code, so the
two cannot drift — and when it closes it prints each command line it ran, which is what to put in a
script to make the same change again:

```text
sysl config dep usb --set ports=3
sysl config dep usb --features +msc
```

It writes **this project's entry** for a dependency — its `features`, its `default_features` and its
`options` — and never the dependency's own manifest. Turning off a feature the entry asks for takes
it out of the list; turning off one that is on by default turns the entry's `default_features` off
and asks by name for the defaults that stay on. A feature that is on for a reason the entry cannot
take back is refused with that reason: another feature enables it, or another package depending on
the same one asks for it.

**A change is written only once the graph still resolves**, and a value is held to the option's
declaration first — its type, its range, its values — with the refusal a build would give:

```text
this project sets the option 'ports' of 'usb' to 9, outside the option's range [1, 4]
```

Each change is written as it is made, so closing the menu needs no saving and a change is never
lost to one that came after it.
