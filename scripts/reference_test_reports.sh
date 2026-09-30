#!/bin/sh
# Every test case the reference compiler's suite RAN, one per iteration, as
#
#     <suite file>\t<concrete name>\t<suite timestamp>\t<report file, relative to the bootstrap root>
#
# read out of sbt's JUnit reports, `TEST-<class>.xml`, which name every iteration of a case written
# inside a loop with the name that iteration computed -- the source inventory cannot, a template
# like `s"$name sizes the array at $width"` being one line of source and one case per target.
#
# The name is scalatest's FULL name: every enclosing `"group" - { ... }` joined in front of the leaf
# with a space. A name carrying newlines (a stripMargin literal) is joined to one line exactly as
# `reference_test_inventory.sh` joins one: each newline and the whitespace after it become a single
# space. XML's entities are decoded. <suite file> is the class's last segment plus `.scala`, the key
# `reference_tests.map` uses.
#
# THE REPORTS ARE WHATEVER THE LAST RUN LEFT IN `target/`, and a `testOnly` rewrites only the suites
# it names, so this list is only as current as the run behind each file -- which is why the census
# reads a committed snapshot of it (`reference_inventory.tsv`) and regenerates that only through
# `--refresh`, after checking these reports against the source.
#
# The bootstrap tree is $SYSL_BOOTSTRAP, defaulting to the sibling checkout.

set -u

root=${SYSL_BOOTSTRAP:-$HOME/dev/sysl-lang/sysl-bootstrap}

for d in native/target/test-reports doc/native/target/test-reports jvm/target/test-reports; do
    for f in "$root/$d"/TEST-*.xml; do
        [ -r "$f" ] || continue
        awk -v rel="$d/${f##*/}" '
            function attr(s, key,   i, v) {
                i = index(s, " " key "=\"")
                if (i == 0) return ""
                v = substr(s, i + length(key) + 3)
                return substr(v, 1, index(v, "\"") - 1)
            }
            function decode(s) {
                gsub(/\r/, "", s)
                gsub(/\n[ \t]*/, " ", s)
                gsub(/&#10;[ \t]*/, " ", s)
                gsub(/&lt;/, "<", s); gsub(/&gt;/, ">", s)
                gsub(/&quot;/, "\"", s); gsub(/&apos;/, "'"'"'", s); gsub(/&#39;/, "'"'"'", s)
                gsub(/&#9;/, "\t", s)
                gsub(/&amp;/, "\\&", s)
                return s
            }
            { buf = buf $0 "\n" }
            END {
                i = index(buf, "<testsuite ")
                head = substr(buf, i, index(substr(buf, i), ">"))
                stamp = attr(head, "timestamp")
                while ((i = index(buf, "<testcase ")) > 0) {
                    buf = substr(buf, i + 9)
                    tag = substr(buf, 1, index(buf, ">"))
                    cls = attr(tag, "classname")
                    n = split(cls, seg, ".")
                    printf "%s.scala\t%s\t%s\t%s\n", seg[n], decode(attr(tag, "name")), stamp, rel
                }
            }
        ' "$f"
    done
done
