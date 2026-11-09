#!/usr/bin/env bash
# Exercise archived-log retrieval and the final reproducible-cache fallback
# without Docker, a gate, or writes outside a private temp directory.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1090
source <(sed -n '/^newest_logs() {/,/^}/p' "$SCRIPT_DIR/wt")
# shellcheck disable=SC1090
source <(sed -n '/^gate_log_tail() {/,/^}/p' "$SCRIPT_DIR/wt")
# shellcheck disable=SC1090
source <(sed -n '/^cmd_log() {/,/^}/p' "$SCRIPT_DIR/wt")
# shellcheck disable=SC1090
source <(sed -n '/^cmd_status() {/,/^}/p' "$SCRIPT_DIR/wt")
# shellcheck disable=SC1090
source <(sed -n '/^archive_gate_logs() {/,/^}/p' "$SCRIPT_DIR/wt")
# shellcheck disable=SC1090
source <(sed -n '/^ensure_disk_headroom() {/,/^}/p' "$SCRIPT_DIR/wt")

TEST_DIR="$(mktemp -d)"
trap 'rm -r -- "$TEST_DIR"' EXIT
LOG_DIR="$TEST_DIR/logs"
mkdir -p "$LOG_DIR"
info() { printf '%s\n' "$*"; }
warn() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
cmd_list() { :; }
# shellcheck disable=SC2034
BLD='' RST=''
int_or() { printf '%s' "${1:-$2}"; }

printf 'archived gate result\n' > "$LOG_DIR/_train-20261101-100000.log"
ln "$LOG_DIR/_train-20261101-100000.log" "$LOG_DIR/feature-20261101-100000.log"
touch -d '2 days ago' "$LOG_DIR/_train-20261101-100000.log"
printf 'active gate result\n' > "$LOG_DIR/_train-20261104-100000.log"
archive_gate_logs
[ -f "$LOG_DIR/_train-20261101-100000.log.gz" ]
[ -f "$LOG_DIR/feature-20261101-100000.log.gz" ]
[ -f "$LOG_DIR/_train-20261104-100000.log" ]
[ "$(newest_logs 1 feature)" = "$LOG_DIR/feature-20261101-100000.log.gz" ]
cmd_log feature | grep -q 'archived gate result'
cmd_status | grep -q '_train-20261101-100000.log.gz  \[ feature  \]'

printf '11000\n' > "$TEST_DIR/free-mb"
unset WTQ_DISK_MIN_MB WTQ_DISK_LAST_STAGE WTQ_DISK_LAST_FREE_MB
CLEANUP_FREE_MB=''
FALLBACK_FREE_MB=12500
disk_avail_mb() {
  printf 'sample\n' >> "$TEST_DIR/samples"
  cat "$TEST_DIR/free-mb"
}
gc_gate_leftovers() {
  printf 'age-bounded cleanup\n' >> "$TEST_DIR/events"
  if [ -n "$CLEANUP_FREE_MB" ]; then printf '%s\n' "$CLEANUP_FREE_MB" > "$TEST_DIR/free-mb"; fi
}
docker() {
  [ "$*" = 'builder prune -a -f' ]
  printf 'full build-cache prune\n' >> "$TEST_DIR/events"
  printf '%s\n' "$FALLBACK_FREE_MB" > "$TEST_DIR/free-mb"
}
ensure_disk_headroom ci > "$TEST_DIR/ci.out"
[ "$(paste -sd, "$TEST_DIR/events")" = 'age-bounded cleanup,full build-cache prune' ]
grep -Fxq 'disk headroom: stage=ci phase=before-cleanup free_mb=11000 minimum_mb=12000 previous_stage=none previous_stage_delta_mb=unknown' "$TEST_DIR/ci.out"
grep -Fxq 'disk headroom: stage=ci phase=after-cleanup free_mb=12500 minimum_mb=12000 cleanup_attempted=1 reclaimed_mb=1500' "$TEST_DIR/ci.out"
[ "$(wc -l < "$TEST_DIR/samples")" -eq 3 ]

# An admitted boundary neither cleans nor adds a sample for its telemetry.
printf '12300\n' > "$TEST_DIR/free-mb"
ensure_disk_headroom build > "$TEST_DIR/build.out"
grep -Fxq 'disk headroom: stage=build phase=before-cleanup free_mb=12300 minimum_mb=12000 previous_stage=ci previous_stage_delta_mb=-200' "$TEST_DIR/build.out"
grep -Fxq 'disk headroom: stage=build phase=after-cleanup free_mb=12300 minimum_mb=12000 cleanup_attempted=0 reclaimed_mb=0' "$TEST_DIR/build.out"
[ "$(wc -l < "$TEST_DIR/samples")" -eq 4 ]
[ "$(wc -l < "$TEST_DIR/events")" -eq 2 ]

# A first cleanup can admit the stage without the full Docker fallback.
CLEANUP_FREE_MB=12600
printf '11900\n' > "$TEST_DIR/free-mb"
ensure_disk_headroom e2e > "$TEST_DIR/e2e.out"
grep -Fxq 'disk headroom: stage=e2e phase=before-cleanup free_mb=11900 minimum_mb=12000 previous_stage=build previous_stage_delta_mb=-400' "$TEST_DIR/e2e.out"
grep -Fxq 'disk headroom: stage=e2e phase=after-cleanup free_mb=12600 minimum_mb=12000 cleanup_attempted=1 reclaimed_mb=700' "$TEST_DIR/e2e.out"
[ "$(wc -l < "$TEST_DIR/samples")" -eq 6 ]
[ "$(wc -l < "$TEST_DIR/events")" -eq 3 ]

# Preserve the refusal when both existing cleanup steps leave too little room.
CLEANUP_FREE_MB=''
FALLBACK_FREE_MB=11842
printf '8706\n' > "$TEST_DIR/free-mb"
if ensure_disk_headroom integration > "$TEST_DIR/integration.out"; then
  echo 'insufficient disk unexpectedly admitted' >&2
  exit 1
fi
grep -Fxq 'disk headroom: stage=integration phase=before-cleanup free_mb=8706 minimum_mb=12000 previous_stage=e2e previous_stage_delta_mb=-3894' "$TEST_DIR/integration.out"
grep -Fxq 'disk headroom: stage=integration phase=after-cleanup free_mb=11842 minimum_mb=12000 cleanup_attempted=1 reclaimed_mb=3136' "$TEST_DIR/integration.out"
[ "$(wc -l < "$TEST_DIR/samples")" -eq 9 ]

echo 'disk headroom: archived logs, admission samples, cleanup deltas and refusal passed'
