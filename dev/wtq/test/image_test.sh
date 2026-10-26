#!/usr/bin/env bash
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T="$(mktemp -d "${TMPDIR:-/tmp}/wtq-image-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
git init -q -b develop "$T/repo"
git -C "$T/repo" -c user.name=fixture -c user.email=fixture@example.invalid \
  -c commit.gpgsign=false commit -q --allow-empty -m fixture
mkdir -p "$T/repo/dev/wtq" "$T/repo/target"
cp "$SRC/wt" "$T/repo/dev/wtq/wt"
printf 'verified jar\n' > "$T/repo/target/executor-server.jar"
cp "$T/repo/target/executor-server.jar" "$T/image.jar"

# Exercise the production function with Docker's four responses mocked. The
# independent source file represents the immutable image's jar bytes.
# shellcheck disable=SC1091  # generated test repo holds the copied production script
source "$T/repo/dev/wtq/wt" help >/dev/null
docker() {
  case "$1 $2" in
    'image inspect') printf 'sha256:test-image\n' ;;
    'create sha256:test-image') printf 'test-container\n' ;;
    'cp test-container:/app/executor-server.jar') cp "$T/image.jar" "$3" ;;
    'rm -f') return 0 ;;
    *) printf 'unexpected docker call: %s\n' "$*" >&2; return 1 ;;
  esac
}
verify_candidate_image "$T/repo"
[ "$VERIFIED_IMAGE_ID" = sha256:test-image ]
GRAPHDEN_RESUME_OUT="$T/resumed"
save_candidate_proof "$T/repo" "$T/source.image.tsv"
restore_candidate_proof "$T/repo" "$T/source.image.tsv"
cmp "$T/source.image.tsv" "$GRAPHDEN_RESUME_OUT.image.tsv"
printf 'stale jar\n' > "$T/image.jar"
if restore_candidate_proof "$T/repo" "$T/source.image.tsv" > "$T/rejected-restore.log"; then
  echo 'stale image was restored' >&2
  exit 1
fi
if verify_candidate_image "$T/repo" > "$T/rejected.log"; then
  echo 'stale image jar was accepted' >&2
  exit 1
fi
rg -q 'jar in candidate image .* differs' "$T/rejected.log"
echo 'image_test: exact jar accepted, stale image rejected'

# A killed train leaves its initial receipt explicitly incomplete. A finished
# one records scope, phases, immutable image identity and the linked CI report.
REPORT="$T/train.edn"
bb "$SRC/report.clj" init "$REPORT" abc123 42
bb -e '(require (quote clojure.edn)) (let [r (clojure.edn/read-string (slurp (first *command-line-args*)))] (assert (false? (:complete r))))' -- "$REPORT"
printf '  image build: RUN\n  e2e: SKIPPED — --no-e2e (manual override)\nverified candidate image sha256:test: executor-server.jar sha256 %064d\n── where the gate spent its time ──\n  12s  build the CANDIDATE image\n  ------\n  12s  TOTAL\n' 0 > "$T/gate.log"
bb "$SRC/report.clj" finish "$REPORT" abc123 42 "$T/gate.log" "$T/ci.edn" "$T/resources.csv" 0
bb -e '(require (quote clojure.edn)) (let [r (clojure.edn/read-string (slurp (first *command-line-args*)))] (assert (true? (:complete r))) (assert (= 0 (:exit r))) (assert (= 12000 (:duration-ms r))) (assert (= :selected (get-in r [:scope :image-build]))) (assert (= :manual-skip (get-in r [:scope :e2e]))) (assert (= "sha256:test" (get-in r [:artifact :image :id]))))' -- "$REPORT"
echo 'image_test: partial and finished train receipts verified'
