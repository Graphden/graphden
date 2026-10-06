'use strict';

// A cookie session resolves after DOMContentLoaded. Graph UI integrations need
// the same auth event as bearer login; repeated probes must not reset them.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-auth.js'), 'utf8');
const start = source.indexOf('async function probeAccountsAuth()');
const end = source.indexOf('// Authenticated iff', start);
const events = [];
let reply = {status: 200, ok: true, account: {id: 'test-account'}};
const ctx = vm.createContext({
  accountsMode: false, accountsAuthed: false, Event,
  renderAuthLock() {},
  fetch: async () => ({status: reply.status, ok: reply.ok,
    headers: {get: () => 'application/json'}, json: async () => reply}),
});
ctx.window = ctx;
ctx.dispatchEvent = event => events.push(event.type);
vm.runInContext(source.slice(start, end), ctx);
(async () => {
  await ctx.probeAccountsAuth();
  assert.deepEqual(events, ['gd-auth-changed']);
  assert.equal(ctx.accountsAuthed, true);
  await ctx.probeAccountsAuth();
  assert.equal(events.length, 1);
  reply = {status: 401, ok: false, error: 'unauthenticated'};
  await ctx.probeAccountsAuth();
  assert.deepEqual(events, ['gd-auth-changed', 'gd-auth-changed']);
  assert.equal(ctx.accountsAuthed, false);
  console.log('PASS cookie authentication changes notify graph UI once');
})().catch(error => {console.error(error); process.exitCode = 1;});
