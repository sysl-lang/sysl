#!/bin/sh
# Every test of this compiler's own suite, one per line, as
#
#     <tests_x.sysl>\t<line>\t<function name>\t<@test title>
#
# A test is an `@test` attribute -- bare, `@test("a title")`, or `@test(should_trap)` -- on the line
# above the function it names. The function name is what the map file cites, being the one of the
# two that a reader can grep for; the title is what the runner prints.

set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")

for f in "$root"/sh/sysl/compiler/tests_*.sysl; do
    [ -r "$f" ] || continue
    awk -v file="${f##*/}" '
        /^@test([(]|[ \t]*$)/ {
            title = ""
            if (match($0, /@test\("/)) {
                rest = substr($0, RSTART + 7)
                q = index(rest, "\"")
                if (q > 0) title = substr(rest, 1, q - 1)
            }
            at = NR
            pending = 1
            next
        }
        pending == 1 {
            name = $0
            sub(/\(.*$/, "", name)
            sub(/^[ \t]+/, "", name)
            if (name != "") {
                printf "%s\t%d\t%s\t%s\n", file, at, name, title
                pending = 0
            }
        }
    ' "$f"
done
