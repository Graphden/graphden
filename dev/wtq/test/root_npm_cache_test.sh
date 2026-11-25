#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1090
source <(sed -n '/^sync_root_node_modules() (/ ,/^)/p' "$SCRIPT_DIR/wt")
TEST_DIR="$(mktemp -d)"
trap 'rm -r -- "$TEST_DIR"' EXIT
export WTQ_DIR="$TEST_DIR/state"
info() { :; }
# Called by the sourced installer.
# shellcheck disable=SC2329
npm() {
  printf 'npm ci\n' >> "$TEST_DIR/npm.log"
  if grep -q 'invalid' package-lock.json; then return 1; fi
  mkdir node_modules
  cp package-lock.json node_modules/lockfile
}

for name in first second third; do
  mkdir "$TEST_DIR/$name"
  printf '{}\n' > "$TEST_DIR/$name/package.json"
  printf '{"key":"first"}\n' > "$TEST_DIR/$name/package-lock.json"
done
sync_root_node_modules "$TEST_DIR/first"
sync_root_node_modules "$TEST_DIR/second"
[ "$(wc -l < "$TEST_DIR/npm.log")" -eq 1 ]
[ "$TEST_DIR/first/node_modules/lockfile" -ef "$TEST_DIR/second/node_modules/lockfile" ]
printf '{"key":"second"}\n' > "$TEST_DIR/third/package-lock.json"
sync_root_node_modules "$TEST_DIR/third"
[ "$(wc -l < "$TEST_DIR/npm.log")" -eq 2 ]
grep -qx '{"key":"second"}' "$TEST_DIR/third/node_modules/lockfile"
printf 'invalid\n' > "$TEST_DIR/third/package-lock.json"
if sync_root_node_modules "$TEST_DIR/third"; then exit 1; fi
grep -qx '{"key":"second"}' "$TEST_DIR/third/node_modules/lockfile"
echo 'WTQ root npm cache shares matching installs and preserves the old tree on install failure'
