#!/usr/bin/env bash
# Real kernel admission with tiny shell workloads, no JVM, Docker or gate.
# shellcheck disable=SC1090,SC2034,SC2329
set -euo pipefail
SRC="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"
trap 'jobs -p | xargs -r kill 2>/dev/null || true; rm -rf "$T"' EXIT
WTQ_DIR="$T"
GATE_HEAVY="$T/gate-heavy"
export WTQ_AGENT_MEM_MIN_MB=0
for fn in heavy_slot_acquire heavy_slot_release heavy_on heavy_off yield_to_gate cmd_test; do
  source <(sed -n "/^$fn() {/,/^}/p" "$SRC/wt")
done
int_or() { echo "${1:-$2}"; }
mem_avail_mb() { echo 9000; }
warn() { echo "$*" >&2; }
die() { echo "$*" >&2; exit 1; }
wait_for() {
  local end=$((SECONDS + 10))
  until "$@"; do
    [ "$SECONDS" -lt "$end" ] || { echo "timed out: $*" >&2; exit 1; }
    sleep .05
  done
}
# FIFO rendezvous ensures that each admitted command stays alive until the
# test releases it. Waiting logs prove a competitor reached the lock call.
cat > "$T/runner" <<'RUNNER'
#!/usr/bin/env bash
echo admitted > "$1.started"
read -r _ < "$1.release"
exit "${2:-0}"
RUNNER
chmod +x "$T/runner"
export WTQ_KAOCHA="$T/runner"
start_test() {
  mkfifo "$T/$1.release"
  (cmd_test "$T/$1" "${2:-0}") > "$T/$1.log" 2>&1 &
  TEST_PID=$!
}
release_test() { echo released > "$T/$1.release"; }

# Exercise the production phase dispatcher too: priority-wave labels must
# acquire admission just like bb test-* (they previously cleared the marker).
source <(sed -n '/^  step() {/,/^  }/p' "$SRC/wt")
(
  GATE_T0=$SECONDS; _ph_name=""; _ph_t0=$SECONDS; _ph_rows=""
  for phase in 'bb ci' 'build the CANDIDATE image' 'bb test-visual' \
    'manual related integration namespaces' 'automatic related integration namespaces' \
    'manual related browser files' 'automatic related browser files' \
    'automatic related browser: host headroom after integration' \
    'bb test-integration: remaining namespaces' 'bb test-e2e: remaining files'; do
    step "$phase"
    if flock -xn "$WTQ_DIR/heavy-work.lock" true; then
      echo "phase lacks admission: $phase" >&2; exit 1
    fi
  done
  step 'land: fast-forward'
  flock -xn "$WTQ_DIR/heavy-work.lock" true
) > "$T/phases.log"

start_test first
first=$TEST_PID
wait_for test -f "$T/first.started"
(
  heavy_on integration
  touch "$T/gate.started"
  read -r _ < "$T/gate.release"
  heavy_off
) > "$T/gate.log" 2>&1 &
gate=$!
mkfifo "$T/gate.release"
wait_for grep -q 'owns the heavy-work slot' "$T/gate.log"
test ! -e "$T/gate.started"
release_test first
wait "$first"
wait_for test -f "$T/gate.started"
start_test second
second=$TEST_PID
wait_for grep -q 'owns the heavy-work slot' "$T/second.log"
test ! -e "$T/second.started"
echo released > "$T/gate.release"
wait "$gate"
wait_for test -f "$T/second.started"
test ! -e "$GATE_HEAVY"
# A second focused JVM must not overlap the first, even with ample memory.
start_test third 23
third=$TEST_PID
wait_for grep -q 'owns the heavy-work slot' "$T/third.log"
test ! -e "$T/third.started"
release_test second
wait "$second"
wait_for test -f "$T/third.started"
release_test third
rc=0; wait "$third" || rc=$?
test "$rc" = 23
# SIGKILL cannot leave a kernel lock stale. Even a marker naming this live
# shell is observational and cannot deadlock a later focused command.
start_test killed
killed=$TEST_PID
wait_for test -f "$T/killed.started"
printf 'phase=integration\npid=%s\n' "$$" > "$GATE_HEAVY"
kill -KILL "$killed"
wait "$killed" 2>/dev/null || true
start_test after_kill
after=$TEST_PID
wait_for test -f "$T/after_kill.started"
release_test after_kill
wait "$after"
# An interrupted parent must not unlock a still-running workload descendant.
mkfifo "$T/orphan.release"
(
  heavy_on integration
  "$T/runner" "$T/orphan" &
  echo "$!" > "$T/orphan.pid"
  wait
) > "$T/orphan.log" 2>&1 &
parent=$!
wait_for test -f "$T/orphan.started"
kill -KILL "$parent"
wait "$parent" 2>/dev/null || true
start_test after_orphan
after=$TEST_PID
wait_for grep -q 'owns the heavy-work slot' "$T/after_orphan.log"
test ! -e "$T/after_orphan.started"
release_test orphan
wait_for test -f "$T/after_orphan.started"
release_test after_orphan
wait "$after"
# A command short of memory releases admission while sleeping, so it does
# not pin the gate behind an agent which cannot yet run.
(
  export WTQ_AGENT_MEM_MIN_MB=1 WTQ_YIELD_POLL=1
  mem_avail_mb() { cat "$T/memory"; }
  echo 0 > "$T/memory"
  yield_to_gate 'low-memory test'
  touch "$T/memory.admitted"
  heavy_slot_release
) > "$T/memory.log" 2>&1 &
low=$!
wait_for grep -q 'MemAvailable 0 MB' "$T/memory.log"
(
  heavy_on integration
  echo 9000 > "$T/memory"
  heavy_off
)
wait "$low"
test -f "$T/memory.admitted"
echo 'admission_test: transition exclusion, focused serialization, exit status, killed holder and orphan protection passed'
