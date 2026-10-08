'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const prefsSource = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-prefs.js'), 'utf8');
const componentsSource = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-ui-components.js'), 'utf8');
const first = {'fn-id': '11111111-1111-1111-1111-111111111111', 'branch-id': '33333333-3333-3333-3333-333333333333', org: 'org-a'};
const second = {...first, 'fn-id': '22222222-2222-2222-2222-222222222222'};
const plan = {entries: {view: 'view'}, inputs: {view: {accepted: []}}};
function fixture(initial = null) {
  let server = initial;
  let mirror = JSON.stringify({components: initial});
  const writes = [], plans = [], events = new Map(), loaded = [];
  const window = {API: {api_prefs_key: key => '/prefs/' + key, api_ui_components_plan: '/plan'},
    addEventListener: (name, fn) => events.set(name, fn), dispatchEvent() {},
    gdApplyThemeGraphPreference() {},
    GraphdenBrowser: {createRuntime: () => ({run() {}})},
    authFetch(route, options) {
      if (route.startsWith('/prefs/')) return new Promise((resolve, reject) => writes.push({resolve, reject, value: JSON.parse(options.body).value}));
      plans.push(server);
      return Promise.resolve({ok: !!server, json: async () => server
        ? {ok: true, 'selection-id': server['fn-id'], plan} : {ok: false}});
    },
  };
  const context = vm.createContext({window, graphdenCurrentOrg: 'org-a', document: {addEventListener() {}},
    localStorage: {getItem: () => mirror, setItem: (_, value) => { mirror = value; }},
    setTimeout, clearTimeout, Event, DOMException});
  vm.runInContext(prefsSource, context);
  window.gdPrefsReady = true;
  window.gdPrefOwner = 'owner-a';
  vm.runInContext(componentsSource, context);
  window.gdPrefOnChange(key => {
    if (key === 'components') for (const component of ['account-menu', 'fn-picker'])
      loaded.push(window.gdLoadUIComponentRuntime(component, plan));
  });
  return {window, writes, plans, loaded, events, mirror: () => JSON.parse(mirror),
    complete(index, ok = true) { if (ok) server = writes[index].value; writes[index].resolve({ok}); }};
}
(async () => {
  {
    const f = fixture();
    const writing = f.window.gdPrefWrite('components', first);
    await new Promise(resolve => setTimeout(resolve, 5));
    assert.equal(f.plans.length, 0, 'a pending first selection never asks the server for a nonexistent plan');
    assert.equal(f.window.gdPrefRead('components'), null);
    assert.equal(f.mirror().components, null, 'pending identities do not leak into the reload mirror');
    f.complete(0);
    assert.equal(await writing, true);
    await Promise.all(f.loaded);
    assert.equal(f.plans.length, 2);
    for (const component of ['account-menu', 'fn-picker']) assert.equal(f.window.gdUIComponentRuntimeIdentity(component), first['fn-id']);
  }
  for (const transportError of [false, true]) {
    const f = fixture(first);
    const writing = f.window.gdPrefWrite('components', second);
    if (transportError) f.writes[0].reject(new Error('offline')); else f.complete(0, false);
    assert.equal(await writing, false);
    assert.equal(f.window.gdPrefRead('components')['fn-id'], first['fn-id'], 'rejection preserves the previous selection');
    assert.equal(f.mirror().components['fn-id'], first['fn-id']);
    assert.equal(f.plans.length, 0, 'failure cannot publish an unsaved graph');
  }
  {
    const f = fixture();
    const older = f.window.gdPrefWrite('components', first);
    const newer = f.window.gdPrefWrite('components', second);
    f.complete(1);
    assert.equal(await newer, true);
    await Promise.all(f.loaded);
    f.complete(0);
    assert.equal(await older, false, 'a late superseded response cannot publish the old identity');
    assert.equal(f.window.gdPrefRead('components')['fn-id'], second['fn-id']);
    assert.equal(f.plans.length, 2);
  }
  {
    const f = fixture();
    const writing = f.window.gdPrefWrite('components', first);
    f.events.get('gd-auth-changed')();
    f.complete(0);
    assert.equal(await writing, false);
    assert.equal(f.window.gdPrefRead('components'), null, 'an old account reply cannot restore its selection');
  }
  {
    const f = fixture();
    const writing = f.window.gdPrefWrite('keymap', {payload: {}});
    assert.notEqual(f.window.gdPrefRead('keymap'), null, 'ordinary tab preferences remain immediate');
    f.complete(0);
    assert.equal(await writing, true);
  }
  console.log('PASS component preference timing: pending plan isolation, commit activation, rejection, supersession, account change');
})().catch(error => { console.error(error); process.exitCode = 1; });
