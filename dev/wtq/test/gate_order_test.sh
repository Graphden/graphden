#!/usr/bin/env bash
# Exercise the real gate_suites body with harmless command stubs. No queue,
# Docker daemon, JVM, or browser stack is touched.
# shellcheck disable=SC1090,SC2034
set -euo pipefail

# A nested CI run inherits the enclosing train's scheduling hints. Each case
# below owns its priority and resume receipts, including the default cases.
unset WTQ_FIRST_STAGE WTQ_RESUME_FROM

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source <(sed -n '/^run_scoped_ci_gate() {/,/^}/p' "$SCRIPT_DIR/wt")
source <(sed -n '/^run_gate_stage() {/,/^}/p' "$SCRIPT_DIR/wt")
source <(sed -n '/^gate_suites() {/,/^}/p' "$SCRIPT_DIR/wt")

TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/tools/browser-test"
TEST_LOG="$TEST_DIR/events"
TARGET=develop
MAIN_WT="$TEST_DIR"
LOG_DIR="$TEST_DIR"
WTQ_REPORT_TS=gate-order-test
CANDIDATE_IMAGE=graphden-executor:candidate
E2E_BASELINE_LOCAL="$TEST_DIR/baseline"

step() { :; }
wait_for_headroom() { echo headroom >> "$TEST_LOG"; [ "${FAIL_HEADROOM:-0}" != 1 ]; }
git() { printf '%s\n' 0000000000000000000000000000000000000001; }
node() { :; }
clojure() { echo uber >> "$TEST_LOG"; }
docker-compose() { echo build >> "$TEST_LOG"; }
verify_candidate_image() {
  echo image >> "$TEST_LOG"
  VERIFIED_IMAGE_ID=sha256:test-candidate
  [ "${FAIL_IMAGE:-0}" != 1 ]
}
save_candidate_proof() { printf '%s\t%s\t%s\n' 0000000000000000000000000000000000000001 "$VERIFIED_IMAGE_ID" jar > "$2"; }
restore_candidate_proof() {
  [ "${FAIL_RESTORE_IMAGE:-0}" != 1 ] && [ -f "$2" ] || return 1
  VERIFIED_IMAGE_ID=sha256:test-candidate
  cp "$2" "$GRAPHDEN_RESUME_OUT.image.tsv"
}
bb() {
  case "$1" in
    scripts/ci_resume_cli.clj)
      if [ "${BAD_STAGE_PLAN:-0}" = 1 ] && [ "$2" = start ]; then printf 'ci\n'; return 0; fi
      command bb -cp "$SCRIPT_DIR/../../scripts" "$SCRIPT_DIR/../../$1" "${@:2}" ;;
    scripts/ci_priority.clj)
      [ "${FAIL_HINTS:-0}" != 1 ] || return 1
      printf '%s\n' '{:unit [] :integration []}' ;;
    scripts/ci_fast_cli.clj) echo 'gate bypassed admitted preflight' >&2; return 1 ;;
    scripts/ci_gate.clj)
      echo "$2" >> "$TEST_LOG"
      if [ "$2" = verify ]; then [ "${FAIL_VERIFY:-0}" != 1 ]; fi ;;
    ci) echo ci >> "$TEST_LOG"; [ "${FAIL_CI:-0}" != 1 ] ;;
    test-e2e)
      echo e2e >> "$TEST_LOG"
      if [ "${EXPECT_PROGRESS_POINTER:-0}" = 1 ]; then
        [ "$(prior_failed_stage test)" = e2e ] || return 1
        grep -q '"e2e" :running' "$LOG_DIR/_train-$WTQ_REPORT_TS.stages.edn" || return 1
      fi
      if [ -n "${EXPECT_BROWSER_ORDER:-}" ]; then
        local browser_order
        browser_order="$(command bb -cp "$SCRIPT_DIR/../../scripts" "$SCRIPT_DIR/../../scripts/ci_resume_cli.clj" \
          start browser "$GRAPHDEN_RESUME_OUT.browser.edn" "$GRAPHDEN_RESUME_FROM.browser.edn" \
          0000000000000000000000000000000000000001 a b c)"
        [ "$(printf '%s\n' "$browser_order" | paste -sd,)" = "$EXPECT_BROWSER_ORDER" ] || return 1
      fi
      [ "${FAIL_E2E:-0}" != 1 ] ;;
    test-visual) echo visual >> "$TEST_LOG"; [ "${FAIL_VISUAL:-0}" != 1 ] ;;
    test-integration) echo integration >> "$TEST_LOG"; [ "${FAIL_INTEGRATION:-0}" != 1 ] ;;
    test-fleet-e2e) echo fleet >> "$TEST_LOG"; [ "${FAIL_FLEET:-0}" != 1 ] ;;
    *) echo "unexpected bb $*" >&2; return 1 ;;
  esac
}
classify_changes() {
  NEED_BUILD="$CASE_BUILD"
  NEED_INTEGRATION="$CASE_INTEGRATION"
  NEED_E2E="$CASE_E2E"
  NEED_VISUAL="$CASE_VISUAL"
  NEED_FLEET="${CASE_FLEET:-0}"
  CHANGED_N=1
  DEPS_DEVTOOL_ONLY=0
  BB_CI_ONLY=0
}
assert_events() {
  local expected="$1" actual
  actual="$(paste -sd, "$TEST_LOG")"
  if [ "$actual" != "$expected" ]; then
    echo "expected $expected, got $actual" >&2
    exit 1
  fi
}
run_case() {
  : > "$TEST_LOG"
  built_candidate=0
  gate_suites "$TEST_DIR" 0 0 0 >/dev/null
}

CASE_BUILD=1 CASE_INTEGRATION=0 CASE_E2E=1 CASE_VISUAL=1
run_case
assert_events 'expect,ci,verify,uber,build,image,visual,e2e'
[ "$built_candidate" = 1 ]

CASE_INTEGRATION=1 CASE_E2E=1 CASE_VISUAL=0
run_case
assert_events 'expect,ci,verify,uber,build,image,e2e,integration'
[ "$built_candidate" = 1 ]

CASE_INTEGRATION=0 CASE_E2E=1 CASE_VISUAL=0
FAIL_E2E=1
: > "$TEST_LOG"
built_candidate=0
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then
  echo 'failed browser gate returned success' >&2
  exit 1
fi
assert_events 'expect,ci,verify,uber,build,image,e2e'
[ "$built_candidate" = 0 ]
unset FAIL_E2E

CASE_VISUAL=1
FAIL_VISUAL=1
: > "$TEST_LOG"
built_candidate=0
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then
  echo 'failed visual gate returned success' >&2
  exit 1
fi
assert_events 'expect,ci,verify,uber,build,image,visual'
[ "$built_candidate" = 0 ]
unset FAIL_VISUAL

CASE_VISUAL=0
FAIL_CI=1
: > "$TEST_LOG"
built_candidate=0
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then
  echo 'failed CI after browser returned success' >&2
  exit 1
fi
assert_events 'expect,ci'
[ "$built_candidate" = 0 ]

unset FAIL_CI
FAIL_VERIFY=1
: > "$TEST_LOG"
built_candidate=0
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then
  echo 'stale or partial CI receipt returned success' >&2
  exit 1
fi
assert_events 'expect,ci,verify'
[ "$built_candidate" = 0 ]
unset FAIL_VERIFY

FAIL_IMAGE=1
: > "$TEST_LOG"
built_candidate=0
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then
  echo 'unverified candidate image returned success' >&2
  exit 1
fi
assert_events 'expect,ci,verify,uber,build,image'
[ "$built_candidate" = 0 ]
unset FAIL_IMAGE

CASE_BUILD=0
CASE_E2E=0
run_case
assert_events 'expect,ci,verify'
[ "$built_candidate" = 0 ]

CASE_INTEGRATION=1
unset VERIFIED_IMAGE_ID
run_case
assert_events 'expect,ci,verify,integration'
[ "$built_candidate" = 0 ]
CASE_INTEGRATION=0

# Every priority preserves the complete mandatory roster, exactly once.
CASE_BUILD=1 CASE_INTEGRATION=1 CASE_E2E=1 CASE_VISUAL=1 CASE_FLEET=1
for WTQ_FIRST_STAGE in ci build e2e visual integration fleet; do
  run_case
  case "$WTQ_FIRST_STAGE" in
    ci) assert_events 'expect,ci,verify,uber,build,image,visual,e2e,integration,fleet' ;;
    build) assert_events 'expect,ci,verify,uber,build,image,visual,e2e,integration,fleet' ;;
    e2e) assert_events 'expect,ci,verify,uber,build,image,e2e,integration,fleet,headroom,visual' ;;
    visual) assert_events 'expect,ci,verify,uber,build,image,visual,e2e,integration,fleet' ;;
    integration) assert_events 'expect,ci,verify,uber,build,image,integration,fleet,headroom,visual,e2e' ;;
    fleet) assert_events 'expect,ci,verify,uber,build,image,fleet,headroom,visual,e2e,integration' ;;
  esac
  [ "$built_candidate" = 1 ]
done

WTQ_FIRST_STAGE=integration
FAIL_INTEGRATION=1
: > "$TEST_LOG"
built_candidate=0
if gate_suites "$TEST_DIR" 0 0 0 > "$TEST_DIR/failure.out"; then
  echo 'failed prioritized integration returned success' >&2; exit 1
fi
assert_events 'expect,ci,verify,uber,build,image,integration'
[ "$built_candidate" = 0 ]
grep -qx 'WTQ_FAILED_STAGE=integration' "$TEST_DIR/failure.out"
unset FAIL_INTEGRATION

# No E2E selected: visual is the first browser boundary, admitted once.
CASE_E2E=0
WTQ_FIRST_STAGE=integration
run_case
assert_events 'expect,ci,verify,uber,build,image,integration,fleet,headroom,visual'

# Admission cannot manufacture success or permit candidate promotion.
FAIL_HEADROOM=1
: > "$TEST_LOG"
built_candidate=0
admission_rc=0
gate_suites "$TEST_DIR" 0 0 0 > "$TEST_DIR/admission.out" || admission_rc=$?
[ "$admission_rc" = 3 ]
assert_events 'expect,ci,verify,uber,build,image,integration,fleet,headroom'
[ "$built_candidate" = 0 ]
if grep -q '^WTQ_FAILED_STAGE=' "$TEST_DIR/admission.out"; then
  echo 'interrupted headroom admission was recorded as a suite failure' >&2; exit 1
fi
unset FAIL_HEADROOM

# An out-of-scope or unknown hint never adds a stage or suppresses CI.
CASE_BUILD=0 CASE_INTEGRATION=0 CASE_E2E=0 CASE_VISUAL=0 CASE_FLEET=0
for WTQ_FIRST_STAGE in integration fleet unknown; do
  run_case
  assert_events 'expect,ci,verify'
  [ "$built_candidate" = 0 ]
done

# Explicit skip flags win even over an otherwise valid failure hint.
CASE_BUILD=1 CASE_INTEGRATION=1 CASE_E2E=1 CASE_VISUAL=1 CASE_FLEET=1
WTQ_FIRST_STAGE=fleet
: > "$TEST_LOG"
gate_suites "$TEST_DIR" 1 1 1 >/dev/null
assert_events 'expect,ci,verify,uber,build,image,integration'
unset WTQ_FIRST_STAGE

# A real progress receipt remembers the executed order through repeated reds.
WTQ_REPORT_TS=first-red
FAIL_VISUAL=1
: > "$TEST_LOG"
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then exit 1; fi
assert_events 'expect,ci,verify,uber,build,image,visual'
unset FAIL_VISUAL
WTQ_RESUME_FROM="$LOG_DIR/_train-first-red"
WTQ_REPORT_TS=second-red
FAIL_INTEGRATION=1
: > "$TEST_LOG"
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then exit 1; fi
assert_events 'expect,ci,verify,uber,build,image,visual,e2e,integration'
unset FAIL_INTEGRATION
WTQ_RESUME_FROM="$LOG_DIR/_train-second-red"
WTQ_REPORT_TS=third-green
run_case
assert_events 'expect,ci,verify,uber,build,image,integration,fleet,headroom,visual,e2e'
unset WTQ_RESUME_FROM

# R1 browser b fails; R2 fails build; R3 must retain b,c,a without manual hints.
WTQ_REPORT_TS=browser-r1
FAIL_E2E=1
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then exit 1; fi
unset FAIL_E2E
printf '%s\n' '{:schema 1 :kind "browser" :sha "0000000000000000000000000000000000000001" :roster ["a" "b" "c"] :status {"a" :passed "b" :failed "c" :pending}}' \
  > "$LOG_DIR/_train-browser-r1.browser.edn"
WTQ_RESUME_FROM="$LOG_DIR/_train-browser-r1"
WTQ_REPORT_TS=build-r2
FAIL_IMAGE=1
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then exit 1; fi
unset FAIL_IMAGE
WTQ_RESUME_FROM="$LOG_DIR/_train-build-r2"
WTQ_REPORT_TS=browser-r3
EXPECT_BROWSER_ORDER=b,c,a
run_case
unset EXPECT_BROWSER_ORDER WTQ_RESUME_FROM

# An interrupted exact candidate credits completed stages. A missing image
# invalidates every image consumer, while CI and integration remain independent.
RESUME_SOURCE="$LOG_DIR/_train-interrupted"
printf '%s\n' '{:schema 1 :kind "stages" :sha "0000000000000000000000000000000000000001" :roster ["ci" "build" "visual" "e2e" "integration" "fleet"] :status {"ci" :passed "build" :passed "visual" :passed "e2e" :running "integration" :pending "fleet" :pending}}' \
  > "$RESUME_SOURCE.stages.edn"
printf 'prior ci\n' > "$RESUME_SOURCE.ci.edn"
printf '0000000000000000000000000000000000000001\tsha256:test-candidate\tjar\n' > "$RESUME_SOURCE.image.tsv"
WTQ_RESUME_FROM="$RESUME_SOURCE"
WTQ_REUSE_FROM="$RESUME_SOURCE"
WTQ_REPORT_TS=resume-green
CASE_BUILD=1 CASE_E2E=1 CASE_VISUAL=1 CASE_INTEGRATION=1 CASE_FLEET=1
run_case
assert_events 'expect,verify,e2e,integration,fleet'
[ "$built_candidate" = 1 ]
FAIL_RESTORE_IMAGE=1
WTQ_REPORT_TS=resume-image-missing
run_case
grep -qx uber "$TEST_LOG"
grep -qx build "$TEST_LOG"
grep -qx image "$TEST_LOG"
grep -qx visual "$TEST_LOG"
sed 's/ "fleet"//; s/ "fleet" :pending//' "$RESUME_SOURCE.stages.edn" > "$RESUME_SOURCE.changed.edn"
mv "$RESUME_SOURCE.changed.edn" "$RESUME_SOURCE.stages.edn"
unset FAIL_RESTORE_IMAGE
WTQ_REPORT_TS=resume-roster-changed
run_case
assert_events 'expect,ci,verify,uber,build,image,e2e,integration,fleet,headroom,visual'
unset FAIL_RESTORE_IMAGE WTQ_REUSE_FROM WTQ_RESUME_FROM

# The shell independently rejects a planner that omits mandatory stages.
BAD_STAGE_PLAN=1
: > "$TEST_LOG"
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then exit 1; fi
assert_events expect
unset BAD_STAGE_PLAN

# Hint preparation failure admits no check or expensive stage.
FAIL_HINTS=1
: > "$TEST_LOG"
if gate_suites "$TEST_DIR" 0 0 0 > "$TEST_DIR/fast.out"; then exit 1; fi
assert_events expect
if grep -q '^WTQ_FAILED_STAGE=' "$TEST_DIR/fast.out"; then exit 1; fi
unset FAIL_HINTS

# Validate durable hints against real git ancestry without touching the queue.
(
  unset -f git
  source <(sed -n '/^prior_failed_stage() {/,/^}/p' "$SCRIPT_DIR/wt")
  source <(sed -n '/^record_failed_stage() {/,/^}/p' "$SCRIPT_DIR/wt")
  MAIN_WT="$TEST_DIR/repo"
  FAILURE_DIR="$TEST_DIR/hints"
  mkdir -p "$FAILURE_DIR"
  git init -q -b develop "$MAIN_WT"
  git -C "$MAIN_WT" config user.email t@example.invalid
  git -C "$MAIN_WT" config user.name wtq-test
  git -C "$MAIN_WT" commit -q --allow-empty -m root
  root="$(git -C "$MAIN_WT" rev-parse HEAD)"
  git -C "$MAIN_WT" commit -q --allow-empty -m base
  base="$(git -C "$MAIN_WT" rev-parse HEAD)"
  git -C "$MAIN_WT" checkout -qb feature/test
  git -C "$MAIN_WT" commit -q --allow-empty -m member
  member="$(git -C "$MAIN_WT" rev-parse HEAD)"
  declare -A M_BRANCH=([test]=feature/test) M_SHA=([test]="$member")
  [ -z "$(prior_failed_stage test)" ]
  record_failed_stage integration "$base" test
  [ "$(prior_failed_stage test)" = integration ]
  git -C "$MAIN_WT" commit -q --allow-empty -m fix
  M_SHA[test]="$(git -C "$MAIN_WT" rev-parse HEAD)"
  [ "$(prior_failed_stage test)" = integration ]
  M_BRANCH[test]=feature/reused
  [ -z "$(prior_failed_stage test)" ]
  M_BRANCH[test]=feature/test
  M_SHA[test]="$root"
  [ -z "$(prior_failed_stage test)" ]
  M_SHA[test]="$member"
  git -C "$MAIN_WT" branch -f develop "$root"
  [ -z "$(prior_failed_stage test)" ]
  git -C "$MAIN_WT" branch -f develop "$base"
  printf 'truncated' > "$FAILURE_DIR/test"
  [ -z "$(prior_failed_stage test)" ]
  printf 'feature/test\tmissing-object\t%s\tintegration\n' "$base" > "$FAILURE_DIR/test"
  [ -z "$(prior_failed_stage test)" ]
  # Saving a pointer happens before dispatch, even if the child never returns.
  WTQ_PROGRESS_MEMBERS=(test)
  EXPECT_PROGRESS_POINTER=1
  WTQ_REPORT_TS=inflight
  CASE_BUILD=1 CASE_INTEGRATION=0 CASE_E2E=1 CASE_VISUAL=0 CASE_FLEET=0
  mkdir -p "$MAIN_WT/tools/browser-test"
  gate_suites "$MAIN_WT" 0 0 0 >/dev/null
  [ "$(prior_failed_stage test)" = e2e ]
  [ "$(WTQ_HINT_RECEIPT=1 prior_failed_stage test)" = "$LOG_DIR/_train-inflight" ]
  unset EXPECT_PROGRESS_POINTER
  record_failed_stage invalid "$base" test
  [ -z "$(prior_failed_stage test)" ]
)

echo 'gate order: default, all priorities, fail-fast, scope and override cases passed'
