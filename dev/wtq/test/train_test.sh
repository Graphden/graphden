#!/usr/bin/env bash
# train_test.sh — the merge train (dev/wtq/wt) end to end, in throwaway repos.
#
# Every scenario builds a fresh temp git repo with this checkout's `wt` +
# GOVERNANCE committed in it, claims feature worktrees with `wt new`, and runs
# REAL `wt merge` waiters against it. Only the expensive part of the gate is
# replaced: WTQ_GATE_STUB points at a script that reds the train iff the
# assembled tree holds a file named BAD, and logs which members it was asked to
# gate. Everything else — queue entries, the gate lock, conductor election,
# assembly in the _train worktree, the false-green guards, the develop
# fast-forward, verdicts, bisection — is the production code path.
#
# Nothing here touches the real repo's .git/wtq: `wt` derives all its state
# from the git dir of the copy it runs from, which lives under $T.
#
# Run: bash dev/wtq/test/train_test.sh      (bb wtq-test; in bb lint / bb ci)
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # dev/wtq of this checkout
ROOT_T="$(mktemp -d "${TMPDIR:-/tmp}/wtq-train-test.XXXXXX")"
trap 'jobs -p | xargs -r kill 2>/dev/null || true; rm -rf "$ROOT_T"' EXIT

PASS=0; FAIL=0
pass() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$*"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL %s\n' "$*"; }
check() { local msg="$1"; shift; if "$@"; then pass "$msg"; else fail "$msg"; fi; }
eq() { [ "$1" = "$2" ] || { printf '       expected [%s] got [%s]\n' "$2" "$1"; return 1; }; }

wait_for() {   # <timeout-s> <cmd...>
  local t="$1"; shift
  local end=$((SECONDS + t))
  until "$@"; do
    [ "$SECONDS" -lt "$end" ] || return 1
    sleep 0.2
  done
}

# --- one fresh world per scenario -------------------------------------------
new_world() {
  T="$ROOT_T/$1"; REPO="$T/repo"
  mkdir -p "$T"
  export WTQ_ROOT="$T/wt"
  export WTQ_GATE_STUB="$T/stub.sh" STUB_LOG="$T/gates.log"
  export WTQ_NO_PUSH=1 WTQ_LOAD_MAX=100000 WTQ_POLL_SECS=1 WTQ_SOON_POLL=1
  export WTQ_MEM_MIN_MB=0 WTQ_AGENT_MEM_MIN_MB=0 WTQ_YIELD_POLL=1
  export WTQ_LINT_CMD="$T/lint.sh" LINT_LOG="$T/lints.log"
  : > "$LINT_LOG"
  cat > "$WTQ_LINT_CMD" <<'EOF'
#!/usr/bin/env bash
git rev-parse HEAD >> "$LINT_LOG"
if [ -e LINTFAIL ]; then echo "lint: LINTFAIL is present"; exit 1; fi
exit 0
EOF
  chmod +x "$WTQ_LINT_CMD"
  unset WTQ_TRAIN_MAX WTQ_SOON_UNIT WTQ_TARGET
  : > "$STUB_LOG"
  cat > "$WTQ_GATE_STUB" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
[ -f "$(git rev-parse --git-common-dir)/wtq/gate-heavy" ] && echo heavy >> "$STUB_LOG.heavy"
if [ -e BAD ]; then echo "stub: BAD is in the train"; exit 1; fi
exit 0
EOF
  chmod +x "$WTQ_GATE_STUB"
  git init -q -b develop "$REPO"
  git -C "$REPO" config user.email t@example.invalid
  git -C "$REPO" config user.name wtq-test
  mkdir -p "$REPO/dev/wtq"
  cp "$SRC/wt" "$SRC/GOVERNANCE" "$REPO/dev/wtq/"
  printf 'base\n' > "$REPO/f.txt"; printf 'g\n' > "$REPO/g.txt"; printf 'rules\n' > "$REPO/CLAUDE.md"
  git -C "$REPO" add -A && git -C "$REPO" commit -q -m base
  Q="$REPO/.git/wtq"
}

wt_main() { "$REPO/dev/wtq/wt" "$@"; }
feature() {   # <name> <file> <content>  — claim a worktree, commit one change
  wt_main new "$1" >/dev/null 2>&1
  printf '%s\n' "$3" > "$WTQ_ROOT/$1/$2"
  git -C "$WTQ_ROOT/$1" add -A && git -C "$WTQ_ROOT/$1" commit -q -m "feat: $1"
}
declare -A PID
merge_bg() {   # <name> [flags...] — a real `wt merge` waiter in the background
  local n="$1"; shift
  ( cd "$WTQ_ROOT/$n" && exec ./dev/wtq/wt merge "$@" ) > "$T/$n.out" 2>&1 &
  PID[$n]=$!
}
queued() { [ -f "$Q/queue/$1" ]; }
enqueue_in_order() { local n; for n in "$@"; do merge_bg "$n"; wait_for 20 queued "$n"; done; }
# `wait` must run in THIS shell (a $(...) subshell has no children to wait
# for), so exit codes are collected first and read back from RC.
declare -A RC
finish() { local n rc; for n in "$@"; do rc=0; wait "${PID[$n]}" || rc=$?; RC[$n]=$rc; done; }
rc_of() { echo "${RC[$1]:-none}"; }
verdict() { cut -f1 "$Q/results/$1" 2>/dev/null || echo none; }
gates() { cat "$STUB_LOG"; }
hold_lock() {   # hold the gate lock until release_lock, so waiters pile up
  mkdir -p "$Q"; rm -f "$T/release" "$T/held"
  flock "$Q/queue.lock" -c "touch '$T/held'; while [ ! -e '$T/release' ]; do sleep 0.1; done" &
  HOLD_PID=$!
  # Wait until the lock is HELD, not a fixed nap: on a loaded host a waiter
  # started next could win the lock first and run the train early.
  wait_for 30 test -e "$T/held"
}
release_lock() { touch "$T/release"; wait "$HOLD_PID" 2>/dev/null || true; }
# Capture first, grep after: under pipefail `wt ... | grep -q` reds whenever
# grep exits before wt has finished writing (SIGPIPE).
plain() { sed 's/\x1b\[[0-9;]*m//g'; }
list_has() { local out; out="$(wt_main list 2>/dev/null | plain)"; grep -Eq "$1" <<<"$out"; }
log_has()  { local out; out="$(wt_main log "$1" 2>/dev/null | plain)"; grep -Fq "$2" <<<"$out"; }
on_develop() { git -C "$REPO" cat-file -e "develop:$1" 2>/dev/null; }

# --- scenarios ----------------------------------------------------------------
echo "== single branch: a train of one behaves like the old gate"
new_world single
feature solo-wtqtest a.txt a
merge_bg solo-wtqtest
finish solo-wtqtest
check "exit 0" eq "$(rc_of solo-wtqtest)" 0
check "RESULT GREEN" eq "$(verdict solo-wtqtest)" GREEN
check "a.txt landed on develop" on_develop a.txt
check "one gate, one member" eq "$(gates)" "solo-wtqtest"
check "branch untouched, now an ancestor of develop" git -C "$REPO" merge-base --is-ancestor feature/solo-wtqtest develop
check "queue entry removed" eval '! queued solo-wtqtest'
check "wt log shows the train log" log_has solo-wtqtest "GREEN: [ solo-wtqtest ] landed"
check "lint ran once, on the queued sha" eq "$(cat "$LINT_LOG")" "$(git -C "$REPO" rev-parse feature/solo-wtqtest)"
check "lint stamp records that sha" eq "$(cat "$Q/lint-ok/solo-wtqtest")" "$(git -C "$REPO" rev-parse feature/solo-wtqtest)"
check "the gate marked its heavy phase while the suites ran" test -s "$STUB_LOG.heavy"
check "and cleared the marker afterwards" test ! -e "$Q/gate-heavy"
check "wt drop accepts the landed member" eval "wt_main drop solo-wtqtest >/dev/null 2>&1"
check "_train is not a droppable feature" eval "! wt_main drop _train >/dev/null 2>&1"
check "wt list hides the train worktree" eval "! list_has '^_train'"

echo "== three ready branches ride ONE train; non-conductors read their verdict"
new_world three
feature b b.txt b; feature c c.txt c; feature d d.txt d
hold_lock
enqueue_in_order b c d
check "wt list shows them QUEUED" list_has '^c +feature/c.*QUEUED' 
watch_out="$( (timeout 3 "$REPO/dev/wtq/wt" watch c || true) 2>&1 | plain)"
check "wt watch keeps watching a QUEUED branch" grep -q -- '--- .*QUEUED' <<<"$watch_out"
release_lock
finish b c d
for n in b c d; do check "$n exits 0" eq "$(rc_of "$n")" 0; check "$n GREEN" eq "$(verdict "$n")" GREEN; done
check "exactly one gate for all three" eq "$(gates)" "b c d"
check "every waiter printed its own RESULT" eval "grep -q 'RESULT: GREEN' '$T/b.out' && grep -q 'RESULT: GREEN' '$T/c.out' && grep -q 'RESULT: GREEN' '$T/d.out'"
watch_out="$("$REPO/dev/wtq/wt" watch c 2>&1 | plain)"
check "wt watch ends on the final verdict" grep -q '^RESULT: GREEN' <<<"$watch_out"
check "exactly one waiter conducted" eq "$(grep -l 'conducting the train' "$T"/b.out "$T"/c.out "$T"/d.out | wc -l)" 1
check "all three landed" eval "on_develop b.txt && on_develop c.txt && on_develop d.txt"

echo "== four branches, one culprit: bisection lands three, FAILs the culprit"
new_world bisect
feature a a.txt a; feature b b.txt b; feature c BAD c; feature d d.txt d
hold_lock
enqueue_in_order a b c d
release_lock
finish a b c d
for n in a b d; do check "$n GREEN" eq "$(verdict "$n")" GREEN; check "$n exit 0" eq "$(rc_of "$n")" 0; done
check "c FAIL" eq "$(verdict c)" FAIL
check "c exit 1" eq "$(rc_of c)" 1
check "the culprit never reached develop" eval '! on_develop BAD'
check "gates: full train, [a b], [c] alone, [d] alone" eq "$(gates | tr '\n' '|')" "a b c d|a b|c|d|"
check "extra gates within ceil(log2 4)+1 = 3" eq "$(($(gates | wc -l) - 1))" 3
check "no ref ambiguity with the .git/wtq state dir" eval "! grep -q 'broken ref' '$Q'/logs/*.log"
check "the red train's log says it is bisecting" eval "grep -q 'TRAIN RED with 4 members' '$Q'/logs/_train-*.log"
check "c's newest log is its own one-member train" log_has c "gate: wtq-train = develop + [ c ]"

echo "== known-red second half: the lone culprit is confirmed by one gate"
new_world confirm
feature a a.txt a; feature b b.txt b; feature c c.txt c; feature d BAD d
hold_lock
enqueue_in_order a b c d
release_lock
finish a b c d
for n in a b c; do check "$n GREEN" eq "$(verdict "$n")" GREEN; done
check "d FAIL" eq "$(verdict d)" FAIL
check "gates: full, [a b], [c] (known-red [c d] split free), [d] confirm" eq "$(gates | tr '\n' '|')" "a b c d|a b|c|d|"

echo "== two culprits: the bisection budget hands the rest back as a fresh train"
new_world two-culprits
feature a BAD a; feature b b.txt b; feature c BAD2 c; feature d d.txt d
sed -i 's/-e BAD ]/-e BAD ] || [ -e BAD2 ]/' "$WTQ_GATE_STUB"
hold_lock
enqueue_in_order a b c d
release_lock
finish a b c d
check "a FAIL" eq "$(verdict a)" FAIL
check "c FAIL" eq "$(verdict c)" FAIL
check "b GREEN" eq "$(verdict b)" GREEN
check "d GREEN" eq "$(verdict d)" GREEN
check "gates: full, [a b], [a], [b], budget spent -> fresh [c d], [c], [d]" \
  eq "$(gates | tr '\n' '|')" "a b c d|a b|a|b|c d|c|d|"
check "the budget hand-back is announced" grep -q "budget of 3 extra gate(s) spent" "$T/a.out" "$T/b.out" "$T/c.out" "$T/d.out"

# Two members that pass ALONE and fail TOGETHER (neither holds BAD; the stub
# reds only when both marker files are in the tree). No single culprit exists,
# so the sane outcome is: the first of the pair to land goes green, the second
# is gated on a develop that already holds the first and FAILs — every member
# gets a FINAL verdict, the innocent ones land, and the red combination never
# reaches develop. Checked with the pair in one half and split across halves.
pair_scenario() {   # <world> <members...> — PAIR_A / PAIR_B name the pair
  new_world "$1"; shift
  local n
  for n in "$@"; do
    case "$n" in
      "$PAIR_A") feature "$n" PAIR_X "$n" ;;
      "$PAIR_B") feature "$n" PAIR_Y "$n" ;;
      *) feature "$n" "$n.txt" "$n" ;;
    esac
  done
  sed -i 's/-e BAD ]/-e BAD ] || { [ -e PAIR_X ] \&\& [ -e PAIR_Y ]; }/' "$WTQ_GATE_STUB"
  hold_lock
  enqueue_in_order "$@"
  release_lock
  finish "$@"
}
pair_checks() {   # <members...>
  local n final=1 innocent=1
  for n in "$@"; do
    case "$(verdict "$n")" in GREEN|FAIL) ;; *) final=0 ;; esac
    case "$n" in "$PAIR_A"|"$PAIR_B") ;; *) [ "$(verdict "$n")" = GREEN ] && on_develop "$n.txt" || innocent=0 ;; esac
  done
  check "every member got a final verdict (nothing lost mid-bisection)" eq "$final" 1
  check "every innocent member landed" eq "$innocent" 1
  check "the pair: first lands GREEN, second FAILs on top of it" \
    eq "$(verdict "$PAIR_A") $(verdict "$PAIR_B")" "GREEN FAIL"
  check "the FAILed one exits 1, the rest 0" \
    eq "$(for n in "$@"; do printf '%s ' "$(rc_of "$n")"; done)" \
       "$(for n in "$@"; do if [ "$n" = "$PAIR_B" ]; then printf '1 '; else printf '0 '; fi; done)"
  check "the red combination never reached develop" eval '! { on_develop PAIR_X && on_develop PAIR_Y; }'
  check "the queue is empty" eq "$(queued_names_count)" 0
  check "no bisection is left open" eq "$(find "$Q/bisect" -type f 2>/dev/null | wc -l)" 0
  check "the FAIL names its own one-member train" log_has "$PAIR_B" "gate: wtq-train = develop + [ $PAIR_B ]"
}
queued_names_count() { find "$Q/queue" -type f 2>/dev/null | wc -l; }

echo "== two members fail only TOGETHER (same half): one lands, the other FAILs"
PAIR_A=a PAIR_B=b
pair_scenario pair-same a b c d
pair_checks a b c d
check "gates: full, [a b] red, [a], [b] confirm, [c d]" eq "$(gates | tr '\n' '|')" "a b c d|a b|a|b|c d|"

echo "== two members fail only TOGETHER (across halves): one lands, the other FAILs"
PAIR_A=b PAIR_B=c
pair_scenario pair-split a b c d
pair_checks a b c d
unset PAIR_A PAIR_B

echo "== develop moves UNDER the gate -> re-queued, never FAILed"
new_world moved
feature mv mv.txt mv
# One-shot: the first gate commits to develop behind the train's back (what
# `wt new` / `claim` do when they fast-forward develop to an outside push).
touch "$T/move-once"
cat > "$WTQ_GATE_STUB" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
if [ -e "$MOVE_ONCE" ]; then
  rm -f "$MOVE_ONCE"
  printf 'out\n' > "$MOVE_REPO/outside.txt"
  git -C "$MOVE_REPO" add outside.txt && git -C "$MOVE_REPO" commit -qm outside
fi
exit 0
STUB
export MOVE_ONCE="$T/move-once" MOVE_REPO="$REPO"
merge_bg mv
finish mv
unset MOVE_ONCE MOVE_REPO
check "exit 0" eq "$(rc_of mv)" 0
check "RESULT GREEN" eq "$(verdict mv)" GREEN
check "two gates: the moved one, then a train on the new tip" eq "$(gates | tr '\n' '|')" "mv|mv|"
check "both the outside commit and mv landed" eval "on_develop outside.txt && on_develop mv.txt"
check "the train log says it re-queued" eval "grep -q 'moved under the gate' '$Q'/logs/_train-*.log"

echo "== a waiter reads a settled verdict without waiting for memory"
new_world settled
feature sv sv.txt sv
hold_lock
export WTQ_MEM_MIN_MB=99999999 WTQ_HEADROOM_POLL=1
enqueue_in_order sv
export WTQ_MEM_MIN_MB=0; unset WTQ_HEADROOM_POLL
sv_sha="$(git -C "$REPO" rev-parse feature/sv)"
# What a running conductor does when it settles the entry: result, then dequeue.
printf 'GREEN\t0\tfeature/sv\t%s\tt\tlanded elsewhere\n' "$sv_sha" > "$Q/results/sv"
rm -f "$Q/queue/sv"
check "the waiter reports within seconds" wait_for 10 grep -q 'RESULT: GREEN' "$T/sv.out"
grep -q 'RESULT:' "$T/sv.out" || kill "${PID[sv]}" 2>/dev/null || true   # never hang the suite
finish sv
check "with the conductor's verdict" eq "$(rc_of sv)" 0
check "and never waited on memory" eval "! grep -q MemAvailable '$T/sv.out'"
release_lock

echo "== the resource sampler writes one field per column when no java runs"
new_world sampler
mkdir -p "$T/bin"
cat > "$T/bin/pgrep" <<'PGREP'
#!/usr/bin/env bash
# pgrep -c on no match: prints 0 AND exits 1.
if [ "${1:-}" = -c ]; then echo 0; exit 1; fi
exec REAL_PGREP "$@"
PGREP
sed -i "s|REAL_PGREP|$(command -v pgrep)|" "$T/bin/pgrep"
chmod +x "$T/bin/pgrep"
sed -i '2i sleep 2   # long enough for the sampler to write a row' "$WTQ_GATE_STUB"
feature sm sm.txt sm
saved_path="$PATH"; export PATH="$T/bin:$PATH"
merge_bg sm
finish sm
export PATH="$saved_path"
check "sm GREEN" eq "$(rc_of sm)" 0
csv="$(ls "$Q"/logs/_train-*.resources.csv)"
check "every row has 7 fields" eval "awk -F, 'NF != 7 {bad=1} END {exit bad}' '$csv'"
check "it sampled at least once" test "$(wc -l < "$csv")" -ge 2
check "java_procs is 0" eval "tail -n1 '$csv' | grep -q ',0\$'"

echo "== wt list warns about the shared stash"
new_world stash
feature st st.txt st
check "no warning while the stash is empty" eval "! list_has 'stash is SHARED'"
printf 'wip\n' >> "$WTQ_ROOT/st/st.txt"
git -C "$WTQ_ROOT/st" stash -q
check "a stash entry is flagged" list_has 'stash is SHARED'
check "naming the branch it came from" list_has 'stash@\{0\}: WIP on feature/st'

echo "== a green gate that ran e2e feeds the rolling per-file baseline"
new_world e2e-baseline
mkdir -p "$REPO/tools/browser-test"
cp "$SRC/../../tools/browser-test/e2e-baseline.js" "$REPO/tools/browser-test/"
git -C "$REPO" add -A && git -C "$REPO" commit -qm "baseline script"
cat > "$WTQ_GATE_STUB" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
secs="$(cat "$E2E_SECS")"
printf '─── edit-a.test.js ───\n  [ %ss  executor=ok]\n' "$secs"
[ -e DEGRADED ] && echo "  ⚠ ENVIRONMENT DEGRADED: 3 file(s) ran past 2.5x their baseline"
exit 0
STUB
export E2E_SECS="$T/secs"
for i in 1 2 3; do
  echo $((i * 10)) > "$E2E_SECS"
  feature "e$i" "e$i.txt" "e$i"; merge_bg "e$i"; finish "e$i"
done
check "three green runs recorded" eq "$(find "$Q/e2e-runs" -name '*.log' | wc -l)" 3
check "the local baseline holds their median" grep -qP '^20\tedit-a\.test\.js$' "$Q/e2e-baseline.tsv"
echo 500 > "$E2E_SECS"
feature e4 DEGRADED e4; merge_bg e4; finish e4
check "a DEGRADED green run is not recorded" eq "$(find "$Q/e2e-runs" -name '*.log' | wc -l)" 3
check "and does not move the baseline" grep -qP '^20\tedit-a\.test\.js$' "$Q/e2e-baseline.tsv"
unset E2E_SECS

echo "== between trains the gate lets a running pre-queue lint finish"
new_world yield-lint
feature yl yl.txt yl
# shellcheck disable=SC2016  # expands inside the stub, not here
sed -i '2i date +%s.%N > "$STUB_LOG.start"' "$WTQ_GATE_STUB"
hold_lock
enqueue_in_order yl
# Another agent's lint holds the lint lock (it waits for the gate's heavy
# phase to end — i.e. for exactly the gap between two trains).
flock "$Q/lint.lock" -c "echo 'other @ deadbeef' > '$Q/lint.holder'; touch '$T/lint-held'; sleep 2; date +%s.%N > '$T/lint.end'; : > '$Q/lint.holder'" &
lint_pid=$!
wait_for 10 test -e "$T/lint-held"
release_lock
finish yl
wait "$lint_pid" 2>/dev/null || true
check "yl GREEN" eq "$(rc_of yl)" 0
check "the train started only after that lint ended" \
  awk -v a="$(cat "$STUB_LOG.start")" -v b="$(cat "$T/lint.end")" 'BEGIN {exit !(a >= b)}'
check "and said why it waited" grep -q 'letting the pre-queue lint of other' "$T/yl.out"

echo "== conflict with develop itself -> CONFLICT"
new_world conflict
feature x f.txt x
printf 'dev\n' > "$REPO/f.txt"; git -C "$REPO" commit -qam "dev edits f"
merge_bg x
finish x
check "exit 2" eq "$(rc_of x)" 2
check "RESULT CONFLICT" eq "$(verdict x)" CONFLICT
check "no gate ran" eq "$(gates)" ""

echo "== conflict only in the baked devtour -> regenerated, GREEN"
new_world devtour
mkdir -p "$REPO/docs/devtour/org"
printf 'bake0\n' > "$REPO/docs/devtour/index.html"; printf 'o0\n' > "$REPO/docs/devtour/org/a.org"
printf 'src\n' > "$REPO/docs/devtour/tour.edn"
git -C "$REPO" add -A && git -C "$REPO" commit -qm "devtour baked"
export WTQ_DEVTOUR_CMD="$T/devtour.sh"
cat > "$WTQ_DEVTOUR_CMD" <<'EOS'
#!/usr/bin/env bash
printf 'rebaked\n' > docs/devtour/index.html
printf 'rebaked\n' > docs/devtour/org/a.org
EOS
chmod +x "$WTQ_DEVTOUR_CMD"
wt_main new d >/dev/null 2>&1
printf 'bake-d\n' > "$WTQ_ROOT/d/docs/devtour/index.html"; printf 'o-d\n' > "$WTQ_ROOT/d/docs/devtour/org/a.org"
printf 'd\n' > "$WTQ_ROOT/d/d.txt"
git -C "$WTQ_ROOT/d" add -A && git -C "$WTQ_ROOT/d" commit -qm "feat: d re-bakes"
printf 'bake-dev\n' > "$REPO/docs/devtour/index.html"; printf 'o-dev\n' > "$REPO/docs/devtour/org/a.org"
git -C "$REPO" commit -qam "develop re-bakes"
merge_bg d
finish d
check "GREEN" eq "$(verdict d)" GREEN
check "the gate ran for d" eq "$(gates)" "d"
check "develop carries the re-bake, not either side" eq "$(git -C "$REPO" show develop:docs/devtour/index.html)" "rebaked"
check "and d's own change" on_develop d.txt
check "the train log says it regenerated" eval "grep -q 'baked devtour; regenerated' '$Q'/logs/_train-*.log"

echo "== conflict in the devtour SOURCE (tour.edn) -> still CONFLICT"
new_world devtour-src
mkdir -p "$REPO/docs/devtour"
printf 'src\n' > "$REPO/docs/devtour/tour.edn"; printf 'bake0\n' > "$REPO/docs/devtour/index.html"
git -C "$REPO" add -A && git -C "$REPO" commit -qm "devtour"
export WTQ_DEVTOUR_CMD="$T/devtour.sh"
printf '#!/usr/bin/env bash\nprintf "rebaked\\n" > docs/devtour/index.html\n' > "$WTQ_DEVTOUR_CMD"; chmod +x "$WTQ_DEVTOUR_CMD"
wt_main new e >/dev/null 2>&1
printf 'src-e\n' > "$WTQ_ROOT/e/docs/devtour/tour.edn"; printf 'bake-e\n' > "$WTQ_ROOT/e/docs/devtour/index.html"
git -C "$WTQ_ROOT/e" add -A && git -C "$WTQ_ROOT/e" commit -qm "feat: e edits the tour"
printf 'src-dev\n' > "$REPO/docs/devtour/tour.edn"; printf 'bake-dev\n' > "$REPO/docs/devtour/index.html"
git -C "$REPO" commit -qam "develop edits the tour"
merge_bg e
finish e
check "CONFLICT (tour.edn is source, not a bake)" eq "$(verdict e)" CONFLICT
check "no gate ran" eq "$(gates)" ""
unset WTQ_DEVTOUR_CMD

echo "== conflict only with a sibling -> deferred to the next train"
new_world sibling
feature x g.txt x; printf 'x\n' > "$WTQ_ROOT/x/BAD"; git -C "$WTQ_ROOT/x" add -A; git -C "$WTQ_ROOT/x" commit -qm bad
feature y g.txt y
hold_lock
enqueue_in_order x y
release_lock
finish x y
check "x FAIL (it was red on its own)" eq "$(verdict x)" FAIL
check "y GREEN in the next train" eq "$(verdict y)" GREEN
check "gates: [x], then [y]" eq "$(gates | tr '\n' '|')" "x|y|"
check "the first train log says y was deferred" eval "grep -q 'y conflicts only with an earlier member' '$Q'/logs/_train-*.log"

echo "== branch moved after queueing -> STALE"
new_world stale
feature s s.txt s
hold_lock
enqueue_in_order s
printf 'more\n' >> "$WTQ_ROOT/s/s.txt"; git -C "$WTQ_ROOT/s" commit -qam more
release_lock
finish s
check "exit 5" eq "$(rc_of s)" 5
check "RESULT STALE" eq "$(verdict s)" STALE
check "no gate ran" eq "$(gates)" ""

echo "== a rule change travels alone; its sibling waits for 'wt ack' in its place"
new_world governance
feature t1 CLAUDE.md "new rules"
feature t2 t2.txt t2
hold_lock
enqueue_in_order t1 t2
t2_ts="$(sed -n 's/^ts=//p' "$Q/queue/t2")"
release_lock
finish t1
check "t1 GREEN" eq "$(verdict t1)" GREEN
check "t2 is NEEDS-ACK, not PRECOND" wait_for 20 list_has '^t2 .*NEEDS-ACK'
check "still queued, at its original place" eq "$(sed -n 's/^ts=//p' "$Q/queue/t2" 2>/dev/null)" "$t2_ts"
check "its waiter is still waiting" kill -0 "${PID[t2]}"
check "and told its author to ack" wait_for 10 grep -q 'bb wt ack' "$T/t2.out"
check "no verdict for t2 yet" eq "$(verdict t2)" none
ack_out="$( (cd "$WTQ_ROOT/t2" && ./dev/wtq/wt ack) 2>&1 | plain)"
check "wt ack prints the rule diff" grep -q 'new rules' <<<"$ack_out"
check "and re-arms the queued entry" grep -q 're-armed' <<<"$ack_out"
finish t2
check "t2 GREEN after the ack, same waiter" eq "$(verdict t2)" GREEN
check "t2 exit 0" eq "$(rc_of t2)" 0
check "gates: [t1] alone, then [t2]" eq "$(gates | tr '\n' '|')" "t1|t2|"
check "t2 was linted once — the ack did not re-lint" \
  eq "$(grep -c "$(git -C "$REPO" rev-parse feature/t2)" "$LINT_LOG")" 1

echo "== concurrent 'wt merge's lint one at a time"
new_world lint-serial
cat > "$WTQ_LINT_CMD" <<'EOF'
#!/usr/bin/env bash
echo "start $(basename "$PWD")" >> "$LINT_LOG"
sleep 1
echo "end $(basename "$PWD")" >> "$LINT_LOG"
EOF
feature l1 l1.txt l1; feature l2 l2.txt l2
merge_bg l1; merge_bg l2
finish l1 l2
check "both GREEN" eq "$(rc_of l1)$(rc_of l2)" 00
check "the two lints never overlapped" \
  eq "$(cut -d' ' -f1 "$LINT_LOG" | tr '\n' ' ')" "start end start end "
check "the second one said what it waited for" \
  eval "grep -q 'another pre-queue lint is running' '$T/l1.out' '$T/l2.out'"

echo "== soon: the train waits for a marked branch"
new_world soon
export WTQ_SOON_UNIT=5
feature a a.txt a; feature z z.txt z
( cd "$WTQ_ROOT/z" && ./dev/wtq/wt soon 2 ) >/dev/null
check "soon marker written" test -f "$Q/soon/z"
check "wt list shows it" list_has '^Soon: +z ' 
merge_bg a
sleep 2
merge_bg z
finish a z
check "a GREEN" eq "$(rc_of a)" 0
check "z GREEN" eq "$(rc_of z)" 0
check "one train [a z]" eq "$(gates)" "a z"
check "enqueueing cleared the marker" test ! -e "$Q/soon/z"

echo "== soon: an expired marker stops holding the train"
new_world soon-expiry
export WTQ_SOON_UNIT=2
feature a a.txt a; feature w w.txt w
( cd "$WTQ_ROOT/w" && ./dev/wtq/wt soon 1 ) >/dev/null
t0=$SECONDS
merge_bg a
finish a
check "a GREEN without w" eq "$(rc_of a)" 0
check "waited for the marker (>= 1s)" test "$((SECONDS - t0))" -ge 1
check "one train [a]" eq "$(gates)" "a"
check "expired marker removed" test ! -e "$Q/soon/w"
unset WTQ_SOON_UNIT

echo "== WTQ_TRAIN_MAX caps a train"
new_world cap
export WTQ_TRAIN_MAX=2
feature p p.txt p; feature q q.txt q; feature r r.txt r
hold_lock
enqueue_in_order p q r
release_lock
finish p q r
for n in p q r; do check "$n GREEN" eq "$(rc_of "$n")" 0; done
check "trains [p q] then [r]" eq "$(gates | tr '\n' '|')" "p q|r|"
unset WTQ_TRAIN_MAX

echo "== killing a waiter takes it out of the queue"
new_world abort
feature k k.txt k
hold_lock
enqueue_in_order k
kill -TERM "${PID[k]}"
finish k
check "waiter exits 130" eq "$(rc_of k)" 130
check "queue entry removed" eval '! queued k'
release_lock

echo "== a dead waiter's entry is pruned, not landed"
new_world prune
feature g1 g1.txt g1; feature g2 g2.txt g2
hold_lock
enqueue_in_order g1 g2
kill -KILL "${PID[g1]}"; wait "${PID[g1]}" 2>/dev/null || true
release_lock
finish g2
check "g2 GREEN" eq "$(rc_of g2)" 0
check "only g2 was gated" eq "$(gates)" "g2"
check "g1's entry is gone" eval '! queued g1'
check "g1 never landed" eval '! on_develop g1.txt'

echo "== lint red at enqueue -> refused, never queued"
new_world lint-red
feature lr LINTFAIL x
merge_bg lr
finish lr
check "exit 3" eq "$(rc_of lr)" 3
check "RESULT PRECOND" eq "$(verdict lr)" PRECOND
check "the lint output reached the agent" grep -q 'LINTFAIL is present' "$T/lr.out"
check "never queued" eval '! queued lr'
check "no gate ran" eq "$(gates)" ""
check "no stamp for a red lint" test ! -e "$Q/lint-ok/lr"

echo "== lint stamp is reused for the same sha, re-checked by the conductor"
new_world lint-stamp
feature ls1 ls1.txt x
hold_lock
enqueue_in_order ls1
kill -TERM "${PID[ls1]}"; finish ls1
enqueue_in_order ls1
check "the re-queue reused the stamp (lint ran once)" eq "$(wc -l < "$LINT_LOG")" 1
printf 'deadbeef\n' > "$Q/lint-ok/ls1"   # a stamp for some other commit
release_lock
finish ls1
check "stale stamp at the conductor -> PRECOND" eq "$(verdict ls1)" PRECOND
check "no gate ran" eq "$(gates)" ""

echo "== wt test yields to a live heavy phase, ignores a stale one"
new_world yield
export WTQ_KAOCHA="echo KAOCHA-RAN"
feature y1 y1.txt x
sleep 300 & live=$!
printf 'phase=bb test-e2e\npid=%s\n' "$live" > "$Q/gate-heavy"
( cd "$WTQ_ROOT/y1" && exec ./dev/wtq/wt test --focus some.ns ) > "$T/test.out" 2>&1 &
tpid=$!
sleep 2
check "waits while the marker's gate is alive" eval "! grep -q KAOCHA-RAN '$T/test.out'"
check "and says why" grep -q 'heavy phase (bb test-e2e)' "$T/test.out"
kill "$live"; wait "$live" 2>/dev/null || true
wait "$tpid"
check "runs once the marker's pid is gone (stale)" grep -q 'KAOCHA-RAN --focus some.ns' "$T/test.out"
printf 'phase=bb test-e2e\npid=999999\n' > "$Q/gate-heavy"
out="$( (cd "$WTQ_ROOT/y1" && timeout 10 ./dev/wtq/wt test x) 2>&1 )"
check "a stale marker never blocks" grep -q 'KAOCHA-RAN x' <<<"$out"
unset WTQ_KAOCHA

echo
echo "train_test: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
