---
title: sysl.args
summary: How argc and argv become a []string, and the two layers that read options out of them.
weight: 90
---

**Every declaration in `sysl.args`, with its signature:** [the generated API page](/api/sysl-args/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.args` is three things, in the order a program meets them:

- **`args_of`** turns what the platform hands an entry point into a `[]string`. Almost no program
  writes its name — declaring `main(args: []string)` is what calls it.
- **`Scan`** reads a command line one option at a time and leaves what each one *means* to the
  program. This is the layer a small program wants.
- **`Cli`** describes the options in a table and generates the `--help` that documents them.

The two parsing layers are not a beginner's and an expert's. They answer different shapes: a table
is worth writing when the options are many enough that their help text is the point, and a `match` is
better when there are three of them.

## Getting the arguments at all

```
args_of(argc: i32, argv: **u8) -> []string
```

What the platform hands an entry point is C's pair — a count, and a vector of NUL-terminated byte
runs. What a sysl program asks for is a slice of strings. Something has to walk the one and build the
other, and doing it **in the library** is what keeps the pair out of every sysl signature: the two
foreign types are named in one place instead of in every program that wants its arguments.

## Where it is actually called

A program's top-level statements are its entry point, and a declared `main` is the other way of
writing that same place — one that has the thing statements cannot get at: the arguments the program
was started with. A program starts in **one** place, so it writes one or the other, and a program that
wants its arguments puts inside `main` what it would otherwise have written above.

```sysl
main(args: []string)
    print("the work runs here, with", args.len, "argument")
```

```output
the work runs here, with 1 argument
```

Declaring it with a parameter is the whole of what asks for the conversion — the entry point the
compiler lays out is what calls `args_of`, which is why a program that reads its arguments still
contains no mention of this module.

The count is `1` above because the program was started with no arguments of its own. Element zero is
always there, and it is the program's own path — the same convention C has, and the reason a loop
over arguments starts at one:

```sysl
main(args: []string)
    print(args.len)

    for i in 1..<args.len
        print(i, args[i])
```

```output
1
```

Run through the compiler's own driver, **everything after a bare `--` belongs to the program**:

```
$ sysl run report.sysl -- --verbose report.txt
3
1 --verbose
2 report.txt
```

The split is made before sysl's own options are parsed, which is why an argument that looks like one
of sysl's is still the program's.

## The two signatures, and nothing else

`main()` and `main(args: []string)`. A `[]const string` is accepted in the same position, because a
program that only reads its arguments may say so and it costs the entry point nothing — the two
views are one layout, and what `args_of` yields may stand in for either:

```sysl
main(args: []const string)
    print(args.len)
```

```output
1
```

**A result other than a `Result[unit, E]` is refused**, because it would be an exit status, and an
exit status is not something a sysl signature spells:

```sysl
main() -> int
    0
```

```error
'main' yields nothing or a 'Result[unit, E]', so it may not result in int — a program's exit status is not something a signature can say
```

The one result a `main` may have is [`Result[unit, E]`](/reference/modules/), which is not an exit
status but an error to report: a failure travels out as a value, is printed on stderr, and the status
is `1`.

**The platform's own pair is refused**, which is the refusal this module exists to make unnecessary:

```sysl
main(argc: i32, argv: **u8)
    print(argc)
```

```error
'main' takes either nothing or one '[]string' of the program's arguments, not (int, **byte)
```

**Type parameters are refused**, since the caller is the platform and it has none to give:

```sysl
main[T]()
    print("nothing calls this with a type")
```

```error
'main' is called by the platform, which has no type arguments to give it
```

**And there is one `main` in a program**, wherever it is written — so a module may not declare one
beside the one the program starts at. That is the same reservation C makes and for the same reason:
it is not a name a program calls, it is the name the platform calls, and two of them would leave
which one the program *is* to whichever was emitted last.

```sysl
main()
    print("one")

main()
    print("the other one")
```

```error
'main' is where a program starts, so there is one — a second declaration of it would overload the name, and a program has one beginning rather than a set of them
```

## Reading options: the scanner

`Scan` knows the *shape* of a command line and nothing about which options a program has. It knows
that `--name=value` carries its value with it, that `-abc` is three options in one word, that `--`
ends the options and everything after it is an operand. Deciding what each one means is the `match`.

`scan(args)` skips the zeroth argument, since that is the program's own path and never an option.
`scan_all` reads every word it is given, which is what the examples on this page use — a page's
programs are run with no arguments, so the words have to come from somewhere.

```sysl
import sysl.args.*

read(argv: []string)
    var a = scan_all(argv)
    var verbose = false
    var output = "-"

    loop
        a.next() match
            Ok(Some(Short('v'))) | Ok(Some(Long("verbose"))) -> verbose = true

            Ok(Some(Short('o'))) | Ok(Some(Long("output"))) ->
                a.value() match
                    Ok(v)  -> output = v
                    Err(e) -> print("error:", e.message())

            Ok(Some(Positional(p))) -> print("operand:", p)

            Ok(None) -> break

            Ok(Some(_)) -> print("unknown option:", a.option)

            Err(e) ->
                print("error:", e.message())
                break

    print("verbose:", verbose)
    print("output :", output)
end read

read(["-vo", "out.txt", "one", "--", "-two"])
```

```output
operand: one
operand: -two
verbose: true
output : out.txt
```

Three things in that loop are the whole design.

**The `match` is why to prefer this to a query API.** An option nobody handled is a missing arm, and
[exhaustiveness](/reference/patterns/) makes that a diagnostic — where a `parsed.value("output")`
spelling would hand back a `None` at run time that reads exactly like an option the user did not
pass. `Short('o') | Long("output")` is the arm that reads best, and it is legal because an
alternative may not bind and neither of those two does.

**A value is asked for rather than reported.** Whether the next word belongs to the option or stands
on its own is not something the shape can tell — `-o x` is an option and its value, `-v x` is an
option and an operand. Only the program knows, so `value()` is a call it makes on the options that
take one, and all four spellings reach it: `-ox`, `-o x`, `--output=x`, `--output x`. What it takes
is the next word *whatever it looks like*, which is what getopt has always done and what every
program that has had to name a file `-` depends on.

**There is deliberately no `Iterate`**, so `for arg in a` does not compile. A `for` walks a *copy* of
its cursor, so the `value()` call inside the loop would advance a cursor the loop is not reading, and
every option's value would arrive somewhere else. The loop is written by hand and cannot go wrong
that way.

The scanner reports two failures of its own. One is an option whose value is not there; the other is
a value nobody asked for, which is the case a scanner without a `Result` gets wrong:

```sysl
import sysl.args.*

read(argv: []string)
    var a = scan_all(argv)

    loop
        a.next() match
            Ok(Some(Long(n))) -> print("long:", n)
            Ok(Some(Short(c))) -> print("short:", c)
            Ok(Some(Positional(p))) -> print("operand:", p)
            Ok(None) -> break
            Err(e) ->
                print("error:", e.message())
                break
end read

read(["--verbose=yes"])
read(["--output"])
```

```output
long: verbose
error: --verbose takes no value
long: output
```

`--verbose=yes` at a program whose `--verbose` takes nothing cannot be reported as an operand and
must not be dropped — silently dropping it would turn a mistake about what a flag means into a run
that looked successful. It is caught when the program goes on to the next argument, since asking for
a value is precisely what does *not* reveal it.

## Describing a command line

The upper layer adds a table, and what the table buys is the two things a hand-written `match` cannot
generate for itself: a usage text that cannot drift from the options it documents, and one wording
for every failure.

```sysl
import sysl.args.*

var verbose = flag('v', "verbose", "print more about what is happening")
var output  = option('o', "output", "path", "write the result here")
var dry     = long_flag("dry-run", "work out what would happen, and do none of it")

var spec = cli("count", [verbose, output, dry],
               about = "Count what is in a file.",
               version = "0.1.0",
               operands = "[file...]")

prints(help(spec))
```

```output
usage: count [options] [file...]

Count what is in a file.

options:
  -v, --verbose        print more about what is happening
  -o, --output <path>  write the result here
      --dry-run        work out what would happen, and do none of it
  -h, --help           show this help and exit
  -V, --version        show the version and exit
```

Six constructors build an option — `flag` and `option` for one written both ways, `long_flag` and
`long_option` for one with no letter, `short_flag` and `short_option` for one with no word. Only
`name` and the table are required of `cli`; `about`, `version` and `operands` are
[defaults](/reference/functions/), so a program writes the ones it has.

**An option is named by the value that declares it**, not by a string or an index:

```sysl
import sysl.args.*

var verbose = flag('v', "verbose", "print more")
var output  = option('o', "output", "path", "write here")
var spec    = cli("count", [verbose, output], operands = "[file...]")

parse(spec, ["count", "-v", "-o", "out.txt", "in.txt"]) match
    Ok(Ready(p)) ->
        print("verbose:", p.given(verbose))
        print("output :", p.value_or(output, "-"))
        print("files  :", p.positionals.len)
    Ok(HelpRequested)    -> print("help was asked for")
    Ok(VersionRequested) -> print("the version was asked for")
    Err(e)               -> print("error:", e.message())
```

```output
verbose: true
output : out.txt
files  : 1
```

The alternative spellings were both worse. A string — `p.value("output")` — makes a typo a `None`
that reads like an option nobody passed. An index into the table makes it worse: a wrong number is a
*different option's* value, silently, and it asks a program to keep two lists in step by hand. A
binding is a name, so a typo is `undefined name`.

The table is built **inside a body** rather than at the top level, and that is a rule rather than a
preference: an `Opt` holds strings, and a [module-level `val`](/reference/modules/) whose value is
built while the program runs is refused, since storage that lives for the whole run has nowhere to
write the release its count would need. A program wanting its description at the top level writes a
function returning one.

`p.count` is what a flag given more than once answers, so `-vvv` means what it means everywhere;
`p.value` is `None` for an option that was not given and for one that takes no value however often it
was; `p.positionals` is everything that was not an option, in the order it was written.

### Help and version

`--help` is always offered and `--version` whenever a version was given, along with `-h` and `-V` —
but **only where the program has not claimed the spelling**. A program whose `-V` means verbose keeps
it, and the help text then lists `--version` with no letter rather than taking one that means
something else:

```sysl
import sysl.args.*

var height  = option('h', "height", "n", "how tall")
var verbose = flag('V', "verbose", "say more")

prints(help(cli("thing", [height, verbose], version = "2.0")))
```

```output
usage: thing [options]

options:
  -h, --height <n>  how tall
  -V, --verbose     say more
      --help        show this help and exit
      --version     show the version and exit
```

What they do is **reported rather than done**. `parse` neither prints nor stops the program, which is
what keeps it a function of its arguments and what lets a test drive it:

```sysl
import sysl.args.*

var q    = flag('q', "quiet", "say less")
var spec = cli("thing", [q], version = "2.0")

say(argv: []string)
    parse(spec, argv) match
        Ok(Ready(_))         -> print("ready")
        Ok(HelpRequested)    -> print("help")
        Ok(VersionRequested) -> print("version")
        Err(e)               -> print("error:", e.message())
end say

say(["thing", "-q"])
say(["thing", "--help"])
say(["thing", "-V"])
say(["thing", "--nope"])
```

```output
ready
help
version
error: unknown option --nope
```

### The conventions, and who applies them

`parse_or_exit` is the one that acts, and its name says so:

- `--help` and `--version` print to **standard output** and exit **0**, because printing them was
  what the program was asked to do — which is what lets `prog --help | less` work.
- A command line that could not be read goes to **standard error** with the usage line and a pointer
  at `--help`, and exits **2** — the status getopt, argp and every parser since reserve for being
  invoked wrongly, as against 1 for running and failing.

```
$ wc --nope
wc: error: unknown option --nope
usage: wc [options] <file>
try 'wc --help' for more information.
$ echo $?
2
```

**The usage line and the help text are separate outputs on purpose.** `usage_line` is the one line;
`help` is the whole thing. Answering a mistyped flag with forty lines of help buries the sentence
saying what was mistyped.

Nothing in the help text is wrapped to a terminal width, which is a deliberate limit: asking how wide
the terminal is means asking the platform, and this is otherwise pure string work that a program with
no `os` capability can still call. A description longer than its column takes the next line.

## Calling it yourself

The function stays public, and there are two reasons — the second of which is the interesting one.

The first is the ordinary one: a program handed an `argv` by something **other than the platform** —
an embedder, a shell it implements, a test that wants to drive its own argument parsing — has
somewhere to go.

```sysl
import sysl.args.args_of
import sysl.text.cstring

var a = cstring("prog")
var b = cstring("--verbose")
var c = cstring("file.txt")
var vec = [a.ptr, b.ptr, c.ptr]
var made = args_of(3, &vec[0])

print(made.len)
print(made[0], made[1], made[2])
print(made[1].len, made[1] == "--verbose")
```

```output
3
prog --verbose file.txt
9 true
```

The second is that **this is the only surface on which an argument vector's failure can be reached at
all**, since a well-formed one is all a real process will ever hand over. That failure is the next
section.

## What the conversion actually does

Three things, and each is a decision worth knowing about.

**It finds each run's length by looking for the terminator**, rather than by calling `strlen`. So the
conversion asks the platform for nothing beyond the two values it was handed, which is what lets a
target with no libc still start a program.

**It validates and copies.** A `string` owns what it holds, so an argument outlives the vector it
came from, and nothing a program does to one reaches memory the platform still owns. That copy is
not an oversight to be optimized away later — a borrowed view into `argv` would be a slice whose
owner is the process image, which is a thing no sysl type describes.

**An argument that is not UTF-8 stops the program**, the way `unwrap` does, and it names the byte:

```sysl
import sysl.args.args_of

var bad: []u8 = [255, 0]
var vec = [&bad[0]]
var made = args_of(1, &vec[0])

print(made.len)
```

That program prints

```
panic: command-line argument 0 is not UTF-8 at byte 0
```

and exits with status 1. It is not a checked program on this page for that reason — a non-zero exit
is a failure to the harness — but the message is what a real one prints, and note that it *does*
print, unlike the [trap](/library/sync/) a violated contract lowers to. This one is an ordinary
`print` and `exit`, so the text reaches the terminal.

Putting the check here is deliberate: validation belongs **at the boundary**, so that everything
above it can treat a `string` as well-formed without asking. An argument vector is a boundary.

## Why it is a module of its own

Two reasons, and both are what a submodule is for.

**Almost nobody writes this name.** A `main(args: []string)` is what asks for the conversion, and
the entry point the compiler lays out is what makes it. A name nearly nobody writes has no business
in the set every file gets for free, so a program that does want it names `sysl.args.args_of` and
says so.

**It cannot live beside the platform externs in [`sysl.sys`](/library/sys/).** This calls `print` and
`exit`, which are `sysl`'s, and `sysl` reaches `sysl.sys` for its printing — putting both in one
module would make the two depend on each other, which the
[acyclic module graph](/reference/modules/) refuses. What is left in `sys` is a **leaf that needs
nothing**, which is what a platform module should be.

That second reason is worth sitting with, because it is a general shape rather than an accident of
this module. A conversion that reports its failure in words is not a leaf, because reporting is
itself a dependency. Splitting it out is what let the thing underneath stay one.

---

Next: [`sysl.sys`](/library/sys/) — the platform seam, and the leaf it was split out to protect.
