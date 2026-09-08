# sysl

The sysl compiler, written in sysl.

**This is the compiler that will ship as `sysl`.** Until it can build itself it is built by the
[bootstrap compiler](https://github.com/sysl-lang/sysl-bootstrap), which is written in Scala and
remains the reference: the two are checked against the same programs, and where they disagree the
bootstrap decides until this one has earned the last word. The language is specified at
[sysl.sh](https://sysl.sh), and nothing here changes it.

## Building

```
sysl build .
sysl test .
```

Both commands are the bootstrap compiler's, installed with `brew install sysl-lang/tap/sysl`. The
first produces `./sysl`; the second runs every `@test` in the tree.

## The road to self-hosting

1. A conformance suite: programs with the output and the diagnostics they owe, in a form either
   compiler can be run against. It is green against the bootstrap before anything else is written.
2. A scanner and a parser for sysl's grammar, written by hand, producing the same tree the bootstrap
   does.
3. Analysis, then LLVM emission, phase by phase against the suite.
4. This compiler builds itself, and the compiler it builds builds the same thing again.

## License

ISC. See `LICENSE`.
