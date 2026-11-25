#!/usr/bin/env bash
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/reap-test-containers.sh"
TEST_DIR="$(mktemp -d)"
trap 'rm -r -- "$TEST_DIR"' EXIT
export TEST_DIR
export PATH="$TEST_DIR:$PATH"
export STOPPED RUNNING
STOPPED="$(printf '%064d' 1)"
RUNNING="$(printf '%064d' 2)"
cat > "$TEST_DIR/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$TEST_DIR/docker.log"
case "$*" in
  'container ls --all --quiet --no-trunc --filter label=graphden.wtq.disposable=shared-postgres --filter label=graphden.wtq.run'*) printf '%s\n' "$STOPPED" "$RUNNING" 'not-an-id' ;;
  "container inspect --format {{.State.Status}} $STOPPED") echo exited ;;
  "container inspect --format {{.State.Status}} $RUNNING") echo running ;;
  "container rm --volumes $STOPPED") : ;;
  *) exit 1 ;;
esac
DOCKER
chmod +x "$TEST_DIR/docker"

bash "$SCRIPT" --run 20261125-100000
grep -qx "container rm --volumes $STOPPED" "$TEST_DIR/docker.log"
grep -qx 'container ls --all --quiet --no-trunc --filter label=graphden.wtq.disposable=shared-postgres --filter label=graphden.wtq.run=20261125-100000' "$TEST_DIR/docker.log"
if grep -qx "container rm --volumes $RUNNING" "$TEST_DIR/docker.log"; then exit 1; fi

: > "$TEST_DIR/docker.log"
if bash "$SCRIPT" --run 'invalid'; then exit 1; fi
(
  exec 5>>"$TEST_DIR/queue.lock"
  flock -x 5
  bash "$SCRIPT" --idle "$TEST_DIR"
)
(
  exec 5>>"$TEST_DIR/heavy-work.lock"
  flock -x 5
  bash "$SCRIPT" --idle "$TEST_DIR"
)
[ ! -s "$TEST_DIR/docker.log" ]
old_cache="$TEST_DIR/npm-root/$(printf '%064d' 3)"
new_cache="$TEST_DIR/npm-root/$(printf '%064d' 4)"
mkdir -p "$old_cache" "$new_cache" "$TEST_DIR/npm-root/unowned"
printf 'still linked\n' > "$old_cache/package"
ln "$old_cache/package" "$TEST_DIR/worktree-file"
touch -d '16 days ago' "$old_cache" "$TEST_DIR/npm-root/unowned"
bash "$SCRIPT" --idle "$TEST_DIR"
grep -qx "container rm --volumes $STOPPED" "$TEST_DIR/docker.log"
[ ! -e "$old_cache" ]
[ "$(cat "$TEST_DIR/worktree-file")" = 'still linked' ]
[ -d "$new_cache" ]
[ -d "$TEST_DIR/npm-root/unowned" ]
echo 'WTQ scratch database reaper preserves running and unowned resources'
