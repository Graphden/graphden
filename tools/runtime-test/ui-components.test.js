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
    gdPrefsReady: true, gdPrefOwner: 'owner-a', API: {api_ui_configuration_plan: '/api/ui/configuration/plan'},
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
    getCurrentBranchName: () => currentBranch, performance: {now: () => clock}, Event, DOMException,
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
  }};
}
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
  const waiting = {ok: false, code: 'policy-refresh-required', retryable: true, 'retry-after': 1};
  const settle = () => new Promise(resolve => setImmediate(resolve));
  {
    const f = fixture();
    const personal = f.window.gdLoadUIComponentRuntime('recents', plan);
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await personal;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('recents'), first);
    const missing = f.window.gdLoadUIComponentRuntime('recents', plan);
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
    f.finish(f.requests[0], body, false, status);
    await loading;
    assert.match(f.window.gdUIComponentsStatus(), /unavailable/, 'only the classified missing-component response is silent');
  }
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
  for (const capture of [false, true]) {
    const f = fixture();
    const controller = new AbortController();
    const loading = capture ? f.window.gdCaptureUIConfiguration(controller.signal)
      : f.window.gdLoadUIComponentRuntime('fn-picker', plan, {}, controller.signal);
    for (let attempt = 0; attempt < 4; attempt++) {
      f.finish(f.requests[attempt], waiting, false);
      await settle();
      await f.advance();
    }
    f.finish(f.requests[4], {ok: true, 'selection-id': first, plan});
    await loading;
    assert.equal(capture ? f.window.gdUIConfigurationState().selection['fn-id']
      : f.window.gdUIComponentRuntimeIdentity('fn-picker'), first,
      'one current load recovers when a write burst outlasts three attempts');
    assert(f.requests.every(request => request.options.signal === controller.signal),
      'all retries retain the same owned AbortSignal');
  }
  for (const capture of [false, true]) {
    const f = fixture();
    const loading = capture ? f.window.gdCaptureUIConfiguration()
      : f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    const rejected = capture ? assert.rejects(loading, /unavailable/) : null;
    f.elapse(29500);
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
    f.elapse(31000);
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await loading;
    assert.equal(f.window.gdUIComponentRuntimeIdentity('fn-picker'), first,
      'a current successful response remains valid after the retry admission window');
  }
  {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    f.elapse(30000);
    f.finish(f.requests[0], waiting, false);
    await loading;
    assert.equal(f.requests.length, 1);
    assert.equal(f.timers.size, 0, 'an already expired retry window schedules no wait');
    assert.match(f.window.gdUIComponentsStatus(), /Using built-in/);
  }
  {
    const f = fixture();
    const loading = f.window.gdCaptureUIConfiguration();
    const rejected = assert.rejects(loading, {name: 'AbortError'});
    f.elapse(31000);
    f.window.gdPrefOwner = 'owner-b';
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await rejected;
    assert.equal(f.window.gdUIConfigurationState(), null, 'late capture never adopts an old owner');
  }
  for (const [body, status] of [[{ok: false}, 422], [{...waiting, code: 'invalid-selection'}, 422],
    [{...waiting, retryable: false}, 422], [waiting, 401], [waiting, 403], [waiting, 500]]) {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('fn-picker', plan);
    f.finish(f.requests[0], body, false, status);
    await loading;
    assert.equal(f.requests.length, 1, 'permanent or unclassified refusals never retry');
    assert.equal(f.timers.size, 0);
  }
  for (const change of [{branch: 'other'}, {org: 'org-b'}]) {
    const f = fixture();
    const loading = f.window.gdLoadUIComponentRuntime('account-menu', plan);
    const rejected = assert.rejects(loading, {name: 'AbortError'});
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
    if (malformed) f.requests[0].resolve({json: async () => { throw new SyntaxError('invalid JSON'); }});
    else f.requests[0].reject(new TypeError('network failure'));
    await loading;
    assert.equal(f.requests.length, 1, 'network or JSON failures are not policy retries');
    assert.equal(f.timers.size, 0);
    assert.match(f.window.gdUIComponentsStatus(), /Using built-in/);
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
    assert.equal(f.requests[0].route, '/api/ui/configuration/plan');
    assert.equal(f.requests[0].options.headers['X-Graphden-Branch'], undefined, 'current branch wrapper owns request context');
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
    f.window.gdPrefOwner = 'owner-b';
    f.events.get('gd-auth-changed')();
    f.window.gdPrefOwner = 'owner-a';
    f.events.get('gd-auth-changed')();
    f.finish(f.requests[0], {ok: true, 'selection-id': first, plan});
    await assert.rejects(loading, {name: 'AbortError'}, 'A→B→A never adopts the earlier authenticated snapshot');
    assert.equal(f.window.gdUIConfigurationState(), null);
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
    f.finish(f.requests[0], {ok: true, 'selection-id': first,
      'configuration-revision': 'not-a-source-revision', plan});
    await loading;
    assert.match(f.window.gdUIComponentsStatus(), /unavailable/);
    assert.equal(f.window.gdUIConfigurationState(), null, 'invalid source metadata never publishes configuration state');
  }
  console.log('PASS personal UI plans: scoped request, late reply rejection, fallback and input contract');
})().catch(error => { console.error(error); process.exitCode = 1; });
