// `mergeBranchInto` (editor-branches.js) — the merge action's response
// handling, specifically the audit-3 P2 fix: a THROWN fetch (connection
// dropped by the target's post-commit service restart) means "merge
// committed, target restarting" → verify /health + reload; but a RECEIVED
// response — even a 500 with a non-JSON body, or an empty-bodied 401 —
// means the merge did NOT commit and must surface as an error, never as
// "restarting". Before the fix, `await resp.json()` threw on any non-JSON
// body and fell into the restart branch, reporting a failed merge as
// in-progress success; and the 401 guard sat after `resp.json()`.
//
// Driven under plain node: editor-branches.js is eval'd in a vm context
// with the handful of globals mergeBranchInto touches stubbed. No stack.
//
// Run:  node tools/runtime-test/merge-branch-into.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

let failures = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}
function test(name, fn) {
  console.log(' ' + name);
  return fn();
}

// Build a fresh vm context with the merge action's globals stubbed.
// `fetchImpl` is window.authFetch (the merge POST); `healthOk` decides
// whether the /health poll succeeds.
function makeCtx({ authFetch, healthOk = true }) {
  const document = createDocument();
  if (!document.body) document.body = {};
  if (!document.body.addEventListener) document.body.addEventListener = () => {};
  if (!document.addEventListener) document.addEventListener = () => {};
  const errEl = { textContent: '', classList: { add() {}, remove() {} } };
  const conflictErrEl = { textContent: '', classList: { add() {}, remove() {} } };
  document.getElementById = (id) => {
    if (id === 'branch-popover-error') return errEl;
    if (id === 'merge-conflicts-error') return conflictErrEl;
    return null;
  };
  // submitConflictResolutions reads the conflict rows; an empty set is fine —
  // the fetch/response handling under test is independent of the resolutions.
  document.querySelectorAll = () => [];

  const state = { reloaded: false, errEl, conflictErrEl };
  const locationStub = {
    search: '', href: 'http://x/', reload() { state.reloaded = true; },
  };
  const storageStub = { getItem() { return null; }, setItem() {} };
  const ctx = vm.createContext({
    console,
    document,
    location: locationStub,
    localStorage: storageStub,
    URLSearchParams,
    setTimeout: (fn) => fn(),          // run the health-poll sleep instantly
    confirm: () => true,               // accept the "Merge INTO main?" dialog
    alert: () => {},
    // same-origin /health probe used by waitForServerBack
    fetch: () => Promise.resolve({ ok: healthOk }),
    window: {
      authFetch,
      // wrapFetchWithBranch() runs at load and does window.fetch.bind(window);
      // the tested paths use authFetch + the global fetch (/health), not this.
      fetch: () => Promise.resolve({ ok: healthOk }),
      location: locationStub,
      localStorage: storageStub,
      addEventListener() {},
      API: { api_branches_ref_merge: (t) => '/api/branches/' + t + '/merge' },
      history: { pushState() {} },
    },
    API: { api_branches_ref_merge: (t) => '/api/branches/' + t + '/merge' },
  });
  vm.runInContext(
    fs.readFileSync(path.join(EDITOR, 'editor-branches.js'), 'utf8'),
    ctx, { filename: 'editor-branches.js' });
  return { ctx, state };
}

(async () => {
  await test('fetch REJECTS (post-commit restart) → verify + reload, no scary error', async () => {
    const { ctx, state } = makeCtx({
      authFetch: () => Promise.reject(new TypeError('Failed to fetch')),
      healthOk: true,
    });
    await ctx.mergeBranchInto('feat', 'main');
    assert(/restarting, verifying/i.test(state.errEl.textContent),
           'shows "restarting, verifying…" on a severed connection');
    assert(!/Failed to fetch/i.test(state.errEl.textContent),
           'does NOT surface the raw "Failed to fetch"');
    assert(state.reloaded === true,
           'reloads after /health comes back');
  });

  await test('500 with a NON-JSON body → error shown, NOT "restarting", no reload', async () => {
    const { ctx, state } = makeCtx({
      authFetch: () => Promise.resolve({
        status: 500, ok: false,
        json: () => Promise.reject(new SyntaxError('Unexpected token < in JSON')),
      }),
    });
    await ctx.mergeBranchInto('feat', 'main');
    assert(/HTTP 500/.test(state.errEl.textContent),
           'a non-JSON 500 surfaces as "HTTP 500" (defensive parse), not a crash');
    assert(!/restarting/i.test(state.errEl.textContent),
           'a received response is never misclassified as "restarting" (the P2 bug)');
    assert(state.reloaded === false, 'does NOT reload on a real error');
  });

  await test('empty-bodied 401 → "Sign in", guard runs before json()', async () => {
    const { ctx, state } = makeCtx({
      authFetch: () => Promise.resolve({
        status: 401, ok: false,
        json: () => Promise.reject(new SyntaxError('empty body')),
      }),
    });
    await ctx.mergeBranchInto('feat', 'main');
    assert(/sign in/i.test(state.errEl.textContent),
           '401 handled before json() (guard moved ahead of the parse)');
    assert(state.reloaded === false, 'no reload on 401');
  });

  // submitConflictResolutions — the conflict-resolved RE-submit of the same
  // merge. It must carry the SAME severed→verify→reload handling mergeBranchInto
  // has (regression: it had a plain try/catch that reported "Failed to fetch" on
  // an already-committed merge into a live service, leaving the modal open).
  await test('conflict re-submit: fetch REJECTS → verify + reload, no scary error', async () => {
    const { ctx, state } = makeCtx({
      authFetch: () => Promise.reject(new TypeError('Failed to fetch')),
      healthOk: true,
    });
    await ctx.submitConflictResolutions('feat', 'main');
    assert(/restarting, verifying/i.test(state.conflictErrEl.textContent),
           'shows "restarting, verifying…" on a severed connection');
    assert(!/Failed to fetch/i.test(state.conflictErrEl.textContent),
           'does NOT surface the raw "Failed to fetch" on a committed merge');
    assert(state.reloaded === true, 'reloads after /health comes back');
  });

  await test('conflict re-submit: 500 non-JSON → error shown, NOT "restarting", no reload', async () => {
    const { ctx, state } = makeCtx({
      authFetch: () => Promise.resolve({
        status: 500, ok: false,
        json: () => Promise.reject(new SyntaxError('Unexpected token < in JSON')),
      }),
    });
    await ctx.submitConflictResolutions('feat', 'main');
    assert(/HTTP 500/.test(state.conflictErrEl.textContent),
           'a non-JSON 500 surfaces as "HTTP 500" (defensive parse), not a crash');
    assert(!/restarting/i.test(state.conflictErrEl.textContent),
           'a received response is never misclassified as "restarting"');
    assert(state.reloaded === false, 'does NOT reload on a real error');
  });

  await test('conflict re-submit: success → reload', async () => {
    const { ctx, state } = makeCtx({
      authFetch: () => Promise.resolve({
        status: 200, ok: true, json: () => Promise.resolve({ ok: true }),
      }),
    });
    await ctx.submitConflictResolutions('feat', 'main');
    assert(state.reloaded === true, 'reloads on a successful conflict-resolved merge');
    assert(state.conflictErrEl.textContent === '', 'no error shown on success');
  });

  console.log('\n' + passes + ' passed, ' + failures + ' failed');
  process.exit(failures ? 1 : 0);
})();
