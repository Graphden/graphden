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
//   pass | skip:<msg> | fail:<msg> | envfail:<msg> (the probe is dead at failure time)
function stubTest(dir, name, plan) {
  const body = `#!/usr/bin/env bash
n_file="$STUB_STATE/${name}.n"; n=$(( $(cat "$n_file" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$n_file"
plan=(${plan.map((p) => `'${p}'`).join(' ')})
step="\${plan[$((n-1))]:-\${plan[\${#plan[@]}-1]}}"
case "$step" in
  pass) echo "  ✓ ok"; exit 0 ;;
  skip:*) echo "SKIP: \${step#skip:}"; exit 0 ;;
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
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'run-edit-verdicts-'));
  const dir = env.STUB_RESUME ? path.join(root, 'tools', 'browser-test') : root;
  fs.mkdirSync(dir, {recursive: true});
  if (env.STUB_RESUME) {
    const scripts = path.join(root, 'scripts');
    fs.mkdirSync(scripts);
    for (const name of ['ci_resume.clj', 'ci_resume_cli.clj', 'ci_report.clj']) {
      fs.copyFileSync(path.join(__dirname, '..', '..', 'scripts', name), path.join(scripts, name));
    }
    fs.writeFileSync(path.join(root, 'bb.edn'), '{:paths ["scripts"]}');
    for (const args of [['init', '-q'], ['-c', 'user.name=fixture', '-c', 'user.email=fixture@example.invalid',
      '-c', 'commit.gpgsign=false', 'commit', '-q', '--allow-empty', '-m', 'fixture']]) {
      const result = spawnSync('git', args, {cwd: root, encoding: 'utf8'});
      if (result.status !== 0) throw new Error(result.stderr);
    }
    fs.writeFileSync(path.join(root, 'prior.browser.edn'), env.STUB_RESUME);
    env = {...env, GRAPHDEN_RESUME_OUT: path.join(root, 'current'), GRAPHDEN_RESUME_FROM: path.join(root, 'prior')};
  }
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
      HOST_MEM_MIN_MB: '0',
      HOST_LOAD_PER_CPU: '100000',
      GRAPHDEN_TOUR_AUDIT: path.join(dir, 'audit'),
      E2E_BASELINE: '/dev/null',
      GRAPHDEN_RESUME_OUT: '',
      GRAPHDEN_RESUME_FROM: '',
      GRAPHDEN_E2E_PRIORITY_BASE: '',
      ...env,
    },
  });
  const counts = Object.fromEntries(Object.keys(tests).map((n) => {
    const f = path.join(state, n + '.n');
    return [n, fs.existsSync(f) ? Number(fs.readFileSync(f, 'utf8')) : 0];
  }));
  const attempts = (name) => counts[name];
  const receipt = env.STUB_RESUME ? fs.readFileSync(path.join(root, 'current.browser.edn'), 'utf8') : '';
  fs.rmSync(root, { recursive: true, force: true, maxRetries: 2 });
  return { receipt, code: r.status, out: (r.stdout || '') + (r.stderr || ''), attempts };
}

console.log(' two REAL races in one run are two red files, not host jitter');

console.log(' declared skips are visible and an unregistered skip is red');
{
  const declared = runSuite({'edit-tutorial-tour-org.test.js': ['skip:tenancy unavailable']});
  assert(declared.code === 0
         && /edit suite: 0 pass \/ 1 skip \/ 0 fail \/ 1 total/.test(declared.out)
         && /skipped: edit-tutorial-tour-org\.test\.js/.test(declared.out),
         'known environment skip is counted separately from pass', declared.out);
  const tokenAddon = runSuite({'edit-tutorial-token-lifecycle.test.js': ['skip:account addon unavailable']});
  assert(tokenAddon.code === 0
         && /edit suite: 0 pass \/ 1 skip \/ 0 fail \/ 1 total/.test(tokenAddon.out),
         'token lesson skip is counted separately from pass', tokenAddon.out);
  const sharedCloud = runSuite({'edit-tutorial-tour-queues.test.js': ['skip:shared cloud has no persistent worker']});
  assert(sharedCloud.code === 0
         && /edit suite: 0 pass \/ 1 skip \/ 0 fail \/ 1 total/.test(sharedCloud.out),
         'shared-cloud queue lesson skip is counted separately from pass', sharedCloud.out);
  const optionalVault = runSuite({'edit-secret-inline.test.js': ['skip:Vault unavailable']},
    {GRAPHDEN_VAULT_OPTIONAL: '1'});
  assert(optionalVault.code === 0
         && /edit suite: 0 pass \/ 1 skip \/ 0 fail \/ 1 total/.test(optionalVault.out),
         'explicit local Vault opt-out is counted separately', optionalVault.out);
  const requiredVault = runSuite({'edit-secret-inline.test.js': ['skip:Vault unavailable']},
    {GRAPHDEN_VAULT_OPTIONAL: '0'});
  assert(requiredVault.code !== 0
         && /edit-secret-inline\.test\.js\(unexpected-skip\)/.test(requiredVault.out),
         'CI cannot silently skip required Vault coverage', requiredVault.out);
  const unexpected = runSuite({'edit-a.test.js': ['skip:missing fixture']});
  assert(unexpected.code !== 0 && /edit-a\.test\.js\(unexpected-skip\)/.test(unexpected.out),
         'new silent skip fails the suite', unexpected.out);
}

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

console.log(' strict gate collects independent failures and still returns red');
{
  const r = runSuite({
    'edit-a.test.js': ['fail:first'],
    'edit-b.test.js': ['pass'],
    'edit-c.test.js': ['fail:second'],
  });
  assert(r.code !== 0 && r.attempts('edit-a.test.js') === 2 && r.attempts('edit-c.test.js') === 2,
         'both deterministic failures are triaged', r.out);
  assert(r.attempts('edit-b.test.js') === 1, 'the file between failures ran', r.out);
  assert(/edit suite: 1 pass \/ 2 fail \/ 3 total/.test(r.out)
         && /edit-a\.test\.js\(deterministic\)/.test(r.out)
         && /edit-c\.test\.js\(deterministic\)/.test(r.out),
         'summary names every failed file', r.out);
  assert(/entities leaked into the graph/.test(r.out), 'normal post-loop accounting still runs', r.out);
}
console.log(' receipts automatically resume failed file, unrun tail, then prior passes');
{
  const prior = '{:schema 1 :kind "browser" :sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"'
    + ' :roster ["edit-a.test.js" "edit-b.test.js" "edit-c.test.js"]'
    + ' :status {"edit-a.test.js" :passed "edit-b.test.js" :failed "edit-c.test.js" :pending}}';
  const files = {'edit-a.test.js': ['pass'], 'edit-b.test.js': ['pass'], 'edit-c.test.js': ['pass']};
  const r = runSuite(files, {STUB_RESUME: prior});
  const order = [...r.out.matchAll(/─── (edit-.*?\.test\.js) ───/g)].map(match => match[1]);
  assert(r.code === 0 && order.join(',') === 'edit-b.test.js,edit-c.test.js,edit-a.test.js', 'receipt reorders complete suite', r.out);
  assert(Object.keys(files).every(name => r.attempts(name) === 1), 'every current file executes once', r.out);
  const red = runSuite({...files, 'edit-b.test.js': ['fail:same'], 'edit-c.test.js': ['fail:later']}, {STUB_RESUME: prior});
  assert(red.code !== 0 && red.attempts('edit-b.test.js') === 2 && red.attempts('edit-c.test.js') === 2
    && red.attempts('edit-a.test.js') === 1, 'two failures do not leave any mandatory file unrun', red.out);
  assert(red.receipt.includes('"edit-b.test.js" :failed') && red.receipt.includes('"edit-c.test.js" :failed')
    && red.receipt.includes('"edit-a.test.js" :passed'),
    'receipt preserves both failures and final passing file', red.receipt);
}
console.log(' strict retry-pass flake is red while later files still run');
{
  const prior = '{:schema 1 :kind "browser" :sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"'
    + ' :roster ["edit-a.test.js" "edit-b.test.js"]'
    + ' :status {"edit-a.test.js" :pending "edit-b.test.js" :pending}}';
  const gate = runSuite({'edit-a.test.js': ['fail:race', 'pass'], 'edit-b.test.js': ['pass']}, {STUB_RESUME: prior});
  assert(gate.code !== 0 && gate.attempts('edit-a.test.js') === 2 && gate.attempts('edit-b.test.js') === 1,
    'strict flake does not prevent later files from running', gate.out);
  assert(/flaked-passed-on-retry/.test(gate.out), 'strict flake remains a named red verdict', gate.out);
  assert(gate.receipt.includes('"edit-a.test.js" :failed') && gate.receipt.includes('"edit-b.test.js" :passed'),
    'strict retry-pass stays failed in receipt while later pass is recorded', gate.receipt);
}
console.log(' environment-signed retries and already proven thrash retain their exceptions');
{
  const r = runSuite({'edit-a.test.js': ['envfail:window', 'pass'], 'edit-b.test.js': ['pass']});
  assert(r.code === 0 && r.attempts('edit-b.test.js') === 1, 'server-window retry does not stop the suite', r.out);
  const degraded = runSuite({'edit-a.test.js': ['envfail:window', 'pass'], 'edit-b.test.js': ['envfail:window', 'pass'],
    'edit-c.test.js': ['fail:race', 'pass'], 'edit-d.test.js': ['pass']});
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
  const limit = (baseline, env = '', file = 'f') => spawnSync('bash', ['-c', `
    eval "$(sed -n '/^timeout_for() {/,/^}/p' "$0")"
    eval "$(sed -n '/^slow_limit() {/,/^}/p' "$0")"
    declare -A BASELINE=(${baseline == null ? '' : `[${file}]=${baseline}`})
    THRASH_FILE_SECS=\${THRASH_FILE_SECS:-150} SLOW_FACTOR=2.5 SLOW_MIN_EXTRA=30 ${env}
    slow_limit ${file}`, RUNNER], { encoding: 'utf8' }).stdout;
  assert(limit(122) === '299', 'tour-ux 122 s × 2.5 = 305 → capped at 299 (got ' + limit(122) + ')');
  assert(limit(20) === '50', 'an uncapped limit is unchanged (got ' + limit(20) + ')');
  assert(limit(null) === '150', 'no baseline → THRASH_FILE_SECS (got ' + limit(null) + ')');
  assert(limit(null, 'THRASH_FILE_SECS=400') === '299', 'a no-baseline limit is capped too (got ' + limit(null, 'THRASH_FILE_SECS=400') + ')');
  assert(limit(122, 'PER_TEST_TIMEOUT=200') === '199', 'the cap follows PER_TEST_TIMEOUT (got ' + limit(122, 'PER_TEST_TIMEOUT=200') + ')');
  assert(limit(200, '', 'edit-ui-components-selective.test.js') === '359', 'the selective cleanup cap follows its measured 360s timeout');
  assert(limit(200, 'PER_TEST_TIMEOUT=200', 'edit-ui-components-selective.test.js') === '199', 'an explicit timeout overrides the selective default');
}

console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures ? 1 : 0);
