#!/usr/bin/env bash
# Run the editor-edit e2e suite. Each test file exits 0 on PASS,
# non-zero on FAIL — we accumulate and surface the worst.
#
# Requires the dev server at http://localhost:9002 (override with
# GRAPHDEN_URL) and a matching AUTH_TOKEN env var. Default token is
# `test123` which matches the default `bb rebuild` flow.
#
# Between-test delay is intentional. Running ~40 tests in a tight
# loop builds up storage state + JVM allocations faster than the
# dev container can GC; the executor's `restart: unless-stopped`
# kicks in mid-sweep and every subsequent test fails with
# ERR_CONNECTION_REFUSED until the rebuild completes. Two seconds
# is empirically enough breathing room — sub-second still flakes,
# 5+ is needlessly slow.
#
# Override with SWEEP_DELAY=N if you want a different pace. The
# isolated e2e stack (graphden.dev.e2e-stack, what the gate runs)
# sets SWEEP_DELAY=0 itself — its executor has restart:on-failure
# and doesn't bounce, so the delay is dead sleep there. This 2s
# default is for a run against the DEMO (:9002).

set -u
cd "$(dirname "$0")" || exit 1

# Resolve the test list once so adding new files only takes a glob.
FILES=$(ls edit-*.test.js 2>/dev/null)
if [ -z "$FILES" ]; then
  echo "no edit-*.test.js files found" >&2
  exit 2
fi

SWEEP_DELAY="${SWEEP_DELAY:-2}"
URL="${GRAPHDEN_URL:-http://localhost:9002}"

# Health probe — block until the executor responds 200. Used between
# tests because docker's `restart: unless-stopped` policy bounces the
# container mid-sweep on cumulative load; without this, every test
# after a bounce ERR_CONNECTION_REFUSED's until the next ~30s
# rebuild completes.
wait_for_server() {
  local deadline=$((SECONDS + 90))
  until curl -fsS -o /dev/null "$URL/health" 2>/dev/null; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "  (server still unhealthy after 90s — giving up)" >&2
      return 1
    fi
    sleep 2
  done
}

# --- instrumentation --------------------------------------------------------
# This suite takes ~46 minutes for 55 files and nobody could say why, because
# the runner reported pass/fail and nothing else. Worse, the retry below carries
# a note that "suite-tail tests flake under JVM GC pressure when heap passes
# ~85%" — a diagnosis nobody ever measured, only worked around with a retry.
#
# So measure both series, per file: wall time, and the executor's memory after
# it. "Time is spread evenly" and "three files eat ten minutes" are different
# problems. "Heap is flat" and "heap climbs to the cap by test 30" are different
# problems. We were guessing between them.
executor_mem() {
  local id
  id="$(docker ps --filter "ancestor=${GD_IMAGE:-graphden-executor:latest}" \
                  --format '{{.ID}}' 2>/dev/null | head -1)"
  [ -n "$id" ] || { printf '?'; return; }
  docker stats --no-stream --format '{{.MemUsage}}' "$id" 2>/dev/null \
    | awk '{print $1}' | head -1
}
TIMINGS=""          # "<seconds>\t<mem>\t<file>" per line, for the summary
SUITE_START=$SECONDS

# Count the fns in the graph. A test that leaves entities behind does not fail —
# it makes the NEXT file fail, on a graph it never created. That is why the
# flakes here always turned up somewhere innocent, got "fixed" there with a
# longer timeout or a retry, and came back.
#
# Measured: edit-inheritance-regression deleted parents and children in one
# parallel batch, the parent DELETEs 409'd ("Graph is a parent of 1 other
# graph"), the errors were swallowed, and 8 fns stayed in the graph. Run it, then
# run edit-arg-type-override, and that one times out. Run it alone and it passes
# 3/3. Nobody could see the connection because nothing was counting.
#
# So count, per file, and name the file that leaked in the run that leaked.
#
# Count the FNS, not the string `"name"`. The payload is `{fns, namespaces}` and
# both carry a name, so the naive grep reported a package install's two leftover
# NAMESPACES as "LEAKED 2 fn(s)" and sent me looking for a cascade bug that was
# not there. An instrument that misnames what it measures is worse than none: it
# spends the time you gave it to save.
fn_count() {
  curl -fsS -H "Authorization: Bearer ${AUTH_TOKEN:-}" \
       "$URL/api/graph/entities?scope=index" 2>/dev/null \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print(len(d.get("fns") or []))' \
       2>/dev/null || echo 0
}
# Namespaces leak too — a package install creates one per version — and they show
# up in the sidebar tree of every file that runs after. Counted separately so the
# report says which kind of row was left behind.
ns_count() {
  curl -fsS -H "Authorization: Bearer ${AUTH_TOKEN:-}" \
       "$URL/api/graph/entities?scope=index" 2>/dev/null \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print(len(d.get("namespaces") or []))' \
       2>/dev/null || echo 0
}
LEAKS=""
FLAKED=""

WORST=0
PASS=0
FAIL=0
FAILED_NAMES=""
# Consecutive server-down counter. Demo (:9002) has docker restart-
# policy so a single bounce recovers; an isolated testcontainer
# stack does NOT auto-restart, so a single crash cascades through
# every subsequent test as a 90s `ERR_CONNECTION_REFUSED` wait. Cap
# the consecutive cascade — if the server stays down past N
# attempts, mark remaining files as skipped and abort the loop.
# Saves ~37 min on a full 47-test suite (90s × 25 = ~37 min wasted
# in the cascade window).
CONSECUTIVE_DOWN=0
CASCADE_CAP=${CASCADE_CAP:-3}
SKIP_AFTER_CASCADE=0
REMAINING_FILES=""
for f in $FILES; do
  if [ "$SKIP_AFTER_CASCADE" = "1" ]; then
    REMAINING_FILES="$REMAINING_FILES $f"
    continue
  fi
  echo "─── $f ───"
  FILE_START=$SECONDS
  FN_BEFORE="$(fn_count)"
  NS_BEFORE="$(ns_count)"
  if ! wait_for_server; then
    WORST=1
    FAIL=$((FAIL+1))
    FAILED_NAMES="$FAILED_NAMES $f(server-down)"
    CONSECUTIVE_DOWN=$((CONSECUTIVE_DOWN+1))
    if [ "$CONSECUTIVE_DOWN" -ge "$CASCADE_CAP" ]; then
      echo "  (server down for $CONSECUTIVE_DOWN consecutive tests — aborting cascade)" >&2
      SKIP_AFTER_CASCADE=1
    fi
    continue
  fi
  CONSECUTIVE_DOWN=0
  # Per-test wall-clock cap. Individual tests should complete in
  # < 1 min under load; bounded at 5 min hard, then SIGKILL via the
  # GNU coreutils `timeout`. Without this a stuck `page.evaluate`
  # against an unresponsive editor JS can pin a single test for
  # arbitrarily long (verified empirically: edit-effects-badges hung
  # 51 min on a slow server window). The cap turns the hang into a
  # discrete failure that gets cascade-counted, so the rest of the
  # suite isn't held hostage.
  #
  # 5 min is the chosen number because the heaviest test
  # (edit-inheritance-regression, 30 await ops) was tripping a 3 min
  # cap during slow-server windows even though it eventually would
  # have completed correctly. 5 min preserves the hang-bound
  # contract while reducing false-positive timeouts.
  # First attempt — common case, finishes here.
  if timeout -k 5 "${PER_TEST_TIMEOUT:-300}" node "$f"; then
    PASS=$((PASS+1))
  else
    rc=$?
    # Retry once after a sleep — suite-tail tests (~test 30+) flake
    # under JVM GC pressure (>5s pauses in the e2e stack when heap
    # passes ~85%). A fresh test instance after a recovery window
    # typically passes. WORST/FAIL only bump on the SECOND failure
    # so a transient flake doesn't fail the run.
    is_timeout=0
    if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then is_timeout=1; fi
    echo "  (first attempt rc=$rc — sleeping 10s then retrying once)" >&2
    sleep 10
    if wait_for_server && timeout -k 5 "${PER_TEST_TIMEOUT:-300}" node "$f"; then
      PASS=$((PASS+1))
      # A test that fails and then passes is NOT a pass. Every root cause found in
      # this suite so far — the dead type picker, the empty Run form — first showed
      # up as exactly this: one failure, swallowed by a retry, the run still green.
      # Twice the "fix" was to raise the timeout the retry was hiding. So the retry
      # stays (it tells a transient apart from a hard break) but it no longer buys
      # a green run: the suite goes red and names the file.
      FLAKED="$FLAKED $f"
      WORST=1
      FAILED_NAMES="$FAILED_NAMES $f(flaked-passed-on-retry)"
      echo "  (retry succeeded — FLAKE, and a flake fails this run)" >&2
    else
      rc2=$?
      WORST=1
      FAIL=$((FAIL+1))
      if [ $rc2 -eq 124 ] || [ $rc2 -eq 137 ] || [ $is_timeout -eq 1 ]; then
        FAILED_NAMES="$FAILED_NAMES $f(timeout)"
      else
        FAILED_NAMES="$FAILED_NAMES $f"
      fi
    fi
  fi
  FILE_SECS=$((SECONDS - FILE_START))
  FILE_MEM="$(executor_mem)"
  FN_AFTER="$(fn_count)"
  NS_AFTER="$(ns_count)"
  FN_LEAKED=$(( (FN_AFTER - FN_BEFORE) + (NS_AFTER - NS_BEFORE) ))
  if [ "$FN_LEAKED" -gt 0 ] 2>/dev/null; then
    printf '  [%3ds  executor=%s]  \033[31mLEAKED %d entities into the graph\033[0m\n' \
      "$FILE_SECS" "$FILE_MEM" "$FN_LEAKED"
    LEAKS="$LEAKS$FN_LEAKED	$f
"
    # A leak FAILS the suite. It is not a warning: the entities stay, and the
    # next file runs against a graph it did not create — which is how a cleanup
    # bug in one test surfaces as a "flake" in another, gets a longer timeout
    # bolted on there, and comes back. 37 of the 55 files swallow their cleanup
    # errors with `.catch(() => {})`; patching each one invites a 38th. The
    # invariant belongs here, once: a test leaves the graph as it found it.
    WORST=1
    FAILED_NAMES="$FAILED_NAMES $f(leaked-$FN_LEAKED)"
  else
    printf '  [%3ds  executor=%s]\n' "$FILE_SECS" "$FILE_MEM"
  fi
  TIMINGS="$TIMINGS$FILE_SECS	$FILE_MEM	$f
"
  echo
  if [ "$SWEEP_DELAY" != "0" ]; then sleep "$SWEEP_DELAY"; fi
done

# Mark cascade-skipped tests in the failed-names list so the summary
# is honest about what wasn't even attempted.
if [ -n "$REMAINING_FILES" ]; then
  for f in $REMAINING_FILES; do
    FAIL=$((FAIL+1))
    FAILED_NAMES="$FAILED_NAMES $f(cascade-skip)"
  done
  WORST=1
fi

echo "============================================================"
echo "edit suite: $PASS pass / $FAIL fail / $((PASS+FAIL)) total"
if [ -n "$FLAKED" ]; then
  echo "  FLAKED (failed once, passed on retry — counted as FAILURES):$FLAKED" >&2
fi
if [ "$FAIL" != "0" ]; then
  echo "  failed:$FAILED_NAMES" >&2
fi

# The profile. Read it before optimising anything: the suite's 45 minutes were
# a single number for its whole life, and every theory about where they went —
# browser startup, page loads, GC pauses — was a guess.
echo
echo "── where the time went ──"
printf '%s' "$TIMINGS" | sort -rn | head -12 \
  | awk -F'\t' '{printf "  %4ds  %-10s %s\n", $1, $2, $3}'
TOTAL_SECS=$((SECONDS - SUITE_START))
FILE_COUNT=$((PASS + FAIL))
[ "$FILE_COUNT" -gt 0 ] && echo "  ---" \
  && printf '  %4ds  TOTAL (%d files, %ds median-ish avg)\n' \
       "$TOTAL_SECS" "$FILE_COUNT" "$((TOTAL_SECS / FILE_COUNT))"

# The executor's memory, first file vs last. The retry above blames "JVM GC
# pressure when heap passes ~85%" for the suite-tail flakes. Nobody had checked.
echo
echo "── entities leaked into the graph ──"
if [ -n "$LEAKS" ]; then
  printf '%b' "$LEAKS" | sort -rn | awk -F'\t' '{printf "  %3d  %s\n", $1, $2}'
  echo "  ^ each of these poisons every file that runs after it. Fix the leaker,"
  echo "    not the file that trips over the mess."
else
  echo "  none — every file left the graph as it found it"
fi

echo
echo "── executor memory, first file -> last ──"
printf '%s' "$TIMINGS" | awk -F'\t' 'NR==1{first=$2} {last=$2} END{
  printf "  %s  ->  %s   (climbing = the tail-flake theory has legs)\n", first, last}'
echo "============================================================"
exit "$WORST"
