# The 0.5.0 manifest — design

0.5.0 is the manifest release. Three items share one grammar, so they are designed together:

1. **`c` and `defines` under a target and under a feature** — one vendored package serving several
   chips (TinyUSB on RP2350 and STM32H7; lwIP and FreeRTOS ports are the same shape).
2. **The syntax pass** — a feature becomes a block that says everything it does (doc, default, the
   machines it is for, what it enables, its options, its C, its target keys); exclusive features
   become a `choice`; options and features reach C through a generated header. It respells 0.3.7's
   provisional `targets.<t>.features {}`.
3. **`sysl config`** — the command that writes and edits a `package.hocon` in that grammar,
   including a menuconfig-style menu over an operating system's options.
4. **Generated linker scripts** — memory regions from the chip or board package, standard sections,
   stacks and heaps sized by options, named sections placed from sysl, chip blocks, and overflow
   reported in sysl's terms. A hand-written `linker_script` stays as the escape hatch.

**The split (decided by the user).** `sysl build` **generates** everything the manifest mandates:
config headers, defines, sysl constants and linker scripts. `sysl config` **only manages the
manifest**: it reads, writes and validates `package.hocon`, and never produces a build artifact.
Generated files are build outputs. They live in the build directory, are covered by the run
cache's keying, are never checked in, and are inspected with `sysl build --emit <what>` (§ 8.6).

**Configuring an operating system is a first-class case**: FreeRTOS (vendored C), skiff (sysl), keel,
Zephyr. Its shape — tunable values, on/off subsystems, a port per chip, an application overriding
the OS package's defaults, and the values reaching both C and sysl — is the test the grammar is
held to, alongside TinyUSB (§ 6).

**Status: proposed.** Nothing here is built. Every block marked *proposed* is new syntax; every
other block is 0.4.0's. The questions come first; each has one recommendation.

## Questions for the user

**The feature block (§ 3)**

- **Q1. Where a feature's target keys live.** (a) feature inside target, as 0.3.7; (b) target inside
  feature; (c) both. **Recommend (b) only**: a feature is then one block saying all it does, and an
  undeclared feature cannot be configured because the block *is* the declaration.
- **Q2. A feature's long form, and the list form.** **Recommend**: a feature is a block (`doc`,
  `default`, `if`, `enables`, `options`, `c`, `defines`, `config`, `requires`, `targets`); the list
  form `uv = [libuv]` stays valid forever as exactly `uv { enables = [libuv] }`.
- **Q3. How a package says what is on by default.** (a) the `default = [...]` entry, as today; (b)
  `default = true` on each feature. **Recommend (b)**, with a feature named `default` refused and
  the fix-it showing the rewrite (no manifest in the org uses `default = [...]`).
- **Q4. Machine conditions on a feature.** Key `if` (as on a dependency) or `when`; meaning
  "available only there" or "turned on there". **Recommend `if`, availability only**: asking for it
  elsewhere is refused; `--all-features` and `sysl test` skip it on other machines and `-v` says so.
  Nothing is turned on by the machine — what is compiled stays what somebody asked for.
- **Q5. Exclusive features.** (a) `conflicts = [...]` on a feature; (b) a `choices` block naming a
  group of which exactly one is on; (c) both. **Recommend (b) only.** The rare constraint a choice
  cannot say (RP2040 has one controller, so not device *and* host) is the package's own check, an
  `#error` in its C config or an `@assert`, as a range is the manifest's check and `@assert` the
  source's.
- **Q6. A source needing two features at once** (TinyUSB's `hcd_rp2040.c`: the chip *and* host).
  **Recommend no syntax in 0.5**: TinyUSB, FreeRTOS and lwIP all guard such files on their own
  config macros, so the chip's feature lists them and they compile to nothing when the role is off.

**C under targets and features (§ 2)**

- **Q7. How a `c` library merges across layers.** (a) a same-named library in a feature replaces
  the base one; (b) key by key, the feature's `sources` replacing the base's; (c) merge: lists add,
  `defines` merge by macro. **Recommend (c)**: a feature only ever adds, and replacing would force
  each chip to repeat the common core's file list — two copies that drift.
- **Q8. One macro set by two enabled features, even to the same value.** **Recommend refuse**, as
  0.3.7 refuses two features setting one target key; the fix is to say it once in the base.
- **Q9. Do a dependency's target and feature `c` blocks apply?** **Recommend yes**: they describe
  the package's own C, like its top-level `c` block. Only image keys (`linker_script`, `entry`,
  `linker`, `triple`, `capabilities`, `codegen`) stay root-only.
- **Q10. Reaching into a required header tree from a `c` block.** (a) nothing new, the consumer
  passes a dozen `--include-path` flags; (b) `"@pico_sdk/src/..."` in `include`, resolved under
  the path the requirement was answered with. **Recommend (b).**
- **Q11. Compiling C from a required tree** (`root = "@pico_sdk/src/rp2_common/hardware_irq"`).
  TinyUSB's RP2 port calls `irq_set_exclusive_handler`, so a build without the SDK's CMake needs it.
  **Recommend allow**: the files are still listed, nothing runs at build time, and the headers from
  the same tree are already code (inline functions). The alternative is a vendored `pico-hardware`
  package.
- **Q12. `requires` under a feature or target**, so an STM32 build is never asked for the pico-sdk.
  **Recommend yes.**
- **Q13. A consumer-chosen macro *name*** (CMSIS's `STM32H743xx`). (a) `--define` on the command
  line; (b) `c_defines = { STM32H743xx = true }` on the dependency entry, beside `c_include`; (c) an
  option whose value names the macro. **Recommend (b)**, held to `c_include`'s rule: two consumers
  setting it differently are refused.

**Options and the config header (§ 4)**

- **Q14. How options and features reach C.** (a) `-D` on every compilation; (b) a generated header.
  **Recommend (b)**: `sysl/<package>.h` in a generated include root, holding each option or feature
  that names a `macro`, plus `config {}` lines from enabled features and targets. It reaches the
  package's C, its probes, and every package that takes its exported roots.
- **Q15. Options in `#if`.** **Recommend**: a `bool` option is an `#if` symbol `option_<name>`
  beside `feature_<name>`; integers stay constants, which `if` and `@assert` already fold.
- **Q16. Options declared inside a feature**, existing only while it is on (Kconfig's `depends on`).
  **Recommend yes**: setting one while its feature is off is refused, naming the feature, and its
  macro is left out of the header, so the C's own `#ifndef` default stands.
- **Q17. Enumerated values.** **Recommend `values = [...]`** on an integer or string option. A
  value that must reach C as a bare token (`OPT_MODE_HIGH_SPEED`) is a `choice` with `config` lines.
- **Q18. Hex in option values** (`vid = 0x2E8A`). **Recommend accept** `0x`/`0b`/`0o`, as the
  language does.

**Old manifests (§ 3.4)**

- **Q19.** (a) accept the old spellings with a warning; (b) refuse with a fix-it showing the
  rewrite, which `sysl config fix` applies. **Recommend (b)**: `targets.<t>.features` was
  documented as provisional, and no manifest in the org uses it or `default = [...]`.

**`sysl config` (§ 5)**

- **Q20. Subcommands, a menu, or both.** **Recommend both**: subcommands first, the menu on top of
  them, both in 0.5.
- **Q21. The menu's form.** Full-screen like menuconfig, or a numbered line menu. **Recommend
  full-screen.** If `sysl.term` cannot do raw keys and cursor moves for a program other than its
  line editor, that is a library gap to fix first.
- **Q22. Starting a package.** **Recommend `sysl init`** at the top level, because that is the word
  people type. Everything after is `sysl config`, and `sysl add` stays as `sysl config dep add`.
- **Q23. The edit engine.** (a) extend `manifest_edit.sysl`'s text scanning; (b) a lossless HOCON
  tree in `sysl-lang/parsing` (the compiler already depends on it), with the manifest reader built
  on it. **Recommend (b)**: one parser, so an edit and a build can never read a file differently.

**FreeRTOS (§ 7)**

- **Q24.** Vendor the kernel into `sysl-lang/freertos` now that the grammar can configure it (a
  1.0.0), or keep it declare-only and leave the build to the application. **Recommend vendor**,
  keeping `c_include` as the way to bring your own `FreeRTOSConfig.h`.

**Generated linker scripts (§ 8)**

- **Q25. When a script is generated.** (a) whenever the image's memory map is known and no
  `linker_script` is named; (b) only with an explicit `layout {}` in the root. **Recommend (a)**: a
  chip package declaring its memory is the ordinary case, and naming `linker_script` opts out.
  `-v` says which script was used.
- **Q26. A dependency declaring memory and sections.** Image keys are root-only today. **Recommend**:
  `memory`, `sections` and `symbols` may come from a dependency, because they are facts about the
  chip, the board or a library's buffers. *Which* regions code and data go in stays the root's
  `layout`. Two packages declaring one region name are refused.
- **Q27. A manifest value taken from an option** (a stack's size, a flash length). **Recommend
  `"option:<package>.<name>"`**, quoted, as `"dep:lmdb"` is. The value must be a whole option; no
  arithmetic in the manifest.
- **Q28. How a library's section asks for memory it cannot name** (TinyUSB's buffers on a board
  whose region names it does not know). (a) the root always places it; (b) regions carry tags
  (`dma`, `fast`, `noinit`) and a section asks for a tag, which the root may override.
  **Recommend (b).**
- **Q29. The start-up symbols' names.** sysl's own (`__sysl_bss_start`) or a vendor's (`_sbss`,
  `__bss_start__`). **Recommend sysl's own**, plus a `symbols {}` block for the aliases a vendor
  start-up wants. The generated script also carries a copy table and a zero table, so one start-up
  loop handles any number of regions.
- **Q30. How overflow is reported.** (a) pass the linker's message through; (b) on a failed link,
  relink with every region widened, read the measured ELF (symbols, sizes, sections — the same
  whichever linker made it), and report per region in sysl's terms. **Recommend (b).**
- **Q31. The inspection flag.** `sysl build --emit <what>[,<what>]` (`config`, `config:<package>`,
  `link`, `all`) or `emit-*` subcommands beside `emit-llvm`. **Recommend `--emit` on `build`**:
  it writes the files under `.sysl/` and prints their paths, the build still runs, and so what is
  shown is exactly what the build used.
- **Q32. Keeping a declaration nothing references** (RP2350's `image_def`, a vector table). sysl
  has no `@used`, and reachability drops such a `val` before the linker's `KEEP` can see it.
  (a) a new `@keep` attribute; (b) a declaration whose `@section` names a section a manifest marks
  `keep = true` becomes a reachability root. **Recommend (b)**: the manifest already says the
  section must survive, and a second spelling of that fact could disagree with the first.

**Consumers**

- **Q33. Turning off one default feature of a dependency.** Today the only way is
  `default_features = false` and then naming the rest back. **Recommend `features = [-timers]`**:
  a `-` member removes one default for this consumer. Union still holds across consumers: another
  consumer that leaves `timers` on keeps it on, as `default` survives today.

## 1. What exists (0.4.0)

| exists | where |
|---|---|
| `features { f = [dep, other_feature, "dep/feature"] }`, `default` as an entry, union across consumers, additive by contract | packages.md § Features |
| `#if feature_<name>` in the package's own files | § Gating source on a feature |
| `options { n { type, default, range, doc } }`; consumer sets them on the dependency entry; root wins, two intermediate values refused; `option_<name>` constants in the package's own files | § Options |
| `targets.<t> { linker, linker_script, entry, triple, capabilities, codegen }`, root-only | § Linking a freestanding image |
| `targets.<t>.features.<f> { … }` — a feature sets target keys again; two enabled features on one key refused; undeclared feature refused; root-only; **"provisional"** | `target_features.sysl`, `tests_fls_feature_link_script.sysl` |
| `c { lib { root, include, include_private, sources, exclude, defines, export, libc } }` at top level only | § A vendored C library |
| `defines { "file.c" { M = v } }` at top level only | § What the vendored C is compiled with |
| `c_include = [...]` on a dependency entry: the consumer's config headers first | § A consumer's own configuration header |
| `requires.headers { name = "note" \| { note, env } }` answered by `--include-path name=dir` | § Headers a package needs |
| a dependency's `if = "freestanding && thumb"` — `#if`'s language plus `libc` | § A dependency only some machines take |
| `sysl add` edits by splicing the fewest bytes, follows the file's indent and alignment, reads the result back before writing | `add.sysl`, `manifest_edit.sysl` |

**The gap 0.5 closes**: `c` or `defines` written under a target block is reported as "not something
this sysl understands, and is ignored" (`tests_utk_unknown_target_keys.sysl`), and options never
reach C. So a package cannot pick its C by chip, and an OS package cannot be configured by the
application that uses it.

## 2. C under targets and features

### 2.1 The layers

A key may be written in four layers. From least to most specific:

```
package            c { … }, defines { … }, config { … }, requires { … }
targets.<t>        the same keys, for the machine being built
features.<f>       the same keys, while the feature is on
features.<f>.targets.<t>   the same keys, while the feature is on and for that machine
```

Only the target block for the machine being built, and only enabled features, contribute.

### 2.2 The rules

They are 0.3.7's, carried over:

- **A scalar** (a library's `root`, a single macro's value) is taken from the most specific layer
  that sets it. Within one layer, **two enabled features setting it are refused**, naming both
  (Q8). A library's `root` may be set in one layer only, because every relative path in every layer
  resolves against it. A layer adding to a library it does not root uses the base's root.
- **A list** (`sources`, `exclude`, `include`, `include_private`, `export`) is the union of every
  contributing layer. **Include roots search the more specific layers first**, so a chip's own
  `config/` shadows the generic one, as `c_include` does today. A file listed twice is refused,
  as it is today.
- **`defines`** (inside a library, or the per-file block) merge by macro. The more specific layer
  wins over a less specific one, and two features in one layer collide (Q8).
- **A library named only in a feature** is a new library that exists while the feature is on.
- **An undeclared feature** cannot be written. Under Q1(b) the block is the declaration.
- **A dependency's layers apply to that dependency** (Q9). These keys describe the package's own
  C. The features consulted are the ones resolved for it — the union every consumer asked for — so
  two consumers picking different chips meet in a choice (Q5) and are refused there.

Why merge rather than replace (Q7): every vendored library in view is *a common core plus one port*.
TinyUSB is `tusb.c`, `usbd.c` and the classes, plus `dcd_<chip>.c`. lwIP is `core/` plus
`netif/`, plus a `sys_arch` per OS. FreeRTOS is `tasks.c` and `queue.c`, plus `portable/<cc>/<arch>`
and one `heap_N.c`. Replacing the library would make every chip's block repeat the core's list. Key
by key would leave a chip unable to add a source without restating the others. Merging is the
feature contract — a feature only adds — applied to C.

### 2.3 Reaching the trees the package does not carry (Q10–Q12)

The pico-sdk port of TinyUSB includes `pico.h`, `hardware/irq.h` and `hardware/structs/usb.h`,
which live in a dozen directories of an 81 MB tree only the consumer has. Today each of those is an
`--include-path` flag. Proposed: **an include path, or a root, that starts with `@<name>/` names
a directory under the path the requirement `<name>` was answered with**. The name must be
declared in a `requires.headers` in a layer that is contributing. A requirement is then answered
once (`PICO_SDK_PATH`, or `--include-path pico_sdk=<dir>`), and the package says which
subdirectories it reads.

`requires` may itself sit in a feature or a target (Q12), so an STM32H7 build is never asked for the
pico-sdk.

## 3. The syntax pass

### 3.1 The grammar *(proposed)*

```hocon
package { name = "…", version = "…", sysl = "0.5.0" }

choices {
  <group> {
    doc     = "…"
    of      = [<feature>, …]
    default = <feature>              // optional; without one, a build that needs the group must pick
  }
}

features {
  <f> = [<dep | feature | "dep/feature">, …]     // shorthand for { enables = [...] }

  <f> {
    doc      = "…"                   // shown by sysl config, by refusals, and by sysl doc
    default  = true                  // on unless a consumer says default_features = false
    if       = "thumb && freestanding"   // the machines it exists on (Q4)
    enables  = [<dep | feature | "dep/feature">, …]
    macro    = "CFG_TUD_ENABLED"     // written to the config header as 1 or 0 (§ 4)
    options  { <o> { type, default, range, values, doc, macro } }   // exist only while on (Q16)
    requires { headers { … } }
    c        { <lib> { … } }
    defines  { "<file>" { … } }
    config   { CFG_TUSB_MCU = OPT_MCU_RP2040 }    // lines for the config header (§ 4)
    targets  { <t> { linker_script = "…", entry = "…", c { … }, … } }
  }
}

options  { <o> { type, default, range, values, doc, macro } }
targets  { <t> { …image keys…, c { … }, defines { … }, config { … }, requires { … } } }
c        { … }
defines  { … }
config   { … }
requires { … }
dependencies { <d> { git, version, features, default_features, options, c_include, c_defines, if, optional } }
```

A choice is a group of features of which **exactly one** is on wherever the group applies. The
group applies where at least one of its members' `if` holds, so a group of chip ports does not
apply to a `sysl test` on the host. Its members are ordinary features. A consumer picks one by
naming it in `features`. An explicit pick beats the group's `default`. Two consumers picking two
different members are refused, naming both. A group that applies, has no default, and was given no
pick is refused, listing the members.

`sysl test` and `--all-features` turn on every feature whose `if` holds on the build's machine.
For each group they turn on its default, or its first member, and they say which in `-v`. A
package that wants each member of a choice tested names them in a `test` block. (The
`test { features }` key is a small addition. Without it, a choice's other members go untested on
the host.)

### 3.2 Before and after: the provisional feature-in-target spelling

The reference's own example (`packages.md § A feature may set a target's keys again`), before:

```hocon
features { m7 = [], m4 = [] }

targets {
  thumb-freestanding {
    linker_script = "m7.ld"
    features {
      m7 { linker_script = "m7.ld" }
      m4 { linker_script = "m4.ld", entry = "m4_reset" }
    }
  }
}
```

After *(proposed)*. The two cores are a choice, because one image is built for one of them:

```hocon
choices {
  core { doc = "which core of the STM32H747 this image runs on", of = [m7, m4], default = m7 }
}

features {
  m7 { targets.thumb-freestanding.linker_script = "m7.ld" }
  m4 {
    targets.thumb-freestanding {
      linker_script = "m4.ld"
      entry         = "m4_reset"
    }
  }
}
```

The 0.3.7 rules carry over unchanged. A feature's target keys override the target block's. Two
features setting one key are refused, which a choice now makes impossible for these two. A
dependency setting image keys is refused.

### 3.3 Before and after: real manifests

`kairos/package.hocon` and `sdl3/package.hocon` are the only org manifests with a `features` block,
and both use the list form, which stays valid (Q2):

```hocon
features {
  uv = [libuv]
}
```

The long form says what the comment above it says today, in a place `sysl config` and refusals can
show:

```hocon
features {
  uv {
    doc     = "the libuv driver, sh.sysl.kairos.uv: TCP, pipes, files, child processes, signals; needs libuv installed"
    enables = [libuv]
  }
}
```

`skiff/kernel/package.hocon`'s `options` block is already in the final grammar. It gains only
`macro` (where C reads a value) and the per-feature placement of § 7.2.

### 3.4 Old manifests (Q19)

| old | new | 0.5 says |
|---|---|---|
| `features { f = [a] }` | unchanged | nothing |
| `features { default = [a, b] }` | `a { default = true … }` | refused, the fix-it showing the rewritten block |
| `targets.<t>.features.<f> { k = v }` | `features.<f>.targets.<t> { k = v }` | refused, the same way |
| `c`/`defines` under a target | now meaningful | no longer the "ignored" warning |

`sysl config fix` applies every fix-it in place, by the edit engine of § 5.3, so the comments
survive. **A manifest using any 0.5 construct needs `package.sysl` at least `"0.5.0"`**. The
reader says so when the floor is lower. `sysl config` raises the floor when it writes such a
construct. A 0.4 compiler then refuses with "needs sysl 0.5.0" instead of mis-reading a feature
block as a malformed list.

## 4. Options and features reach C and sysl

### 4.1 The config header (Q14)

For each package that declares any `macro` or `config`, the build writes **`sysl/<package>.h`**
into a generated include root. The `sysl/` directory keeps it from colliding with an upstream
header. The header holds, in this order:

1. each option with a `macro`, at its settled value. A `bool` is written `1` or `0`, an integer as
   a decimal literal, a string as a C string literal;
2. each feature with a `macro`: `1` if it is on, `0` if it is off;
3. the `config` lines of the package, then of the target being built, then of each enabled feature.
   A value is a C token sequence, written as a `defines` block's are (`"\"x.h\""` for a string).

An option inside a feature that is off writes nothing (Q16), so the C's own `#ifndef` default
stands. **The header reaches** every C file of the package (its libraries, shims, `c const` probes)
and every package that takes its exported roots, because the values are ABI. `configUSE_16_BIT_TICKS`
changes `TickType_t`, and a consumer's C including `FreeRTOS.h` must see the same width.

**What the header buys over `-D`.** It is a file. `sysl build --emit config` writes it out, a reader opens it,
and the package's own config header `#include`s it at the right point among its own definitions.
A `-D` set would be invisible to a reader and would apply to every translation unit of the build.
The run cache already keys on settled values (§ Values and the caches), so nothing new is keyed.

### 4.2 In sysl (Q15)

The package's own files see `option_<name>` as a constant, as today, and `feature_<name>` in `#if`.
Proposed addition: a `bool` option is also an `#if` symbol, so a subsystem configured by a value
can drop its lines. An integer option sizes arrays and folds in `if`, `match` and `@assert`, which
covers every use skiff makes of `cores`, `tasks` and `cache_line`.

### 4.3 Who sets what

These are 0.4's rules, unchanged:

- **The package declares**: type, default, range, values, doc.
- **A consumer sets** values on its dependency entry. The root's value wins. An intermediate
  package's value (a board package setting `cores = 2`) applies where the root is silent. Two
  intermediate packages that disagree are refused.
- **Features unify by union.** Choices unify by the one explicit pick.

That is the menuconfig split already: the OS declares the menu, the board package sets what the
board fixes, and the application overrides the rest. What 0.5 adds is the menu itself (§ 5) and the
path to C (§ 4.1).

## 5. `sysl config`

### 5.1 The commands (Q20, Q22)

```
sysl init [<dir>] [--name <n>] [--lib]
sysl config dep add <coordinate>[@<version>] [--optional] [--if <cond>] [--label <l>]
sysl config dep remove <label>
sysl config dep <label> --features +cdc,-msc        choose a dependency's features and choice members
sysl config dep <label> --set cdc_rx_buffer=256      set a dependency's option
sysl config feature add <f> [--doc …] [--default] [--enables a,b] [--if <cond>]
sysl config feature remove <f>
sysl config option add <o> --type u32 --default 100 [--range 1,10000] [--macro …] [--feature <f>]
sysl config target add <t> [--linker-script …] [--entry …]
sysl config c add <lib> --root … --sources … [--feature <f>] [--target <t>]
sysl config get|set|unset <path> [<value>]           the escape hatch, by HOCON path
sysl config fix                                       apply every fix-it of § 3.4
sysl config show [--target <t>] [--features …]        the settled configuration (below)
sysl config                                           the menu, on a terminal
```

Every edit is checked by the manifest reader before it is written, as `sysl add` is today. An edit
the reader would refuse leaves the file untouched and prints the refusal. `--dry-run` prints the
diff instead of writing.

**`sysl config show` is the manifest half of keeping a systems build explicit.** For one machine
and one feature set it prints what the manifests *resolve to*:

- each package's enabled features and choices;
- every option with its value and **who set it**: the default, the board package `qemu-an521`, or
  this project;
- each C library's final source list and include order;
- the memory regions and which package declared each one.

It is `sysl deps` one level deeper, and it writes nothing. The generated files — headers and the
linker script — are `sysl build --emit`'s (§ 8.6). Under the split, `config` never renders a build
output.

### 5.2 The menu (Q21)

`sysl config` on a terminal opens a full-screen tree over the **root project's view**:

```
 app 0.1.0 — thumb-freestanding                                        [ / ] search  [ ? ] doc
 ├─ tinyusb 0.1.0
 │   ├─ port        (•) rp2350   ( ) rp2040   ( ) stm32h7 — not this machine's
 │   ├─ role        [x] device   [ ] host
 │   ├─ [x] cdc     ports 1 (1..4) · rx buffer 256 (64..4096) · tx buffer 256
 │   ├─ [x] msc     buffer 4096 — set by this project, default 512
 │   └─ [ ] hid
 └─ freertos 1.0.0
     ├─ port        (•) cm33_ntz
     ├─ tick_hz     1000 — set by this project, default 1000
     └─ [x] timers  priority 6 · queue length 10
```

- Choices are radio buttons. A member whose `if` fails on the selected target is shown and greyed,
  with the reason, so the menu reads the same on every machine.
- Features are check boxes. A feature's options are indented under it and greyed while it is off.
- An option shows its range or its values, and `?` shows its `doc`.
- **Edits write the root manifest's dependency entries** (`features`, `options`, `c_defines`) and
  never a dependency's manifest. The OS package declares the menu, and the application's file holds
  the answers.
- `--target` picks the machine the menu evaluates `if` against. The default is `targets.default`,
  then the host.

The menu is a front over the subcommands. Every change it makes is one of them, so the two cannot
drift. `sysl.term` today offers escape constants and a line editor. Raw keys and redraws for a
full-screen program are to be checked; if they are missing, that is a library item that comes
first (Rule 9).

### 5.3 Keeping comments and layout (Q23)

**This is the hard part.** The org's manifests are about three-fifths comment, and a tool that
reprints the parsed value deletes all of that. `manifest_edit.sysl` already does the right thing for
one case: it splices the fewest bytes, skips strings and all three comment forms, follows the
siblings' indent and alignment, and moves nothing that is already there. 0.5 generalises it:

- **A lossless tree.** Every token, including whitespace and comments, sits in a tree with its byte
  span. The manifest reader derives its values from that tree, so the reader and the editor cannot
  disagree about a file. This is generic HOCON machinery, so it belongs in `sysl-lang/parsing`
  first (org rule), which the compiler already depends on.
- **Edits are splices.** *Replace a value* rewrites only the value's span. *Add an entry* inserts
  one line into the deepest block that already exists, with the siblings' indent and their `=`
  column where they are aligned. A missing block is added in the style the file already uses
  (nested blocks, or a dotted key `a.b.c = 1` where the file writes those). *Remove an entry* takes
  its line, plus the comment lines directly above it with no blank line between, plus a trailing
  comment on the same line.
- **An inline block** (`{ a = 1, b = 2 }`) gains `, c = 3` in place. If the line would pass 100
  columns, that one entry is reflowed to a block, and nothing else moves.

These cases are refused or handled imperfectly:

- **One path written in two places.** A key may be spelled `a.b = 1` in one place and inside
  `a { … }` in another. The edit refuses and names both lines rather than guess which one is
  meant.
- **A comment written about two entries** loses its context when one of the entries is removed. The
  rule above keeps the comment only when it sat directly above the removed entry. `--dry-run` shows
  the diff for that reason.
- **Reordering or reformatting** is never done. A messy file stays messy apart from the lines
  edited.
- **Values computed elsewhere.** HOCON's `${…}` substitutions are not part of the subset sysl reads,
  so this case never arises.

The cost is honest too. `hocon.sysl` (409 lines) becomes a client of a new lossless reader, and
the edit operations are a new module of about `manifest_edit.sysl`'s size again. The tests are
golden files: an input manifest, an edit, the exact expected output, and a check that untouched
bytes are identical.

## 6. Worked example: TinyUSB

One vendored TinyUSB tree serves RP2040, RP2350 and STM32H7, as a device, a host, or both, with
the classes a consumer picks. Upstream is left byte for byte as released.

### 6.1 The package *(proposed)*

```hocon
// TinyUSB, vendored at c/tinyusb (upstream 0.18.0, tarball SHA-256 in README.md).
package { name = "tinyusb", version = "0.1.0", sysl = "0.5.0" }

choices {
  port  { doc = "which USB controller TinyUSB drives", of = [rp2040, rp2350, stm32h7] }
  speed { doc = "the root port's fastest speed", of = [full_speed, high_speed], default = full_speed }
}

// The core every build compiles. Each class and port file below is guarded by TinyUSB's own
// CFG_ macros, so the lists say what is compiled and the config header says what is in it.
c {
  tinyusb {
    root    = "c/tinyusb/src"
    include = [".", "/config"]
    export  = true
    sources = ["tusb.c", "common/tusb_fifo.c"]
  }
}

// /config/tusb_config.h is the package's, and it starts with #include "sysl/tinyusb.h".
config {
  CFG_TUSB_OS          = OPT_OS_NONE
  CFG_TUSB_MEM_ALIGN   = "__attribute__((aligned(4)))"
}

options {
  debug { type = u8, default = 0, range = [0, 3], macro = "CFG_TUSB_DEBUG", doc = "TinyUSB's log level; 0 is silent" }
}

features {

  // ---- ports: one per controller ----

  rp2040 {
    doc  = "the RP2040's USB controller (TinyUSB's rp2040 port)"
    if   = "thumb && freestanding"
    requires.headers.pico_sdk = { note = "the pico-sdk", env = "PICO_SDK_PATH" }
    config { CFG_TUSB_MCU = OPT_MCU_RP2040 }
    c {
      tinyusb {
        sources = ["portable/raspberrypi/rp2040/{dcd_rp2040,hcd_rp2040,rp2040_usb}.c"]
        include = ["/config/rp2040", "@pico_sdk/src/rp2040/hardware_regs/include",
                   "@pico_sdk/src/rp2040/hardware_structs/include", "@pico_sdk/src/rp2040/pico_platform/include"]
      }
      pico_hardware = ${rp2_hardware}       // see below: the same block in both RP features
    }
  }

  rp2350 {
    doc  = "the RP2350's USB controller (the same port), on its Arm or its RISC-V cores"
    if   = "freestanding && (thumb || riscv32)"
    requires.headers.pico_sdk = { note = "the pico-sdk", env = "PICO_SDK_PATH" }
    config { CFG_TUSB_MCU = OPT_MCU_RP2040, PICO_RP2350 = 1 }
    c {
      tinyusb {
        sources = ["portable/raspberrypi/rp2040/{dcd_rp2040,hcd_rp2040,rp2040_usb}.c"]
        include = ["/config/rp2350", "@pico_sdk/src/rp2350/hardware_regs/include",
                   "@pico_sdk/src/rp2350/hardware_structs/include", "@pico_sdk/src/rp2350/pico_platform/include",
                   "@pico_sdk/src/common/pico_base_headers/include", "@pico_sdk/src/rp2_common/hardware_irq/include",
                   "@pico_sdk/src/rp2_common/hardware_resets/include", "@pico_sdk/src/rp2_common/hardware_sync/include",
                   "@pico_sdk/src/rp2_common/hardware_base/include", "@pico_sdk/src/rp2_common/pico_platform_compiler/include"]
      }
      pico_hardware {                       // Q11: C compiled out of the required tree
        root    = "@pico_sdk/src/rp2_common"
        include = ["hardware_irq/include", "hardware_sync/include"]
        sources = ["hardware_irq/irq.c", "hardware_sync/sync.c"]
      }
    }
  }

  stm32h7 {
    doc  = "the STM32H7's Synopsys DWC2 OTG controllers (TinyUSB's dwc2 port)"
    if   = "thumb && freestanding"
    requires.headers.stm32cube_h7 = "STMicroelectronics' STM32CubeH7 tree, for its CMSIS headers — github.com/STMicroelectronics/STM32CubeH7"
    config {
      CFG_TUSB_MCU         = OPT_MCU_STM32H7
      CFG_TUSB_MEM_SECTION = "__attribute__((section(\".sysl.tinyusb.dma\")))"
    }
    // § 8.2: the OTG DMA cannot reach DTCM, so the buffers ask for a region tagged dma (Q28)
    sections { tinyusb_dma { contents = [".sysl.tinyusb.dma"], wants = dma, zero = true, align = 32 } }
    c {
      tinyusb {
        sources = ["portable/synopsys/dwc2/{dcd_dwc2,hcd_dwc2,dwc2_common}.c"]
        include = ["@stm32cube_h7/Drivers/CMSIS/Device/ST/STM32H7xx/Include", "@stm32cube_h7/Drivers/CMSIS/Include"]
      }
    }
  }

  full_speed { config { CFG_TUD_MAX_SPEED = OPT_MODE_FULL_SPEED, CFG_TUH_MAX_SPEED = OPT_MODE_FULL_SPEED } }
  high_speed { config { CFG_TUD_MAX_SPEED = OPT_MODE_HIGH_SPEED, CFG_TUH_MAX_SPEED = OPT_MODE_HIGH_SPEED } }

  // ---- roles ----

  device {
    doc     = "act as a USB device"
    default = true
    macro   = "CFG_TUD_ENABLED"
    options { ep0_size { type = u8, values = [8, 16, 32, 64], default = 64, macro = "CFG_TUD_ENDPOINT0_SIZE" } }
    c.tinyusb.sources = ["device/{usbd,usbd_control}.c"]
  }

  host {
    doc   = "act as a USB host"
    macro = "CFG_TUH_ENABLED"
    options {
      devices     { type = u8, default = 4, range = [1, 16], macro = "CFG_TUH_DEVICE_MAX", doc = "devices attached at once, hubs included" }
      enum_buffer { type = u16, default = 256, range = [64, 1024], macro = "CFG_TUH_ENUMERATION_BUFSIZE" }
    }
    c.tinyusb.sources = ["host/usbh.c"]
  }

  // ---- device classes; each enables the device role ----

  cdc {
    doc     = "a serial port (CDC-ACM)"
    enables = [device]
    options {
      ports     { type = u8,  default = 1,   range = [1, 4],     macro = "CFG_TUD_CDC" }
      rx_buffer { type = u16, default = 256, range = [64, 4096], macro = "CFG_TUD_CDC_RX_BUFSIZE" }
      tx_buffer { type = u16, default = 256, range = [64, 4096], macro = "CFG_TUD_CDC_TX_BUFSIZE" }
    }
    c.tinyusb.sources = ["class/cdc/cdc_device.c"]
  }

  msc {
    doc     = "a disk (mass storage, bulk-only)"
    enables = [device]
    options { buffer { type = u16, default = 512, range = [64, 16384], macro = "CFG_TUD_MSC_EP_BUFSIZE" } }
    config  { CFG_TUD_MSC = 1 }
    c.tinyusb.sources = ["class/msc/msc_device.c"]
  }

  hid {
    doc     = "keyboards, mice, gamepads and raw reports"
    enables = [device]
    options {
      interfaces { type = u8, default = 1,  range = [1, 4],  macro = "CFG_TUD_HID" }
      buffer     { type = u8, default = 16, range = [8, 64], macro = "CFG_TUD_HID_EP_BUFSIZE" }
    }
    c.tinyusb.sources = ["class/hid/hid_device.c"]
  }

  midi {
    doc = "MIDI"
    enables = [device]
    config { CFG_TUD_MIDI = 1 }
    c.tinyusb.sources = ["class/midi/midi_device.c"]
  }

  // ---- host classes; each enables the host role ----

  host_hub { enables = [host], config { CFG_TUH_HUB = 1 },  c.tinyusb.sources = ["host/hub.c"] }
  host_cdc { enables = [host], options { ports { type = u8, default = 1, range = [1, 4], macro = "CFG_TUH_CDC" } },
             c.tinyusb.sources = ["class/cdc/cdc_host.c"] }
  host_msc { enables = [host], config { CFG_TUH_MSC = 1 }, c.tinyusb.sources = ["class/msc/msc_host.c"] }
  host_hid { enables = [host], options { interfaces { type = u8, default = 4, range = [1, 8], macro = "CFG_TUH_HID" } },
             c.tinyusb.sources = ["class/hid/hid_host.c"] }

  // ---- what a default descriptor set says ----
}

options {
  vid          { type = u16,    default = 0xCAFE, macro = "SYSL_TUSB_VID", doc = "the vendor id the default descriptors carry" }
  pid          { type = u16,    default = 0x4000, macro = "SYSL_TUSB_PID" }
  manufacturer { type = string, default = "sysl" }
  product      { type = string, default = "TinyUSB device" }
}
```

`${rp2_hardware}` above stands for "the same `pico_hardware` block as rp2350's". HOCON
substitutions are not in sysl's subset, so in practice the block is written twice. That is a small
cost, and not a question. Two `options` blocks are written above for reading order; in a real file
they are one.

**What the grammar did and did not have to say:**

| need | how |
|---|---|
| port per chip | the `port` choice; each member adds its port files, its include roots and its `CFG_TUSB_MCU` |
| pico-sdk include path | `requires.headers.pico_sdk` inside the RP features; `@pico_sdk/…` roots (Q10, Q12) |
| the SDK functions the port calls | `pico_hardware`, compiled from the required tree (Q11) |
| ST's CMSIS | `requires.headers.stm32cube_h7`, inside `stm32h7` |
| the CMSIS part macro (`STM32H743xx`) | the consumer's `c_defines` (Q13) |
| `CFG_TUSB_MCU` and every other `tusb_config.h` value | the generated `sysl/tinyusb.h` (Q14); one package-owned `tusb_config.h`, never a header per app |
| classes and their buffer sizes | a feature per class, its sizes as options inside it (Q16) |
| device, host, or both | features `device` and `host`; classes `enable` their role |
| RP2040/RP2350 cannot be device *and* host on one controller | `/config/tusb_config.h`: `#if PICO_RP2350 && CFG_TUD_ENABLED && CFG_TUH_ENABLED` → `#error` (Q5) |
| `hcd_rp2040.c` only with host | listed with the chip; upstream guards it with `#if CFG_TUH_ENABLED` (Q6) |
| speed as a bare token | the `speed` choice with `config` lines (Q17) |
| RP2350 on its RISC-V cores | `if = "freestanding && (thumb \|\| riscv32)"` |
| H7 USB buffers kept out of DTCM | `CFG_TUSB_MEM_SECTION` names a section; `sections` asks for a `dma` region (§ 8.2, Q28) |

One file is honestly the package's own: **`pico/config_autogen.h` and `pico/version.h`**, which the
SDK's CMake generates. The package ships minimal copies under `config/rp2350/pico/`, for the SDK
version its README names. A consumer with a different SDK supplies its own through `c_include`,
which is searched first.

### 6.2 Descriptors and callbacks

TinyUSB asks the application for its descriptors and reports events through callbacks:
`tud_descriptor_device_cb`, `tud_descriptor_configuration_cb`, `tud_descriptor_string_cb`,
`tud_cdc_rx_cb`, `tud_msc_read10_cb`, and so on, which upstream declares weak. None of this is
manifest business. **It is the package's sysl layer**, and the grammar only has to make the options
visible there, which it already does:

- `sh.sysl.tinyusb.descriptors` builds the device and configuration descriptors **at compile time**
  from `option_vid`, `option_pid`, `option_product`, and the class features and options:
  `#if feature_cdc` adds `option_ports` CDC interface pairs. These are `const` functions, so the
  bytes are data in flash.
- The package `@export`s each `tud_descriptor_*_cb` and returns those bytes, or the set an
  application passed to `usb.start(descriptors = mine)`.
- Each class module, under `#if feature_<class>`, `@export`s that class's callbacks and forwards
  them to handlers the application registers (`cdc.on_receive(port, f)`). An MSC disk is a trait the
  application implements (`read10`, `write10`, `capacity`).
- An application that wants a callback in C instead turns the class's sysl module off (the
  `sysl_callbacks` option, default true) and writes it. Two definitions of one symbol are a link
  error, so the choice is explicit.

### 6.3 A Pico 2 application

```hocon
package { name = "pico-cdc-disk", version = "0.1.0", sysl = "0.5.0" }

dependencies {
  pico2 { git = "github.com/sysl-lang/pico2-board", version = "0.1.0" }   // start-up, clocks, memory, image_def (§ 8.3)
  tinyusb {
    git      = "github.com/sysl-lang/tinyusb"
    version  = "0.1.0"
    features = [rp2350, cdc, msc]
    options  = { vid = 0x2E8A, pid = 0x000A, product = "Pico 2 disk", cdc.rx_buffer = 512, msc.buffer = 4096 }
  }
}

targets {
  thumb-freestanding { entry = "_entry_point" }      // no linker_script: the board's memory is known (Q25)
}
```

```
sysl build --target thumb-freestanding -o app.elf .
```

`PICO_SDK_PATH` answers `pico_sdk`, `full_speed` is the `speed` default, and the `device` role
comes in through `cdc` and `msc`. `sysl build --emit config:tinyusb` writes out
`.sysl/out/thumb-freestanding/include/sysl/tinyusb.h`, the header the build compiled with:

```c
/* written by sysl from tinyusb 0.1.0's settled configuration */
#define CFG_TUSB_DEBUG 0
#define SYSL_TUSB_VID 11914
#define SYSL_TUSB_PID 10
#define CFG_TUD_ENABLED 1
#define CFG_TUH_ENABLED 0
#define CFG_TUD_ENDPOINT0_SIZE 64
#define CFG_TUD_CDC 1
#define CFG_TUD_CDC_RX_BUFSIZE 512
#define CFG_TUD_CDC_TX_BUFSIZE 256
#define CFG_TUD_MSC_EP_BUFSIZE 4096
#define CFG_TUSB_OS OPT_OS_NONE
#define CFG_TUSB_MEM_ALIGN __attribute__((aligned(4)))
#define CFG_TUSB_MCU OPT_MCU_RP2040
#define PICO_RP2350 1
#define CFG_TUD_MAX_SPEED OPT_MODE_FULL_SPEED
#define CFG_TUH_MAX_SPEED OPT_MODE_FULL_SPEED
#define CFG_TUD_MSC 1
```

The option names in `options = { cdc.rx_buffer = 512 }` are qualified by their feature. An option
declared inside a feature is named `<feature>.<option>`, so `cdc.ports` and `host_cdc.ports` do
not collide. In sysl the constant is `option_cdc_ports`.

### 6.4 An STM32H7 application: device on one port, host on the other

```hocon
package { name = "h7-bridge", version = "0.1.0", sysl = "0.5.0" }

dependencies {
  board { git = "github.com/sysl-lang/stm32h7-board", version = "0.1.0", features = [nucleo_h743zi] }   // memory, start-up (§ 8.2)
  tinyusb {
    git       = "github.com/sysl-lang/tinyusb"
    version   = "0.1.0"
    features  = [stm32h7, high_speed, cdc, host_hub, host_msc, host_hid]
    options   = { host.devices = 6 }
    c_defines = { STM32H743xx = true }
  }
}

targets {
  thumbv7em-freestanding-dp {
    entry  = "Reset_Handler"
    layout { code = flash, data = dtcm, stack = dtcm }     // § 8.2; TinyUSB's buffers go to a dma region by tag
  }
}
```

```
sysl build --target thumbv7em-freestanding-dp --include-path stm32cube_h7=$HOME/src/STM32CubeH7 -o bridge.elf .
```

The root port of each role is a run-time argument (`tusb_init(rhport, …)`), so the application
starts the device on `OTG_HS` and the host on `OTG_FS` in sysl. The clock tree, the GPIO alternate
functions and VBUS sensing are board code, which the application or a board package writes. They
are not TinyUSB's concern and not the manifest's.

**The one layout fact TinyUSB does own**: on the H7, the OTG controllers' DMA cannot reach DTCM,
and this application keeps its data in DTCM because it is the fastest RAM. TinyUSB's `stm32h7`
feature therefore puts its buffers in a section of their own and asks for DMA-reachable memory
(§ 8.2). Without that, the first transfer fails silently. With it, the buffers land in `ram_d2`
and nothing in this manifest had to know.

## 7. Configuring an operating system

What each OS in the org configures today:

| OS | today | 0.5 |
|---|---|---|
| **FreeRTOS** (`sysl-lang/freertos` 0.4.0) | declares only; the application builds the kernel against its own `FreeRTOSConfig.h` and passes three `--include-path`s, because "a vendored kernel would have to be compiled against your config" | the kernel is vendored and configured by options, features and choices (§ 7.1, Q24) |
| **skiff** (sysl) | `kernel` options `cores`, `tick_hz`, `tasks`, `cache_line`; board packages set `cores = 2` on the kernel entry; chips are packages | unchanged in kind. Subsystems can become features with options inside, `bool` options gate source, and the menu shows the kernel's options to the application (§ 7.2) |
| **keel** | a workspace; the kernel is a program, configured by constants in source (`PREEMPTION`, `TLS_SWITCH`, …) | nothing forced. Its controls could become `bool` options read in `#if`, set by the boot test's own manifest |
| **Zephyr** | binds against the headers Zephyr's CMake generates (`autoconf.h` from Kconfig) | **out of scope**: Kconfig and devicetree remain Zephyr's, and `requires.headers` remains the seam |

### 7.1 FreeRTOS, vendored *(proposed; Q24)*

```hocon
package { name = "freertos", version = "1.0.0", sysl = "0.5.0" }

choices {
  port { doc = "the processor FreeRTOS switches tasks on", of = [cm0, cm33_ntz, cm4f, cm7, posix] }
  heap { doc = "which heap_N.c is pvPortMalloc", of = [heap_1, heap_4, heap_5], default = heap_4 }
}

c {
  kernel {
    root    = "c/FreeRTOS-Kernel"
    include = ["include", "/config"]          // /config/FreeRTOSConfig.h is the package's
    export  = true
    sources = ["{tasks,queue,list,event_groups,stream_buffer,timers}.c"]
  }
}

options {
  tick_hz        { type = u32,   default = 1000,  range = [1, 10000], macro = "configTICK_RATE_HZ" }
  max_priorities { type = u8,    default = 7,     range = [2, 32],    macro = "configMAX_PRIORITIES" }
  min_stack      { type = u16,   default = 128,   macro = "configMINIMAL_STACK_SIZE", doc = "the idle task's stack, in words" }
  heap_bytes     { type = usize, default = 16384, macro = "configTOTAL_HEAP_SIZE", doc = "heap_1/heap_4's arena" }
  task_name      { type = u8,    default = 16,    range = [1, 64],    macro = "configMAX_TASK_NAME_LEN" }
  tick_16_bit    { type = bool,  default = false, macro = "configUSE_16_BIT_TICKS", doc = "changes TickType_t: ABI" }
  stack_check    { type = u8,    values = [0, 1, 2], default = 0, macro = "configCHECK_FOR_STACK_OVERFLOW" }
}

features {
  cm33_ntz {
    if = "thumb && freestanding"
    c.kernel { sources = ["portable/GCC/ARM_CM33_NTZ/non_secure/{port,portasm}.c"],
               include = ["portable/GCC/ARM_CM33_NTZ/non_secure"] }
  }
  cm7 {
    if = "thumb && freestanding"
    c.kernel { sources = ["portable/GCC/ARM_CM7/r0p1/port.c"], include = ["portable/GCC/ARM_CM7/r0p1"] }
  }
  // cm0 and cm4f the same way
  posix {
    if = "hosted && posix"
    c.kernel { sources = ["portable/ThirdParty/GCC/Posix/{port,utils/wait_for_event}.c"],
               include = ["portable/ThirdParty/GCC/Posix", "portable/ThirdParty/GCC/Posix/utils"] }
  }

  heap_1 { c.kernel.sources = ["portable/MemMang/heap_1.c"] }
  heap_4 { c.kernel.sources = ["portable/MemMang/heap_4.c"] }
  heap_5 { c.kernel.sources = ["portable/MemMang/heap_5.c"] }

  dynamic { doc = "pvPortMalloc, and every xCreate that uses it", default = true, macro = "configSUPPORT_DYNAMIC_ALLOCATION" }
  static  { doc = "xCreateStatic, with caller-owned storage", default = true, macro = "configSUPPORT_STATIC_ALLOCATION" }
  mutexes { default = true, macro = "configUSE_MUTEXES" }
  recursive_mutexes { enables = [mutexes], macro = "configUSE_RECURSIVE_MUTEXES" }
  queue_sets { macro = "configUSE_QUEUE_SETS" }
  timers {
    doc = "software timers and the timer service task"
    default = true
    macro = "configUSE_TIMERS"
    options {
      priority     { type = u8,  default = 6,   macro = "configTIMER_TASK_PRIORITY" }
      queue_length { type = u8,  default = 10,  range = [1, 255], macro = "configTIMER_QUEUE_LENGTH" }
      stack        { type = u16, default = 256, macro = "configTIMER_TASK_STACK_DEPTH" }
    }
  }
  trace { macro = "configUSE_TRACE_FACILITY", options { registry { type = u8, default = 8, macro = "configQUEUE_REGISTRY_SIZE" } } }
}

allocator { alloc = "pvPortMalloc", free = "vPortFree" }
```

`/config/FreeRTOSConfig.h` (the package's) is:

```c
#include "sysl/freertos.h"
#define configUSE_PREEMPTION 1
#define configKERNEL_PROVIDED_STATIC_MEMORY 1
/* …the few values the package fixes, each with the reason… */
#if configTIMER_TASK_PRIORITY >= configMAX_PRIORITIES
#error "freertos: timers.priority must be below max_priorities"
#endif
```

The application:

```hocon
dependencies {
  freertos {
    git      = "github.com/sysl-lang/freertos"
    version  = "1.0.0"
    features = [cm33_ntz, queue_sets, -timers]
    options  = { tick_hz = 1000, heap_bytes = 65536, max_priorities = 8 }
  }
}
```

`-timers` turns off a default feature by name. Today the only way is `default_features = false`
followed by naming everything back, so the `-` prefix is a small proposed addition (Q33). A host test of the bindings is
`features = [posix]` on the same package, so the 0.4 package's `test-config/FreeRTOSConfig.h`
becomes a `dev_dependencies` entry. The cross-field rule (timer priority below the priority count)
is the package's `#error`, as § 6 did for TinyUSB (Q5). The heap pair stays declared by the
package, and **`dynamic` off with code that allocates is still the link error 0.4's comment
describes**. A choice of `heap` with `dynamic` off is harmless, because `heap_N.c` compiles to
nothing.

A consumer who must hand-write `FreeRTOSConfig.h` keeps `c_include = ["config"]`, which is searched
first. Its own header can `#include "sysl/freertos.h"` to start from the settled values.

### 7.2 skiff (sysl-native)

`skiff/kernel/package.hocon` already declares typed, ranged, documented options, and
`boards/qemu-an521` sets `cores = 2` on its kernel entry. 0.5 changes nothing in that, and adds:

```hocon
// skiff/kernel/package.hocon — the subsystem features are illustrative
options {
  cores      { type = usize, default = 1,   range = [1, 8],     doc = "how many cores run the kernel, each with a Core of its own" }
  tick_hz    { type = u32,   default = 100, range = [1, 10000], doc = "how many times a second every core ticks" }
  tasks      { type = usize, default = 16,  range = [2, 4096],  doc = "…" }
  cache_line { type = usize, values = [16, 32, 64, 128], default = 32, doc = "the data cache's line in bytes" }
  stack_paint { type = bool, default = false, doc = "fill each stack with a pattern and report the high-water mark" }
}

features {
  channels {
    doc     = "buffer pools and zero-copy channels"
    default = true
    options { pools { type = usize, default = 4, range = [1, 255], doc = "pool ids a program may use" } }
  }
}
```

- `values` replaces "a power of two" in the doc string, so the menu offers four choices and the
  reader refuses `48` (Q17).
- `#if option_stack_paint` drops the painting code entirely when the option is off (Q15).
- `#if feature_channels` gates `pool.sysl` and `channel.sysl`, and `option_channels_pools` exists
  only under it (Q16).
- An application on a board package:

```hocon
dependencies {
  board  { git = "github.com/sysl-lang/skiff-board-pico2", version = "0.1.0" }   // sets kernel cores = 2
  kernel { git = "github.com/sysl-lang/skiff", version = "0.1.0", options = { tasks = 32, tick_hz = 1000 } }
}
```

The root's entry configures the kernel the board package reaches (matched by coordinate, as
today). `cores` stays the board's 2, `tasks` and `tick_hz` are the application's, and `sysl config
show` says so line by line. A skiff option a C driver needs (a chip's DMA descriptor count) takes a
`macro` and reaches the chip package's C through `sysl/kernel.h`. Nothing about skiff needs C to be
configured otherwise.

## 8. Generated linker scripts

Every freestanding image in the org links by a hand-written script: keel's `link.ld`, skiff's
per-board `link.ld` with `.shared (NOLOAD)` and `_stack_top0`/`_stack_top1`, the pico-sdk's
`memmap_default.ld`. Each script restates the chip's memory map, which is a fact about the chip,
and the stack sizes, which are facts about the program. When the program's options change, the
script does not follow. 0.5 has `sysl build` write the script from the manifests. It is the same
kind of output as the config header: generated, kept under `.sysl/`, and keyed in the cache.

### 8.1 The grammar *(proposed)*

```hocon
// in a chip or board package (Q26)
memory {
  <region> { origin = 0x2000_0000, length = 128K, kind = ram, tags = [fast] }
}
sections {
  <name> {
    contents = [".input.section", …]      // what goes in; the sysl side writes @section(".input.section")
    region   = <region>                    // or:
    wants    = <tag>                       // a region carrying this tag; the root may override (Q28)
    zero     = true                        // zeroed at start-up (in the zero table)
    load     = true                        // initialised: stored in rom, copied at start-up (in the copy table)
    noload   = true                        // neither: kept across a warm reset
    keep     = true                        // never collected (vectors, chip blocks)
    first    = true                        // at the region's start (RP2350's image_def)
    align    = 32
  }
}
symbols { _estack = stack_top, _sidata = data_load }     // aliases for a vendor's start-up (Q29)

// in the root, in the target block
layout {
  code  = flash
  data  = sram                 // .data and .bss
  stack = sram  { bytes = "option:kernel.stack_bytes", per_core = "option:kernel.cores" }   // Q27
  heap  = sram  { bytes = rest }                // optional; `rest` = what is left of the region
  place { tinyusb_dma = ram_d2 }                // override a library's tag-based placement
}
```

- **Sizes** take `K` and `M` (binary). Underscores are allowed in numbers, as in sysl. A `stack`
  with no `bytes` is 4K. A `layout` key left out takes the first region of a fitting `kind`.
- **A value from an option** is `"option:<package>.<name>"`: a board package's `flash_bytes` sets
  the flash length, and the kernel's `stack_bytes` and `cores` size the stacks. It is one whole
  option, never an expression (Q27).
- **The standard sections are always generated**: the vector table first in `code` (`keep`),
  `.text`, `.rodata`, `.ARM.exidx`, `.data` (stored in `code`, run in `data`), `.bss`, `.noinit`,
  then the stacks and the heap.
- **The start-up symbols** are sysl's (`__sysl_stack_top`, `__sysl_stack_top_<n>` per core,
  `__sysl_heap_start`/`_end`). The generated script also carries **a copy table and a zero table**
  (`__sysl_copy_table`: load, start, end; `__sysl_zero_table`: start, end). A board's start-up is
  one loop over each table, however many regions the program uses (Q29). `symbols` adds the names a
  vendor's start-up expects.
- **Placement from sysl** is the existing attribute: `@section(".sysl.tinyusb.dma") var buf: [512]u8`
  goes wherever the manifest's `sections` entry puts `.sysl.tinyusb.dma`. A section that no
  `sections` entry and no standard rule claims is refused at the link, naming the module that put
  it there. It is never left to the linker's orphan placement.
- **A `linker_script` in the target block** (or in an enabled feature's) means none is generated.
  That is the escape hatch, and its rules are 0.3.7's.

### 8.2 STM32H743: DTCM for speed, DMA buffers outside it

The board package declares the chip's memory and the board's flash part:

```hocon
// stm32h7-board/package.hocon
memory {
  flash   { origin = 0x0800_0000, length = "option:flash_bytes", kind = rom }
  itcm    { origin = 0x0000_0000, length = 64K,  kind = ram, tags = [fast, code] }
  dtcm    { origin = 0x2000_0000, length = 128K, kind = ram, tags = [fast] }       // the OTG DMA cannot reach it
  ram_d1  { origin = 0x2400_0000, length = 512K, kind = ram, tags = [dma] }
  ram_d2  { origin = 0x3000_0000, length = 288K, kind = ram, tags = [dma] }        // its clock is enabled by the start-up
  ram_d3  { origin = 0x3800_0000, length = 64K,  kind = ram, tags = [dma, backup] }
}
options { flash_bytes { type = usize, values = [1048576, 2097152], default = 2097152 } }
symbols { _estack = stack_top }
```

The application (§ 6.4) says `data = dtcm`. TinyUSB's `stm32h7` feature asks for `wants = dma`.
The generator picks the **first** region in declaration order that carries the tag, which is
`ram_d1`. The application can write `place { tinyusb_dma = ram_d2 }` to keep the AXI SRAM for
frame buffers. The tag's word "first" is the whole rule, and `sysl config show` prints the region
each section landed in.

The overflow report (Q30), when the application's `.bss` outgrows DTCM:

```
error: the region 'dtcm' (128 KiB, stm32h7-board) is 9,216 bytes too small for what this build
puts in it — data and stack, as layout.data and layout.stack say. The largest:
    65,536  freertos.ucHeap            (freertos heap_bytes = 65536, set by this project)
    32,768  stack, core 0              (layout.stack: kernel.stack_bytes = 32768)
    16,384  app.frame                  (app/display.sysl:12)
  move one with layout.data = ram_d1, or place a section of your own with @section
```

It is made by relinking with every region widened and reading the measured ELF's sections and
symbols. The ELF is the same whichever linker wrote it, so the report is too. Package and option
attribution come from the object each symbol came from and from the settled options.

### 8.3 RP2350: the image_def block

The RP2350's boot ROM looks for an `IMAGE_DEF` block in the first 4 KiB of flash. The board package
carries it in sysl and places it:

```hocon
// pico2-board/package.hocon
memory {
  flash     { origin = 0x1000_0000, length = "option:flash_bytes", kind = rom }
  sram      { origin = 0x2000_0000, length = 512K, kind = ram, tags = [dma] }
  scratch_x { origin = 0x2008_0000, length = 4K,   kind = ram, tags = [core0] }
  scratch_y { origin = 0x2008_1000, length = 4K,   kind = ram, tags = [core1] }
}
sections {
  image_def { contents = [".embedded_block"], region = flash, first = true, keep = true }
}
options { flash_bytes { type = usize, default = 4194304 } }
```

```sysl
// pico2-board: the minimal Arm secure executable IMAGE_DEF the datasheet gives (§ 5.9.5)
@section(".embedded_block")
val image_def: [5]u32 = [0xffffded3, 0x10210142, 0x000001ff, 0x00000000, 0xab123579]
```

Nothing references `image_def`, and sysl drops unreachable declarations before emission, so
`KEEP` in the script alone would keep nothing (Q32).

The vector table then follows it. The RP2040's 256-byte `boot2` is the same shape, `first = true`
in `flash`. Its trailing CRC is computed once, when the board package vendors the bytes, because
computing it at build time would be a build script.

### 8.4 skiff: per-core stacks and the shared partition

skiff's two hand-written `link.ld`s hold `.shared (NOLOAD)` after `.bss` and a stack per core. The
generated form:

```hocon
// skiff/kernel/package.hocon
options { stack_bytes { type = usize, default = 2048, range = [512, 65536], doc = "each core's boot stack" } }
sections {
  shared { contents = [".shared"], wants = shared, zero = true, align = 32 }   // one Shared, every core's
}

// skiff/boards/qemu-an521/package.hocon
memory {
  ram { origin = 0x1000_0000, length = 4M, kind = ram, tags = [shared] }
}
targets {
  thumb-freestanding-soft {
    entry  = "_start"
    layout {
      code  = ram
      data  = ram
      stack = ram { bytes = "option:kernel.stack_bytes", per_core = "option:kernel.cores" }
    }
  }
}
symbols { _stack_top0 = stack_top_0, _stack_top1 = stack_top_1, __shared_start = shared_start, __shared_end = shared_end }
```

`cores = 2` on the board's kernel entry now sizes the stacks as well as `[option_cores]Core`. Both
come from one value, so the linker script and the kernel cannot disagree. Today they can.
`kernel.shared_within` still proves the placement at boot, because the check is about what ran,
not about what the script said.

### 8.5 FreeRTOS

FreeRTOS's heap is a C array (`ucHeap[configTOTAL_HEAP_SIZE]` in `heap_4.c`), so `heap_bytes`
reaches the linker as `.bss` and its size is the option's. The overflow report above names it by
the option that sized it. Only `heap_5` takes its regions at run time (`vPortDefineHeapRegions`).
For it, a `layout.heap = ram_d1 { bytes = rest }` gives `__sysl_heap_start`/`_end`, and the
package's `heap_5` glue passes them on. The interrupt stack on a Cortex-M is the main stack, which
`layout.stack` sizes.

### 8.6 Both linkers, and inspecting the output

- **The generated script uses only what GNU ld and lld both implement**: `MEMORY`, `SECTIONS`, `>
  region AT> region`, `KEEP`, `ALIGN`, `NOLOAD`, `PROVIDE`, `ASSERT`, `LONG` (for the tables). It
  uses none of `INSERT`, `OVERWRITE_SECTIONS` or `INCLUDE`. The tests link each worked example with
  `ld.lld` and with `arm-none-eabi-ld` (`linker = "arm-none-eabi-ld"`, the existing key), and
  assert that the symbol addresses and section placements agree.
- **`sysl build --emit link`** writes `.sysl/out/<target>/link.ld` and prints its path.
  `--emit config` writes every `sysl/<package>.h`, `--emit config:<package>` writes one, and
  `--emit all` writes both. The build still runs, so what is shown is what was used (Q31). Nothing
  generated is ever written beside the sources. `.sysl/` is already a working directory, not source.

## 9. What this note does not change

- No build scripts. Everything above is declared: a generated header or linker script is a
  rendering of settled values, and nothing runs.
- Options still have no command-line flag for the root's own values. `sysl config` edits the file.
- Image keys stay root-only.
- `if` on a feature cannot name a feature, as on a dependency. Feature-to-feature links are
  `enables` and `choices`.
