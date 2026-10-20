#!/usr/bin/env bash
# Run one browser proof against graphden.dev.e2e-stack's isolated server.
# Use GD_IMAGE=graphden-executor:candidate when reproducing a merge gate;
# graphden-executor:latest may still point to an older build.
# Example: GD_IMAGE=graphden-executor:candidate GRAPHDEN_FOCUSED_TESTS='edit-ui-components-selective.test.js edit-ui-components.test.js' \
#   clojure -M:dev -m graphden.dev.e2e-stack tools/browser-test/run-focused-test.sh
set -euo pipefail
cd "$(dirname "$0")"
focused_tests="${GRAPHDEN_FOCUSED_TESTS:-${GRAPHDEN_FOCUSED_TEST:-}}"
if [[ -z "$focused_tests" ]]; then
  echo "set GRAPHDEN_FOCUSED_TESTS to one or more edit-*.test.js filenames" >&2
  exit 2
fi
read -r -a files <<< "$focused_tests"
for test_file in "${files[@]}"; do
  case "$test_file" in
    edit-*.test.js) ;;
    *) echo "invalid browser test filename: $test_file" >&2; exit 2 ;;
  esac
  if [[ ! -f "$test_file" ]]; then
    echo "browser test not found: $test_file" >&2
    exit 2
  fi
  node "$test_file"
done
