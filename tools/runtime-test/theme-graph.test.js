'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-theme-graph.js'), 'utf8');

function fixture() {
  const events = new Map();
  const requests = [];
  const applied = [];
  const writes = [];
  const timers = new Map();
  let timerId = 0;
  const window = {
    API: {api_ui_theme_evaluate: '/api/ui/theme/evaluate', api_ui_theme_create: '/api/ui/theme/create'},
    gdPrefsReady: true,
    gdPrefOwner: 'owner-a',
    gdApplyThemePayload: (payload) => applied.push(payload),
    gdSanitizeThemePayload: (payload) => payload,
    gdPrefsRefresh() {},
    gdPrefWrite: async (key, value) => {
      writes.push({key, value});
      await window.gdApplyThemeGraphPreference(value);
      return true;
    },
    addEventListener: (name, callback) => events.set(name, callback),
    dispatchEvent() {},
    authFetch: (url, options) => new Promise((resolve) => requests.push({url, options, resolve})),
  };
  const ctx = vm.createContext({window, Event, AbortController, DOMException,
    setTimeout(callback, delay) { const id = ++timerId; timers.set(id, {callback, delay}); return id; },
    clearTimeout(id) { timers.delete(id); },
    graphdenCurrentOrg: 'org-a', graphData: {namespaces: []},
    getCurrentBranchName: () => 'main', loadGraphData: async () => {}, isAuthenticated: () => false, accountsAuthed: false,
    openNamespacePicker: (options) => { window.namespacePicker = options; }});
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-ui-policy.js'), 'utf8'), ctx);
  vm.runInContext(source, ctx);
  const selection = {graph: {'fn-id': '11111111-1111-1111-1111-111111111111', org: 'org-a', branch: 'theme'},
    payload: {tokens: {'--bg': '#111111'}}};
  const finish = (request, payload) => request.resolve({ok: true, json: async () => ({ok: true, payload})});
  return {ctx, window, events, requests, applied, writes, selection, finish, timers,
    refuse(index, body, status = 422) { requests[index].resolve({ok: false, status, json: async () => body}); },
    async advance() {
      assert.equal(timers.size, 1);
      const [id, timer] = timers.entries().next().value;
      assert.equal(timer.delay, 1000);
      timers.delete(id); timer.callback();
      await new Promise(setImmediate);
    }};
}

(async () => {
  const transient = {ok: false, reason: 'result-unavailable', code: 'graph-changed', retryable: true};
  const settle = () => new Promise(setImmediate);
  const fn = f => ({id: f.selection.graph['fn-id'], name: 'theme'});
  {
    const f = fixture();
    const applying = f.window.gdApplyThemeGraphPreference(f.selection);
    f.refuse(0, transient);
    await settle();
    assert.equal(f.applied.at(-1), f.selection.payload, 'reloading preserves saved colors during policy refresh');
    assert.equal(f.writes.length, 0);
    await f.advance();
    const payload = {tokens: {'--bg': '#abcdef'}};
    f.finish(f.requests[1], payload);
    await applying;
    assert.equal(f.applied.at(-1), payload);
    assert.equal(f.writes.length, 1, 'only a recovered validated result updates saved colors');
    assert.equal(f.requests.length, 2, 'saving the checked result does not re-evaluate');
  }
  {
    const f = fixture();
    const choosing = f.window.gdUseThemeGraph(fn(f));
    f.refuse(0, transient);
    await settle();
    assert.equal(f.writes.length, 0, 'a rejected snapshot never becomes a preference');
    assert.equal(f.applied.length, 0, 'choosing preserves previous colors while waiting');
    assert.equal(f.requests.length, 1);
    await f.advance();
    const payload = {tokens: {'--bg': '#abcdef'}};
    f.finish(f.requests[1], payload);
    assert.ok(await choosing);
    assert.equal(f.writes.length, 1);
    assert.equal(f.writes[0].value.payload, payload);
    assert.equal(f.timers.size, 0);
  }
  {
    const f = fixture();
    const choosing = f.window.gdUseThemeGraph(fn(f));
    for (let attempt = 0; attempt < 3; attempt++) {
      f.refuse(attempt, transient);
      await settle();
      if (attempt < 2) await f.advance();
    }
    await choosing;
    assert.equal(f.requests.length, 3, 'three total attempts bound repeated invalidation');
    assert.equal(f.writes.length, 0);
    assert.equal(f.applied.length, 0);
    assert.equal(f.timers.size, 0);
  }
  for (const [body, status] of [[transient, 403], [transient, 500],
    [{...transient, code: 'tainted-result'}, 422], [{...transient, code: 'runtime-effects'}, 422],
    [{...transient, reason: 'not-plain-pure'}, 422], [{...transient, retryable: false}, 422],
    [{ok: false, reason: 'result-unavailable'}, 422]]) {
    const f = fixture();
    const choosing = f.window.gdUseThemeGraph(fn(f));
    f.refuse(0, body, status);
    await choosing;
    assert.equal(f.requests.length, 1, 'permission, purity and unknown refusals are permanent');
    assert.equal(f.timers.size, 0);
    assert.equal(f.writes.length, 0, 'a refusal cannot replace the old preference');
  }
  for (const invalidate of [
    f => { f.window.gdPrefOwner = 'owner-b'; },
    f => { f.ctx.graphdenCurrentOrg = 'org-b'; },
    f => { f.ctx.getCurrentBranchName = () => 'other'; },
  ]) {
    const f = fixture();
    const choosing = f.window.gdUseThemeGraph(fn(f));
    f.refuse(0, transient);
    await settle();
    invalidate(f);
    await f.advance();
    await choosing;
    assert.equal(f.requests.length, 1, 'a retry never crosses its original account, organization or branch');
    assert.equal(f.writes.length, 0);
    assert.equal(f.applied.length, 0);
  }
  {
    const f = fixture();
    const first = f.window.gdUseThemeGraph(fn(f));
    f.refuse(0, transient);
    await settle();
    const secondFn = {...fn(f), id: '22222222-2222-2222-2222-222222222222'};
    const second = f.window.gdUseThemeGraph(secondFn);
    await first;
    assert.equal(f.timers.size, 0, 'a new selection cancels the old queued wait immediately');
    assert.equal(f.requests[0].options.signal.aborted, true);
    f.finish(f.requests[1], {tokens: {'--bg': '#222222'}});
    await second;
    assert.equal(f.writes.length, 1);
    assert.equal(f.writes[0].value.graph['fn-id'], secondFn.id);
  }
  {
    const f = fixture();
    const choosing = f.window.gdUseThemeGraph(fn(f));
    f.refuse(0, transient);
    await settle();
    f.events.get('gd-auth-changed')();
    await choosing;
    assert.equal(f.timers.size, 0, 'account disposal removes the pending retry');
    assert.equal(f.writes.length, 0);
  }
  {
    const f = fixture();
    const choosing = f.window.gdUseThemeGraph(fn(f));
    f.window.gdSanitizeThemePayload = () => null;
    f.finish(f.requests[0], {tokens: {'--bg': 'invalid'}});
    await choosing;
    assert.equal(f.writes.length, 0, 'a successful transport still requires a valid theme payload');
  }
  {
    const f = fixture();
    let release;
    let loads = 0;
    const anchor = {isConnected: true, closest: () => null};
    f.ctx.graphData = null;
    f.ctx.loadGraphData = () => { loads++; return new Promise(resolve => { release = resolve; }); };
    const opening = f.window.gdCreateThemeGraph(anchor);
    assert.equal(f.window.namespacePicker, undefined, 'early click waits for the real namespace tree');
    assert.match(f.window.gdThemeGraphStatus(), /Loading namespaces/);
    assert.equal(anchor.disabled, true, 'repeated clicks cannot start duplicate loads');
    await f.window.gdCreateThemeGraph(anchor);
    assert.equal(loads, 1);
    f.ctx.graphData = {namespaces: []};
    release();
    await opening;
    assert.equal(f.window.namespacePicker.anchorEl, anchor, 'the original early click opens after loading');
    assert.equal(anchor.disabled, false);
    await f.window.gdCreateThemeGraph(anchor);
    assert.equal(loads, 1, 'an installed tree is reused without a reload');
  }
  for (const invalidate of [
    f => { f.window.gdPrefOwner = 'owner-b'; },
    f => { f.ctx.getCurrentBranchName = () => 'other'; },
    f => { f.ctx.graphdenCurrentOrg = 'org-b'; },
    f => { f.events.get('gd-auth-changed')(); },
    (_f, anchor) => { anchor.isConnected = false; },
    (_f, anchor) => { anchor.closest = () => ({}); },
  ]) {
    const f = fixture();
    let release;
    const anchor = {isConnected: true, closest: () => null};
    f.ctx.graphData = null;
    f.ctx.loadGraphData = () => new Promise(resolve => { release = resolve; });
    const opening = f.window.gdCreateThemeGraph(anchor);
    invalidate(f, anchor);
    f.ctx.graphData = {namespaces: []};
    release();
    await opening;
    assert.equal(f.window.namespacePicker, undefined, 'late load cannot open into a changed context');
    assert.equal(f.requests.length, 0, 'loading never creates a graph');
    assert.equal(anchor.disabled, false);
  }
  for (const load of [async () => {}, async () => { throw new Error('offline'); }]) {
    const f = fixture();
    const anchor = {isConnected: true, closest: () => null};
    f.ctx.graphData = null;
    f.ctx.loadGraphData = load;
    await f.window.gdCreateThemeGraph(anchor);
    assert.equal(f.window.namespacePicker, undefined);
    assert.equal(anchor.disabled, false, 'failed loading permits retry');
    assert.match(f.window.gdThemeGraphStatus(), /could not be loaded/);
    f.ctx.loadGraphData = async () => { f.ctx.graphData = {namespaces: []}; };
    await f.window.gdCreateThemeGraph(anchor);
    assert.equal(f.window.namespacePicker.anchorEl, anchor, 'a retry opens after successful loading');
  }
  {
    const f = fixture();
    await f.window.gdCreateThemeGraph({isConnected: true, closest: () => null});
    f.window.namespacePicker.onPick({id: 'parent-id'});
    assert.equal(f.requests[0].url, '/api/ui/theme/create', 'creation uses the generated path key');
    assert.deepEqual(JSON.parse(f.requests[0].options.body), {'namespace-id': 'parent-id', owner: 'owner-a'});
    f.requests[0].resolve({ok: false, json: async () => ({ok: false})});
    await new Promise(setImmediate);
    await f.window.gdCreateThemeGraph({isConnected: true, closest: () => null});
    f.window.gdPrefOwner = 'owner-b';
    f.window.namespacePicker.onPick({id: 'parent-id'});
    assert.equal(f.requests.length, 1, 'a picker opened by another owner cannot create a theme');
  }
  {
    const f = fixture();
    await f.window.gdApplyThemeGraphPreference({...f.selection, graph: {...f.selection.graph, org: 'org-b'}});
    assert.equal(f.requests.length, 0, 'a preference never switches into another organization');
    assert.equal(f.applied[0], f.selection.payload, 'saved colors remain the fallback');
    f.window.gdPrefsReady = false;
    await f.window.gdApplyThemeGraphPreference(f.selection);
    assert.equal(f.requests.length, 0, 'an unverified local mirror never evaluates a graph');
  }
  {
    const f = fixture();
    const first = f.window.gdApplyThemeGraphPreference(f.selection);
    const second = f.window.gdApplyThemeGraphPreference({...f.selection, payload: {tokens: {'--bg': '#222222'}}});
    assert.equal(f.requests[0].options.signal.aborted, true);
    assert.equal(f.requests[1].options.headers['X-Graphden-Branch'], 'theme');
    const current = {tokens: {'--bg': '#333333'}};
    f.finish(f.requests[1], current);
    await second;
    f.finish(f.requests[0], {tokens: {'--bg': '#999999'}});
    await first;
    assert.equal(f.applied.at(-1), current, 'an old theme response cannot replace the newer selection');
    assert.equal(f.writes.length, 1, 'only the current result becomes the saved fallback');
    assert.equal(f.writes[0].value.payload, current);
    assert.equal(f.requests.length, 2, 'saving a checked fallback does not execute the graph again');
  }
  {
    const f = fixture();
    const pending = f.window.gdApplyThemeGraphPreference(f.selection);
    f.events.get('gd-auth-changed')();
    f.finish(f.requests[0], {tokens: {'--bg': '#999999'}});
    await pending;
    assert.equal(f.applied.at(-1), null, 'a response from the previous account is discarded');
    assert.equal(f.requests[0].options.signal.aborted, true);
    assert.equal(f.writes.length, 0, 'a previous account cannot save its late result');
  }
  {
    const f = fixture();
    const pending = f.window.gdApplyThemeGraphPreference(f.selection);
    f.requests[0].resolve({ok: false, json: async () => ({ok: false, reason: 'unavailable'})});
    await pending;
    assert.equal(f.applied.at(-1), f.selection.payload, 'a deleted or inaccessible graph preserves saved colors');
    assert.equal(f.writes.length, 0, 'an evaluation failure never replaces the saved fallback');
    assert.match(f.window.gdThemeGraphStatus(), /Using saved colors/);
  }
  console.log('PASS theme graph scope, verified preferences, and response ownership');
})().catch((error) => { console.error(error); process.exitCode = 1; });
