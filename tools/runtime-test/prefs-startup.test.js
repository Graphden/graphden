'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const editor = path.join(__dirname, '../../resources/packages/app/editor');
const settle = () => new Promise(setImmediate);
function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return {promise, resolve};
}
function fixture() {
  const events = new Map();
  const requests = [];
  const notifications = [];
  const mirror = new Map();
  const reloads = {root: 0, explorer: 0};
  const style = {setProperty() {}, removeProperty() {}, getPropertyValue() { return ''; }};
  const window = {
    API: {api_prefs: '/api/prefs'}, innerWidth: 1200,
    gdEditorRoot: {reload() { reloads.root++; }},
    gdExplorerGraph: {reload() { reloads.explorer++; }},
    authFetch: () => { const request = deferred(); requests.push(request); return request.promise; },
    addEventListener(name, callback) {
      if (!events.has(name)) events.set(name, []);
      events.get(name).push(callback);
    },
    dispatchEvent(event) { for (const callback of events.get(event.type) || []) callback(event); },
  };
  const context = vm.createContext({window, Event, AbortController, console, queueMicrotask,
    setTimeout, clearTimeout, getCurrentBranchName: () => 'main',
    isAuthenticated: () => window.authenticated === true,
    document: {
      body: {style, classList: {toggle() {}, contains() { return false; }}},
      documentElement: {style},
      getElementById: id => id === 'sidebar-expand-floating' ? {} : null,
      addEventListener() {},
    },
    localStorage: {getItem: key => mirror.get(key) ?? null, setItem: (key, value) => mirror.set(key, value)},
    getComputedStyle: () => ({getPropertyValue: () => ''}),
  });
  // Load the real theme listener too: it must invalidate old theme work without
  // launching a second preference request beside the preference lifecycle.
  for (const file of ['editor-prefs.js', 'editor-theme-graph.js', 'editor-ui-components.js']) {
    vm.runInContext(fs.readFileSync(path.join(editor, file), 'utf8'), context, {filename: file});
  }
  window.gdPrefOnChange((key, value, detail) => notifications.push({key, value, detail}));
  return {window, requests, notifications, reloads,
    authChanged() { window.dispatchEvent(new Event('gd-auth-changed')); },
    components() { return notifications.filter(event => event.key === 'components'); },
    reply(index, prefs = {}, owner = 'owner-a') {
      requests[index].resolve({ok: true, headers: {get: () => owner}, json: async () => prefs});
    },
    async refresh(prefs, owner = 'owner-a') {
      const result = window.gdPrefsRefresh();
      this.reply(requests.length - 1, prefs, owner);
      await result;
    },
  };
}

test('account discovery and boot issue one preference GET after identity is known', async () => {
  const f = fixture();
  const auth = deferred();
  f.window.gdAccountsReady = auth.promise;
  f.window.initPrefsLate();
  f.authChanged();
  f.notifications.length = 0;
  await settle();
  assert.equal(f.requests.length, 0, 'no anonymous startup request before account discovery');
  auth.resolve(true);
  await settle();
  assert.equal(f.requests.length, 1, 'theme and boot share the current identity refresh');
  assert.equal(f.window.gdPrefsReady, false);
  f.reply(0);
  await f.window.gdPrefsInitialReady;
  assert.equal(f.window.gdPrefsReady, true, 'the first empty map still establishes readiness');
  assert.equal(f.window.gdPrefOwner, 'owner-a');
  assert.equal(f.components().length, 1);
  assert.equal(f.components()[0].detail.initial, true, 'initial result must not abort the waiting root plan');
});

test('new identity starts fresh immediately and owns initial readiness despite the old pending GET', async () => {
  const f = fixture();
  f.window.initPrefsLate();
  let ready = false;
  f.window.gdPrefsInitialReady.then(() => { ready = true; });
  await settle();
  assert.equal(f.requests.length, 1);
  f.authChanged();
  await settle();
  assert.equal(f.requests.length, 2, 'new account does not wait for or reuse the old request');
  f.reply(0, {components: {'fn-id': 'old-selection'}}, 'old-owner');
  await settle();
  assert.equal(ready, false, 'stale completion cannot release initial root readiness');
  assert.equal(f.window.gdPrefRead('components'), null);
  f.reply(1, {}, 'new-owner');
  await f.window.gdPrefsInitialReady;
  assert.equal(f.window.gdPrefOwner, 'new-owner');
  assert.equal(f.window.gdPrefRead('components'), null);
});

test('late old-account response cannot replace a completed new-account refresh', async () => {
  const f = fixture();
  f.window.initPrefsLate();
  await settle();
  f.authChanged();
  await settle();
  f.reply(1, {components: {'fn-id': 'new-selection'}}, 'new-owner');
  await f.window.gdPrefsInitialReady;
  f.reply(0, {components: {'fn-id': 'old-selection'}}, 'old-owner');
  await settle();
  assert.equal(f.window.gdPrefOwner, 'new-owner');
  assert.equal(f.window.gdPrefRead('components')['fn-id'], 'new-selection');
});

test('offline and deployments without preference API still release initial readiness', async () => {
  for (const available of [true, false]) {
    const f = fixture();
    if (!available) delete f.window.API;
    f.window.initPrefsLate();
    await settle();
    if (available) f.requests[0].resolve({ok: false});
    await f.window.gdPrefsInitialReady;
    assert.equal(f.window.gdPrefsReady, false);
    assert.equal(f.components().length, 0);
  }
});

test('later sign-in refreshes preferences and sign-out invalidates without another GET', async () => {
  const f = fixture();
  f.window.initPrefsLate();
  await settle();
  f.reply(0);
  await f.window.gdPrefsInitialReady;
  f.window.authenticated = true;
  f.authChanged();
  await settle();
  assert.equal(f.requests.length, 2);
  f.reply(1, {components: {'fn-id': 'new-selection'}}, 'signed-in-owner');
  await settle();
  assert.equal(f.window.gdPrefOwner, 'signed-in-owner');
  f.window.authenticated = false;
  f.authChanged();
  await settle();
  assert.equal(f.requests.length, 2, 'sign-out preserves the existing no-refresh behavior');
  assert.equal(f.window.gdPrefsReady, false);
  assert.equal(f.window.gdPrefRead('components'), null);
});

test('explicit refresh revalidates unchanged component selection and theme after source or authority changes', async () => {
  const f = fixture();
  const themes = [];
  f.window.gdApplyThemeGraphPreference = value => { themes.push(value); };
  const first = {'fn-id': 'selection', config: {a: 1, b: [1, 2]}};
  const theme = {graph: {'fn-id': 'theme'}};
  await f.refresh({components: first, theme});
  const initialRootReloads = f.reloads.root;
  await f.refresh({components: {config: {b: [1, 2], a: 1}, 'fn-id': 'selection'}, theme: {...theme}});
  assert.equal(f.requests.length, 2, 'explicit refresh is never memoized');
  assert.equal(f.components().length, 2, 'equal selection does not certify unchanged source or authority');
  assert.equal(f.reloads.root, initialRootReloads + 1, 'the real component listener revalidates the root');
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(f.reloads.explorer, 1, 'coalesced component reload still reaches the Explorer');
  assert.equal(themes.length, 2, 'theme still reauthorizes the fresh server object');
  assert.notEqual(themes[0], themes[1]);
  await f.refresh({components: {'fn-id': 'selection', config: {a: 1, b: [2, 1]}}});
  assert.equal(f.components().length, 3, 'array order changes propagate');
  await f.refresh({});
  assert.equal(f.components().length, 4, 'removing a selected configuration propagates');
  await f.refresh({});
  assert.equal(f.components().length, 5, 'unchanged empty selection still revalidates current server defaults');
  await f.refresh({}, 'owner-b');
  assert.equal(f.components().length, 6, 'server owner transition propagates even for an empty selection');
});
