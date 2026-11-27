#!/usr/bin/env bash
# Only remove scratch databases explicitly created for a WTQ run.
set -euo pipefail

case "${1:-}" in
  --run)
    [[ "${2:-}" =~ ^[0-9]{8}-[0-9]{6}$ ]] || exit 2
    owner_filter="label=graphden.wtq.run=$2"
    ;;
  --idle)
    [[ -d "${2:-}" ]] || exit 2
    exec 8>>"$2/queue.lock"
    flock -xn 8 || exit 0
    exec 9>>"$2/heavy-work.lock"
    flock -xn 9 || exit 0
    owner_filter="label=graphden.wtq.run"
    ;;
  *) exit 2 ;;
esac

containers="$(docker container ls --all --quiet --no-trunc \
  --filter label=graphden.wtq.disposable=shared-postgres --filter "$owner_filter")"
while IFS= read -r id; do
  [[ "$id" =~ ^[0-9a-f]{64}$ ]] || continue
  status="$(docker container inspect --format '{{.State.Status}}' "$id" 2>/dev/null)" || continue
  case "$status" in
    created|dead|exited) docker container rm --volumes "$id" || true ;;
  esac
done <<< "$containers"

if [ "$1" = --idle ] && [ -d "$2/npm-root" ]; then
  exec 7>>"$2/npm-root/.lock"
  flock -xn 7 || exit 0
  find "$2/npm-root" -mindepth 1 -maxdepth 1 -type d \
    -regextype posix-extended \
    \( -regex '.*/[0-9a-f]{64}' -mtime +14 -o -name '.install.*' -mtime +1 \) \
    -exec rm -rf -- {} +
fi
