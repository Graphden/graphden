#!/usr/bin/env bash
# Run the editor-edit e2e suite. Each test file exits 0 on PASS,
# non-zero on FAIL — we accumulate and surface the worst.
#
# Requires the dev server at http://localhost:9002 (override with
# GRAPHDEN_URL) and a matching AUTH_TOKEN env var. Default token is
# `test123` which matches the default `bb rebuild` flow.

set -u
cd "$(dirname "$0")"

# Resolve the test list once so adding new files only takes a glob.
FILES=$(ls edit-*.test.js 2>/dev/null)
if [ -z "$FILES" ]; then
  echo "no edit-*.test.js files found" >&2
  exit 2
fi

WORST=0
for f in $FILES; do
  echo "─── $f ───"
  if ! node "$f"; then
    WORST=1
  fi
  echo
done

if [ "$WORST" = "0" ]; then
  echo "edit suite: PASS"
else
  echo "edit suite: FAIL" >&2
fi
exit "$WORST"
