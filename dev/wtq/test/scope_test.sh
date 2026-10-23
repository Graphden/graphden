#!/usr/bin/env bash
# Exercise the production shell classifier on committed EDN blobs, without
# replacing classify_changes or its Babashka parser with a train stub.
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T="$(mktemp -d "${TMPDIR:-/tmp}/wtq-scope-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
REPO="$T/repo"
git init -q -b develop "$REPO"
git -C "$REPO" config user.email scope@example.invalid
git -C "$REPO" config user.name wtq-scope-test
mkdir -p "$REPO/dev/wtq"
cp "$SRC/wt" "$SRC/deps_scope.clj" "$SRC/bb_scope.clj" "$REPO/dev/wtq/"
PASS=0; FAIL=0

cat > "$T/before.edn" <<'EDN'
{:paths ["src"] :deps {example/runtime {:mvn/version "1"}}
 :aliases {:watson {:extra-deps {io.github.clj-holmes/clj-watson
                               {:git/tag "v6.1.0" :git/sha "be98e4d"}}
                    :main-opts ["-m" "clj-watson.cli" "scan"]}
           :test {:extra-paths ["test"]} :build {:ns-default build}}}
EDN
sed 's/v6.1.0/v6.2.0/; s/be98e4d/c0dcdf3/' "$T/before.edn" > "$T/after.edn"

check_scope() { # label, expected flags, before-file, after-file, optional extra path/ref
  local label="$1" expected="$2" before="$3" after="$4" extra="${5:-}" ref="${6:-}" base actual
  rm -f "$REPO/deps.edn" "$REPO/bb.edn" "$REPO/src/change.clj"
  rm -rf "$REPO/docs" "$REPO/resources" "$REPO/test"
  [ "$before" = missing ] || cp "$before" "$REPO/deps.edn"
  git -C "$REPO" add -A; git -C "$REPO" commit -qm base --allow-empty
  base="$(git -C "$REPO" rev-parse HEAD)"
  if [ "$after" = missing ]; then
    rm -f "$REPO/deps.edn"
  else
    cp "$after" "$REPO/deps.edn"
  fi
  if [ -n "$extra" ]; then
    mkdir -p "$(dirname "$REPO/$extra")"; printf 'fixture\n' > "$REPO/$extra"
  fi
  git -C "$REPO" add -A; git -C "$REPO" commit -qm candidate --allow-empty
  actual="$(
    # `help` loads the real functions without starting a queue or mock gate.
    source "$REPO/dev/wtq/wt" help >/dev/null
    TARGET="${ref:-$base}"
    classify_changes "$REPO"
    printf '%s%s%s%s%s/%s' "$NEED_BUILD" "$NEED_INTEGRATION" "$NEED_E2E" "$NEED_FLEET" "$NEED_VISUAL" "$DEPS_DEVTOOL_ONLY"
  )"
  if [ "$actual" = "$expected" ]; then
    PASS=$((PASS + 1)); printf '  ok   %s\n' "$label"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL %s: expected %s got %s\n' "$label" "$expected" "$actual"
  fi
}

check_scope 'audited coordinate: neither runtime nor fleet' 00000/1 "$T/before.edn" "$T/after.edn"
cat > "$T/reordered.edn" <<'EDN'
{:aliases {:build {:ns-default build} :test {:extra-paths ["test"]}
           :watson {:main-opts ["-m" "clj-watson.cli" "scan"]
                    :extra-deps {io.github.clj-holmes/clj-watson
                                 {:git/sha "c0dcdf3" :git/tag "v6.2.0"}}}}
 :deps {example/runtime {:mvn/version "1"}} :paths ["src"]}
EDN
check_scope 'comparison uses EDN values rather than text order' 00000/1 "$T/before.edn" "$T/reordered.edn"
sed 's/:paths \["src"\]/:paths ["src" "other"]/' "$T/after.edn" > "$T/mixed.edn"
check_scope 'mixed paths change retains full runtime gate' 11110/0 "$T/before.edn" "$T/mixed.edn"
sed 's/example\/runtime {:mvn\/version "1"}/example\/runtime {:mvn\/version "2"}/' "$T/after.edn" > "$T/mixed.edn"
check_scope 'mixed root dependency retains full runtime gate' 11110/0 "$T/before.edn" "$T/mixed.edn"
sed 's/:extra-paths \["test"\]/:extra-paths ["test" "other"]/' "$T/after.edn" > "$T/mixed.edn"
check_scope 'test alias change retains full runtime gate' 11110/0 "$T/before.edn" "$T/mixed.edn"
sed 's/:ns-default build/:ns-default other-build/' "$T/after.edn" > "$T/mixed.edn"
check_scope 'build alias change retains full runtime gate' 11110/0 "$T/before.edn" "$T/mixed.edn"
sed 's/:watson /:unknown /' "$T/before.edn" > "$T/unknown-before.edn"
sed 's/:watson /:unknown /' "$T/after.edn" > "$T/unknown-after.edn"
check_scope 'unknown alias is never exempt' 11110/0 "$T/unknown-before.edn" "$T/unknown-after.edn"
sed 's/"clj-watson.cli"/"other.cli"/' "$T/after.edn" > "$T/mixed.edn"
check_scope 'watson entrypoint change is never exempt' 11110/0 "$T/before.edn" "$T/mixed.edn"
sed 's/:git\/tag "v6.2.0"/:deps\/root "other" :git\/tag "v6.2.0"/' "$T/after.edn" > "$T/mixed.edn"
check_scope 'new coordinate keys require an audit' 11110/0 "$T/before.edn" "$T/mixed.edn"
sed 's/c0dcdf3/not-a-sha/' "$T/after.edn" > "$T/mixed.edn"
check_scope 'invalid git coordinate is never exempt' 11110/0 "$T/before.edn" "$T/mixed.edn"
check_scope 'new deps file is never exempt' 11110/0 missing "$T/after.edn"
check_scope 'removed deps file is never exempt' 11110/0 "$T/before.edn" missing
printf '{:aliases\n' > "$T/malformed.edn"
check_scope 'malformed candidate keeps full gate' 11110/0 "$T/before.edn" "$T/malformed.edn"
check_scope 'malformed base keeps full gate' 11110/0 "$T/malformed.edn" "$T/after.edn"
cat "$T/after.edn" > "$T/multiple.edn"; printf '\n{}\n' >> "$T/multiple.edn"
check_scope 'second EDN form keeps full gate' 11110/0 "$T/before.edn" "$T/multiple.edn"
printf '#unregistered/tag {}\n' > "$T/multiple.edn"
check_scope 'unsupported tagged data keeps full gate' 11110/0 "$T/before.edn" "$T/multiple.edn"
check_scope 'missing base ref fails closed including visual' 11111/0 "$T/before.edn" "$T/after.edn" '' nonexistent-ref
check_scope 'ordinary source changes are not hidden' 11100/1 "$T/before.edn" "$T/after.edn" src/change.clj
check_scope 'ordinary docs do not rebuild the image' 00000/0 "$T/before.edn" "$T/before.edn" docs/guide.md
check_scope 'Kaocha plugin also runs image-backed fleet suite' 01010/0 "$T/before.edn" "$T/before.edn" test/kaocha/plugin/parallel.clj
check_scope 'Clojure test runs source suites without image' 01000/0 "$T/before.edn" "$T/before.edn" test/graphden/lesson_test.clj
check_scope 'fleet test also runs image-backed fleet suite' 01010/0 "$T/before.edn" "$T/before.edn" test/graphden/fleet/two_container_e2e_test.clj
check_scope 'Kaocha suite config retains every runtime suite' 11110/0 "$T/before.edn" "$T/before.edn" tests.edn
check_scope 'unknown test asset retains full runtime gate' 11100/0 "$T/before.edn" "$T/before.edn" test/graphden/fixture.edn
check_scope 'shipped AI context rebuilds the image' 10000/0 "$T/before.edn" "$T/before.edn" resources/packages/mcp/mcp/ai-context.md
check_scope 'CI runner edit skips runtime phases' 00000/0 "$T/before.edn" "$T/before.edn" scripts/ci_staged.clj
check_scope 'CI registry edit skips runtime phases' 00000/0 "$T/before.edn" "$T/before.edn" scripts/checks.edn
cat > "$T/before-bb.edn" <<'EDN'
{:paths ["scripts"]
 :tasks {:requires ([ci-fast :as fast])
         :init (def shared "same")
         ci {:task (load-file "scripts/ci.clj")}
         rebuild {:task (load-file "build.clj")}}}
EDN
sed 's/scripts\/ci.clj/scripts\/ci_new.clj/' "$T/before-bb.edn" > "$T/ci-bb.edn"
sed 's/shared "same"/shared "changed"/' "$T/before-bb.edn" > "$T/init-bb.edn"
sed 's/build.clj/build_new.clj/' "$T/before-bb.edn" > "$T/build-bb.edn"
sed 's/ci {:task/unknown {:task 1} ci {:task/' "$T/ci-bb.edn" > "$T/unknown-bb.edn"
printf '{:tasks\n' > "$T/invalid-bb.edn"
cat "$T/ci-bb.edn" > "$T/extra-bb.edn"; printf '\n{}\n' >> "$T/extra-bb.edn"

check_bb_scope() { # label, expected flags, before-file, after-file, optional extra path
  local label="$1" expected="$2" before="$3" after="$4" extra="${5:-}" base actual
  rm -f "$REPO/bb.edn" "$REPO/scripts/ci_staged.clj"
  [ "$before" = missing ] || cp "$before" "$REPO/bb.edn"
  git -C "$REPO" add -A; git -C "$REPO" commit -qm base --allow-empty
  base="$(git -C "$REPO" rev-parse HEAD)"
  if [ "$after" = missing ]; then rm -f "$REPO/bb.edn"; else cp "$after" "$REPO/bb.edn"; fi
  if [ -n "$extra" ]; then
    mkdir -p "$(dirname "$REPO/$extra")"; printf 'fixture\n' > "$REPO/$extra"
  fi
  git -C "$REPO" add -A; git -C "$REPO" commit -qm candidate --allow-empty
  actual="$(
    source "$REPO/dev/wtq/wt" help >/dev/null
    TARGET="$base"
    classify_changes "$REPO"
    printf '%s%s%s%s%s/%s' "$NEED_BUILD" "$NEED_INTEGRATION" "$NEED_E2E" "$NEED_FLEET" "$NEED_VISUAL" "$BB_CI_ONLY"
  )"
  if [ "$actual" = "$expected" ]; then
    PASS=$((PASS + 1)); printf '  ok   %s\n' "$label"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL %s: expected %s got %s\n' "$label" "$expected" "$actual"
  fi
}

check_bb_scope 'audited CI task edit skips runtime' 00000/1 "$T/before-bb.edn" "$T/ci-bb.edn"
check_bb_scope 'shared init edit keeps runtime gate' 11100/0 "$T/before-bb.edn" "$T/init-bb.edn"
check_bb_scope 'build task edit keeps runtime gate' 11100/0 "$T/before-bb.edn" "$T/build-bb.edn"
check_bb_scope 'unknown task added keeps runtime gate' 11100/0 "$T/before-bb.edn" "$T/unknown-bb.edn"
check_bb_scope 'malformed bb.edn keeps runtime gate' 11100/0 "$T/before-bb.edn" "$T/invalid-bb.edn"
check_bb_scope 'extra EDN form keeps runtime gate' 11100/0 "$T/before-bb.edn" "$T/extra-bb.edn"
check_bb_scope 'removed bb.edn keeps runtime gate' 11100/0 "$T/before-bb.edn" missing
check_bb_scope 'CI task mixed with runtime script keeps runtime gate' 11100/1 "$T/before-bb.edn" "$T/ci-bb.edn" scripts/runtime.clj

echo "scope_test: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
