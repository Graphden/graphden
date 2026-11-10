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

# Run the real cleanup helper with isolated host boundaries. A dangling
# volume is deliberately offered by the Docker fixture, and both Trivy DBs
# exist: neither may disappear on routine cleanup or low-disk admission.
# shellcheck disable=SC1090
source <(sed -n '/^gc_gate_leftovers() {/,/^}/p' "$SCRIPT_DIR/wt")
export WTQ_TRIVY_CACHE_DIR="$TEST_DIR/trivy-cache"
mkdir -p "$WTQ_TRIVY_CACHE_DIR/trivy/db" "$WTQ_TRIVY_CACHE_DIR/trivy/java-db" "$TEST_DIR/tmp"
printf 'OS vulnerability database\n' > "$WTQ_TRIVY_CACHE_DIR/trivy/db/trivy.db"
printf 'Java vulnerability database\n' > "$WTQ_TRIVY_CACHE_DIR/trivy/java-db/trivy-java.db"
printf 'metadata\n' > "$WTQ_TRIVY_CACHE_DIR/trivy/db/metadata.json"
printf 'metadata\n' > "$WTQ_TRIVY_CACHE_DIR/trivy/java-db/metadata.json"
printf 'recoverable database volume\n' > "$TEST_DIR/volume"
find() {
  if [ "$1" = /tmp ]; then shift; command find "$TEST_DIR/tmp" "$@"
  else command find "$@"; fi
}
# An executable stub also intercepts Docker launched through xargs.
mkdir -p "$TEST_DIR/bin"
cat > "$TEST_DIR/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$TEST_DIR/docker-calls"
case "$*" in
  'volume ls -q -f dangling=true') printf '%064d\n' 1 ;;
  'volume rm '*) rm "$TEST_DIR/volume" ;;
  'image prune -f'|'builder prune -f --filter unused-for=72h'|'builder prune -a -f') : ;;
  *) exit 1 ;;
esac
DOCKER
chmod +x "$TEST_DIR/bin/docker"
export TEST_DIR
export PATH="$TEST_DIR/bin:$PATH"
assert_preserved() {
  [ "$(cat "$TEST_DIR/volume")" = 'recoverable database volume' ]
  [ "$(cat "$WTQ_TRIVY_CACHE_DIR/trivy/db/trivy.db")" = 'OS vulnerability database' ]
  [ "$(cat "$WTQ_TRIVY_CACHE_DIR/trivy/java-db/trivy-java.db")" = 'Java vulnerability database' ]
  [ "$(cat "$WTQ_TRIVY_CACHE_DIR/trivy/db/metadata.json")" = metadata ]
  [ "$(cat "$WTQ_TRIVY_CACHE_DIR/trivy/java-db/metadata.json")" = metadata ]
  ! grep -q '^volume ' "$TEST_DIR/docker-calls"
}
gc_gate_leftovers
assert_preserved
grep -qx 'image prune -f' "$TEST_DIR/docker-calls"
grep -qx 'builder prune -f --filter unused-for=72h' "$TEST_DIR/docker-calls"
# Called indirectly by the sourced admission helper.
# shellcheck disable=SC2329
disk_avail_mb() { printf '11000\n'; }
if ensure_disk_headroom; then
  echo 'low disk must refuse admission instead of deleting preserved data' >&2
  exit 1
fi
assert_preserved
grep -qx 'builder prune -a -f' "$TEST_DIR/docker-calls"

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

echo 'disk headroom: preserved databases and volumes, archived logs, and cache fallback passed'
