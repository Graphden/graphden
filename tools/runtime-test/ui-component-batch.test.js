'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const source = ['editor-ui-policy.js', 'editor-ui-components.js'].map(name =>
  fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor', name), 'utf8')).join('\n');
const id = '11111111-1111-1111-1111-111111111111';
const plan = {entries: {view: 'view'}, inputs: {view: {accepted: ['state']}}};
const tick = () => new Promise(resolve => setImmediate(resolve));
const success = () => ({ok: true, 'selection-id': id, 'configuration-branch-id': id,
  'configuration-revision': 'a'.repeat(64), 'configuration-defaults-id': id,
  'configuration-preference': {present: false, value: null}, roots: {}, plan});
function fixture(batch = true) {
  const requests = [], timers = new Map(), events = new Map();
  const window = {gdPrefsReady: true, gdPrefOwner: 'alice',
    API: {api_ui_configuration_plan: '/plan', ...(batch ? {api_ui_configuration_plans: '/plans'} : {})},
    gdPrefOnChange() {}, addEventListener: (name, f) => events.set(name, f), dispatchEvent() {},
    GraphdenBrowser: {createRuntime: value => ({run: () => value})},
    authFetch: (url, options) => new Promise((resolve, reject) => {
      const request = {url, options, resolve, reject}; requests.push(request);
      options.signal?.addEventListener('abort', () => reject(new DOMException('abort', 'AbortError')), {once: true});
    })};
  let next = 0;
  vm.runInNewContext(source, {window, document: {addEventListener() {}}, Event, DOMException, AbortController,
    performance: {now: () => 0}, graphdenCurrentOrg: 'public', getCurrentBranchName: () => 'main',
    setTimeout(f, delay) { const key = ++next; timers.set(key, {f, delay}); return key; },
    clearTimeout(key) { timers.delete(key); }});
  return {window, requests, timers, events,
    load: (component, signal) => window.gdLoadUIComponentRuntime(component, plan, {}, signal),
    async flush(delay = 0) { for (const [key, timer] of [...timers]) if (timer.delay === delay) { timers.delete(key); timer.f(); } await tick(); },
    finish(request, results) { request.resolve({ok: true, status: 200, json: async () => ({ok: true, results})}); }};
}
test('eight same-turn loads use one POST and keep per-component failures', async () => {
  const f = fixture(), names = ['inspector', 'explorer', 'canvas', 'canvas-values', 'canvas-inheritance', 'canvas-metadata', 'canvas-type-structure', 'canvas-bindings'];
  const loads = names.map(name => f.load(name));
  assert.equal(f.requests.length, 0);
  await f.flush();
  assert.equal(f.requests.length, 1);
  assert.deepEqual(JSON.parse(f.requests[0].options.body), {components: names});
  assert.equal(f.requests[0].options.cache, 'no-store');
  const results = Object.fromEntries(names.map(name => [name, success()]));
  results.explorer = {ok: false, 'http-status': 422, code: 'component-missing', fallback: 'builtin'};
  results.canvas = {ok: false, 'http-status': 403};
  f.finish(f.requests[0], results);
  await Promise.all(loads);
  assert.equal(f.window.gdUIComponentRuntimeIdentity('inspector'), id);
  assert.equal(f.window.gdUIComponentRuntimeIdentity('explorer'), null);
  assert.match(f.window.gdUIComponentsStatus(), /unavailable/);
});
test('duplicate subscribers share one item; abort never cancels another subscriber', async () => {
  const f = fixture(), a = new AbortController(), b = new AbortController();
  const first = f.load('inspector', a.signal), second = f.load('inspector', b.signal), third = f.load('canvas');
  const rejected = assert.rejects(first, {name: 'AbortError'});
  await f.flush();
  a.abort(); await rejected;
  assert.equal(f.requests[0].options.signal.aborted, false);
  assert.deepEqual(JSON.parse(f.requests[0].options.body).components, ['inspector', 'canvas']);
  f.finish(f.requests[0], {inspector: success(), canvas: success()});
  await Promise.all([second, third]);
});
test('all aborted before flush cause no POST; last abort after send cancels transport', async () => {
  const f = fixture(), a = new AbortController();
  const pending = assert.rejects(f.load('inspector', a.signal), {name: 'AbortError'});
  a.abort(); await pending; await f.flush(); assert.equal(f.requests.length, 0);
  const b = new AbortController(), running = assert.rejects(f.load('inspector', b.signal), {name: 'AbortError'});
  await f.flush(); b.abort(); await running;
  assert.equal(f.requests[0].options.signal.aborted, true);
});
test('auth event invalidates a batch even for the same owner and starts a new capture', async () => {
  const f = fixture();
  const old = assert.rejects(f.load('inspector'), {name: 'AbortError'});
  await f.flush(); f.events.get('gd-auth-changed')(); await old;
  assert.equal(f.requests[0].options.signal.aborted, true);
  const next = f.load('inspector'); await f.flush();
  f.finish(f.requests[0], {inspector: success()});
  assert.equal(f.window.gdUIConfigurationState(), null);
  f.finish(f.requests[1], {inspector: success()}); await next;
  assert.equal(f.window.gdUIComponentRuntimeIdentity('inspector'), id);
});
test('policy refresh retries the batch; forbidden does not fan out to legacy', async () => {
  const f = fixture(), loads = [f.load('inspector'), f.load('canvas')];
  await f.flush();
  f.requests[0].resolve({ok: false, status: 422, json: async () => ({ok: false, code: 'policy-refresh-required', retryable: true})});
  await tick(); await f.flush(1000);
  assert.equal(f.requests.length, 2);
  assert.equal(f.requests[0].options.body, f.requests[1].options.body);
  f.requests[1].resolve({ok: false, status: 403, json: async () => ({ok: false})});
  await Promise.all(loads);
  assert(f.requests.every(request => request.url === '/plans'));
});
test('invalid envelope publishes no partial configuration; legacy API remains supported', async () => {
  const f = fixture(), loads = [f.load('inspector'), f.load('canvas')];
  await f.flush(); f.finish(f.requests[0], {inspector: success()}); await Promise.all(loads);
  assert.equal(f.window.gdUIConfigurationState(), null);
  const legacy = fixture(false), single = legacy.load('inspector');
  assert.equal(legacy.requests[0].url, '/plan');
  legacy.requests[0].resolve({ok: true, status: 200, json: async () => success()}); await single;
  assert.equal(legacy.window.gdUIComponentRuntimeIdentity('inspector'), id);
});
test('configuration capture joins component requests and completed plans are never reused', async () => {
  const f = fixture(), first = f.load('inspector'), capture = f.window.gdCaptureUIConfiguration();
  await f.flush(); assert.equal(f.requests.length, 1);
  f.finish(f.requests[0], {inspector: success(), 'account-menu': success()});
  await Promise.all([first, capture]);
  const later = f.load('inspector'); await f.flush(); assert.equal(f.requests.length, 2);
  f.finish(f.requests[1], {inspector: success()}); await later;
});
