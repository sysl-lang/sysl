# Joins the reference's two enumerations: the cases WRITTEN in its source
# (`reference_test_inventory.sh`) and the cases its suite RAN (`reference_test_reports.sh`, or the
# committed snapshot's first two columns). Used by `reference_test_census.sh`; not run on its own.
#
#     awk -F'\t' -f reference_test_match.awk -v checks=<file> -v commit=<sha> \
#         <inventory --groups> <inventory> <ran>
#
# Every ran row -- `<suite file>\t<full name>` -- is attributed to the written case it is an
# iteration of. scalatest puts every enclosing group in front of the leaf, joined by a space, so
# the suite's group names (`"name" - {`) are stripped from the front first, and a written case equal
# to what is left -- a template (`s"$name sizes ..."`) matching it as a pattern, each hole standing
# for one or more characters -- is the one. Only where no written case matches the stripped name
# exactly (a group computed at run time, say) does the most specific written case the full name
# ENDS with claim it. What is printed, one per ran row, is a row of `reference_inventory.tsv`:
#
#     <suite>  <full name>  <written case>  <kind>  <leaf>  <source path>  <commit>  <measured>
#
# <kind> is `-` for a literal written case, `t` for a template, `v` for a case named by an
# expression, and `?` for a ran row no written case explains. <leaf> is the part of the full name the
# written case accounts for -- the concrete iteration of a template -- or the whole name for `v`/`?`.
# <measured> is `report`; a written case with no ran row -- its whole suite has no report, or it was
# added after the run -- is still counted, as one row measured `source`, and says so in the checks.
#
# The checks file gets one line per disagreement between the two, `<check>\t<suite>\t<detail>`.

function claims(s, k) { return ((s, k) in hit) ? hit[s, k] : 0 }

function unescape(s) {
    gsub(/\\"/, "\"", s)
    gsub(/\\\\/, "\\", s)
    return s
}

# A template's regular expression: literal text escaped, `$$` a dollar, `$name` and `${ ... }` a
# hole of one or more characters.
function template_re(s,   out, n, i, j, c, d, depth) {
    out = ""; n = length(s); i = 1; speclen = 0
    while (i <= n) {
        c = substr(s, i, 1)
        if (c == "$") {
            d = substr(s, i + 1, 1)
            if (d == "$") { out = out "\\$"; i += 2; speclen++; continue }
            if (d == "{") {
                depth = 0
                for (j = i + 1; j <= n; j++) {
                    d = substr(s, j, 1)
                    if (d == "{") depth++
                    else if (d == "}" && --depth == 0) break
                }
                out = out ".+"; i = j + 1; continue
            }
            if (d ~ /[A-Za-z_]/) {
                for (j = i + 1; j <= n && substr(s, j, 1) ~ /[A-Za-z0-9_]/; j++) ;
                out = out ".+"; i = j; continue
            }
        }
        if (index("\\^$.[]|()*+?{}", c)) out = out "\\" c
        else out = out c
        i++; speclen++
    }
    return out
}

# The stages a full name passes through as its enclosing groups are stripped from the front, one
# group at a time: stage[0] is the full name, stage[nstage] the most stripped. A literal group is
# stripped where the name starts with it and a space; a template group by its shortest match.
function stages(s, suite,   i, g, lg, p, cut, at, prev) {
    nstage = 0; stage[0] = s; sline[0] = 0; prev = 0
    while (1) {
        cut = 0; at = 0
        for (i = 1; i <= ng[suite]; i++) {
            if ((suite, i) in gre) {
                for (p = 2; p < length(s); p++)
                    if (substr(s, p, 1) == " " && substr(s, 1, p) ~ gre[suite, i]) {
                        if (p > cut) { cut = p; at = gline[suite, i] }
                        break
                    }
            } else {
                g = gtext[suite, i]; lg = length(g)
                # One group name written twice is two groups: the one after the group stripped
                # before it, nearest first.
                if (length(s) > lg + 1 && substr(s, 1, lg + 1) == g " " && (lg + 1 > cut ||
                    (lg + 1 == cut && gline[suite, i] > prev && (at <= prev || gline[suite, i] < at)))) {
                    cut = lg + 1; at = gline[suite, i]
                }
            }
        }
        if (!cut) break
        s = substr(s, cut + 1)
        stage[++nstage] = s
        sline[nstage] = at; prev = at
    }
}

# Among equally specific candidates, the one written nearest after the group last stripped -- the
# group it sits in -- and then the one claimed fewer times, so a leaf written twice is claimed once
# by each of its two cases.
function nearer(suite, k, j, gl,   dk, dj) {
    dk = wline[suite, k] - gl; if (dk < 0) dk = 1e9
    dj = wline[suite, j] - gl; if (dj < 0) dj = 1e9
    if (dk != dj) return dk < dj
    return claims(suite, k) < claims(suite, j)
}

FILENAME == ARGV[1] {
    n = ++ng[$1]
    gtext[$1, n] = unescape($3)
    gline[$1, n] = $2
    if (index($4, "t")) gre[$1, n] = "^" substr(template_re(unescape($3)), 1) " $"
    next
}

FILENAME == ARGV[2] {
    suite = $1
    k = ++nw[suite]
    wraw[suite, k] = $3
    wflag[suite, k] = $4
    wline[suite, k] = $2
    wtext[suite, k] = unescape($3)
    if (index($4, "t")) {
        wre[suite, k] = "^" template_re(unescape($3)) "$"
        wspec[suite, k] = speclen
    }
    if (index($4, "v")) { hasv[suite] = k; isv[suite, k] = 1 }
    srcpath[suite] = $5
    next
}

{
    suite = $1; full = $2
    ran[suite] = 1
    # The written case that claims a ran row is the most SPECIFIC one that matches it: a literal
    # scores its length, a template the length of its literal text, and a literal wins a tie -- so
    # `on ${t.name}` does not swallow a sibling leaf that happens to sit in a group named "on ...".
    # First pass: equality with a stage of the group-stripped name, the MOST stripped stage that
    # anything equals winning -- a template like `${os}` equals every stage, the full name included.
    best = 0; bscore = -1; bleaf = ""
    stages(full, suite)
    for (st = nstage; st >= 0 && !best; st--) {
        cand = stage[st]
        for (k = 1; k <= nw[suite]; k++) {
            f = wflag[suite, k]
            if (index(f, "v")) continue
            if (index(f, "t")) { if (cand !~ wre[suite, k]) continue; score = 2 * wspec[suite, k] }
            else { if (cand != wtext[suite, k]) continue; score = 2 * length(cand) + 1 }
            if (score > bscore || (score == bscore && nearer(suite, k, best, sline[st]))) {
                best = k; bscore = score; bleaf = cand
            }
        }
    }
    # Second pass, only where nothing equalled a stage: the most specific written case the full
    # name ENDS with, a template's leaf being the shortest suffix it matches.
    lf = length(full); exact = best
    for (k = 1; k <= nw[suite] && !exact; k++) {
        f = wflag[suite, k]
        if (index(f, "v")) continue
        leaf = ""
        if (index(f, "t")) {
            for (p = lf; p >= 1; p--) {
                if (p > 1 && substr(full, p - 1, 1) != " ") continue
                if (substr(full, p) ~ wre[suite, k]) { leaf = substr(full, p); break }
            }
            score = 2 * wspec[suite, k]
        } else {
            t = wtext[suite, k]; lt = length(t)
            if (full == t || (lf > lt && substr(full, lf - lt) == " " t)) leaf = t
            score = 2 * lt + 1
        }
        if (leaf == "") continue
        # One leaf written twice in a suite, under two groups, is two cases: an equal score goes to
        # whichever has been claimed fewer times, so each is claimed by its own.
        if (score > bscore || (score == bscore && claims(suite, k) < claims(suite, best))) {
            best = k; bscore = score; bleaf = leaf
        }
    }
    if (best) {
        written = wraw[suite, best]; kind = index(wflag[suite, best], "t") ? "t" : "-"
        hit[suite, best]++
    } else if (suite in hasv) {
        written = wraw[suite, hasv[suite]]; kind = "v"; bleaf = full
        hit[suite, hasv[suite]]++
    } else {
        written = "?"; kind = "?"; bleaf = full
        if (nw[suite] > 0) print "unexplained\t" suite "\t" full > checks
    }
    if (!(suite in srcpath)) nosrc[suite] = 1
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\treport\n", suite, full, written, kind, bleaf, \
           (suite in srcpath) ? srcpath[suite] : "?", commit
}

END {
    for (s in nosrc) print "no-source\t" s "\tran, but no written case of this suite is in the source" > checks
    for (s in nw) {
        if (!(s in ran)) {
            print "no-report\t" s "\t" nw[s] " written cases, counted from source" > checks
            for (k = 1; k <= nw[s]; k++)
                printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\tsource\n", s, wtext[s, k], wraw[s, k], \
                       index(wflag[s, k], "t") ? "t" : (index(wflag[s, k], "v") ? "v" : "-"), \
                       wtext[s, k], srcpath[s], commit
            continue
        }
        for (k = 1; k <= nw[s]; k++)
            # A suite's expression-named cases share one pool of ran rows, nothing in a computed
            # name saying which expression produced it.
            if (!((s, k) in hit) && !((s, k) in isv && (s, hasv[s]) in hit)) {
                print "never-ran\t" s "\tline " wline[s, k] ": " wraw[s, k] > checks
                printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\tsource\n", s, wtext[s, k], wraw[s, k], \
                       index(wflag[s, k], "t") ? "t" : "-", wtext[s, k], srcpath[s], commit
            }
    }
}
