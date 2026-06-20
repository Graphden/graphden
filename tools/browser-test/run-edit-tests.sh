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
# Override with SWEEP_DELAY=N if you want a different pace; set to
# 0 only when chasing a load-related flake that needs the bulk
# pressure to reproduce.

set -u
cd "$(dirname "$0")"

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
  # < 1 min under load; bounded at 3 min hard, then SIGKILL via the
  # GNU coreutils `timeout`. Without this a stuck `page.evaluate`
  # against an unresponsive editor JS can pin a single test for
  # arbitrarily long (verified empirically: edit-effects-badges hung
  # 51 min on a slow server window). The cap turns the hang into a
  # discrete failure that gets cascade-counted, so the rest of the
  # suite isn't held hostage.
  if timeout -k 5 "${PER_TEST_TIMEOUT:-180}" node "$f"; then
    PASS=$((PASS+1))
  else
    rc=$?
    WORST=1
    FAIL=$((FAIL+1))
    if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
      FAILED_NAMES="$FAILED_NAMES $f(timeout)"
    else
      FAILED_NAMES="$FAILED_NAMES $f"
    fi
  fi
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
if [ "$FAIL" != "0" ]; then
  echo "  failed:$FAILED_NAMES" >&2
fi
echo "============================================================"
exit "$WORST"
