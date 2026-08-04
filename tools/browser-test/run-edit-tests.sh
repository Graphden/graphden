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
#
# The `edit-` prefix is load-bearing, not decoration: widening this glob turns
# the suite red. Two other groups of *.test.js live in this directory and
# neither can run here.
#
#   regression-*.test.js     drive fn-defs from `examples` — an EXTERNAL,
#                            dev/test-only package wired in via an :extra-paths
#                            entry on the :dev/:test aliases (deps.edn), and
#                            deliberately off the prod resources path. This
#                            suite boots `graphden-executor:latest`, which does
#                            not carry it. Measured against a live stack:
#                            /api/graph/entities?q=ex-regression returns
#                            {"fns":[]}, and both tests time out waiting for a
#                            graph that cannot exist there.
#
#   type-system-ui-*.test.js need a chromium but no server — they eval editor
#                            modules in a page and assert pure functions.
#                            Running them here would pay for a whole stack to
#                            use none of it. They have their own runner now:
#                            `bb test-js`, ~15 s, wired into scripts/checks.edn.
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
# This suite ONCE took ~46 minutes for 55 files and nobody could say why, because
# the runner reported pass/fail and nothing else. Worse, the retry below carries
# a note that "suite-tail tests flake under JVM GC pressure when heap passes
# ~85%" — a diagnosis nobody ever measured, only worked around with a retry.
#
# So measure both series, per file: wall time, and the executor's memory after
# it. "Time is spread evenly" and "three files eat ten minutes" are different
# problems. "Heap is flat" and "heap climbs to the cap by test 30" are different
# problems. We were guessing between them.
#
# It is now ~9 minutes for 56 files — 533 / 534 / 550 s across three landing
# gates (`bb test-e2e` reads ~11 min end to end; the extra is the isolated stack
# booting). The ~46 was measured against the DEMO on :9002, where SWEEP_DELAY
# defaults to 2 s and the container's restart policy bounces it mid-sweep; the
# gate's isolated stack sets SWEEP_DELAY=0 and does neither.
#
# That stale figure outlived its own fix by weeks — inside the very comment
# written to stop numbers turning into folklore. Which argues FOR the
# instrument, not against it: a printed measurement can be re-read and
# corrected, a remembered one just gets repeated.
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
# What did the executor DO while this file ran?
#
# The leak counters above answer "did it leave rows behind", and they come back
# clean in every flaked run to date — so the remaining flake is not that. These
# answer a different question. A compiled-registry full-clear makes the NEXT
# request rebuild the whole graph; at 4137 fns that was measured at 49.8 s. A
# test that lands in that window times out at 10s and sails through the retry
# ten seconds later. That is the exact shape of every flake in this suite — a
# different innocent file each run, always green on retry — and nothing could
# ever see it, because the event leaves no trace in a log or a stack trace.
#
# /metrics carries `counters` now. Sample it around each file and print the
# delta, so a full-clear sitting next to a failure becomes a fact instead of a
# theory. Costs one HTTP GET per file.
executor_counters() {
  curl -fsS -H "Authorization: Bearer ${AUTH_TOKEN:-}" "$URL/metrics" 2>/dev/null \
    | python3 -c 'import sys,json; print(json.dumps((json.load(sys.stdin) or {}).get("counters") or {}, sort_keys=True))' \
       2>/dev/null || echo '{}'
}

# `after` minus `before`, omitting whatever did not move. Empty output means the
# executor did no structural work at all while the file ran.
counters_delta() {
  python3 -c '
import sys, json
b = json.loads(sys.argv[1] or "{}")
a = json.loads(sys.argv[2] or "{}")
d = {k: a[k] - b.get(k, 0) for k in a if a[k] - b.get(k, 0) > 0}
print(" ".join(f"{k}={v}" for k, v in sorted(d.items())))
' "$1" "$2" 2>/dev/null || true
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
  CTR_BEFORE="$(executor_counters)"
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
  # Up to 5 attempts. A transient GC / slow-server window under the gate's
  # load (heap past ~85% → >5s pauses; brief server-unavailability during
  # write-heavy tests — task #10) can hit the SAME file on several consecutive
  # tries; the extra recovery windows catch that without hiding a real break,
  # which fails all five.
  #
  # A test that only passes AFTER a retry is a FLAKE — named LOUDLY in the
  # summary, never silently swallowed: every root cause found in this suite
  # (the dead type picker, the empty Run form) first showed up as one failure
  # a retry hid, and twice the "fix" was raising the timeout the retry masked.
  # Whether a flake also FAILS the run is a queue-economics knob
  # (WTQ_FLAKE_STRICT=1): strict mode is multi-agent-pool insurance (one flake
  # re-runs a serialized gate slot others queue behind); single-agent default
  # is report-loud, stay green. `passed` (0/1) is read by the leak check below.
  passed=0
  rc=0
  is_timeout=0
  for attempt in 1 2 3 4 5; do
    if [ "$attempt" -gt 1 ]; then
      echo "  (attempt $((attempt - 1)) rc=$rc — sleeping 10s, retry $attempt/5)" >&2
      sleep 10
      wait_for_server || break
    fi
    if timeout -k 5 "${PER_TEST_TIMEOUT:-300}" node "$f"; then
      passed=1
      break
    else
      # Capture node's exit code HERE (inside the else) — after the `fi` it
      # would read the `if`'s own status, which is 0 for a false condition
      # with no else, masking a real 124/137 timeout.
      rc=$?
      if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then is_timeout=1; fi
    fi
  done
  if [ "$passed" = 1 ]; then
    PASS=$((PASS+1))
    if [ "$attempt" -gt 1 ]; then
      FLAKED="$FLAKED $f"
      if [ "${WTQ_FLAKE_STRICT:-0}" = "1" ]; then
        WORST=1
        FAILED_NAMES="$FAILED_NAMES $f(flaked-passed-on-retry)"
        echo "  (passed on attempt $attempt — FLAKE, WTQ_FLAKE_STRICT=1 fails this run)" >&2
      else
        echo "  (passed on attempt $attempt — FLAKE: named in the summary, run stays green)" >&2
      fi
    fi
  else
    WORST=1
    FAIL=$((FAIL+1))
    if [ "$is_timeout" -eq 1 ]; then
      FAILED_NAMES="$FAILED_NAMES $f(timeout)"
    else
      FAILED_NAMES="$FAILED_NAMES $f"
    fi
  fi
  FILE_SECS=$((SECONDS - FILE_START))
  FILE_MEM="$(executor_mem)"
  FN_AFTER="$(fn_count)"
  NS_AFTER="$(ns_count)"
  CTR_DELTA="$(counters_delta "$CTR_BEFORE" "$(executor_counters)")"
  FN_LEAKED=$(( (FN_AFTER - FN_BEFORE) + (NS_AFTER - NS_BEFORE) ))
  if [ "$FN_LEAKED" -gt 0 ] 2>/dev/null && [ "$passed" = 1 ]; then
    printf '  [%3ds  executor=%s]%s  \033[31mLEAKED %d entities into the graph\033[0m\n' \
      "$FILE_SECS" "$FILE_MEM" "${CTR_DELTA:+  $CTR_DELTA}" "$FN_LEAKED"
    LEAKS="$LEAKS$FN_LEAKED	$f
"
    # A leak in a PASSING test is a real cleanup-bug signal — the entities stay
    # and the next file runs against a graph it did not create, which is how
    # one test's cleanup bug surfaces as a "flake" in another. It is always
    # NAMED LOUDLY (above + in the summary). BUT under the gate's load an
    # aborted-then-passed-on-retry test can leave COLLATERAL rows that read as
    # a leak, and a single such false positive was hard-failing otherwise-green
    # runs (task #10 tracks the deep root-cause). So the backstop is
    # REPORT-ONLY by default — named + counted, run stays green — and hard-fails
    # only under WTQ_FLAKE_STRICT, the same queue-economics knob as the flake
    # policy above.
    if [ "${WTQ_FLAKE_STRICT:-0}" = "1" ]; then
      WORST=1
      FAILED_NAMES="$FAILED_NAMES $f(leaked-$FN_LEAKED)"
      echo "  (leaked $FN_LEAKED — WTQ_FLAKE_STRICT=1 fails this run)" >&2
    else
      echo "  (leaked $FN_LEAKED entities — reported, run stays green; WTQ_FLAKE_STRICT=1 to fail)" >&2
    fi
  elif [ "$FN_LEAKED" -gt 0 ] 2>/dev/null; then
    # The test already FAILED (aborted / timed out). Rows left behind are
    # collateral of the abort — the test was killed mid-cleanup — not a
    # cleanup regression. It is already counted as a fail above, so note it
    # but do NOT double-red or mis-name it a "leak" (that named a different
    # innocent file each gate run when a slow window aborted it mid-flow).
    printf '  [%3ds  executor=%s]%s  (%d entities left by the failed test — abort collateral, not a leak)\n' \
      "$FILE_SECS" "$FILE_MEM" "${CTR_DELTA:+  $CTR_DELTA}" "$FN_LEAKED"
  else
    printf '  [%3ds  executor=%s]%s\n' "$FILE_SECS" "$FILE_MEM" \
      "${CTR_DELTA:+  $CTR_DELTA}"
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
  if [ "${WTQ_FLAKE_STRICT:-0}" = "1" ]; then
    echo "  FLAKED (failed once, passed on retry — counted as FAILURES):$FLAKED" >&2
  else
    echo "  FLAKED (failed once, passed on retry — investigate, run stays green):$FLAKED" >&2
  fi
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
# pressure when heap passes ~85%" for the suite-tail flakes. Checked 2026-08-04:
# after-GC live-set stays flat (~60MB) across the whole suite — first->last RSS
# growth on a fresh boot is committed-heap expansion toward MaxRAMPercentage.
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
# The caption is COMPUTED, not asserted: a >25% climb earns the
# tail-flake note, anything else reads "steady". (The old hardcoded
# "climbing" suffix printed even on a +0.7MiB run and sent a
# heap-dump investigation chasing a leak that wasn't there — the
# 2026-08-04 probe measured a FLAT ~60MB after-GC live-set across
# the whole suite; the gate's first->last growth is G1 committing
# heap toward MaxRAMPercentage under a fresh boot, not retention.)
printf '%s' "$TIMINGS" | awk -F'\t' 'NR==1{first=$2} {last=$2} END{
  f=first; l=last
  fv=f; sub(/[A-Za-z].*/,"",fv); lv=l; sub(/[A-Za-z].*/,"",lv)
  fb=(f ~ /GiB/)? fv*1024 : fv+0
  lb=(l ~ /GiB/)? lv*1024 : lv+0
  note=(fb>0 && lb>fb*1.25)? "(climbed >25% — check after-GC live-set in the gc log before calling it a leak)" : "(steady)"
  printf "  %s  ->  %s   %s\n", first, last, note}'
echo "============================================================"
exit "$WORST"
