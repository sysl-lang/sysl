#!/bin/sh
# Every test case of the reference compiler's suites, one per line, as
#
#     <suite file>\t<line>\t<case name>
#
# The reference is scalatest's AnyFreeSpec, so a leaf case is a string literal followed by `in`
# (`ignore` for a disabled one, whose name is printed with an `IGNORED\t` prefix). Three spellings of
# the literal appear: `"name" in {` on one line; `"name"` with the `in` on the next; and a name
# written as a stripMargin literal spanning two or more source lines --
#
#     """the first line of the name, wrapped for width
#       |the rest of it, however many '|'-continued lines it takes""".stripMargin in {
#
# A NAME THIS SHAPE IS NORMALIZED TO ONE LINE BY JOINING ITS SOURCE LINES WITH A SINGLE SPACE, each
# stripped of leading whitespace and of the stripMargin '|' marker -- so the two lines above collapse
# to "the first line of the name, wrapped for width the rest of it, however many '|'-continued lines
# it takes". That is a deliberate departure from "verbatim" (the real value carries embedded
# newlines, which a tab-separated line here cannot), and it is the same join `reference_tests.map`
# documents at its own header, so a row written against this output matches a row written by reading
# the source directly. A `"""..."""` literal closed on the SAME line it opens on is read as a single
# line and needs no join. An interpolated name -- `s"$name sizes the array at $width"` -- is one case
# per iteration of the loop around it, so the count here is a floor on what sbt prints rather than
# the same number.
#
# The bootstrap tree is $SYSL_BOOTSTRAP, defaulting to the sibling checkout.

set -u

root=${SYSL_BOOTSTRAP:-$HOME/dev/sysl-lang/sysl-bootstrap}

for f in "$root"/shared/src/test/scala/sh/sysl/*.scala "$root"/jvm/src/test/scala/sh/sysl/*.scala; do
    [ -r "$f" ] || continue
    awk -v file="${f##*/}" '
        # A name written as a stripMargin literal is found by tracking, for the WHOLE file, whether
        # each line is inside an already-open triple-quoted string -- `inString` -- because a line
        # that only closes an earlier one (most often a bare `""")` ending a multi-line fixture
        # opened mid-line, e.g. `read("""`) looks identical, read on its own, to a line that OPENS
        # one. Only an occurrence that (a) transitions closed -> open and (b) has nothing but
        # whitespace before it on its own line is a candidate for a case name; everything else -- a
        # fixture opened inline, a fixture closed alone on its line, the run of lines between them --
        # updates `inString` and is otherwise left alone. A candidate is confirmed only once its
        # matching close is immediately followed by `.stripMargin` (optional) and then `in`/`ignore`;
        # a close with no such tag is a fixture, not a case name, and is dropped -- it never printed
        # a row, so nothing needs undoing. This scan makes no use of `next`, so every line -- inside
        # a candidate span or not -- still reaches the single-quote logic below unchanged; a line
        # that opens, continues or closes a triple-quoted literal always starts with `"""` or `|`,
        # which that logic'"'"'s `^"..."` anchoring can never match, so the two never collide.
        {
            scanline = $0
            p = 1
            while (1) {
                idx = index(substr(scanline, p), "\"\"\"")
                if (idx == 0) break
                abspos = p + idx - 1
                if (!inString) {
                    before = substr(scanline, p, idx - 1)
                    if (p == 1 && before ~ /^[ \t]*$/) {
                        nameCandidate = 1
                        tripletext = ""
                        tripleline = NR
                    } else {
                        nameCandidate = 0
                    }
                    inString = 1
                } else {
                    if (nameCandidate) {
                        fragtrim = substr(scanline, p, idx - 1)
                        sub(/^[ \t]+/, "", fragtrim)
                        sub(/^\|/, "", fragtrim)
                        tripletext = (tripletext == "") ? fragtrim : tripletext " " fragtrim
                        rest = substr(scanline, abspos + 3)
                        sub(/^\.stripMargin/, "", rest)
                        sub(/^[ \t]+/, "", rest)
                        if (rest ~ /^(in|ignore)([ \t{(]|$)/) {
                            tag = (rest ~ /^ignore/) ? "IGNORED\t" : ""
                            printf "%s\t%d\t%s%s\n", file, tripleline, tag, tripletext
                        }
                        nameCandidate = 0
                    }
                    inString = 0
                }
                p = abspos + 3
            }
            if (inString && nameCandidate) {
                tailtrim = substr(scanline, p)
                sub(/^[ \t]+/, "", tailtrim)
                sub(/^\|/, "", tailtrim)
                tripletext = (tripletext == "") ? tailtrim : tripletext " " tailtrim
            }
        }
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
