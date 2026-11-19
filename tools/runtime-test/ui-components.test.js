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
  const requests = [];
  const events = new Map();
  const calls = [];
  const timers = new Map();
  let timerId = 0, clock = 0, currentBranch = 'main';
  const window = {
    gdPrefsReady: true, gdPrefOwner: 'owner-a', gdUIPlanBatchMax: 3, API: {api_ui_configuration_plan: '/api/ui/configuration/plan'},
    gdPrefRead: () => null,
    gdPrefOnChange(callback) { window.changed = callback; },
    authFetch: (route, options) => new Promise((resolve, reject) => requests.push({route, options, resolve, reject})),
    addEventListener: (name, callback) => events.set(name, callback), dispatchEvent() {},
    GraphdenBrowser: {createRuntime(value) { return {run(entry, args) {
      const fields = value.inputs[value.entries[entry]];
      for (const required of fields.required) if (!Object.hasOwn(args, required)) throw new Error('Missing argument');
      calls.push({entry, args}); return args;
    }}; }},
  };
  const globals = {window, document: {addEventListener() {}}, graphdenCurrentOrg: 'org-a',
    getCurrentBranchName: () => currentBranch, performance: {now: () => clock}, Event, DOMException, AbortController, Response,
    setTimeout(callback, delay) { const id = ++timerId; timers.set(id, {callback, delay}); return id; },
    clearTimeout(id) { timers.delete(id); }};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-ui-policy.js'), 'utf8') + '\n' + source, globals);
  return {window, requests, events, calls, timers,
    elapse(ms) { clock += ms; },
    scope({org, branch: name}) { if (org) globals.graphdenCurrentOrg = org; if (name) currentBranch = name; },
    async advance() {
      assert.equal(timers.size, 1, 'only one bounded retry wait is queued');
      const [id, timer] = timers.entries().next().value;
      assert.equal(timer.delay, 1000);
      timers.delete(id);
      clock += timer.delay;
      timer.callback();
      await new Promise(resolve => setImmediate(resolve));
    }, choose: () => { window.gdRefreshUIConfiguration();
      for (const [id, timer] of timers) if (timer.delay === 0) { timers.delete(id); timer.callback(); }
    }, finish(request, body, ok = true, status = ok ? 200 : 422) {
    request.resolve({ok, status, json: async () => ok ? {
      'configuration-branch-id': branch, 'configuration-revision': 'a'.repeat(64),
      'configuration-preference': {present: false, value: null},
      'configuration-defaults-id': first, 'custom-components': {}, ...body,
    } : body});
  }, finishBatch(request, items) {
    request.resolve({ok: true, status: 200, json: async () => ({ok: true,
      items: items.map(({component, status = 200, body = {ok: true}}) => ({component,
        result: {status, headers: {'Content-Type': 'application/json'}, body: JSON.stringify(status === 200 ? {
          'configuration-branch-id': branch, 'configuration-revision': 'a'.repeat(64),
          'configuration-preference': {present: false, value: null},
          'configuration-defaults-id': first, 'custom-components': {}, 'selection-id': first, plan, ...body,
        } : body)},
      })),
    })});
  }};
}
const deadline = setTimeout(() => { console.error("UI component test did not settle"); process.exit(1); }, 5000);
(async () => {
  {
    const f = fixture();
    let selection = first;
    f.window.gdEditorRoot = {componentIdentity: name => ['account-menu', 'recents'].includes(name) ? selection : null};
    assert.equal(f.window.gdUIComponentRuntimeIdentity('account-menu'), first);
    assert.equal(f.window.gdUIComponentRuntimeIdentity('recents'), first);
    selection = null;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('account-menu'), null, 'root refusal never falls back to a legacy selection');
    assert.equal(f.window.gdUIComponentRuntimeIdentity('recents'), null);
  }
  {
    const f = fixture();
    let roots = 0, pickers = 0, inspectors = 0;
    f.window.gdEditorRoot = {reload() { roots++; }};
    f.window.gdFnPickerGraph = {reload() { pickers++; }};
    f.window.gdInspectorGraph = {reload() { inspectors++; }};
    f.choose();
    assert.equal(roots, 1, 'configuration refresh reloads the authoritative root');
    assert.equal(pickers, 0, 'legacy refresh never races the root-owned picker loader');
    assert.equal(inspectors, 1, 'remaining legacy consumers still refresh');
  }
  const waiting = {ok: false, code: 'policy-refresh-required', retryable: true, 'retry-after': 1};
  const settle = () => new Promise(resolve => setImmediate(resolve));
  {
    const f = fixture();
    const personal = f.window.gdLoadUIComponentRuntime('recents', plan);
    await settle();
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await personal;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('recents'), first);
    const missing = f.window.gdLoadUIComponentRuntime('recents', plan);
    await settle();
    f.finish(f.requests[1], {ok: false, code: 'component-missing', fallback: 'builtin'}, false);
    const builtin = await missing;
    builtin.run('view', {state: 42});
    assert.equal(f.calls[0].args.state, 42);
    assert.equal(f.window.gdUIComponentRuntimeIdentity('recents'), null, 'removed override releases the old identity');
    assert.equal(f.window.gdUIComponentsStatus(), '', 'an absent override is normal, not a broken graph');
  }
  for (const [body, status] of [
    [{ok: false, code: 'component-missing', fallback: 'builtin'}, 403],
    [{ok: false, code: 'component-missing', fallback: 'builtin'}, 500],
    [{ok: false, code: 'component-missing'}, 422],
    [{ok: false, code: 'invalid-selection', fallback: 'builtin'}, 422],
  ]) {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('recents', plan);
    await settle();
    f.finish(f.requests[0], body, false, status);
    await loading;
    assert.match(f.window.gdUIComponentsStatus(), /unavailable/, 'only the classified missing-component response is silent');
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    await settle();
    f.finish(f.requests[0], waiting, false);
    await settle();
    assert.equal(f.requests.length, 1, 'a temporary refusal waits instead of hammering the server');
    await f.advance();
    await settle();
    f.finish(f.requests[1], {ok: true, 'selection-id': first, plan});
    await loading;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('account-menu'), first);
    assert.equal(f.timers.size, 0);
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('canvas-metadata', plan);
    await settle();
    f.requests[0].reject(new TypeError('Failed to fetch'));
    await settle();
    assert.equal(f.requests.length, 1, 'network loss waits before retrying the same read');
    assert.equal(f.window.gdUIComponentsStatus(), '', 'transient loss does not install a fallback');
    await f.advance();
    await settle();
    f.finish(f.requests[1], {ok: true, 'selection-id': first, plan});
    await loading;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('canvas-metadata'), first);
  }
  {
    const f = fixture();
    const controller = new AbortController();
    const loading = f.window.gdLoadUIComponentRuntime('canvas-metadata', plan, {}, controller.signal);
    const rejection = assert.rejects(loading, {name: 'AbortError'});
    await settle();
    f.requests[0].reject(new TypeError('Failed to fetch'));
    await settle();
    controller.abort();
    await rejection;
    assert.equal(f.requests.length, 1, 'revoked load cannot retry under an old signal');
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('canvas-metadata', plan);
    await settle();
    for (let attempt = 0; attempt < 4; attempt++) {
    await settle();
      f.requests[attempt].reject(new TypeError('Failed to fetch'));
      await settle();
      if (attempt < 3) await f.advance();
    }
    await loading;
    assert.equal(f.requests.length, 4, 'persistent transport loss has a fixed request bound');
    assert.match(f.window.gdUIComponentsStatus(), /unavailable/);
    assert.equal(f.timers.size, 0);
  }
  for (const capture of [false, true]) {
    const f = fixture();
    const controller = new AbortController();
    const loading = capture ? f.window.gdCaptureUIConfiguration(controller.signal)
      : f.window.gdLoadUIComponentRuntime('fn-picker', plan, {}, controller.signal);
    for (let attempt = 0; attempt < 4; attempt++) {
    await settle();
      f.finish(f.requests[attempt], waiting, false);
      await settle();
      await f.advance();
    }
    await settle();
    f.finish(f.requests[4], {ok: true, 'selection-id': first, plan});
    await loading;
    assert.equal(capture ? f.window.gdUIConfigurationState().selection['fn-id']
      : f.window.gdUIComponentRuntimeIdentity('fn-picker'), first,
      'one current load recovers when a write burst outlasts three attempts');
    assert(f.requests.every(request => request.options.signal && !request.options.signal.aborted),
      'retry transports remain active while their consumer is current');
  }
  for (const capture of [false, true]) {
    const f = fixture();
    const loading = capture ? f.window.gdCaptureUIConfiguration()
      : f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    const rejected = capture ? assert.rejects(loading, /unavailable/) : null;
    f.elapse(29500);
    await settle();
    f.finish(f.requests[0], waiting, false);
    await settle();
    await f.advance();
    if (rejected) await rejected; else await loading;
    assert.equal(f.requests.length, 1, 'crossing the retry deadline during the wait starts no request');
    assert.equal(f.timers.size, 0);
    assert.equal(f.window.gdUIComponentRuntimeIdentity('fn-picker'), null);
    if (!capture) assert.match(f.window.gdUIComponentsStatus(), /Using built-in/);
    assert.equal(f.window.gdUIConfigurationState(), null, 'expiry never installs a configuration');
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    await settle();
    f.elapse(31000);
    await settle();
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await loading;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('fn-picker'), first,
      'a current successful response remains valid after the retry admission window');
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    await settle();
    f.elapse(30000);
    await settle();
    f.finish(f.requests[0], waiting, false);
    await loading;
    assert.equal(f.requests.length, 1);
    assert.equal(f.timers.size, 0, 'an already expired retry window schedules no wait');
    assert.match(f.window.gdUIComponentsStatus(), /Using built-in/);
  }
  {
    const f = fixture();
    const loading = f.window.gdCaptureUIConfiguration();
    await settle();
    const rejected = assert.rejects(loading, {name: 'AbortError'});
    f.elapse(31000);
    f.window.gdPrefOwner = 'owner-b';
    await settle();
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await rejected;
    assert.equal(f.window.gdUIConfigurationState(), null, 'late capture never adopts an old owner');
  }
  for (const [body, status] of [[{ok: false}, 422], [{...waiting, code: 'invalid-selection'}, 422],
    [{...waiting, retryable: false}, 422], [waiting, 401], [waiting, 403], [waiting, 500]]) {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    await settle();
    f.finish(f.requests[0], body, false, status);
    await loading;
    assert.equal(f.requests.length, 1, 'permanent or unclassified refusals never retry');
    assert.equal(f.timers.size, 0);
  }
  for (const change of [{branch: 'other'}, {org: 'org-b'}]) {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    await settle();
    const rejected = assert.rejects(loading, {name: 'AbortError'});
    await settle();
    f.finish(f.requests[0], waiting, false);
    await settle();
    f.scope(change);
    await f.advance();
    await rejected;
    assert.equal(f.requests.length, 1, 'branch/org change never starts a stale retry');
  }
  for (const malformed of [false, true]) {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    await settle();
    if (malformed) f.requests[0].resolve({json: async () => { throw new SyntaxError('invalid JSON'); }});
    else f.requests[0].reject(new Error('non-retryable failure'));
    await loading;
    assert.equal(f.requests.length, 1, 'non-retryable transport or JSON failures do not retry');
    assert.equal(f.timers.size, 0);
    assert.match(f.window.gdUIComponentsStatus(), /Using built-in/);
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    await settle();
    const rejected = assert.rejects(loading, {name: 'AbortError'});
    await settle();
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
    await settle();
    const rejected = assert.rejects(loading, {name: 'AbortError'});
    await settle();
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
    const rejection = assert.rejects(loading, {name: 'AbortError'}, 'older preference never installs a plan');
    await settle();
    assert.equal(f.requests[0].route, '/api/ui/configuration/plan');
    assert.equal(f.requests[0].options.headers['X-Graphden-Branch'], undefined, 'current branch wrapper owns request context');
    assert.deepEqual(JSON.parse(f.requests[0].options.body), {component: 'account-menu'});
    f.choose({'fn-id': second, 'branch-id': branch, org: 'org-a'});
    await settle();
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await rejection;
    assert.equal(f.calls.length, 0);
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    const rejection = assert.rejects(loading, {name: 'AbortError'}, 'account change also invalidates a reply');
    await settle();
    f.window.gdPrefOwner = 'owner-b';
    await settle();
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await rejection;
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    const rejection = assert.rejects(loading, {name: 'AbortError'}, 'A→B→A never adopts the earlier authenticated snapshot');
    await settle();
    f.window.gdPrefOwner = 'owner-b';
    f.events.get('gd-auth-changed')();
    f.window.gdPrefOwner = 'owner-a';
    f.events.get('gd-auth-changed')();
    await settle();
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await rejection;
    assert.equal(f.window.gdUIConfigurationState(), null);
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    await settle();
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
    await settle();
    f.finish(f.requests[0], {ok: true, 'selection-id': second, plan});
    await loading;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('account-menu'), second,
      'the authenticated ordinary resolver, not a mirrored preference, selects the root');
    assert.equal(f.window.gdUIConfigurationState().revision, 'a'.repeat(64));
    f.window.graphdenAccountsMode = () => true;
    f.window.isAuthenticated = () => false;
    await f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    assert.equal(f.requests.length, 1, 'true guests use the checked shipped artifact');
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    await settle();
    f.finish(f.requests[0], {ok: true, 'selection-id': first,
      'configuration-revision': 'not-a-source-revision', plan});
    await loading;
    assert.match(f.window.gdUIComponentsStatus(), /unavailable/);
    assert.equal(f.window.gdUIConfigurationState(), null, 'invalid source metadata never publishes configuration state');
  }
  {
    const f = fixture();
    const firstLoad = f.window.gdLoadUIComponentRuntime('canvas', plan);
    const missing = f.window.gdLoadUIComponentRuntime('canvas-values', plan);
    await settle();
    assert.equal(f.requests.length, 1);
    assert.deepEqual(JSON.parse(f.requests[0].options.body), {components: ['canvas', 'canvas-values']});
    f.finishBatch(f.requests[0], [{component: 'canvas'}, {component: 'canvas-values', status: 422,
      body: {ok: false, code: 'component-missing', fallback: 'builtin'}}]);
    await Promise.all([firstLoad, missing]);
    assert.equal(f.window.gdUIComponentRuntimeIdentity('canvas'), first);
    assert.equal(f.window.gdUIComponentRuntimeIdentity('canvas-values'), null);
    assert.equal(f.window.gdUIComponentsStatus(), '', 'one missing role leaves its sibling selected');
  }
  {
    const f = fixture();
    const owner = new AbortController();
    const firstLoad = f.window.gdLoadUIComponentRuntime('canvas', plan, {}, owner.signal);
    const rejected = assert.rejects(firstLoad, {name: 'AbortError'});
    const sibling = f.window.gdLoadUIComponentRuntime('canvas-values', plan);
    await settle();
    owner.abort();
    await rejected;
    assert.equal(f.requests[0].options.signal.aborted, false, 'one consumer cannot abort its sibling transport');
    f.finishBatch(f.requests[0], [{component: 'canvas'}, {component: 'canvas-values'}]);
    await sibling;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('canvas'), null);
    assert.equal(f.window.gdUIComponentRuntimeIdentity('canvas-values'), first);
  }
  {
    const f = fixture();
    const owners = [new AbortController(), new AbortController()];
    const loads = ['canvas', 'canvas-values'].map((component, index) =>
      f.window.gdLoadUIComponentRuntime(component, plan, {}, owners[index].signal));
    const settled = Promise.allSettled(loads);
    await settle();
    owners.forEach(owner => owner.abort());
    assert.equal(f.requests[0].options.signal.aborted, true, 'last consumer retires the shared transport');
    assert((await settled).every(result => result.status === 'rejected' && result.reason.name === 'AbortError'));
  }
  {
    const f = fixture();
    const loads = ['canvas', 'canvas-values'].map(component => f.window.gdLoadUIComponentRuntime(component, plan));
    const settled = Promise.allSettled(loads);
    await settle();
    f.events.get('gd-auth-changed')();
    assert.equal(f.requests[0].options.signal.aborted, true);
    f.finishBatch(f.requests[0], [{component: 'canvas'}, {component: 'canvas-values'}]);
    assert((await settled).every(result => result.status === 'rejected' && result.reason.name === 'AbortError'));
    assert.equal(f.window.gdUIConfigurationState(), null);
  }
  {
    const f = fixture();
    const loads = ['canvas', 'canvas-values'].map(component => f.window.gdLoadUIComponentRuntime(component, plan));
    await settle();
    f.finish(f.requests[0], {ok: false}, false, 403);
    await settle();
    assert.equal(f.requests.length, 3, 'a batch refusal falls back to independently authorized reads once');
    for (const request of f.requests.slice(1)) {
      assert.deepEqual(Object.keys(JSON.parse(request.options.body)), ['component']);
      f.finish(request, {ok: true, 'selection-id': first, plan});
    }
    await Promise.all(loads);
    assert.equal(f.window.gdUIComponentRuntimeIdentity('canvas'), first);
    assert.equal(f.window.gdUIComponentRuntimeIdentity('canvas-values'), first);
  }
  {
    const f = fixture();
    const components = ['canvas', 'canvas-values', 'canvas-metadata', 'canvas-inheritance',
      'canvas-bindings', 'canvas-type-structure', 'explorer', 'inspector', 'feedback'];
    const loads = components.map(component => f.window.gdLoadUIComponentRuntime(component, plan));
    await settle();
    assert.equal(f.requests.length, 3);
    for (const [index, request] of f.requests.entries()) {
      const names = JSON.parse(request.options.body).components;
      assert.equal(names.length, 3);
      assert.deepEqual(names, components.slice(index * 3, index * 3 + 3));
      f.finishBatch(request, names.map(component => ({component})));
    }
    await Promise.all(loads);
  }
  {
    const f = fixture();
    f.window.gdUIPlanBatchMax = 1;
    const loads = ['canvas', 'canvas-values'].map(component => f.window.gdLoadUIComponentRuntime(component, plan));
    await settle();
    assert.equal(f.requests.length, 2, 'ordinary runtimes retain independent plan requests');
    for (const [index, request] of f.requests.entries()) {
      assert.deepEqual(JSON.parse(request.options.body), {component: ['canvas', 'canvas-values'][index]});
      f.finish(request, {ok: true, 'selection-id': first, plan});
    }
    await Promise.all(loads);
  }
  console.log('PASS personal UI plans: scoped requests, bounded batches, independent cancellation, partial fallback and input contract');
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => clearTimeout(deadline));
