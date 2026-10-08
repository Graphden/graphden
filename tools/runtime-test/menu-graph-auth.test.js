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
(async () => {
  const loading = ctx.gdShellMenuGraph.reload();
  assert.equal(typeof reply, 'function');
  events.get('gd-auth-changed')();
  reply({ok: true, json: async () => ({entries: {}})});
  await loading;
  assert.equal(created, 0);
  assert.equal(ctx.gdShellMenuGraph.ready, false);
  assert.equal(ctx.gdShellMenuGraph.runtime, null);
  console.log('PASS late menu graph response after logout is ignored');
})().catch((error) => { console.error(error); process.exitCode = 1; });
