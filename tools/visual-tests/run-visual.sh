#!/usr/bin/env bash
# Run the visual-regression suite against whatever stack the caller points us
# at. Exists so `graphden.dev.e2e-stack` can drive this suite the same way it
# drives run-edit-tests.sh: it boots an isolated instance, exports
# GRAPHDEN_URL + AUTH_TOKEN, and executes one script.
#
# Every baseline in this suite must be INSTANCE-INDEPENDENT — that is what
# makes running it here meaningful. Measured 2026-08-22 by re-capturing all 24
# against a fresh isolated stack: 9 of the 12 PNGs came back byte-identical to
# baselines captured months earlier against the demo box, and the 3 that did
# not were all the sidebar scenario, which was photographing whatever
# namespaces the instance happened to hold. It now filters to `core.` first.
#
# If you add a scenario whose picture depends on instance DATA, it will pass
# on your machine and red the gate. Filter, scope to a package-defined
# subtree, or build the fixture through the API — do not re-baseline against
# your own box.
#
# Human loop stays what it was: `bb visual` / `bb visual-update` against a
# running editor of your choosing.

set -eu
cd "$(dirname "$0")" || exit 1

: "${GRAPHDEN_URL:?run-visual.sh expects GRAPHDEN_URL (the e2e stack sets it)}"

echo "visual-regression: ${GRAPHDEN_URL}"

if [ ! -d node_modules ]; then
  echo "no node_modules in tools/visual-tests — run 'npm ci' there" >&2
  exit 2
fi

# Block until the editor answers. The stack reports healthy before the graph
# is compiled, and a screenshot of a half-booted editor is a red baseline that
# says nothing about the change under test.
deadline=$(( $(date +%s) + 120 ))
until curl -fsS -o /dev/null "${GRAPHDEN_URL}/health"; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "editor never became healthy at ${GRAPHDEN_URL}" >&2
    exit 2
  fi
  sleep 2
done

exec npx playwright test
