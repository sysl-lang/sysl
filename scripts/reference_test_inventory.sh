#!/bin/sh
# Every test case WRITTEN in the reference compiler's suites, one per line, as
#
#     <suite file>\t<line>\t<case name>\t<flags>\t<source path, relative to the bootstrap root>
#
# This is the SOURCE half of the census: what a reader of the Scala sees. It is one row per case as
# written, so a case written once inside a loop is one row here and many in sbt's reports -- the
# per-iteration list is `reference_test_reports.sh`, and the census joins the two.
#
# <flags> is `-`, or any of: `t` the name is an interpolated literal (`s"$name sizes the array at
# $width"`), so it is a TEMPLATE, one case per iteration of the loop around it; `v` the name is not
# a literal at all but an expression (`label in {`, `s.what in {`), printed verbatim, and every
# iteration's name is whatever the loop computes; `i` the case is `ignore`d.
#
# The reference is scalatest's AnyFreeSpec, so a leaf case is a name followed by `in` (`ignore` for
# a disabled one). Three spellings of a literal name appear: `"name" in {` on one line; `"name"`
# with the `in` on the next; and a name written as a stripMargin literal spanning two or more source
# lines --
#
#     """the first line of the name, wrapped for width
#       |the rest of it, however many '|'-continued lines it takes""".stripMargin in {
#
# A NAME THIS SHAPE IS NORMALIZED TO ONE LINE BY JOINING ITS SOURCE LINES WITH A SINGLE SPACE, each
# stripped of leading whitespace and of the stripMargin '|' marker -- so the two lines above collapse
# to "the first line of the name, wrapped for width the rest of it, however many '|'-continued lines
# it takes". That is a deliberate departure from "verbatim" (the real value carries embedded
# newlines, which a tab-separated line here cannot), and it is the same join `reference_tests.map`
# documents at its own header and `reference_test_reports.sh` applies to sbt's names. A `"""..."""`
# literal closed on the SAME line it opens on is read as a single line and needs no join.
#
# EVERY `.scala` UNDER ANY `src/test/scala` OF THE TREE IS READ, at any depth -- the root project's
# `shared`/`jvm`/`native` and the `doc` subproject's alike. A glob naming the directories once missed
# four whole suites; the census checks that every file with an `in {` in it produced a row here.
#
# The bootstrap tree is $SYSL_BOOTSTRAP, defaulting to the sibling checkout.

set -u

root=${SYSL_BOOTSTRAP:-$HOME/dev/sysl-lang/sysl-bootstrap}

# `--groups` prints the GROUP names instead -- every `"name" - {` -- in the same five columns, for
# the census to strip from the front of sbt's full names when it attributes an iteration.
groups=0
[ "${1:-}" = "--groups" ] && groups=1

find "$root" -path '*/src/test/scala/*' -name '*.scala' -not -path '*/target/*' |
    LC_ALL=C sort |
while IFS= read -r f; do
    [ -r "$f" ] || continue
    awk -v file="${f##*/}" -v rel="${f#"$root"/}" -v groups="$groups" '
        # The position, within s (which opens with a quote), of the quote that closes it -- the
        # first one not escaped by a backslash, so `"beside '"'"'@section(\"x\")'"'"'"` is read whole.
        function closing(s,   i, c) {
            for (i = 2; i <= length(s); i++) {
                c = substr(s, i, 1)
                if (c == "\\") { i++; continue }
                if (c == "\"") return i
            }
            return 0
        }
        function emit(ln, nm, fl) {
            if (groups) return
            if (fl == "") fl = "-"
            printf "%s\t%d\t%s\t%s\t%s\n", file, ln, nm, fl, rel
        }
        function group(ln, nm, fl) {
            if (!groups) return
            if (fl == "") fl = "-"
            printf "%s\t%d\t%s\t%s\t%s\n", file, ln, nm, fl, rel
        }
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
                        if (rest ~ /^(in|ignore)([ \t{(]|$)/)
                            emit(tripleline, tripletext, (rest ~ /^ignore/) ? "i" : "")
                        else if (rest ~ /^-[ \t]*\{/)
                            group(tripleline, tripletext, "")
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
            interp = sub(/^s"/, "\"", line)
            if (match(line, /^"([^"]|\\")*"[ \t]*(in|ignore)([ \t{(]|$)/)) {
                q = closing(line)
                name = substr(line, 2, q - 2)
                rest = substr(line, q + 1)
                sub(/^[ \t]+/, "", rest)
                emit(NR, name, ((interp && index(name, "$")) ? "t" : "") ((rest ~ /^ignore/) ? "i" : ""))
                pending = ""
                next
            }
            if (match(line, /^"([^"]|\\")*"[ \t]*-[ \t]*\{/)) {
                q = closing(line)
                name = substr(line, 2, q - 2)
                group(NR, name, (interp && index(name, "$")) ? "t" : "")
                pending = ""
                next
            }
            if (match(line, /^"([^"]|\\")*"[ \t]*$/)) {
                q = closing(line)
                pending = substr(line, 2, q - 2)
                pendingflag = (interp && index(pending, "$")) ? "t" : ""
                pendingline = NR
                next
            }
            if (pending != "" && (line ~ /^in([ \t{(]|$)/ || line ~ /^ignore([ \t{(]|$)/)) {
                emit(pendingline, pending, pendingflag ((line ~ /^ignore/) ? "i" : ""))
                pending = ""
                next
            }
            if (pending != "" && line ~ /^-[ \t]*\{/) {
                group(pendingline, pending, pendingflag)
                pending = ""
                next
            }
            pending = ""
            # A case named by an expression rather than a literal: `label in {`, `s.what in {`.
            if (!inString && match(line, /^[A-Za-z_][A-Za-z0-9_.]*[ \t]+(in|ignore)[ \t]*[{(]/)) {
                expr = line
                sub(/[ \t]+(in|ignore)[ \t]*[{(].*$/, "", expr)
                emit(NR, expr, "v" ((line ~ /[ \t]ignore[ \t]*[{(]/) ? "i" : ""))
            }
        }
    ' "$f"
done
