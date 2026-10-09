// run-edit-tests.sh — the strict-mode VERDICTS, driven end to end against a
// fake stack: the runner is copied into a scratch dir next to stub
// `edit-*.test.js` files, with `node` / `curl` / `sleep` / `docker` stubbed on
// PATH. No browser, no server — what is under test is only how the runner
// judges a sequence of attempt outcomes.
//
//   - a retry counts toward the run-level thrash trigger only when a failure
//     of that file was ENVIRONMENT-signed (here: the compiled-path probe dead
//     at failure time). Two real races in one run stay two red files;
//   - the same real assertion failure twice in a row stops the retries;
//   - slow_limit never reaches the per-attempt hard timeout.
//
// Run:  node tools/runtime-test/run-edit-tests-verdicts.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const RUNNER = process.env.RUN_EDIT_TESTS
  || path.join(__dirname, '..', 'browser-test', 'run-edit-tests.sh');

let failures = 0;
let passes = 0;
function assert(cond, msg, out) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
  if (out) console.error(out.split('\n').map((l) => '      | ' + l).join('\n'));
}

// Stub tests are bash scripts run by the fake `node`. Each gets its attempt
// number from a counter file and does what its `plan` line says:
//   pass | fail:<msg> | envfail:<msg> (the probe is dead at failure time)
function stubTest(dir, name, plan) {
  const body = `#!/usr/bin/env bash
n_file="$STUB_STATE/${name}.n"; n=$(( $(cat "$n_file" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$n_file"
plan=(${plan.map((p) => `'${p}'`).join(' ')})
step="\${plan[$((n-1))]:-\${plan[\${#plan[@]}-1]}}"
case "$step" in
  pass) echo "  ✓ ok"; exit 0 ;;
  fail:*) echo "  ✗ \${step#fail:}" >&2; exit 1 ;;
  rebuildfail:*) echo 1 > "$STUB_STATE/rebuilds"; echo 300 > "$STUB_STATE/clock"; echo "  ✗ late cleanup" >&2; exit 1 ;;
  recentfail:*) echo 1 > "$STUB_STATE/rebuilds"; echo 140 > "$STUB_STATE/clock"; echo "  ✗ compilation window" >&2; exit 1 ;;
  restartfail:*) echo 0 > "$STUB_STATE/rebuilds"; echo "  ✗ server restart" >&2; exit 1 ;;
  envfail:*) touch "$STUB_STATE/probe-dead-once"; echo "  ✗ \${step#envfail:}" >&2; exit 1 ;;
esac
`;
  fs.writeFileSync(path.join(dir, name), body);
}

function runSuite(tests, env = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'run-edit-verdicts-'));
  const bin = path.join(dir, 'bin');
  const state = path.join(dir, 'state');
  fs.mkdirSync(bin);
  fs.mkdirSync(state);
  fs.copyFileSync(RUNNER, path.join(dir, 'run-edit-tests.sh'));
  for (const [name, plan] of Object.entries(tests)) stubTest(dir, name, plan);
  const stub = (n, body) => fs.writeFileSync(path.join(bin, n), '#!/usr/bin/env bash\n' + body, { mode: 0o755 });
  stub('node', 'exec bash "$@"\n');
  stub('sleep', '/bin/sleep 0.05\n');
  stub('date', 'cat "$STUB_STATE/clock" 2>/dev/null || echo 100\n');
  if (env.STUB_RESTART) fs.writeFileSync(path.join(state, 'rebuilds'), '2');
  stub('docker', 'exit 1\n');
  // The probe is the `-o /dev/null --max-time 5 …scope=index` call; a stub
  // test failing with `envfail` kills it for exactly one call.
  stub('curl', `out=""; url=""; probe=0
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift ;; --max-time|-H) shift ;; http*) url="$1" ;; esac; shift; done
case "$url" in *scope=index*) [ "$out" = /dev/null ] && probe=1 ;; esac
if [ "$probe" = 1 ] && [ -e "$STUB_STATE/probe-dead-once" ]; then rm -f "$STUB_STATE/probe-dead-once"; exit 22; fi
case "$url" in
  *scope=index*) body='{"fns":[],"namespaces":[]}' ;;
  */api/branches) body='{"branches":[]}' ;;
  */metrics) body="{\\"counters\\":{\\"registry/rebuild\\":$(cat "$STUB_STATE/rebuilds" 2>/dev/null || echo 0)}}" ;;
  *) body='{}' ;;
esac
if [ -n "$out" ]; then [ "$out" = /dev/null ] || printf '%s' "$body" > "$out"; else printf '%s' "$body"; fi
`);
  const r = spawnSync('bash', [path.join(dir, 'run-edit-tests.sh')], {
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: bin + ':' + process.env.PATH,
      STUB_STATE: state,
      GRAPHDEN_URL: 'http://stub:1',
      SWEEP_DELAY: '0',
      WTQ_FLAKE_STRICT: '1',
      WTQ_FAIL_FAST: '0',
      HOST_MEM_MIN_MB: '0',
      HOST_LOAD_PER_CPU: '100000',
      GRAPHDEN_TOUR_AUDIT: path.join(dir, 'audit'),
      E2E_BASELINE: '/dev/null',
      ...env,
    },
  });
  const counts = Object.fromEntries(Object.keys(tests).map((n) => {
    const f = path.join(state, n + '.n');
    return [n, fs.existsSync(f) ? Number(fs.readFileSync(f, 'utf8')) : 0];
  }));
  const attempts = (name) => counts[name];
  fs.rmSync(dir, { recursive: true, force: true, maxRetries: 2 });
  return { code: r.status, out: (r.stdout || '') + (r.stderr || ''), attempts };
}

console.log(' two REAL races in one run are two red files, not host jitter');
{
  const r = runSuite({
    'edit-a.test.js': ['fail:race A', 'pass'],
    'edit-b.test.js': ['fail:race B', 'pass'],
  });
  assert(r.code !== 0, 'the run is red', r.out);
  assert(!/ENVIRONMENT DEGRADED/.test(r.out), 'the run is NOT degraded', r.out);
  assert(/edit-a\.test\.js\(flaked-passed-on-retry\)/.test(r.out)
         && /edit-b\.test\.js\(flaked-passed-on-retry\)/.test(r.out), 'both are named strict flakes', r.out);
}

console.log(' environment-signed retries still mark the run degraded');
{
  const r = runSuite({
    'edit-a.test.js': ['envfail:server window', 'pass'],
    'edit-b.test.js': ['envfail:server window', 'pass'],
    'edit-c.test.js': ['fail:race C', 'pass'],
  });
  assert(/ENVIRONMENT DEGRADED/.test(r.out), 'degraded on two env-signed retries', r.out);
  assert(/2 file\(s\) needed an environment-signed retry/.test(r.out), 'counts only the env-signed two', r.out);
  assert(r.code === 0, 'the real flake is report-only under a degraded run', r.out);
}

console.log(' one env-signed retry + one real race: not degraded, the race is red');
{
  const r = runSuite({
    'edit-a.test.js': ['envfail:server window', 'pass'],
    'edit-b.test.js': ['fail:race B', 'pass'],
  });
  assert(!/ENVIRONMENT DEGRADED/.test(r.out), 'not degraded', r.out);
  assert(r.code !== 0 && /edit-b\.test\.js\(flaked-passed-on-retry\)/.test(r.out), 'the race is red', r.out);
  assert(!/edit-a\.test\.js\(flaked/.test(r.out), 'the env-signed retry is not a strict flake', r.out);
}

console.log(' the same real assertion twice in a row is deterministic: two attempts, not five');
{
  const r = runSuite({ 'edit-a.test.js': ['fail:save says signed-in (403 in 2554ms)', 'fail:save says signed-in (403 in 2496ms)'] });
  assert(r.attempts('edit-a.test.js') === 2, 'stopped after attempt 2 (ran ' + r.attempts('edit-a.test.js') + ')', r.out);
  assert(r.code !== 0 && /edit-a\.test\.js\(deterministic\)/.test(r.out), 'red, tagged deterministic', r.out);
}

console.log(' different failures keep their retries');
{
  const r = runSuite({ 'edit-a.test.js': ['fail:first thing', 'fail:second thing', 'pass'] });
  assert(r.attempts('edit-a.test.js') === 3, 'ran to the passing attempt 3', r.out);
  assert(!/\(deterministic\)/.test(r.out), 'not tagged deterministic', r.out);
}

console.log(' an env-signed failure between two identical real ones does not count as a repeat');
{
  const r = runSuite({ 'edit-a.test.js': ['fail:same', 'envfail:same', 'fail:same', 'pass'] });
  assert(r.attempts('edit-a.test.js') === 4, 'retried through to attempt 4', r.out);
}

console.log(' strict gate stops after deterministic triage, with normal summary');
{
  const r = runSuite({ 'edit-a.test.js': ['fail:same'], 'edit-b.test.js': ['pass'] }, {WTQ_FAIL_FAST: '1'});
  assert(r.code !== 0 && r.attempts('edit-a.test.js') === 2, 'deterministic classification keeps two attempts', r.out);
  assert(r.attempts('edit-b.test.js') === 0, 'later file was never scheduled', r.out);
  assert(/edit suite: 0 pass \/ 1 fail \/ 1 total/.test(r.out) && /NOT RUN after gate failure:.*edit-b/.test(r.out), 'summary distinguishes real fail from unrun', r.out);
  assert(/entities leaked into the graph/.test(r.out), 'normal post-loop accounting still runs', r.out);
}
console.log(' adhoc default aggregates failures and healthy gate stops strict flakes');
{
  const files = {'edit-a.test.js': ['fail:same'], 'edit-b.test.js': ['pass']};
  const r = runSuite(files, {WTQ_FLAKE_STRICT: '0', WTQ_FAIL_FAST: ''});
  assert(r.code !== 0 && r.attempts('edit-b.test.js') === 1, 'default adhoc still runs later files', r.out);
  const gate = runSuite({'edit-a.test.js': ['fail:race', 'pass'], 'edit-b.test.js': ['pass']}, {WTQ_FAIL_FAST: ''});
  assert(gate.code !== 0 && gate.attempts('edit-a.test.js') === 2 && gate.attempts('edit-b.test.js') === 0,
    'strict default stops only after retry-pass flake classification', gate.out);
  assert(/flaked-passed-on-retry/.test(gate.out), 'strict flake remains a named red verdict', gate.out);
}
console.log(' environment-signed retries and already proven thrash retain their exceptions');
{
  const r = runSuite({'edit-a.test.js': ['envfail:window', 'pass'], 'edit-b.test.js': ['pass']}, {WTQ_FAIL_FAST: '1'});
  assert(r.code === 0 && r.attempts('edit-b.test.js') === 1, 'server-window retry does not stop the suite', r.out);
  const degraded = runSuite({'edit-a.test.js': ['envfail:window', 'pass'], 'edit-b.test.js': ['envfail:window', 'pass'],
    'edit-c.test.js': ['fail:race', 'pass'], 'edit-d.test.js': ['pass']}, {WTQ_FAIL_FAST: '1'});
  assert(degraded.code === 0 && degraded.attempts('edit-d.test.js') === 1, 'already proven degraded run preserves report-only flake', degraded.out);
}

console.log(' early rebuild cannot forgive a late failure; recent rebuild and restart remain signed');
{
  const late = runSuite({'edit-a.test.js': ['rebuildfail:late', 'pass']});
  assert(late.code !== 0 && /flaked-passed-on-retry/.test(late.out), 'late cleanup remains a strict real flake', late.out);
  assert(!/during a FULL registry rebuild/.test(late.out), 'old rebuild is not an environment signature', late.out);
  const recent = runSuite({'edit-a.test.js': ['recentfail:window', 'pass']});
  assert(recent.code === 0 && /during a FULL registry rebuild/.test(recent.out), 'recent rebuild remains signed', recent.out);
  const restart = runSuite({'edit-a.test.js': ['restartfail:restart', 'pass']}, {STUB_RESTART: '1'});
  assert(restart.code === 0 && /SERVER RESTART/.test(restart.out), 'counter reset remains signed', restart.out);
}
console.log(' rebuild window ignores stale samples and unreadable sampling fails closed');
{
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rebuild-window-'));
  const trace = path.join(dir, 'trace');
  const classify = (samples) => {
    fs.writeFileSync(trace, samples.map((sample) => JSON.stringify(sample)).join('\n'));
    return spawnSync('bash', ['-c', `eval "$(sed -n '/^counters_full_rebuild() {/,/^}/p' "$1")"; counters_full_rebuild "$2" 300`, 'bash', RUNNER, trace], {encoding: 'utf8'}).stdout.trim();
  };
  const c = (n) => ({'registry/rebuild': n});
  assert(classify([[0,c(0)],[17,c(2)],[58,c(2)],[293,c(2)],[300,c(2)]]) === '0', 'actual early compile / late cleanup chronology stays strict');
  assert(classify([[240,c(2)],[290,c(3)],[300,c(3)]]) === '1', 'observed increase within the window is signed');
  assert(classify([[0,c(2)],[300,c(3)]]) === '0', 'a sampling gap cannot establish recent compilation');
  assert(classify([[250,{}],[300,c(3)]]) === '0', 'unavailable metrics cannot create a rebuild signature');
  assert(classify([]) === '0', 'missing samples cannot forgive failure');
  fs.rmSync(dir, {recursive: true, force: true});
}

console.log(' owned counter sampler is stopped on parent termination');
{
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sampler-lifecycle-'));
  const r = spawnSync('bash', ['-c', `
    eval "$(sed -n '/^counter_sample() {/,/^}/p; /^start_counter_sampler() {/,/^}/p; /^stop_counter_sampler() {/,/^}/p; /^trap /p' "$1")"
    SAMPLE_DIR="$2" CTR_ATTEMPT='{"registry/rebuild":0}' COUNTER_SAMPLER_PID=""
    executor_counters() { echo '{"registry/rebuild":0}'; }
    start_counter_sampler
    echo "$COUNTER_SAMPLER_PID"
    kill -TERM "$$"`, 'bash', RUNNER, dir], {encoding: 'utf8', timeout: 2000});
  const pid = Number(r.stdout.trim());
  let alive = false;
  try { process.kill(pid, 0); alive = true; } catch {}
  assert(r.status === 143 && pid > 0 && !alive && !fs.existsSync(dir), 'TERM tears down owned sampler and private traces');
  fs.rmSync(dir, {recursive: true, force: true});
}

console.log(' slow_limit stays under the per-attempt hard timeout');
{
  const limit = (baseline, env = '') => spawnSync('bash', ['-c', `
    eval "$(sed -n '/^slow_limit() {/,/^}/p' "$0")"
    declare -A BASELINE=(${baseline == null ? '' : `[f]=${baseline}`})
    THRASH_FILE_SECS=\${THRASH_FILE_SECS:-150} SLOW_FACTOR=2.5 SLOW_MIN_EXTRA=30 ${env}
    slow_limit f`, RUNNER], { encoding: 'utf8' }).stdout;
  assert(limit(122) === '299', 'tour-ux 122 s × 2.5 = 305 → capped at 299 (got ' + limit(122) + ')');
  assert(limit(20) === '50', 'an uncapped limit is unchanged (got ' + limit(20) + ')');
  assert(limit(null) === '150', 'no baseline → THRASH_FILE_SECS (got ' + limit(null) + ')');
  assert(limit(null, 'THRASH_FILE_SECS=400') === '299', 'a no-baseline limit is capped too (got ' + limit(null, 'THRASH_FILE_SECS=400') + ')');
  assert(limit(122, 'PER_TEST_TIMEOUT=200') === '199', 'the cap follows PER_TEST_TIMEOUT (got ' + limit(122, 'PER_TEST_TIMEOUT=200') + ')');
}

console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures ? 1 : 0);
