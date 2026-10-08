'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-ui-components.js'), 'utf8');
const first = '11111111-1111-1111-1111-111111111111';
const second = '22222222-2222-2222-2222-222222222222';
const branch = '33333333-3333-3333-3333-333333333333';
const plan = {entries: {view: 'view-id'}, inputs: {'view-id': {accepted: ['state'], required: ['state']}}};
function fixture() {
  let chosen = {'fn-id': first, 'branch-id': branch, org: 'org-a'};
  const requests = [];
  const events = new Map();
  const calls = [];
  const timers = new Map();
  let timerId = 0;
  const window = {
    gdPrefsReady: true, gdPrefOwner: 'owner-a', API: {api_ui_components_plan: '/api/ui/components/plan'},
    gdPrefRead: () => chosen,
    gdPrefOnChange(callback) { window.changed = callback; },
    authFetch: (route, options) => new Promise(resolve => requests.push({route, options, resolve})),
    addEventListener: (name, callback) => events.set(name, callback), dispatchEvent() {},
    GraphdenBrowser: {createRuntime(value) { return {run(entry, args) {
      const fields = value.inputs[value.entries[entry]];
      for (const required of fields.required) if (!Object.hasOwn(args, required)) throw new Error('Missing argument');
      calls.push({entry, args}); return args;
    }}; }},
  };
  vm.runInNewContext(source, {window, document: {addEventListener() {}}, graphdenCurrentOrg: 'org-a',
    Event, DOMException,
    setTimeout(callback, delay) { const id = ++timerId; timers.set(id, {callback, delay}); return id; },
    clearTimeout(id) { timers.delete(id); }});
  return {window, requests, events, calls, timers,
    async advance() {
      assert.equal(timers.size, 1, 'only one bounded retry wait is queued');
      const [id, timer] = timers.entries().next().value;
      assert.equal(timer.delay, 1000);
      timers.delete(id);
      timer.callback();
      await new Promise(resolve => setImmediate(resolve));
    }, choose: value => { chosen = value; }, finish(request, body, ok = true, status = ok ? 200 : 422) {
    request.resolve({ok, status, json: async () => body});
  }};
}
(async () => {
  const waiting = {ok: false, code: 'policy-refresh-required', retryable: true, 'retry-after': 1};
  const settle = () => new Promise(resolve => setImmediate(resolve));
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    f.finish(f.requests[0], waiting, false);
    await settle();
    assert.equal(f.requests.length, 1, 'a temporary refusal waits instead of hammering the server');
    await f.advance();
    f.finish(f.requests[1], {ok: true, 'selection-id': first, plan});
    await loading;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('account-menu'), first);
    assert.equal(f.timers.size, 0);
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    for (let attempt = 0; attempt < 3; attempt++) {
      f.finish(f.requests[attempt], waiting, false);
      await settle();
      if (attempt < 2) await f.advance();
    }
    await loading;
    assert.equal(f.requests.length, 3, 'the complete load makes at most three attempts');
    assert.equal(f.timers.size, 0);
    assert.equal(f.window.gdUIComponentRuntimeIdentity('fn-picker'), null);
    assert.match(f.window.gdUIComponentsStatus(), /Using built-in/);
  }
  for (const [body, status] of [[{ok: false}, 422], [{...waiting, code: 'invalid-selection'}, 422],
    [{...waiting, retryable: false}, 422], [waiting, 403], [waiting, 500]]) {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    f.finish(f.requests[0], body, false, status);
    await loading;
    assert.equal(f.requests.length, 1, 'permanent or unclassified refusals never retry');
    assert.equal(f.timers.size, 0);
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    const rejected = assert.rejects(loading, {name: 'AbortError'});
    f.finish(f.requests[0], waiting, false);
    await settle();
    f.choose({'fn-id': second, 'branch-id': branch, org: 'org-a'});
    await f.advance();
    await rejected;
    assert.equal(f.requests.length, 1, 'a changed selection never sends the stale retry');
  }
  {
    const f = fixture();
    const controller = new AbortController();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan, {}, controller.signal);
    const rejected = assert.rejects(loading, {name: 'AbortError'});
    f.finish(f.requests[0], waiting, false);
    await settle();
    controller.abort();
    await rejected;
    assert.equal(f.timers.size, 0, 'cancel/dispose removes the wait immediately');
    assert.equal(f.requests.length, 1);
    assert.equal(f.window.gdUIComponentsStatus(), '', 'cancellation does not publish a failure');
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    assert.equal(f.requests[0].options.headers['X-Graphden-Branch'], branch);
    assert.deepEqual(JSON.parse(f.requests[0].options.body), {component: 'account-menu'});
    f.choose({'fn-id': second, 'branch-id': branch, org: 'org-a'});
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await assert.rejects(loading, {name: 'AbortError'}, 'older preference never installs a plan');
    assert.equal(f.calls.length, 0);
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    f.window.gdPrefOwner = 'owner-b';
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await assert.rejects(loading, {name: 'AbortError'}, 'account change also invalidates a reply');
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    f.finish(f.requests[0], {ok: false}, false);
    const runtime = await loading;
    runtime.run('view', {state: 42, theme: 'formerly free input'});
    assert.deepEqual(Object.keys(f.calls[0].args), ['state'], 'host filters inputs removed by graph composition');
    assert.equal(f.calls[0].args.state, 42);
    assert.throws(() => runtime.run('view'), /Missing argument/, 'required input validation remains authoritative');
    assert.match(f.window.gdUIComponentsStatus(), /Using built-in/);
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    f.finish(f.requests[0], {ok: true, 'selection-id': second, plan});
    await loading;
    assert.match(f.window.gdUIComponentsStatus(), /unavailable/, 'mismatched identity falls back');
    f.choose({'fn-id': first, 'branch-id': branch, org: 'org-b'});
    await f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    assert.equal(f.requests.length, 1, 'foreign organization never sends a plan request');
  }
  console.log('PASS personal UI plans: scoped request, late reply rejection, fallback and input contract');
})().catch(error => { console.error(error); process.exitCode = 1; });
