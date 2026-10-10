#!/usr/bin/env bash
set -euo pipefail

guard="$(dirname "$0")/ci-release-guard.sh"
candidate_sha=1111111111111111111111111111111111111111
other_sha=2222222222222222222222222222222222222222
proof_id=12345678-1234-1234-1234-123456789abc

run_guard() {
  GITHUB_SHA="$1" EXPECTED_SHA="$2" REQUEST_ID="$3" bash "$guard" >/dev/null 2>&1
}

run_guard "$candidate_sha" "$candidate_sha" "$proof_id"
for scenario in moved missing-sha malformed-sha missing-id malformed-id; do
  case "$scenario" in
    moved) actual="$other_sha"; expected="$candidate_sha"; request="$proof_id" ;;
    missing-sha) actual=''; expected=''; request="$proof_id" ;;
    malformed-sha) actual='not-a-commit'; expected='not-a-commit'; request="$proof_id" ;;
    missing-id) actual="$candidate_sha"; expected="$candidate_sha"; request='' ;;
    malformed-id) actual="$candidate_sha"; expected="$candidate_sha"; request='not-a-uuid' ;;
  esac
  if run_guard "$actual" "$expected" "$request"; then
    echo "Release proof guard accepted invalid scenario: $scenario" >&2
    exit 1
  fi
done
echo 'Release proof guard: 6 scenarios passed.'
