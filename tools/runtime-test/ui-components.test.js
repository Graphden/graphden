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
    Event, DOMException, setTimeout, clearTimeout});
  return {window, requests, events, calls, choose: value => { chosen = value; }, finish(request, body, ok = true) {
    request.resolve({ok, json: async () => body});
  }};
}
(async () => {
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
