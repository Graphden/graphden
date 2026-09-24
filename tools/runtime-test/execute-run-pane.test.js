'use strict';

// editor-execute.js — the Run pane's submit state machine. Pinned:
//   * a double click on Run submits ONCE (Run is disabled while the POST is
//     in flight, and restored afterwards — through the effects confirm gate);
//   * the Cancel button a pending run reveals is hidden again once the run is
//     terminal, with its exec id dropped;
//   * two overlapping mounts (A selected, then B before A's arg forms landed)
//     leave B's arg readers published — B's Run never sends A's values;
//   * a second Run while one is pending keeps the NEW run's Cancel armed;
//   * a stale poll (the pane moved on to a newer run) writes nothing into
//     the result host and never stops the newer run's poll — on the HTTP
//     error, network error and terminal-result paths;
//   * a new Run retires the previous run's poll, so an inline result is not
//     painted over by an earlier pending run finishing.
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument, MiniElement } = require('./mini-dom');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-execute.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

// The server's /partials/execute-popover shell, built by hand: one arg host
// per slot, the (optional) effects confirm box, Run / Cancel, result host.
function buildShell(doc, pane, { slots, effects }) {
  const mk = (tag, cls, attrs) => {
    const e = doc.createElement(tag);
    if (cls) e.className = cls;
    for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
    return e;
  };
  const body = mk('div', 'execute-popover-body');
  for (const s of slots) {
    body.appendChild(mk('div', 'execute-arg-form', { 'data-slot-id': s, 'data-slot-name': s }));
  }
  if (effects) body.appendChild(mk('input', 'execute-confirm-checkbox', { type: 'checkbox' }));
  body.appendChild(mk('input', 'execute-persist-checkbox', { type: 'checkbox' }));
  pane.appendChild(body);
  const run = mk('button', 'execute-run-btn');
  if (effects) run.disabled = true;
  pane.appendChild(run);
  const cancel = mk('button', 'execute-cancel-btn');
  cancel.style.display = 'none';
  pane.appendChild(cancel);
  pane.appendChild(mk('div', 'execute-result-host'));
}

function boot({ effects = false } = {}) {
  const doc = createDocument();
  const runHost = doc.createElement('div');
  runHost.id = 'gd-insp-run-host';
  const runsHost = doc.createElement('div');
  runsHost.id = 'gd-insp-runs';
  doc.body.appendChild(runHost);
  doc.body.appendChild(runsHost);

  const posts = [];
  const timers = [];
  const formWaits = new Map();   // fnId → resolve() for its value-form
  let execResponse = () => ({ status: 'succeeded', 'execution-id': null, result: 1 });
  let pollStatus = 'pending';

  const res = (body, text) => ({ ok: true, status: 200, json: async () => body, text: async () => text ?? '' });
  const ctx = vm.createContext({
    console, Promise, JSON,
    document: doc,
    window: {},
    API: { api_execute: '/api/execute', api_execute_id: (id) => '/api/execute/' + id,
           api_execute_id_cancel: (id) => '/api/execute/' + id + '/cancel' },
    lookups: { fnMap: new Map([['A', { id: 'A' }], ['B', { id: 'B' }]]) },
    isAuthenticated: () => true,
    fetchValueForm: ({ 'fn-id': fnId }) => new Promise((resolve) => {
      if (formWaits.has(fnId)) formWaits.get(fnId).push(resolve);
      else formWaits.set(fnId, [resolve]);
    }),
    renderValueForm: (host, payload) => {
      const input = doc.createElement('input');
      input.setAttribute('data-form-root', '');
      input.setAttribute('data-value', payload.value);
      host.appendChild(input);
    },
    collectFormValue: (root) => ({ ok: true, value: root.getAttribute('data-value') }),
    renderSubmitSpinner: () => doc.createElement('span'),
    renderErrorPane: () => { const e = doc.createElement('span'); e.className = 'err'; return e; },
    renderPendingPane: () => doc.createElement('span'),
    appendRuntimeEffectsStrip() {},
    buildHistoryPanel: async () => doc.createElement('div'),
    authFetchErrorMessage: () => 'err',
    requestAnimationFrame() {},
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
    clearTimeout() {},
    authFetch: async (url, opts) => {
      if (url.startsWith('/partials/execute-popover')) return res(null, 'SHELL');
      if (url === '/api/execute') {
        posts.push(JSON.parse(opts.body));
        await new Promise((r) => { ctx.__releasePost = r; });
        return res(execResponse());
      }
      if (url.startsWith('/api/execute/')) {
        if (ctx.__pollHandler) return ctx.__pollHandler(url);
        return res({ status: pollStatus });
      }
      if (ctx.__partialHandler) return ctx.__partialHandler(url);
      return res(null, '<p>result ' + url + '</p>');
    },
  });
  ctx.window = ctx;
  // innerHTML is how the shell lands; mini-dom does not parse HTML, so the
  // pane's shell is built by hand when the partial's body arrives.
  Object.defineProperty(MiniElement.prototype, 'innerHTML', {
    configurable: true,
    get() { return this.__html || ''; },
    set(v) {
      this.textContent = '';
      this.__html = v;
      if (v === 'SHELL') buildShell(doc, this, ctx.__shell);
    },
  });
  ctx.__shell = { slots: ['x'], effects };
  vm.runInContext(SRC, ctx);
  const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
  const resolveForm = (fnId, value) => formWaits.get(fnId).shift()({ ok: true, value });
  // A controllable response: `hold()` returns [promise, release(value)].
  const hold = () => { let release; const p = new Promise((r) => { release = r; }); return [p, release]; };
  return { ctx, doc, posts, timers, flush, resolveForm, res, hold,
           setExec: (f) => { execResponse = f; }, setPoll: (s) => { pollStatus = s; } };
}

(async () => {
  console.log(' double click on Run submits once; Run comes back after');
  {
    const t = boot();
    const p = t.ctx.gdMountRunPane('A');
    await t.flush();
    t.resolveForm('A', 'a-val');
    await p;
    const run = t.doc.querySelector('.execute-run-btn');
    run.click();
    run.click();
    await t.flush();
    assert(t.posts.length === 1, 'one POST, got ' + t.posts.length);
    assert(run.disabled === true, 'Run is disabled while the POST is in flight');
    t.ctx.__releasePost();
    await t.flush();
    assert(run.disabled === false, 'Run re-enabled after the run');
  }

  console.log(' effects gate: confirm box toggled mid-submit cannot re-enable Run');
  {
    const t = boot({ effects: true });
    const p = t.ctx.gdMountRunPane('A');
    await t.flush();
    t.resolveForm('A', 'a-val');
    await p;
    const run = t.doc.querySelector('.execute-run-btn');
    const cb = t.doc.querySelector('.execute-confirm-checkbox');
    cb.checked = true;
    cb.dispatch('change');
    assert(run.disabled === false, 'ticking confirm enables Run');
    run.click();
    await t.flush();
    cb.dispatch('change');
    assert(run.disabled === true, 'still disabled while in flight');
    t.ctx.__releasePost();
    await t.flush();
    assert(run.disabled === false, 'restored through the (ticked) gate');
  }

  console.log(' Cancel is hidden once a pending run turns terminal');
  {
    const t = boot();
    t.setExec(() => ({ status: 'pending', 'execution-id': 'e1' }));
    const p = t.ctx.gdMountRunPane('A');
    await t.flush();
    t.resolveForm('A', 'a-val');
    await p;
    const cancel = t.doc.querySelector('.execute-cancel-btn');
    t.doc.querySelector('.execute-run-btn').click();
    await t.flush();
    t.ctx.__releasePost();
    await t.flush();
    assert(cancel.style.display === '' && cancel.dataset.execId === 'e1', 'Cancel shown while pending');
    t.setPoll('succeeded');
    t.timers.shift()();
    await t.flush();
    assert(cancel.style.display === 'none', 'Cancel hidden after the run ended');
    assert(cancel.dataset.execId === undefined, 'stale exec id dropped');
  }

  console.log(' overlapping mounts: B\'s Run sends B\'s values');
  {
    const t = boot();
    const pa = t.ctx.gdMountRunPane('A');
    await t.flush();
    const pb = t.ctx.gdMountRunPane('B');
    await t.flush();
    t.resolveForm('B', 'b-val');
    await pb;
    t.resolveForm('A', 'a-val');
    await pa;
    t.doc.querySelector('.execute-run-btn').click();
    await t.flush();
    assert(t.posts.length === 1 && t.posts[0].args.x === 'b-val',
      'posted B\'s value, got ' + JSON.stringify(t.posts.map((p) => p.args)));
    assert(t.posts[0]['fn-id'] === 'B', 'ran B');
    const published = vm.runInContext('argFormHosts', t.ctx);
    assert(published.length === 1 && published[0].read() === 'b-val',
      'the published readers are B\'s (' + published.length + ')');
  }

  // Mount A, click Run with the exec response `resp`; resolves once the
  // POST landed (the pending pane + poll are armed for pending responses).
  async function mountA(t) {
    const p = t.ctx.gdMountRunPane('A');
    await t.flush();
    t.resolveForm('A', 'a-val');
    await p;
  }
  async function run(t, resp) {
    t.setExec(() => resp);
    t.doc.querySelector('.execute-run-btn').click();
    await t.flush();
    t.ctx.__releasePost();
    await t.flush();
  }
  const pollOf = (t) => vm.runInContext('pollState && pollState.execId', t.ctx);

  console.log(' a second pending Run keeps ITS Cancel armed');
  {
    const t = boot();
    await mountA(t);
    const cancel = t.doc.querySelector('.execute-cancel-btn');
    await run(t, { status: 'pending', 'execution-id': 'e1' });
    await run(t, { status: 'pending', 'execution-id': 'e2' });
    assert(cancel.style.display === '', 'Cancel visible for the second pending run');
    assert(cancel.dataset.execId === 'e2', 'Cancel targets e2, got ' + cancel.dataset.execId);
    assert(pollOf(t) === 'e2', 'polling e2');
  }

  console.log(' a stale poll that fails (HTTP / network) leaves the newer run alone');
  for (const mode of ['http', 'throw']) {
    const t = boot();
    await mountA(t);
    const cancel = t.doc.querySelector('.execute-cancel-btn');
    const host = t.doc.querySelector('.execute-result-host');
    await run(t, { status: 'pending', 'execution-id': 'e1' });
    const [held, release] = t.hold();
    t.ctx.__pollHandler = () => held;
    t.timers.shift()();          // e1's first poll goes out and hangs
    await t.flush();
    t.ctx.__pollHandler = null;
    await run(t, { status: 'pending', 'execution-id': 'e2' });
    const before = host.children.length;
    if (mode === 'http') release({ ok: false, status: 500, json: async () => ({}) });
    else release(Promise.reject(new Error('boom')));
    await t.flush();
    assert(pollOf(t) === 'e2', mode + ': e2 still polled, got ' + pollOf(t));
    assert(cancel.dataset.execId === 'e2' && cancel.style.display === '',
      mode + ': e2\'s Cancel still armed');
    assert(host.children.length === before && !host.querySelector('.err'),
      mode + ': no stale error written into the pane');
  }

  console.log(' a stale terminal poll does not paint over the newer run');
  {
    const t = boot();
    await mountA(t);
    const host = t.doc.querySelector('.execute-result-host');
    await run(t, { status: 'pending', 'execution-id': 'e1' });
    const [held, release] = t.hold();
    t.ctx.__partialHandler = () => held;
    t.setPoll('succeeded');
    t.timers.shift()();          // e1 turns terminal; its result partial hangs
    await t.flush();
    t.ctx.__partialHandler = null;
    t.setPoll('pending');
    await run(t, { status: 'pending', 'execution-id': 'e2' });
    release(t.res(null, 'E1-RESULT'));
    await t.flush();
    assert(host.innerHTML !== 'E1-RESULT', 'e1\'s result did not land over e2');
    assert(pollOf(t) === 'e2', 'e2 still polled');
  }

  console.log(' an inline result is not overwritten by an earlier run\'s poll');
  {
    const t = boot();
    await mountA(t);
    const host = t.doc.querySelector('.execute-result-host');
    await run(t, { status: 'pending', 'execution-id': 'e1' });
    await run(t, { status: 'succeeded', 'execution-id': 'e2', result: 2 });
    assert(host.innerHTML.includes('id=e2'), 'e2\'s result shown, got ' + host.innerHTML);
    t.setPoll('succeeded');
    while (t.timers.length) t.timers.shift()();
    await t.flush();
    assert(host.innerHTML.includes('id=e2'), 'still e2 after e1\'s timer fired, got ' + host.innerHTML);
    assert(t.doc.querySelector('.execute-cancel-btn').style.display === 'none',
      'no Cancel for a finished run');
  }

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
