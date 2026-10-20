'use strict';

// Logout invalidates an authenticated export even if its response arrives late.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-shell-menu-graph.js'), 'utf8');
const events = new Map();
let reply;
let created = 0;
const ctx = vm.createContext({
  URLSearchParams, Map, console, AbortController,
  location: {search: '?branch=review&ui-initial=11111111-1111-1111-1111-111111111111&ui-update=22222222-2222-2222-2222-222222222222&ui-view=33333333-3333-3333-3333-333333333333'},
  document: {addEventListener() {}},
  getCurrentBranchName: () => 'review',
  isAuthenticated: () => false,
  accountsAuthed: false,
});
ctx.window = ctx;
ctx.gdClearGraphTheme = () => {};
ctx.addEventListener = (name, callback) => events.set(name, callback);
ctx.authFetch = () => new Promise((resolve) => { reply = resolve; });
ctx.GraphdenBrowser = {keyword: (name) => name,
  createRuntime() { created++; throw new Error('Logged-out response must never initialize a runtime'); }};
vm.runInContext(script, ctx);
function readinessFixture(review, failTheme = false) {
  let accepts = 0;
  const node = (type, props, key) => ({type, props, key});
  const rows = ['settings', 'operate'].map((action, index) => node('button', {
    className: 'auth-menu-item', role: 'menuitem', type: 'button', tabIndex: -1,
    'data-action': action, 'data-item': ['Settings', 'Organization'][index], children: 'Label',
  }, action));
  const output = new Map([
    ['frame', node('div', {className: 'auth-menu', role: 'menu'})],
    ['common-tree', node('div', {className: 'auth-menu-common', children: rows})],
    ['styles', []], ['theme-tokens', new Map()], ['menu-tokens', new Map([['--gd-account-menu-hover', 'blue']])],
    ['motion', new Map([['duration-ms', 0], ['offset-y', 0], ['opacity', 0]])],
    ['phase', 'closed'], ['active', -1], ['handled', false],
  ]);
  const runtime = {run: entry => entry === 'view' ? output : new Map()};
  const sandbox = vm.createContext({URLSearchParams, Map, AbortController, console,
    location: {search: review ? ctx.location.search : ''}, document: {addEventListener() {}},
    getCurrentBranchName: () => 'review', isAuthenticated: () => true, accountsAuthed: true,
    addEventListener() {}, gdClearGraphTheme() {}, gdGraphThemeBase: () => ({}),
    gdSanitizeThemePayload: value => value,
    gdApplyGraphTheme() { if (failTheme) throw new Error('theme failed'); },
    authFetch: async () => ({ok: true, json: async () => ({})}),
    GraphdenBrowser: {keyword: name => name, createRuntime: () => runtime},
    GraphdenRenderer: {vnode: value => value}, GraphdenStyles: {normalize() {}},
    gdEditorRoot: {component: async () => runtime,
      acceptComponent(value) { assert.equal(value, runtime); accepts++; }, releaseComponent() {}},
  });
  sandbox.window = sandbox;
  vm.runInContext(script, sandbox);
  return {sandbox, accepts: () => accepts};
}
(async () => {
  const loading = ctx.gdShellMenuGraph.reload();
  assert.equal(typeof reply, 'function');
  events.get('gd-auth-changed')();
  reply({ok: true, json: async () => ({entries: {}})});
  await loading;
  assert.equal(created, 0);
  assert.equal(ctx.gdShellMenuGraph.ready, false);
  assert.equal(ctx.gdShellMenuGraph.runtime, null);
  for (const review of [false, true]) {
    const f = readinessFixture(review);
    await f.sandbox.gdShellMenuGraph.reload();
    assert.equal(f.sandbox.gdShellMenuGraph.ready, true);
    assert.equal(f.accepts(), review ? 0 : 1, 'preview readiness cannot claim the root selection');
  }
  const failed = readinessFixture(false, true);
  await failed.sandbox.gdShellMenuGraph.reload();
  assert.equal(failed.sandbox.gdShellMenuGraph.ready, false);
  assert.equal(failed.accepts(), 0, 'theme/render failure cannot accept a root facade');
  console.log('PASS late menu graph response after logout is ignored');
})().catch((error) => { console.error(error); process.exitCode = 1; });
