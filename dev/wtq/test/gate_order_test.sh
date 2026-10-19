#!/usr/bin/env bash
# Exercise the real gate_suites body with harmless command stubs. No queue,
# Docker daemon, JVM, or browser stack is touched.
# shellcheck disable=SC1090,SC2034
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source <(sed -n '/^run_scoped_ci_gate() {/,/^}/p' "$SCRIPT_DIR/wt")
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
git() { printf '%s\n' 00000001; }
node() { :; }
clojure() { echo uber >> "$TEST_LOG"; }
docker-compose() { echo build >> "$TEST_LOG"; }
verify_candidate_image() {
  echo image >> "$TEST_LOG"
  VERIFIED_IMAGE_ID=sha256:test-candidate
  [ "${FAIL_IMAGE:-0}" != 1 ]
}
bb() {
  case "$1" in
    scripts/ci_gate.clj)
      echo "$2" >> "$TEST_LOG"
      if [ "$2" = verify ]; then [ "${FAIL_VERIFY:-0}" != 1 ]; fi ;;
    ci) echo ci >> "$TEST_LOG"; [ "${FAIL_CI:-0}" != 1 ] ;;
    test-e2e) echo e2e >> "$TEST_LOG"; [ "${FAIL_E2E:-0}" != 1 ] ;;
    test-visual) echo visual >> "$TEST_LOG"; [ "${FAIL_VISUAL:-0}" != 1 ] ;;
    test-integration) echo integration >> "$TEST_LOG" ;;
    test-fleet-e2e) echo fleet >> "$TEST_LOG" ;;
    *) echo "unexpected bb $*" >&2; return 1 ;;
  esac
}
classify_changes() {
  NEED_BUILD="$CASE_BUILD"
  NEED_INTEGRATION="$CASE_INTEGRATION"
  NEED_E2E="$CASE_E2E"
  NEED_VISUAL="$CASE_VISUAL"
  NEED_FLEET=0
  CHANGED_N=1
  DEPS_DEVTOOL_ONLY=0
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
assert_events 'expect,uber,build,image,e2e,visual,ci,verify'
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
assert_events 'expect,uber,build,image,e2e'
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
assert_events 'expect,uber,build,image,e2e,visual'
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
assert_events 'expect,uber,build,image,e2e,ci'
[ "$built_candidate" = 0 ]

unset FAIL_CI
FAIL_VERIFY=1
: > "$TEST_LOG"
built_candidate=0
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then
  echo 'stale or partial CI receipt returned success' >&2
  exit 1
fi
assert_events 'expect,uber,build,image,e2e,ci,verify'
[ "$built_candidate" = 0 ]
unset FAIL_VERIFY

FAIL_IMAGE=1
: > "$TEST_LOG"
built_candidate=0
if gate_suites "$TEST_DIR" 0 0 0 >/dev/null; then
  echo 'unverified candidate image returned success' >&2
  exit 1
fi
assert_events 'expect,uber,build,image'
[ "$built_candidate" = 0 ]
unset FAIL_IMAGE

CASE_BUILD=0
CASE_E2E=0
run_case
assert_events 'expect,ci,verify'
[ "$built_candidate" = 0 ]

echo 'gate order: 8 cases passed'
