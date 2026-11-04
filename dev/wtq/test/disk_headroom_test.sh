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
disk_avail_mb() { cat "$TEST_DIR/free-mb"; }
gc_gate_leftovers() { printf 'age-bounded cleanup\n' >> "$TEST_DIR/events"; }
docker() {
  [ "$*" = 'builder prune -a -f' ]
  printf 'full build-cache prune\n' >> "$TEST_DIR/events"
  printf '12500\n' > "$TEST_DIR/free-mb"
}
ensure_disk_headroom
[ "$(paste -sd, "$TEST_DIR/events")" = 'age-bounded cleanup,full build-cache prune' ]

echo 'disk headroom: archived logs and final cache fallback passed'
