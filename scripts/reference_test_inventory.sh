#!/bin/sh
# Every test case of the reference compiler's suites, one per line, as
#
#     <suite file>\t<line>\t<case name>
#
# The reference is scalatest's AnyFreeSpec, so a leaf case is a string literal followed by `in`
# (`ignore` for a disabled one, whose name is printed with an `IGNORED\t` prefix). The two spellings
# are `"name" in {` on one line and `"name"` with the `in` on the next, and both appear. An
# interpolated name -- `s"$name sizes the array at $width"` -- is one case per iteration of the loop
# around it, so the count here is a floor on what sbt prints rather than the same number.
#
# The bootstrap tree is $SYSL_BOOTSTRAP, defaulting to the sibling checkout.

set -u

root=${SYSL_BOOTSTRAP:-$HOME/dev/sysl-lang/sysl-bootstrap}

for f in "$root"/shared/src/test/scala/sh/sysl/*.scala "$root"/jvm/src/test/scala/sh/sysl/*.scala; do
    [ -r "$f" ] || continue
    awk -v file="${f##*/}" '
        {
            line = $0
            sub(/^[ \t]+/, "", line)
            sub(/^s"/, "\"", line)
            if (match(line, /^"([^"]|\\")*"[ \t]*(in|ignore)([ \t{(]|$)/)) {
                q = index(substr(line, 2), "\"")
                name = substr(line, 2, q - 1)
                rest = substr(line, q + 2)
                sub(/^[ \t]+/, "", rest)
                tag = (rest ~ /^ignore/) ? "IGNORED\t" : ""
                printf "%s\t%d\t%s%s\n", file, NR, tag, name
                pending = ""
                next
            }
            if (match(line, /^"([^"]|\\")*"[ \t]*$/)) {
                q = index(substr(line, 2), "\"")
                pending = substr(line, 2, q - 1)
                pendingline = NR
                next
            }
            if (pending != "" && (line ~ /^in([ \t{(]|$)/ || line ~ /^ignore([ \t{(]|$)/)) {
                tag = (line ~ /^ignore/) ? "IGNORED\t" : ""
                printf "%s\t%d\t%s%s\n", file, pendingline, tag, pending
            }
            pending = ""
        }
    ' "$f"
done
