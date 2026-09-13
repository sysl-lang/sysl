#!/bin/sh
# How much of the reference compiler's test suite this repository answers.
#
#     scripts/reference_test_census.sh                    the table, per suite, and the totals
#     scripts/reference_test_census.sh --unmapped         ...and every unmapped case name
#     scripts/reference_test_census.sh --unmapped Trait   ...only the suites whose name matches
#     scripts/reference_test_census.sh --candidates Trait a name-similarity suggestion per
#                                                         unmapped case, for whoever ports it next
#
# It exits non-zero while anything is unmapped, which is what `tests_census.sysl` gates on.
#
# The reference tree is $SYSL_BOOTSTRAP, defaulting to the sibling checkout; the site, whose
# executable documentation is the second population, is $SYSL_SITE.

set -u

here=$(cd "$(dirname "$0")" && pwd)
bootstrap=${SYSL_BOOTSTRAP:-$HOME/dev/sysl-lang/sysl-bootstrap}
site=${SYSL_SITE:-$HOME/dev/sysl-lang/sysl.sh}
map=$here/reference_tests.map

mode=${1:-}
pattern=${2:-}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

SYSL_BOOTSTRAP=$bootstrap "$here/reference_test_inventory.sh" > "$work/ref.tsv"
"$here/own_test_inventory.sh" > "$work/ours.tsv"

if [ ! -s "$work/ref.tsv" ]; then
    echo "no reference suites under $bootstrap -- set SYSL_BOOTSTRAP"
    exit 2
fi

# One line per reference case: suite, ignored?, mapped?, name.
awk -F'\t' -v mapfile="$map" '
    FILENAME == mapfile {
        if ($0 ~ /^#/ || NF < 3) next
        if ($2 == "*") wild[$1] = $3
        else           seen[$1 "\t" $2] = $3
        next
    }
    {
        name = $3
        ign = 0
        if (name ~ /^IGNORED\t/) { ign = 1; sub(/^IGNORED\t/, "", name) }
        key = $1 "\t" name
        how = (key in seen) ? seen[key] : (($1 in wild) ? wild[$1] : "")
        printf "%s\t%d\t%d\t%s\n", $1, ign, (how == "" ? 0 : 1), name
    }
' "$map" "$work/ref.tsv" > "$work/state.tsv"

if [ "$mode" = "--unmapped" ]; then
    awk -F'\t' -v p="$pattern" '$3 == 0 && (p == "" || index($1, p)) { printf "%-34s %s\n", $1, $4 }' "$work/state.tsv"
    exit 0
fi

if [ "$mode" = "--candidates" ]; then
    # The suggestion is the test here whose name shares the most content words with the reference
    # case's. It is a hint and never a mapping: a score says two names rhyme, not that two bodies
    # assert one claim on one input.
    awk -F'\t' -v p="$pattern" '
        function words(s,   i, n, parts, out) {
            s = tolower(s)
            gsub(/[^a-z0-9]+/, " ", s)
            n = split(s, parts, " ")
            out = " "
            for (i = 1; i <= n; i++)
                if (parts[i] != "" && !(parts[i] in stop)) out = out parts[i] " "
            return out
        }
        BEGIN {
            n = split("a an the is are of to in on at for with and or not it its this that as be " \
                      "by from into one two which what when where how does do so still also only " \
                      "every each any no", s, " ")
            for (i = 1; i <= n; i++) stop[s[i]] = 1
        }
        NR == FNR { ours[FNR] = $1 ":" $3; ow[FNR] = words($3 " " $4); on = FNR; next }
        $3 == 0 && (p == "" || index($1, p)) {
            nr = split(words($4), rp, " ")
            best = ""; bs = 0
            for (i = 1; i <= on; i++) {
                hit = 0
                for (j = 1; j <= nr; j++) if (index(ow[i], " " rp[j] " ")) hit++
                no = split(ow[i], op, " ")
                if (nr + no - hit > 0) {
                    sc = hit / (nr + no - hit)
                    if (sc > bs) { bs = sc; best = ours[i] }
                }
            }
            printf "%.2f  %-30s %-58s %s\n", bs, $1, substr($4, 1, 58), best
        }
    ' "$work/ours.tsv" "$work/state.tsv" | sort -rn
    exit 0
fi

echo "reference: $bootstrap"
echo "map:       $map"
echo
printf "%-36s %6s %6s %7s %8s\n" SUITE TOTAL IGN MAPPED UNMAPPED
awk -F'\t' '
    { n[$1]++; g[$1] += $2; m[$1] += $3 }
    END { for (f in n) printf "%-36s %6d %6d %7d %8d\n", f, n[f], g[f], m[f], n[f] - m[f] }
' "$work/state.tsv" | sort -k5 -rn

awk -F'\t' '
    { n++; g += $2; m += $3 }
    END { printf "%-36s %6d %6d %7d %8d\n", "-- every suite", n, g, m, n - m }
' "$work/state.tsv"

# The executable documentation is a second population: `DocsTests` compiles every ```sysl block a
# page carries an `output` or an `error` block under. A fragment is never compiled, so it is
# counted and not owed.
docs=$site/test/DocsTests.scala
if [ -r "$docs" ]; then
    echo
    echo "sysl.sh DocsTests -- blocks the suite compiles"
    grep -oE '"docs/content/[^"]*"[ ]*->[ ]*\([0-9]+, *[0-9]+, *[0-9]+\)' "$docs" |
        sed 's/"//g; s/ *-> *(/ /; s/)//; s/, */ /g' |
        awk '{ r += $2; e += $3; g += $4; p++ }
             END { printf "%d pages, %d runnable, %d refused, %d fragments -- %d blocks owed\n",
                          p, r, e, g, r + e }'
fi

left=$(awk -F'\t' '$3 == 0' "$work/state.tsv" | wc -l | tr -d ' ')
echo
echo "UNMAPPED $left"
[ "$left" -eq 0 ]
