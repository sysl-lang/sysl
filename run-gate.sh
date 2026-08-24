#!/bin/zsh
#
# The Native gate: the whole test suite, on the platform a release ships.
#
#     ./run-gate.sh
#
# WHY THIS IS A SCRIPT AND NOT `sbt syslNative/test`
#
# Because that command cannot finish here, at any setting, and the reason is structural rather than
# a matter of tuning. sbt forks one test agent per core and keeps each one for the whole run, so a
# single invocation accumulates the entire suite's retained memory in a handful of processes. Raise
# the heap ceiling and the total overruns the machine; lower it and an agent reaches the ceiling --
# where Scala Native's collector does not abort but **spirals**, collecting and reclaiming nothing,
# at around 80% CPU, for as long as it is left alone. One agent burned 93 minutes of CPU that way
# having printed nothing for two hours, which is indistinguishable from a slow test.
#
# Running one `sbt` per group of suites recycles the agents between groups, and that is the whole
# trick. Measured 2026-08-08 on an 18-core, 64 GB machine: 7798 tests, 0 failures, 7m17s.
#
# THIS IS A WORKAROUND AND SHOULD BE READ AS ONE. The underlying fact is that the suite needs
# roughly twelve times the memory on Native that it needs on the JVM, where the same 7798 tests fit
# in `-Xmx8g`. Nobody has explained the gap. If it is ever closed, delete this script.
#
# EVERY GROUP HAS A HARD TIME LIMIT, because the failure mode above is silence, and silence looks
# exactly like progress. A group that exceeds it is killed and recorded as TIMEOUT together with the
# last suite it announced, which is what names the offender.

set -u

REPO=${0:a:h}
LOGS=$REPO/target/gate
SUITES=$REPO/shared/src/test/scala

HEAVY_HEAP=24g;  HEAVY_AGENTS=1     # a suite that builds for every target, on its own
LIGHT_HEAP=16g;  LIGHT_AGENTS=3     # 3 x 16g = the same 48 GB ceiling, redistributed -- see below
LIMIT=900                           # seconds per group; the groups take 15-75s

# **WHY 3 x 16g RATHER THAN 4 x 12g, measured 2026-08-22 cutting 0.0.66.** A wedged chunk was caught
# in the act: four agents sat at **4.3 GB** each and the fifth at **exactly 12.0 GB**, which is the
# cap. The machine was nowhere near full at the time. So the agent that died did not run out of
# memory -- it ran out of *permission*, while tens of gigabytes sat unused beside it.
#
# The ceiling is unchanged at 48 GB, which is what protects the machine. What changed is how it is
# divided: an agent that balloons now has 16 GB to balloon into rather than 12, and the ordinary
# agents never wanted more than about 4.3 anyway. Fewer agents also means fewer of them able to
# balloon at once, which is the case the ceiling exists for.
#
# **Neither this nor the grouping is a fix.** Something makes one agent occasionally need three times
# what its neighbours need, and nobody has explained that -- see the note above about Native wanting
# twelve times the JVM's memory for the same code. The most promising account is that Scala Native's
# collector scans the stack conservatively, so garbage that a stack word happens to look like a
# pointer to cannot be proven dead; a fresh process is then the only thing that reclaims it, which is
# exactly what this script provides and why it works at all.

mkdir -p "$LOGS"
SUMMARY=$LOGS/summary.txt
rm -f "$SUMMARY"

echo "grouping the suites" | tee -a "$SUMMARY"
python3 "$REPO/gate-groups.py" "$SUITES" "$LOGS" | tee -a "$SUMMARY" || exit 1

# **RECONCILE WHAT THE GROUPER FOUND AGAINST WHAT sbt SAYS EXISTS.** The grouper reads source text,
# and a count printed against nothing is not evidence: it said 334 suites for months while there were
# 350, because a suite extending a base declared in this tree did not match its pattern — the whole
# package manager among the sixteen it could not see. A fix alone would hold until the next support
# trait; this is what makes it unable to recur.
#
# **Asked of the platform being gated**, not of the JVM. There is one test source root today, so the
# two sets are identical and `syslJVM` would be a cheaper proxy — but the moment anybody adds
# `native/src/test/scala` the proxy diverges quietly, in the direction that reads as fine, which is
# the same failure this check exists to catch.
#
# It costs one sbt start; the test classes it needs compiled are ones the first group compiles anyway.
print "reconciling the suite list against sbt" | tee -a "$SUMMARY"

sbt -batch --error "print syslNative/Test/definedTestNames" > "$LOGS/defined.log" 2>&1

grep -oE 'sh\.sysl\.[A-Za-z0-9_]+' "$LOGS/defined.log" | sort -u > "$LOGS/defined.txt"

python3 -c "
import json, sys
chunks = json.load(open('$LOGS/chunks.json'))
heavy  = json.load(open('$LOGS/heavy.json'))
found  = sorted({s for g in chunks for s in g} | set(heavy))
print('\n'.join(found))
" | sort -u > "$LOGS/grouped.txt"

MISSING=$(comm -23 "$LOGS/defined.txt" "$LOGS/grouped.txt")
EXTRA=$(comm -13 "$LOGS/defined.txt" "$LOGS/grouped.txt")

if [[ -n "$MISSING" || -n "$EXTRA" ]]; then
  {
    print "GATE: RED -- the suite list does not reconcile with sbt"
    print "  sbt has $(wc -l < "$LOGS/defined.txt" | tr -d ' '), the grouper found $(wc -l < "$LOGS/grouped.txt" | tr -d ' ')"
    [[ -n "$MISSING" ]] && print "  never run:" && print "$MISSING" | sed 's/^/    /'
    [[ -n "$EXTRA" ]] && print "  grouped but unknown to sbt:" && print "$EXTRA" | sed 's/^/    /'
    print "  fix gate-groups.py rather than this list -- see its self-test"
  } | tee -a "$SUMMARY"

  exit 0
fi

print "  $(wc -l < "$LOGS/defined.txt" | tr -d ' ') suites, and sbt agrees" | tee -a "$SUMMARY"

run_group () {
  local label=$1 heap=$2 agents=$3 suites=$4

  print "=== $label start $(date +%H:%M:%S)  heap=$heap agents=$agents" >> "$SUMMARY"

  GC_MAXIMUM_HEAP_SIZE=$heap SYSL_RELEASE=1 sbt -J-XX:-DoEscapeAnalysis -batch \
    "set Global/concurrentRestrictions += Tags.limit(Tags.Test, $agents)" \
    "syslNative/testOnly $suites" > "$LOGS/$label.log" 2>&1 &
  local sbt_pid=$!

  ( sleep $LIMIT; kill -9 $sbt_pid 2>/dev/null ) &
  local watchdog=$!
  wait $sbt_pid
  kill $watchdog 2>/dev/null

  # The log is the verdict. Never the exit status: a backgrounded pipeline reports the wrong one,
  # and a killed run reports a plausible one.
  local result=$(grep -E "^\[(info|error)\] Tests: succeeded" "$LOGS/$label.log" | tail -1)
  local failed=$(grep -c "\*\*\* FAILED \*\*\*" "$LOGS/$label.log")
  local oom=$(grep -c "Heap_exitWithOutOfMemory" "$LOGS/$label.log")

  if [[ -z $result ]]; then
    local last=$(grep -E "^\[info\] [A-Z][A-Za-z]*Tests:" "$LOGS/$label.log" | tail -1)
    print "$label  TIMEOUT/KILLED $(date +%H:%M:%S)  oom=$oom  last suite: $last" >> "$SUMMARY"
  else
    print "$label  done $(date +%H:%M:%S)  failed=$failed oom=$oom  $result" >> "$SUMMARY"
  fi

  # An agent can outlive a -9'd sbt, and a stranded one is charged to the next group's budget.
  # Reported rather than killed: `pkill` would take another session's build with it.
  local stranded=$(pgrep -x sysl-test | wc -l | tr -d ' ')
  [[ $stranded != 0 ]] && print "  STRANDED AGENTS: $stranded -- kill by PID before continuing" >> "$SUMMARY"
}

run_group heavy $HEAVY_HEAP $HEAVY_AGENTS \
  "$(python3 -c "import json;print(' '.join(json.load(open('$LOGS/heavy.json'))))")"

NCHUNKS=$(python3 -c "import json;print(len(json.load(open('$LOGS/chunks.json'))))")

for i in $(seq 0 $((NCHUNKS - 1))); do
  run_group "chunk-$i" $LIGHT_HEAP $LIGHT_AGENTS \
    "$(python3 -c "import json;print(' '.join(json.load(open('$LOGS/chunks.json'))[$i]))")"
done

print "=== all groups done $(date +%H:%M:%S)" >> "$SUMMARY"

python3 - "$SUMMARY" >> "$SUMMARY" <<'PY'
import re, sys

total = failed = 0
stalled = []

for line in open(sys.argv[1]):
    if m := re.search(r'Tests: succeeded (\d+), failed (\d+)', line):
        total += int(m.group(1))
        failed += int(m.group(2))
    if 'TIMEOUT' in line:
        stalled.append(line.split()[0])

print(f'TOTAL: {total} succeeded, {failed} failed')
print('TIMED OUT: ' + (', '.join(stalled) if stalled else 'none'))
print('GATE: ' + ('GREEN' if failed == 0 and not stalled else 'RED'))
PY

tail -3 "$SUMMARY"
print "\nfull summary: $SUMMARY"
