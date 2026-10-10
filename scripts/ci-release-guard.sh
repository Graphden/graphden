#!/usr/bin/env bash
# Dispatch inputs identify the exact release proof, never a moving branch.
set -euo pipefail

if [[ ! ${EXPECTED_SHA:-} =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Release proof requires a full commit SHA.' >&2
  exit 1
fi
if [[ ! ${REQUEST_ID:-} =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
  echo 'Release proof requires a UUID request ID.' >&2
  exit 1
fi
if [[ ${GITHUB_SHA:-} != "$EXPECTED_SHA" ]]; then
  echo 'The dispatched ref moved from the expected release commit.' >&2
  exit 1
fi
echo 'Release proof commit and request identity verified.'
