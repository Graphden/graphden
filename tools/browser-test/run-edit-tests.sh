#!/usr/bin/env bash
# Run the editor-edit e2e suite. Each test file exits 0 on PASS,
# non-zero on FAIL — we accumulate and surface the worst.
#
# Requires the dev server at http://localhost:9002 (override with
# GRAPHDEN_URL) and a matching AUTH_TOKEN env var. Default token is
# `test123` which matches the default `bb rebuild` flow.
#
# Between-test delay is intentional. Running ~40 tests in a tight
# loop builds up storage state + JVM allocations faster than the
# dev container can GC; the executor's `restart: unless-stopped`
# kicks in mid-sweep and every subsequent test fails with
# ERR_CONNECTION_REFUSED until the rebuild completes. Two seconds
# is empirically enough breathing room — sub-second still flakes,
# 5+ is needlessly slow.
#
# Override with SWEEP_DELAY=N if you want a different pace. The
# isolated e2e stack (graphden.dev.e2e-stack, what the gate runs)
# sets SWEEP_DELAY=0 itself — its executor has restart:on-failure
# and doesn't bounce, so the delay is dead sleep there. This 2s
# default is for a run against the DEMO (:9002).
#
# STRICT MODE (WTQ_FLAKE_STRICT=1, what the landing gate runs) — how a
# failure is judged, in one place (details at each site below):
#
#   * A file that fails and then passes on a retry is a REAL flake → red
#     result, UNLESS the failure carried an environment signature at the
#     moment it happened: the compiled-path probe was dead (a server
#     unavailability window), the registry did a FULL rebuild during the
#     attempt (the request queued behind it), or the host was starved
#     (MemAvailable / load average). A wait TIMEOUT is not by itself an
#     environment signature any more: a UI race shows up exactly as a
#     timeout, and exempting every timeout made strict mode report-only
#     for every race.
#   * The run as a whole is DEGRADED — and every strict flake / leak
#     verdict drops to report-only — when the host starved the stack: at
#     least THRASH_MIN_FILES files ran slow against THEIR OWN baseline
#     (e2e-baseline.tsv: median seconds per file from green gate runs; slow
#     = the passing attempt took > SLOW_FACTOR × baseline and at least
#     SLOW_MIN_EXTRA seconds over it; a file with no baseline yet falls back
#     to the absolute THRASH_FILE_SECS), or THRASH_MIN_FLAKED different
#     files needed a retry. The old rule — any 3 files over an absolute
#     150 s — fired on every healthy run once three tour files grew past
#     it, so strict mode silently did nothing for weeks.
#     Refresh the baseline after a suite change with
#       node e2e-baseline.js <gate log>... > e2e-baseline.tsv
#   * Leaks are counted per file as fns + namespaces + un-archived
#     branches left behind.

set -u
cd "$(dirname "$0")" || exit 1

# Resolve the test list once so adding new files only takes a glob.
#
# EVERY *.test.js in this directory is `edit-`-prefixed and runs here. That is
# new as of 2026-08-22: two other prefixes used to live alongside them, each
# excluded from this glob for a good reason and consequently running NOWHERE —
# not here, not in `bb test-js`, not in GitHub CI. An audit found both. If you
# add a file whose name says it cannot run in this suite, you are adding a test
# that will never execute; make it fit here, or make it fit `bb test-js`.
#
#   regression-*.test.js     drove fn-defs from `examples`, a dev/test-only
#                            package deliberately absent from
#                            `graphden-executor` — so the graph they navigated
#                            to could not exist in this stack. One was rewritten
#                            to build its shape through the API
#                            (edit-regression-sequence-fn-ref.test.js); the
#                            other's assertion moved into
#                            `layout.graph-real-test/layout-migrate-on-fn-ref-test`,
#                            whose fixture DOES load `examples`.
#
#   type-system-ui-*.test.js drove a chromium at a live editor to assert pure
#                            functions. They now run under plain `node` over a
#                            small DOM stub — tools/runtime-test/{type-helpers,
#                            type-resolution-section}.test.js, in `bb test-js`.
FILES=$(ls edit-*.test.js 2>/dev/null)
if [ -z "$FILES" ]; then
  echo "no edit-*.test.js files found" >&2
  exit 2
fi

# Playwright does NOT await an async `waitForFunction` predicate: the pending
# Promise is truthy, the wait returns on its first tick, and the "wait" is
# decorative. Three such waits sat in this suite until 2026-09-05 — lesson 26's
# "did the description land" check among them, which is how a PUT that 400'd
# passed as landed. Poll server state from Node instead (`waitUntil` in
# tutorial-tour-helpers.js, `waitFor` in edit-test-helpers.js).
if grep -nE 'waitForFunction\(\s*async' ./*.js; then
  echo "async waitForFunction predicate — Playwright returns on the Promise, not its value; use waitUntil/waitFor" >&2
  exit 2
fi

SWEEP_DELAY="${SWEEP_DELAY:-2}"
URL="${GRAPHDEN_URL:-http://localhost:9002}"

# Every lesson walk in this run doubles as a SPOTLIGHT AUDIT
# (`installSpotlightAudit` in tutorial-tour-helpers.js): each attempt of each
# file writes to its own directory under AUDIT_ROOT, and only the attempt that
# PASSED is read back — a failed attempt stops mid-lesson and would report the
# steps it never reached as never ringed. The verdict runs after the loop
# (`tour-spotlight-report.js --gate`): a step whose target was never on
# screen for the whole walk is a red, because the reader would sit through
# that step with no ring and the walk itself cannot notice (it clicks by its
# own selectors). GRAPHDEN_TOUR_AUDIT_GATE=0 keeps the report, drops the red.
# An auto-created root is DELETED after a green verdict (nothing to read;
# every gate would otherwise leave one behind) and kept, with its path
# printed, after a red one. A root the caller named is theirs and stays.
AUDIT_AUTO=0
if [ -z "${GRAPHDEN_TOUR_AUDIT:-}" ]; then AUDIT_AUTO=1; fi
AUDIT_ROOT="${GRAPHDEN_TOUR_AUDIT:-$(mktemp -d /tmp/tour-audit.XXXXXX)}"
AUDIT_DIRS=""

# Health probe — block until the executor responds 200. Used between
# tests because docker's `restart: unless-stopped` policy bounces the
# container mid-sweep on cumulative load; without this, every test
# after a bounce ERR_CONNECTION_REFUSED's until the next ~30s
# rebuild completes.
wait_for_server() {
  local deadline=$((SECONDS + 90))
  # /health rides a light path and stays 200 while a request-path recompile
  # parks every worker — the compiled-path probe is the one that proves the
  # server actually serves (same double-probe as edit-test-helpers'
  # waitForServerHealthy; see that comment for the measured repro).
  until curl -fsS -o /dev/null "$URL/health" 2>/dev/null \
        && probe_compiled_path; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "  (server still unhealthy after 90s — giving up)" >&2
      return 1
    fi
    sleep 2
  done
}

# 200 from a REAL compiled-path read, bounded. Used by wait_for_server and by
# the strict-flake triage below.
probe_compiled_path() {
  curl -fsS -o /dev/null --max-time 5 \
       -H "Authorization: Bearer ${AUTH_TOKEN:-}" \
       "$URL/api/graph/entities?scope=index" 2>/dev/null
}

# --- instrumentation --------------------------------------------------------
# This suite ONCE took ~46 minutes for 55 files and nobody could say why, because
# the runner reported pass/fail and nothing else. Worse, the retry below carries
# a note that "suite-tail tests flake under JVM GC pressure when heap passes
# ~85%" — a diagnosis nobody ever measured, only worked around with a retry.
#
# So measure both series, per file: wall time, and the executor's memory after
# it. "Time is spread evenly" and "three files eat ten minutes" are different
# problems. "Heap is flat" and "heap climbs to the cap by test 30" are different
# problems. We were guessing between them.
#
# It is now ~9 minutes for 56 files — 533 / 534 / 550 s across three landing
# gates (`bb test-e2e` reads ~11 min end to end; the extra is the isolated stack
# booting). The ~46 was measured against the DEMO on :9002, where SWEEP_DELAY
# defaults to 2 s and the container's restart policy bounces it mid-sweep; the
# gate's isolated stack sets SWEEP_DELAY=0 and does neither.
#
# That stale figure outlived its own fix by weeks — inside the very comment
# written to stop numbers turning into folklore. Which argues FOR the
# instrument, not against it: a printed measurement can be re-read and
# corrected, a remembered one just gets repeated.
# Memory is read from the executor container's cgroup (`memory.current` minus
# reclaimable `inactive_file` — the figure `docker stats` prints), not from
# `docker stats --no-stream`: that call blocks 1-2 s for a fresh sample, and
# with a `docker ps` to find the container it cost ~3 s per file, minutes per
# gate. The container is resolved ONCE — the e2e stack hands its id over in
# GD_EXECUTOR_CONTAINER; otherwise the one publishing $URL's port (a `bb wt up`
# stack, the demo), not the canonical image tag: with several executors on
# the box the ancestor filter picked the first one (2026-09-07: a worktree
# run reported the personal instance's memory). Re-resolved only when the
# cgroup disappears (a recreated container). No cgroup v2 on the host →
# `docker stats` as before.
EXECUTOR_ID=""
EXECUTOR_CGROUP=""
resolve_executor() {
  local id="${GD_EXECUTOR_CONTAINER:-}" port p
  if [ -z "$id" ]; then
    port="$(printf '%s' "$URL" | sed -nE 's#^[a-z]+://[^:/]+:([0-9]+).*#\1#p')"
    if [ -n "$port" ]; then
      id="$(docker ps --no-trunc --filter "publish=$port" --format '{{.ID}}' 2>/dev/null | head -1)"
    fi
    [ -n "$id" ] || id="$(docker ps --no-trunc --filter "ancestor=${GD_IMAGE:-graphden-executor:latest}" \
                    --format '{{.ID}}' 2>/dev/null | head -1)"
  fi
  EXECUTOR_ID="$id"
  EXECUTOR_CGROUP=""
  [ -n "$id" ] || return
  for p in "/sys/fs/cgroup/system.slice/docker-$id.scope" "/sys/fs/cgroup/docker/$id"; do
    if [ -r "$p/memory.current" ]; then EXECUTOR_CGROUP="$p"; return; fi
  done
}
executor_mem() {
  if [ -z "$EXECUTOR_ID" ] || { [ -n "$EXECUTOR_CGROUP" ] && [ ! -r "$EXECUTOR_CGROUP/memory.current" ]; }; then
    resolve_executor
  fi
  [ -n "$EXECUTOR_ID" ] || { printf '?'; return; }
  if [ -n "$EXECUTOR_CGROUP" ]; then
    awk -v cur="$(cat "$EXECUTOR_CGROUP/memory.current" 2>/dev/null)" \
        '$1 == "inactive_file" {inact = $2}
         END {v = cur - inact; if (cur == "" || v <= 0) {printf "?"; exit}
              if (v >= 1073741824) printf "%.3fGiB", v / 1073741824;
              else printf "%.1fMiB", v / 1048576}' \
        "$EXECUTOR_CGROUP/memory.stat" 2>/dev/null || printf '?'
    return
  fi
  docker stats --no-stream --format '{{.MemUsage}}' "$EXECUTOR_ID" 2>/dev/null \
    | awk '{print $1}' | head -1
}
TIMINGS=""          # "<seconds>\t<mem>\t<file>" per line, for the summary
SUITE_START=$SECONDS

# Count the fns in the graph. A test that leaves entities behind does not fail —
# it makes the NEXT file fail, on a graph it never created. That is why the
# flakes here always turned up somewhere innocent, got "fixed" there with a
# longer timeout or a retry, and came back.
#
# Measured: edit-inheritance-regression deleted parents and children in one
# parallel batch, the parent DELETEs 409'd ("Graph is a parent of 1 other
# graph"), the errors were swallowed, and 8 fns stayed in the graph. Run it, then
# run edit-arg-type-override, and that one times out. Run it alone and it passes
# 3/3. Nobody could see the connection because nothing was counting.
#
# So count, per file, and name the file that leaked in the run that leaked.
#
# Count the FNS, not the string `"name"`. The payload is `{fns, namespaces}` and
# both carry a name, so the naive grep reported a package install's two leftover
# NAMESPACES as "LEAKED 2 fn(s)" and sent me looking for a cascade bug that was
# not there. An instrument that misnames what it measures is worse than none: it
# spends the time you gave it to save.
#
# A FAILED sample returns -1, never 0. The executor is allowed to die and come
# back mid-suite (`--memory 3g` + `restart=on-failure:3` turn a >3GB burst into
# a discrete, recoverable event on purpose), and during that window this curl
# gets a connection reset. Reading that as "the graph holds 0 fns" made the next
# file's after-count read as a leak of the WHOLE GRAPH — 8892 rows blamed on an
# innocent test, which is the same misnaming trap as the namespaces note above.
# -1 is not a count, so the leak verdict below can tell "no rows" from "no answer".
#
# Namespaces leak too — a package install creates one per version — and they
# show up in the sidebar tree of every file that runs after. Counted
# separately so the report says which kind of row was left behind. Both come
# from ONE `scope=index` read (the payload carries both lists; the old
# fn_count / ns_count pair downloaded the same whole-graph index twice per
# sample, four times per file).
#
# Branches leak as well, and nothing counted them: four files left ten
# branches in every gate run because their cleanup DELETEs were refused (a
# merged branch is undeletable while its target lives) and the refusal was
# swallowed. An ARCHIVED branch is not counted — archiving is the designed
# end state of a branch that cannot be deleted (see deleteBranches in
# edit-test-helpers.js).
#
# Prints "<fns> <namespaces> <branches>"; "-1 -1 -1" when any read failed.
SAMPLE_DIR="$(mktemp -d /tmp/e2e-sample.XXXXXX)"
trap 'rm -rf "$SAMPLE_DIR"' EXIT
graph_counts() {
  if curl -fsS --max-time 30 -H "Authorization: Bearer ${AUTH_TOKEN:-}" \
          -o "$SAMPLE_DIR/index.json" "$URL/api/graph/entities?scope=index" 2>/dev/null \
     && curl -fsS --max-time 30 -H "Authorization: Bearer ${AUTH_TOKEN:-}" \
          -o "$SAMPLE_DIR/branches.json" "$URL/api/branches" 2>/dev/null; then
    python3 -c '
import sys, json
d = json.load(open(sys.argv[1]))
b = json.load(open(sys.argv[2]))
live = [x for x in (b.get("branches") or []) if not x.get("archived-at")]
print(len(d.get("fns") or []), len(d.get("namespaces") or []), len(live))
' "$SAMPLE_DIR/index.json" "$SAMPLE_DIR/branches.json" 2>/dev/null || echo "-1 -1 -1"
  else
    echo "-1 -1 -1"
  fi
}

# What did the executor DO while this file ran?
#
# The leak counters above answer "did it leave rows behind", and they come back
# clean in every flaked run to date — so the remaining flake is not that. These
# answer a different question. A compiled-registry full-clear makes the NEXT
# request rebuild the whole graph; at 4137 fns that was measured at 49.8 s. A
# test that lands in that window times out at 10s and sails through the retry
# ten seconds later. That is the exact shape of every flake in this suite — a
# different innocent file each run, always green on retry — and nothing could
# ever see it, because the event leaves no trace in a log or a stack trace.
#
# /metrics carries `counters` now. Sample it around each file and print the
# delta, so a full-clear sitting next to a failure becomes a fact instead of a
# theory. Costs one HTTP GET per file.
executor_counters() {
  curl -fsS -H "Authorization: Bearer ${AUTH_TOKEN:-}" "$URL/metrics" 2>/dev/null \
    | python3 -c 'import sys,json; print(json.dumps((json.load(sys.stdin) or {}).get("counters") or {}, sort_keys=True))' \
       2>/dev/null || echo '{}'
}

# `after` minus `before`, omitting whatever did not move. Empty output means the
# executor did no structural work at all while the file ran.
counters_delta() {
  python3 -c '
import sys, json
b = json.loads(sys.argv[1] or "{}")
a = json.loads(sys.argv[2] or "{}")
d = {k: a[k] - b.get(k, 0) for k in a if a[k] - b.get(k, 0) > 0}
print(" ".join(f"{k}={v}" for k, v in sorted(d.items())))
' "$1" "$2" 2>/dev/null || true
}

# Did the executor PROCESS restart while this file ran?
#
# The counters are monotonic within one JVM, so any of them moving BACKWARDS
# means the numbers came from two different incarnations. That happens by
# design: `--memory 3g` + `restart=on-failure:3` turn a >3GB burst into a
# discrete OOM-and-come-back, and `wait_for_server` then recovers the suite
# transparently. Nothing about that is a test's fault — but it invalidates
# every before/after comparison taken across it, the leak count first of all.
# Prints `1` when the counters cannot belong to the same process.
counters_restarted() {
  python3 -c '
import sys, json
b = json.loads(sys.argv[1] or "{}")
a = json.loads(sys.argv[2] or "{}")
print(1 if any(k in a and a[k] < b[k] for k in b) else 0)
' "$1" "$2" 2>/dev/null || echo 0
}

# Did the registry do a FULL rebuild between two counter samples? That is the
# one executor event known to stall a request long enough to time a wait out
# (a full-clear makes the next request recompile the whole graph — 49.8 s at
# 4137 fns); delta recompiles are routine and small. Prints `1` or `0`.
counters_full_rebuild() {
  python3 -c '
import sys, json
b = json.loads(sys.argv[1] or "{}")
a = json.loads(sys.argv[2] or "{}")
keys = ("registry/invalidate-full", "registry/rebuild", "registry/delta-fell-back-to-rebuild")
restarted = any(k in a and a[k] < b[k] for k in b)
print(1 if restarted or any(a.get(k, 0) > b.get(k, 0) for k in keys) else 0)
' "$1" "$2" 2>/dev/null || echo 0
}

# Is the HOST starving the stack right now? Empty when not; otherwise what it
# saw. Thresholds: HOST_MEM_MIN_MB (MemAvailable, default 1000) and
# HOST_LOAD_PER_CPU (1-min load per CPU, default 2).
host_starved() {
  local avail load cpus
  avail="$(awk '/^MemAvailable:/ {printf "%d", $2 / 1024}' /proc/meminfo 2>/dev/null)"
  load="$(cut -d' ' -f1 /proc/loadavg 2>/dev/null)"
  cpus="$(nproc 2>/dev/null || echo 1)"
  awk -v a="${avail:-}" -v l="${load:-0}" -v c="$cpus" \
      -v amin="${HOST_MEM_MIN_MB:-1000}" -v lmax="${HOST_LOAD_PER_CPU:-2}" 'BEGIN {
    if (a != "" && a + 0 < amin + 0) printf "MemAvailable=%dMB ", a;
    if (l + 0 > lmax * c) printf "load1=%s on %d cpus", l, c }'
}

# Per-file duration baseline for the DEGRADED verdict (see the header).
declare -A BASELINE
BASELINE_FILE="${E2E_BASELINE:-e2e-baseline.tsv}"
if [ -r "$BASELINE_FILE" ]; then
  while IFS=$'\t' read -r b_secs b_file; do
    case "$b_secs" in ''|'#'*) continue ;; esac
    BASELINE["$b_file"]="$b_secs"
  done < "$BASELINE_FILE"
fi
SLOW_FACTOR="${SLOW_FACTOR:-2.5}"
SLOW_MIN_EXTRA="${SLOW_MIN_EXTRA:-30}"
# Seconds past which a file's attempt reads as STARVED rather than slow-ish.
slow_limit() {
  local b="${BASELINE[$1]:-}"
  if [ -z "$b" ]; then printf '%s' "$THRASH_FILE_SECS"; return; fi
  awk -v b="$b" -v f="$SLOW_FACTOR" -v m="$SLOW_MIN_EXTRA" \
      'BEGIN {l = b * f; if (l < b + m) l = b + m; printf "%d", l}'
}

pos() { if [ "$1" -gt 0 ] 2>/dev/null; then echo "$1"; else echo 0; fi; }

LEAKS=""
FLAKED=""
SLOW_FILES=""       # "file(secs>limit)" for the DEGRADED banner

WORST=0
PASS=0
FAIL=0
FAILED_NAMES=""
# --- run-level thrash detection (decided in the escalation block after the loop) ---
# A strict-flake (a file that fails once then PASSES on retry) and a leak-in-a-
# passing-test are only worth bouncing the branch for when the HOST was healthy:
# on a starved host a retry-pass is the test being SLOW, not a real race, and the
# leaked rows are abort-collateral. So we DEFER those two strict verdicts here and
# escalate them to a red RESULT only if the run was NOT degraded. A genuine race
# still reproduces on a quiet host, where DEGRADED=0 and strict stays on.
STRICT_FLAKES=""    # flaked-passed-on-retry files; strict-escalated only if NOT degraded
STRICT_LEAKS=""     # leak-in-passing-test files (name(count)); same
DEGRADED_FILES=0    # count of files whose attempt ran past slow_limit (own baseline)
UNCOUNTABLE_LEAKS=0 # files whose leak check was skipped: the executor was down for a
                    # sample, or restarted between the two. Named in the leak banner so
                    # a skipped check never reads as a clean one.
HEAP_HWM_MIB=0      # executor heap high-water (docker stats), MiB — INFO ONLY in the banner,
                    # NOT a degraded trigger: a JVM at MaxRAMPercentage commits heap toward
                    # the cap regardless of pressure (the "executor memory" note at the end
                    # of this file measured a FLAT after-GC live-set), so ~1.7GiB is normal.
THRASH_FILE_SECS=${THRASH_FILE_SECS:-150}   # slow limit for a file with NO baseline yet (cap is 300s)
THRASH_MIN_FILES=${THRASH_MIN_FILES:-3}     # this many slow files (vs own baseline) => degraded run
THRASH_MIN_FLAKED=${THRASH_MIN_FLAKED:-2}   # OR this many DIFFERENT files needing a retry: a
                                            # real race is localized to one file, so several
                                            # innocent files flaking in one run = host jitter
# Consecutive server-down counter. Demo (:9002) has docker restart-
# policy so a single bounce recovers; an isolated testcontainer
# stack does NOT auto-restart, so a single crash cascades through
# every subsequent test as a 90s `ERR_CONNECTION_REFUSED` wait. Cap
# the consecutive cascade — if the server stays down past N
# attempts, mark remaining files as skipped and abort the loop.
# Saves ~37 min on a full 47-test suite (90s × 25 = ~37 min wasted
# in the cascade window).
CONSECUTIVE_DOWN=0
CASCADE_CAP=${CASCADE_CAP:-3}
SKIP_AFTER_CASCADE=0
REMAINING_FILES=""
for f in $FILES; do
  if [ "$SKIP_AFTER_CASCADE" = "1" ]; then
    REMAINING_FILES="$REMAINING_FILES $f"
    continue
  fi
  echo "─── $f ───"
  FILE_START=$SECONDS
  # NOTE: the baseline is sampled AFTER wait_for_server, not before it. The
  # previous file may have ended on an executor OOM-restart (a designed,
  # recoverable event — see the --memory bullet in e2e_stack.clj), and a
  # baseline taken against the dead or still-booting server is not a baseline.
  if ! wait_for_server; then
    WORST=1
    FAIL=$((FAIL+1))
    FAILED_NAMES="$FAILED_NAMES $f(server-down)"
    CONSECUTIVE_DOWN=$((CONSECUTIVE_DOWN+1))
    if [ "$CONSECUTIVE_DOWN" -ge "$CASCADE_CAP" ]; then
      echo "  (server down for $CONSECUTIVE_DOWN consecutive tests — aborting cascade)" >&2
      SKIP_AFTER_CASCADE=1
    fi
    continue
  fi
  CONSECUTIVE_DOWN=0
  read -r FN_BEFORE NS_BEFORE BR_BEFORE <<<"$(graph_counts)"
  CTR_BEFORE="$(executor_counters)"
  CTR_ATTEMPT="$CTR_BEFORE"
  # Per-test wall-clock cap. Individual tests should complete in
  # < 1 min under load; bounded at 5 min hard, then SIGKILL via the
  # GNU coreutils `timeout`. Without this a stuck `page.evaluate`
  # against an unresponsive editor JS can pin a single test for
  # arbitrarily long (verified empirically: edit-effects-badges hung
  # 51 min on a slow server window). The cap turns the hang into a
  # discrete failure that gets cascade-counted, so the rest of the
  # suite isn't held hostage.
  #
  # 5 min is the chosen number because the heaviest test
  # (edit-inheritance-regression, 30 await ops) was tripping a 3 min
  # cap during slow-server windows even though it eventually would
  # have completed correctly. 5 min preserves the hang-bound
  # contract while reducing false-positive timeouts.
  # Up to 5 attempts. A transient GC / slow-server window under the gate's
  # load (heap past ~85% → >5s pauses; brief server-unavailability during
  # write-heavy tests — task #10) can hit the SAME file on several consecutive
  # tries; the extra recovery windows catch that without hiding a real break,
  # which fails all five.
  #
  # A test that only passes AFTER a retry is a FLAKE — named LOUDLY in the
  # summary, never silently swallowed: every root cause found in this suite
  # (the dead type picker, the empty Run form) first showed up as one failure
  # a retry hid, and twice the "fix" was raising the timeout the retry masked.
  # Whether a flake also FAILS the run is a queue-economics knob
  # (WTQ_FLAKE_STRICT=1): strict mode is multi-agent-pool insurance (one flake
  # re-runs a serialized gate slot others queue behind); single-agent default
  # is report-loud, stay green. `passed` (0/1) is read by the leak check below.
  passed=0
  rc=0
  is_timeout=0
  real_flake=0
  judged_secs=""      # the passing attempt's seconds, else the fastest failed one
  for attempt in 1 2 3 4 5; do
    if [ "$attempt" -gt 1 ]; then
      echo "  (attempt $((attempt - 1)) rc=$rc — sleeping 10s, retry $attempt/5)" >&2
      sleep 10
      wait_for_server || break
      CTR_ATTEMPT="$(executor_counters)"
    fi
    attempt_out="$(mktemp)"
    GRAPHDEN_TOUR_AUDIT="$AUDIT_ROOT/${f%.test.js}.attempt$attempt"
    export GRAPHDEN_TOUR_AUDIT
    ATTEMPT_START=$SECONDS
    if timeout -k 5 "${PER_TEST_TIMEOUT:-300}" node "$f" >"$attempt_out" 2>&1; then
      judged_secs=$((SECONDS - ATTEMPT_START))
      cat "$attempt_out"; rm -f "$attempt_out"
      passed=1
      [ -d "$GRAPHDEN_TOUR_AUDIT" ] && AUDIT_DIRS="$AUDIT_DIRS $GRAPHDEN_TOUR_AUDIT"
      break
    else
      # Capture node's exit code HERE (inside the else) — after the `fi` it
      # would read the `if`'s own status, which is 0 for a false condition
      # with no else, masking a real 124/137 timeout.
      rc=$?
      a_secs=$((SECONDS - ATTEMPT_START))
      if [ -z "$judged_secs" ] || [ "$a_secs" -lt "$judged_secs" ]; then judged_secs=$a_secs; fi
      cat "$attempt_out"
      if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then is_timeout=1; fi
      # Strict-flake TRIAGE. Three environment signatures, probed at the
      # moment of failure; anything else is a REAL flake candidate:
      #   - compiled-path probe DEAD → unavailability window (a request-
      #     path recompile parks the worker pool while /health stays 200);
      #   - the registry did a FULL rebuild during this attempt → a request
      #     queued behind the recompile (reads serve — the probe passes —
      #     while a write waits on the compile permit; measured: the same
      #     publish is >60s in-sweep and 4-5s solo, 8/8);
      #   - the HOST is starved right now (host_starved).
      # A wait TIMEOUT alone is NOT one of them. It used to be — "every
      # flake so far was timeout-shaped" — but a UI race looks exactly like
      # a timeout too (the selector the race removed never shows up), so
      # that rule exempted every race and strict mode caught nothing. A
      # timeout with none of the signatures above is judged like any other
      # failure; a starved RUN is still forgiven by the run-level DEGRADED
      # verdict after the loop.
      shape="assertion"
      if grep -qE 'Timeout [0-9]+ms exceeded|TimeoutError' "$attempt_out" \
         || [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then shape="timeout"; fi
      starved="$(host_starved)"
      if ! probe_compiled_path; then
        echo "  (probe: compiled path DEAD at failure time — SERVER WINDOW, not counted strict)" >&2
      elif [ "$(counters_full_rebuild "$CTR_ATTEMPT" "$(executor_counters)")" = 1 ]; then
        echo "  ($shape-shaped failure during a FULL registry rebuild — stalled behind the recompile, not counted strict)" >&2
      elif [ -n "$starved" ]; then
        echo "  ($shape-shaped failure on a starved host ($starved) — not counted strict)" >&2
      else
        real_flake=1
        echo "  (probe OK, no rebuild, host healthy — $shape-shaped failure is a REAL flake candidate)" >&2
      fi
      rm -f "$attempt_out"
    fi
  done
  if [ "$passed" = 1 ]; then
    PASS=$((PASS+1))
    if [ "$attempt" -gt 1 ]; then
      FLAKED="$FLAKED $f"
      if [ "${WTQ_FLAKE_STRICT:-0}" = "1" ] && [ "$real_flake" = 1 ]; then
        STRICT_FLAKES="$STRICT_FLAKES $f"
        echo "  (passed on attempt $attempt — REAL flake candidate; strict verdict DEFERRED to the run-level thrash check)" >&2
      elif [ "${WTQ_FLAKE_STRICT:-0}" = "1" ]; then
        echo "  (passed on attempt $attempt — server-window retry: named in the summary, NOT a strict failure)" >&2
      else
        echo "  (passed on attempt $attempt — FLAKE: named in the summary, run stays green)" >&2
      fi
    fi
  else
    WORST=1
    FAIL=$((FAIL+1))
    if [ "$is_timeout" -eq 1 ]; then
      FAILED_NAMES="$FAILED_NAMES $f(timeout)"
    else
      FAILED_NAMES="$FAILED_NAMES $f"
    fi
  fi
  FILE_SECS=$((SECONDS - FILE_START))
  FILE_MEM="$(executor_mem)"
  # The wall time above spans EVERY attempt; say so on the line itself —
  # a 181 s file that was 11 s + a 150 s timeout + a retry reads very
  # differently from one slow run (the FLAKED note lives only in the summary).
  ATTEMPT_NOTE=""
  if [ "${attempt:-1}" -gt 1 ]; then ATTEMPT_NOTE="  attempts=$attempt"; fi
  # Thrash signal: an attempt far past THIS file's own baseline — the passing
  # attempt, or the fastest failed one (a retried file's wall time spans every
  # attempt, and a failure that sat out a wait timeout is slow BECAUSE it
  # failed, which says nothing about the host). (Heap high-water is tracked
  # too but only for the banner — see the HEAP_HWM_MIB note above for why it
  # is not a trigger.) executor_mem is like "1.701GiB" / "812.3MiB" / "?" —
  # normalise to MiB.
  file_limit="$(slow_limit "$f")"
  if [ -n "$judged_secs" ] && [ "$judged_secs" -gt "$file_limit" ]; then
    DEGRADED_FILES=$((DEGRADED_FILES+1))
    SLOW_FILES="$SLOW_FILES $f(${judged_secs}s>${file_limit}s)"
    echo "  (slow: ${judged_secs}s vs this file's limit ${file_limit}s — counts toward DEGRADED)" >&2
  fi
  file_mib="$(printf '%s' "$FILE_MEM" | awk '{v=$0; g=(v ~ /GiB/); sub(/[A-Za-z].*/,"",v); if (v+0>0) printf "%d", (g? v*1024 : v+0); else print 0}')"
  if [ "${file_mib:-0}" -gt "$HEAP_HWM_MIB" ] 2>/dev/null; then HEAP_HWM_MIB="$file_mib"; fi
  read -r FN_AFTER NS_AFTER BR_AFTER <<<"$(graph_counts)"
  CTR_AFTER="$(executor_counters)"
  CTR_DELTA="$(counters_delta "$CTR_BEFORE" "$CTR_AFTER")"
  # Each kind on its own, positives only: a file that removed two stray fns
  # and left two branches behind has not "leaked 0".
  FN_LEAKED=$(( $(pos $((FN_AFTER - FN_BEFORE))) + $(pos $((NS_AFTER - NS_BEFORE))) \
                + $(pos $((BR_AFTER - BR_BEFORE))) ))
  LEAK_KINDS="fns=$((FN_AFTER - FN_BEFORE)) namespaces=$((NS_AFTER - NS_BEFORE)) branches=$((BR_AFTER - BR_BEFORE))"
  # Is the leak number MEANINGFUL at all? Two ways it is not, and in both the
  # honest answer is silence rather than a number: a sample that never arrived
  # (-1 sentinel, the executor was down when we asked), and a process restart
  # between the two samples (monotonic counters moved backwards), which resets
  # the graph to its boot state and makes the whole corpus read as "new rows".
  # This is the accounting that reported `8892 edit-tutorial-tour-structure` and
  # reddened an otherwise-green run: the file was innocent, the ruler was not.
  LEAK_COUNTABLE=1
  if [ "$FN_BEFORE" -lt 0 ] 2>/dev/null || [ "$FN_AFTER" -lt 0 ] 2>/dev/null \
     || [ "$NS_BEFORE" -lt 0 ] 2>/dev/null || [ "$NS_AFTER" -lt 0 ] 2>/dev/null \
     || [ "$BR_BEFORE" -lt 0 ] 2>/dev/null || [ "$BR_AFTER" -lt 0 ] 2>/dev/null; then
    LEAK_COUNTABLE=0
    UNCOUNTABLE_LEAKS=$((UNCOUNTABLE_LEAKS+1))
    echo "  (graph counts unavailable — executor was unreachable; leak check skipped)" >&2
  elif [ "$(counters_restarted "$CTR_BEFORE" "$CTR_AFTER")" = "1" ]; then
    LEAK_COUNTABLE=0
    UNCOUNTABLE_LEAKS=$((UNCOUNTABLE_LEAKS+1))
    echo "  (executor restarted while this file ran — leak check skipped, counts span two JVMs)" >&2
  fi
  if [ "$LEAK_COUNTABLE" = "0" ]; then
    FN_LEAKED=0
  fi
  if [ "$FN_LEAKED" -gt 0 ] 2>/dev/null && [ "$passed" = 1 ]; then
    printf '  [%3ds  executor=%s%s]%s  \033[31mLEAKED %d rows into the graph (%s)\033[0m\n' \
      "$FILE_SECS" "$FILE_MEM" "$ATTEMPT_NOTE" "${CTR_DELTA:+  $CTR_DELTA}" "$FN_LEAKED" "$LEAK_KINDS"
    LEAKS="$LEAKS$FN_LEAKED	$f ($LEAK_KINDS)
"
    # A leak in a PASSING test is a real cleanup-bug signal — the entities stay
    # and the next file runs against a graph it did not create, which is how
    # one test's cleanup bug surfaces as a "flake" in another. It is always
    # NAMED LOUDLY (above + in the summary). BUT under the gate's load an
    # aborted-then-passed-on-retry test can leave COLLATERAL rows that read as
    # a leak, and a single such false positive was hard-failing otherwise-green
    # runs (task #10 tracks the deep root-cause). So the backstop is
    # REPORT-ONLY by default — named + counted, run stays green — and hard-fails
    # only under WTQ_FLAKE_STRICT, the same queue-economics knob as the flake
    # policy above.
    if [ "${WTQ_FLAKE_STRICT:-0}" = "1" ]; then
      STRICT_LEAKS="$STRICT_LEAKS $f($FN_LEAKED)"
      echo "  (leaked $FN_LEAKED — strict verdict DEFERRED to the run-level thrash check)" >&2
    else
      echo "  (leaked $FN_LEAKED entities — reported, run stays green; WTQ_FLAKE_STRICT=1 to fail)" >&2
    fi
  elif [ "$FN_LEAKED" -gt 0 ] 2>/dev/null; then
    # The test already FAILED (aborted / timed out). Rows left behind are
    # collateral of the abort — the test was killed mid-cleanup — not a
    # cleanup regression. It is already counted as a fail above, so note it
    # but do NOT double-red or mis-name it a "leak" (that named a different
    # innocent file each gate run when a slow window aborted it mid-flow).
    printf '  [%3ds  executor=%s%s]%s  (%d entities left by the failed test — abort collateral, not a leak)\n' \
      "$FILE_SECS" "$FILE_MEM" "$ATTEMPT_NOTE" "${CTR_DELTA:+  $CTR_DELTA}" "$FN_LEAKED"
  else
    printf '  [%3ds  executor=%s%s]%s\n' "$FILE_SECS" "$FILE_MEM" "$ATTEMPT_NOTE" \
      "${CTR_DELTA:+  $CTR_DELTA}"
  fi
  TIMINGS="$TIMINGS$FILE_SECS	$FILE_MEM	$f
"
  echo
  if [ "$SWEEP_DELAY" != "0" ]; then sleep "$SWEEP_DELAY"; fi
done

# Mark cascade-skipped tests in the failed-names list so the summary
# is honest about what wasn't even attempted.
if [ -n "$REMAINING_FILES" ]; then
  for f in $REMAINING_FILES; do
    FAIL=$((FAIL+1))
    FAILED_NAMES="$FAILED_NAMES $f(cascade-skip)"
  done
  WORST=1
fi

# --- run-level thrash decision (see the state block before the loop) ---
# The run is DEGRADED when the host was starving the stack: several files ran far
# past their OWN baseline, or several different files needed a retry. Under
# those conditions a strict flake/leak is the environment, not the branch.
DEGRADED=0
FLAKED_COUNT=0
for _x in $FLAKED; do FLAKED_COUNT=$((FLAKED_COUNT+1)); done
if [ "$DEGRADED_FILES" -ge "$THRASH_MIN_FILES" ] || [ "$FLAKED_COUNT" -ge "$THRASH_MIN_FLAKED" ]; then
  DEGRADED=1
fi
if [ -n "$STRICT_FLAKES$STRICT_LEAKS" ]; then
  if [ "$DEGRADED" = 1 ]; then
    echo "  (run-level thrash: env degraded — strict flakes/leaks are REPORT-ONLY this run, NOT a red RESULT)" >&2
  else
    # Healthy host: escalate exactly as strict mode did before this change.
    for x in $STRICT_FLAKES; do FAILED_NAMES="$FAILED_NAMES $x(flaked-passed-on-retry)"; done
    for x in $STRICT_LEAKS;  do FAILED_NAMES="$FAILED_NAMES $x(leaked)"; done
    WORST=1
  fi
fi

# --- the spotlight verdict over every lesson walk that passed ---
TOUR_AUDIT_NOTE=""
if [ -n "$AUDIT_DIRS" ]; then
  echo
  echo "── tour spotlight audit ──"
  # shellcheck disable=SC2086  # AUDIT_DIRS is a space-separated list of paths
  if node tour-spotlight-report.js --gate $AUDIT_DIRS; then
    TOUR_AUDIT_NOTE="every ringed step had its target on screen"
    if [ "$AUDIT_AUTO" = 1 ]; then rm -rf "$AUDIT_ROOT"; AUDIT_ROOT="(removed — green)"; fi
  elif [ "${GRAPHDEN_TOUR_AUDIT_GATE:-1}" = "0" ]; then
    TOUR_AUDIT_NOTE="never-ringed step(s) above — REPORT-ONLY (GRAPHDEN_TOUR_AUDIT_GATE=0)"
  else
    TOUR_AUDIT_NOTE="never-ringed step(s) above — counted as a FAILURE"
    FAILED_NAMES="$FAILED_NAMES tour-spotlight(never-ringed-step)"
    WORST=1
  fi
  if [ -d "$AUDIT_ROOT" ]; then
    echo "  full report: node tour-spotlight-report.js $AUDIT_ROOT/<file>.attemptN"
  fi
fi

echo "============================================================"
echo "edit suite: $PASS pass / $FAIL fail / $((PASS+FAIL)) total"
[ -n "$TOUR_AUDIT_NOTE" ] && echo "  tour spotlight: $TOUR_AUDIT_NOTE"
if [ -n "$FLAKED" ]; then
  if [ "${WTQ_FLAKE_STRICT:-0}" = "1" ] && [ "$DEGRADED" != 1 ]; then
    echo "  FLAKED (failed once, passed on retry):$FLAKED" >&2
    echo "    counted as FAILURES (no environment signature):${STRICT_FLAKES:- none}" >&2
  elif [ "${WTQ_FLAKE_STRICT:-0}" = "1" ]; then
    echo "  FLAKED (failed once, passed on retry — REPORT-ONLY, env degraded):$FLAKED" >&2
  else
    echo "  FLAKED (failed once, passed on retry — investigate, run stays green):$FLAKED" >&2
  fi
fi
# FAILED_NAMES also carries the strict escalations (a flake, a leak), which
# are not in FAIL — print it whenever it names anything.
if [ -n "$FAILED_NAMES" ]; then
  echo "  failed:$FAILED_NAMES" >&2
fi
if [ "$DEGRADED" = 1 ]; then
  echo "  ⚠ ENVIRONMENT DEGRADED: ${DEGRADED_FILES} file(s) ran past ${SLOW_FACTOR}x their baseline:${SLOW_FILES:- none}; ${FLAKED_COUNT} file(s) needed a retry; executor heap high-water ${HEAP_HWM_MIB}MiB (info)." >&2
  echo "    Strict flake/leak verdicts were downgraded to report-only — a retry-pass under thrash is a pass, not a race." >&2
  if [ "$FAIL" != "0" ]; then
    echo "    A file HARD-failed above: the host is too starved to judge it. Free RAM (e.g. 'docker stop graphden-executor' to drop the demo stack) and re-run on a quiet host — do NOT read this as a branch regression." >&2
  fi
fi

# The profile. Read it before optimising anything: the suite's 45 minutes were
# a single number for its whole life, and every theory about where they went —
# browser startup, page loads, GC pauses — was a guess.
echo
echo "── where the time went ──"
printf '%s' "$TIMINGS" | sort -rn | head -12 \
  | awk -F'\t' '{printf "  %4ds  %-10s %s\n", $1, $2, $3}'
TOTAL_SECS=$((SECONDS - SUITE_START))
FILE_COUNT=$((PASS + FAIL))
[ "$FILE_COUNT" -gt 0 ] && echo "  ---" \
  && printf '  %4ds  TOTAL (%d files, %ds median-ish avg)\n' \
       "$TOTAL_SECS" "$FILE_COUNT" "$((TOTAL_SECS / FILE_COUNT))"

# The executor's memory, first file vs last. The retry above blames "JVM GC
# pressure when heap passes ~85%" for the suite-tail flakes. Checked 2026-08-04:
# after-GC live-set stays flat (~60MB) across the whole suite — first->last RSS
# growth on a fresh boot is committed-heap expansion toward MaxRAMPercentage.
echo
echo "── entities leaked into the graph ──"
if [ -n "$LEAKS" ]; then
  printf '%b' "$LEAKS" | sort -rn | awk -F'\t' '{printf "  %3d  %s\n", $1, $2}'
  echo "  ^ each of these poisons every file that runs after it. Fix the leaker,"
  echo "    not the file that trips over the mess."
else
  echo "  none — every file left the graph as it found it"
fi
if [ "$UNCOUNTABLE_LEAKS" -gt 0 ] 2>/dev/null; then
  echo "  ($UNCOUNTABLE_LEAKS file(s) could not be checked — the executor was down or restarted"
  echo "   around them. 'none' above covers the files that WERE measured, not those.)"
fi

echo
echo "── executor memory, first file -> last ──"
# The caption is COMPUTED, not asserted: a >25% climb earns the
# tail-flake note, anything else reads "steady". (The old hardcoded
# "climbing" suffix printed even on a +0.7MiB run and sent a
# heap-dump investigation chasing a leak that wasn't there — the
# 2026-08-04 probe measured a FLAT ~60MB after-GC live-set across
# the whole suite; the gate's first->last growth is G1 committing
# heap toward MaxRAMPercentage under a fresh boot, not retention.)
printf '%s' "$TIMINGS" | awk -F'\t' 'NR==1{first=$2} {last=$2} END{
  f=first; l=last
  fv=f; sub(/[A-Za-z].*/,"",fv); lv=l; sub(/[A-Za-z].*/,"",lv)
  fb=(f ~ /GiB/)? fv*1024 : fv+0
  lb=(l ~ /GiB/)? lv*1024 : lv+0
  note=(fb>0 && lb>fb*1.25)? "(climbed >25% — check after-GC live-set in the gc log before calling it a leak)" : "(steady)"
  printf "  %s  ->  %s   %s\n", first, last, note}'
echo "============================================================"
exit "$WORST"
