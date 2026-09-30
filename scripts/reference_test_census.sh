#!/bin/sh
# How much of the reference compiler's test suite this repository answers.
#
#     scripts/reference_test_census.sh                    the table, per suite, and the totals
#     scripts/reference_test_census.sh --unmapped         ...and every unmapped iteration
#     scripts/reference_test_census.sh --unmapped Trait   ...only the suites whose name matches
#     scripts/reference_test_census.sh --candidates Trait a name-similarity suggestion per
#                                                         unmapped iteration, for whoever ports it next
#     scripts/reference_test_census.sh --orphans          map rows that match no case at all
#     scripts/reference_test_census.sh --fixes            bootstrap fix tests not yet ported here
#     scripts/reference_test_census.sh --refresh          rewrite the snapshot from sbt's reports,
#                                                         only if they agree with the source
#     scripts/reference_test_census.sh --refresh --partial  ...and write it even where they do not,
#                                                         every disagreement recorded in its header
#
# It exits non-zero while anything is unmapped or the snapshot disagrees with the source, which is
# what `tests_census.sysl` gates on.
#
# WHAT IS COUNTED IS EVERY ITERATION THE REFERENCE'S SUITE RUNS. A case written once inside a loop
# -- `for kind <- kinds do s"'$line' in $kind is ..." in {` -- runs once per element, and each run
# is its own row: the committed snapshot `reference_inventory.tsv`, derived from sbt's JUnit reports
# by `--refresh` (never a copy of them; the XML stays in the bootstrap's `target/`). Beside that the
# table prints the WRITTEN count, one per case as the source spells it, from
# `reference_test_inventory.sh`.
#
# THE REPORTS ARE STALE BY DEFAULT: a `testOnly` rewrites only the suites it names, and every test
# added to the bootstrap after the run has no report at all. So the snapshot records the bootstrap
# commit its reports were measured on (read from the bootstrap's HEAD reflog at the oldest report's
# timestamp), and every run of this script checks it against the live source:
#
#     no-report      a suite with written cases and no report: counted from source, one per case
#     never-ran      a written case no iteration in the snapshot belongs to
#     unexplained    a ran case no written case accounts for (the source lost or renamed it)
#     no-source      a whole suite that ran and is no longer in the source
#     changed        a test file the bootstrap changed after the measured commit
#     unread         a file under a `src/test/scala` holding an `in {` that produced no written case
#     moved          the bootstrap's HEAD moved while the reports were being written
#
# Any of them is printed under STALE and fails the run. The cure is a fresh full run of the
# reference suite (`sbt syslNative/test doc/test` in the bootstrap -- see the census section of
# CLAUDE.md for its cost) and `--refresh`.
#
# The reference tree is $SYSL_BOOTSTRAP, defaulting to the sibling checkout; the site, whose
# executable documentation is the second population, is $SYSL_SITE.

set -u

here=$(cd "$(dirname "$0")" && pwd)
bootstrap=${SYSL_BOOTSTRAP:-$HOME/dev/sysl-lang/sysl-bootstrap}
site=${SYSL_SITE:-$HOME/dev/sysl-lang/sysl.sh}
map=$here/reference_tests.map
snapshot=$here/reference_inventory.tsv
ledger=$here/fix_tests.tsv

mode=${1:-}
pattern=${2:-}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [ "$mode" = "--fixes" ]; then
    awk -F'\t' '
        /^#/ || NF < 7 { next }
        $2 == "bootstrap" && $7 == "unported" { n++; printf "%s  %-9s %-28s %s\n      %s\n", $1, $3, $5, $4, $6 }
        END { printf "\n%d bootstrap fix tests unported\n", n }
    ' "$ledger"
    exit 0
fi

# The live source, when there is one: the written half, and what the checks compare against.
have_source=0
if [ -d "$bootstrap" ]; then
    SYSL_BOOTSTRAP=$bootstrap "$here/reference_test_inventory.sh" > "$work/src.tsv"
    [ -s "$work/src.tsv" ] && have_source=1
fi

# Files holding a case the inventory did not read. Anything under a `src/test/scala` with an
# `in {` or `ignore {` in it has to have produced a written case, or the inventory has a blind spot.
unread() {
    find "$bootstrap" -path '*/src/test/scala/*' -name '*.scala' -not -path '*/target/*' |
        while IFS= read -r f; do
            grep -qE '(^|[ \t"])(in|ignore)[ \t]*[{(]' "$f" || continue
            b=${f##*/}
            awk -F'\t' -v b="$b" '$1 == b { found = 1; exit } END { exit !found }' "$work/src.tsv" ||
                printf 'unread\t%s\t%s\n' "$b" "${f#"$bootstrap"/}"
        done
}

# The test files the bootstrap changed after the commit a snapshot was measured on.
changed_since() {
    [ "$1" = "unknown" ] && { printf 'changed\t*\tthe measured commit is unknown\n'; return; }
    if ! git -C "$bootstrap" rev-parse -q --verify "$1^{commit}" > /dev/null 2>&1; then
        printf 'changed\t*\t%s is not a commit of the checkout at %s\n' "$1" "$bootstrap"
        return
    fi
    git -C "$bootstrap" diff --name-only "$1" HEAD -- '*/src/test/scala/*' |
        while IFS= read -r f; do printf 'changed\t%s\t%s\n' "${f##*/}" "$f"; done
}

if [ "$mode" = "--refresh" ]; then
    if [ "$have_source" -eq 0 ]; then
        echo "no reference suites under $bootstrap -- set SYSL_BOOTSTRAP"
        exit 2
    fi
    SYSL_BOOTSTRAP=$bootstrap "$here/reference_test_reports.sh" > "$work/ran.tsv"
    if [ ! -s "$work/ran.tsv" ]; then
        echo "no JUnit reports under $bootstrap/{native,doc/native,jvm}/target/test-reports"
        exit 2
    fi
    files=$(cut -f4 "$work/ran.tsv" | sort -u | wc -l | tr -d ' ')
    oldest=$(cut -f3 "$work/ran.tsv" | sort | head -1)
    newest=$(cut -f3 "$work/ran.tsv" | sort | tail -1)
    # The commit is what HEAD was when the first report was written: the last reflog entry at or
    # before that moment. An entry between the first and the last report means HEAD moved mid-run.
    git -C "$bootstrap" reflog --date=format-local:%Y-%m-%dT%H:%M:%S --format='%H %gd' > "$work/reflog"
    commit=$(awk -v t="$oldest" '{ s = $2; sub(/^[^{]*\{/, "", s); sub(/\}$/, "", s) } s <= t { print $1; exit }' "$work/reflog")
    [ -n "$commit" ] || commit=unknown
    : > "$work/checks.tsv"
    awk -v a="$oldest" -v b="$newest" '{ s = $2; sub(/^[^{]*\{/, "", s); sub(/\}$/, "", s) }
        s > a && s <= b { printf "moved\t*\tHEAD became %s at %s, inside the run\n", substr($1, 1, 10), s }' \
        "$work/reflog" >> "$work/checks.tsv"
    short=$(printf '%s' "$commit" | cut -c1-10)
    SYSL_BOOTSTRAP=$bootstrap "$here/reference_test_inventory.sh" --groups > "$work/groups.tsv"
    awk -F'\t' -f "$here/reference_test_match.awk" -v checks="$work/match-checks.tsv" -v commit="$short" \
        "$work/groups.tsv" "$work/src.tsv" "$work/ran.tsv" > "$work/body.tsv"
    [ -f "$work/match-checks.tsv" ] && cat "$work/match-checks.tsv" >> "$work/checks.tsv"
    changed_since "$commit" >> "$work/checks.tsv"
    unread >> "$work/checks.tsv"
    if [ -s "$work/checks.tsv" ]; then
        echo "STALE -- the reports disagree with the source:"
        sort "$work/checks.tsv" | awk -F'\t' '{ printf "  %-12s %-34s %s\n", $1, $2, substr($3, 1, 90) }'
        if [ "${2:-}" != "--partial" ]; then
            echo
            echo "snapshot NOT written; rerun the reference suite, or pass --partial to record these"
            exit 3
        fi
    fi
    described=$(git -C "$bootstrap" describe --tags "$commit" 2>&1 || true)
    {
        echo "# The reference compiler's suite, one row per ITERATION it ran -- derived from sbt's JUnit"
        echo "# reports by \`reference_test_census.sh --refresh\`; never edited by hand, never a copy of the XML."
        echo "# Columns: suite, full name as sbt printed it, written case (as the source spells it, or"
        echo "# \`?\`), kind (\`-\` literal, \`t\` template, \`v\` expression-named, \`?\` unexplained), leaf"
        echo "# (the part of the full name the written case accounts for), source file, bootstrap commit,"
        echo "# measured (\`report\`, or \`source\` for a suite that had no report)."
        echo "#"
        echo "# measured-at $oldest .. $newest (bootstrap-local time), $files report files"
        echo "# bootstrap $short ($described), read from HEAD's reflog at the first report"
        if [ -s "$work/checks.tsv" ]; then
            echo "# PARTIAL -- written with these disagreements outstanding:"
            sort "$work/checks.tsv" | sed 's/^/# check: /'
        fi
        LC_ALL=C sort -t "$(printf '\t')" -k1,1 -k2,2 "$work/body.tsv"
    } > "$snapshot"
    echo "wrote $snapshot: $(wc -l < "$work/body.tsv" | tr -d ' ') rows, measured on $short ($described)"
    exit 0
fi

if [ ! -s "$snapshot" ]; then
    echo "no snapshot at $snapshot -- run with --refresh"
    exit 2
fi
grep -v '^#' "$snapshot" > "$work/snap.tsv"
commit=$(awk '/^# bootstrap / { print $3; exit }' "$snapshot")

# The checks, against the live source.
: > "$work/checks.tsv"
grep '^# check: ' "$snapshot" | sed 's/^# check: //' >> "$work/checks.tsv"
if [ "$have_source" -eq 1 ]; then
    awk -F'\t' '
        FNR == NR { if ($3 != "?") inSnap[$1 "\t" $3] = 1; ranSuite[$1] = 1; next }
        { src[$1 "\t" $3] = 1; srcSuite[$1] = 1
          if (!(($1 "\t" $3) in inSnap)) printf "never-ran\t%s\tline %s: %s\n", $1, $2, $3 }
        END {
            for (k in inSnap) if (!(k in src)) { split(k, p, "\t"); printf "unexplained\t%s\t%s\n", p[1], p[2] }
            for (s in ranSuite) if (!(s in srcSuite)) printf "no-source\t%s\tran, and is not in the source\n", s
        }
    ' "$work/snap.tsv" "$work/src.tsv" >> "$work/checks.tsv"
    changed_since "$commit" >> "$work/checks.tsv"
    unread >> "$work/checks.tsv"
fi
sort -u "$work/checks.tsv" -o "$work/checks.tsv"

# One line per iteration: suite, mapped?, the name a map row would be written against, written case.
awk -F'\t' -v mapfile="$map" -v orphans="$work/orphans.tsv" '
    FILENAME == mapfile {
        if ($0 ~ /^#/ || NF < 3) next
        if ($2 == "*") wild[$1] = $3
        else { seen[$1 "\t" $2] = $3; nk = ++keys[$1]; key[$1, nk] = $2 }
        next
    }
    {
        s = $1; kind = $4
        if (kind == "-")      k = $3
        else if (kind == "t") k = $5
        else {
            # An expression-named or unexplained case: the longest row of its suite that the full
            # name ends with, as a leaf under whatever groups surround it.
            k = ""; lf = length($2)
            for (i = 1; i <= keys[s]; i++) {
                t = key[s, i]; lt = length(t)
                if (($2 == t || (lf > lt && substr($2, lf - lt) == " " t)) && lt > length(k)) k = t
            }
            if (k == "") k = $2
        }
        if ((s "\t" k) in seen) { how = seen[s "\t" k]; used[s "\t" k] = 1 }
        else if (s in wild)     { how = wild[s]; usedw[s] = 1 }
        else how = ""
        printf "%s\t%d\t%s\t%s\n", s, (how == "" ? 0 : 1), k, $3
    }
    END {
        # The site'"'"'s rows are pages, not reference cases -- not orphans.
        for (r in seen) if (!(r in used) && r !~ /^DocsTests\.scala\t/) print r > orphans
        for (s in wild) if (!(s in usedw)) print s "\t*" > orphans
    }
' "$map" "$work/snap.tsv" > "$work/state.tsv"

if [ "$mode" = "--unmapped" ]; then
    awk -F'\t' -v p="$pattern" '$2 == 0 && (p == "" || index($1, p)) { printf "%-34s %s\n", $1, $3 }' "$work/state.tsv"
    exit 0
fi

if [ "$mode" = "--orphans" ]; then
    sort "$work/orphans.tsv" | awk -F'\t' '{ printf "%-34s %s\n", $1, $2 }'
    exit 0
fi

if [ "$mode" = "--candidates" ]; then
    "$here/own_test_inventory.sh" > "$work/ours.tsv"
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
        $2 == 0 && (p == "" || index($1, p)) {
            nr = split(words($3), rp, " ")
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
            printf "%.2f  %-30s %-58s %s\n", bs, $1, substr($3, 1, 58), best
        }
    ' "$work/ours.tsv" "$work/state.tsv" | sort -rn
    exit 0
fi

# The written half: a case as the source spells it is mapped when every iteration of it is. With no
# source at hand it is counted from the snapshot, where one leaf written under two groups is one.
if [ "$have_source" -eq 1 ]; then
    awk -F'\t' 'FNR == NR { n[$1 "\t" $4]++; m[$1 "\t" $4] += $2; next }
                { k = $1 "\t" $3; printf "%s\t%d\n", $1, ((k in n) && m[k] == n[k]) }' \
        "$work/state.tsv" "$work/src.tsv" > "$work/written.tsv"
else
    awk -F'\t' '{ k = $1 "\t" $4; n[k]++; m[k] += $2 }
                END { for (k in n) { split(k, p, "\t"); printf "%s\t%d\n", p[1], (m[k] == n[k]) } }' \
        "$work/state.tsv" > "$work/written.tsv"
fi

echo "reference: $bootstrap"
echo "snapshot:  $snapshot"
grep -E '^# (measured-at|bootstrap) ' "$snapshot" | sed 's/^# /           /'
echo "map:       $map"
echo
printf "%-36s %6s %7s %8s   %7s %7s\n" SUITE RAN MAPPED UNMAPPED WRITTEN MAPPED
awk -F'\t' '
    FNR == NR { w[$1]++; wm[$1] += $2; next }
    { n[$1]++; m[$1] += $2 }
    END { for (f in n) printf "%-36s %6d %7d %8d   %7d %7d\n", f, n[f], m[f], n[f] - m[f], w[f], wm[f] }
' "$work/written.tsv" "$work/state.tsv" | sort -k4 -rn

awk -F'\t' '
    FNR == NR { w++; wm += $2; next }
    { n++; m += $2 }
    END {
        printf "%-36s %6d %7d %8d   %7d %7d\n", "-- every suite", n, m, n - m, w, wm
        printf "\nby ITERATION (every case the suite runs):  %d total, %d mapped, %d unmapped\n", n, m, n - m
        printf "by WRITTEN case (one per case in source):   %d total, %d mapped, %d unmapped\n", w, wm, w - wm
    }
' "$work/written.tsv" "$work/state.tsv"
awk -F'\t' '$8 == "source" { n++ } END { if (n) printf "  (%d of the iterations are counted from source, no report having run them)\n", n }' "$work/snap.tsv"
[ "$have_source" -eq 1 ] ||
    echo "  (no bootstrap source here: the written count is the snapshot's, where a leaf written twice counts once)"
orph=$(wc -l < "$work/orphans.tsv" | tr -d ' ')
[ "$orph" -gt 0 ] && echo "  $orph map rows match no case -- see --orphans"

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

stale=$(wc -l < "$work/checks.tsv" | tr -d ' ')
if [ "$stale" -gt 0 ]; then
    echo
    echo "STALE $stale -- the snapshot and the bootstrap's source disagree (a fresh run and --refresh cure it):"
    awk -F'\t' '{ printf "  %-12s %-34s %s\n", $1, $2, substr($3, 1, 90) }' "$work/checks.tsv"
fi

left=$(awk -F'\t' '$2 == 0' "$work/state.tsv" | wc -l | tr -d ' ')
echo
echo "UNMAPPED $left"
[ "$left" -eq 0 ] && [ "$stale" -eq 0 ]
