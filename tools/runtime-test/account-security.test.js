// Shipped account security graph keeps action targets bounded and secrets in the host.
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');

const root = path.join(__dirname, '..', '..', 'resources', 'packages');
const document = createDocument();
const ctx = vm.createContext({console, document, Map, Set, WeakSet, Symbol, Date, URLSearchParams});
ctx.window = ctx;
for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js',
  'app/ui-preview/graph-renderer.js', 'app/ui-preview/builtin-plans.js',
  'app/editor/editor-account.js']) {
  vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), ctx, {filename: file});
}

const off = ctx.gdAcctGraphSecurity('tfa', false, []);
const on = ctx.gdAcctGraphSecurity('tfa', true, []);
if (off.querySelector('[data-tfa-action]')?.dataset.tfaAction !== 'enroll'
    || on.querySelector('[data-tfa-action]')?.dataset.tfaAction !== 'disable') {
  throw new Error('Shipped 2FA status graph lost its exact action');
}
let action;
ctx.gdAcctPost = url => { action = url; return Promise.resolve([500, {}]); };
off.querySelector('button').click();
if (action !== '/auth/totp/enroll') throw new Error('2FA action did not reach the host');

const tokens = [{id: 'token-a', label: 'Laptop', scopes: 'read', expiry: '2026-11-01'},
  {id: 'token-b', label: '', scopes: '', expiry: ''}];
const list = ctx.gdAcctGraphSecurity('tokens', false, tokens);
if (list.querySelectorAll('.gd-acct-row').length !== 2
    || list.querySelector('[data-revoke-token="token-b"]')?.dataset.revokeToken !== 'token-b'
    || !list.textContent.includes('(unlabeled)') || !list.textContent.includes('no expiry')
    || !ctx.gdAcctGraphSecurity('tokens', false, []).textContent.includes('No API tokens yet.')) {
  throw new Error('Shipped token graph lost rows or display metadata');
}

let released = 0;
let failed = 0;
ctx.gdEditorRoot = {
  componentNow: () => ({run: () => new Map([[ctx.GraphdenBrowser.keyword('tree'),
    [ctx.GraphdenBrowser.keyword('button'), new Map([[ctx.GraphdenBrowser.keyword('data-revoke-token'), 'wrong']]), 'Revoke']]])}),
  acceptComponent: () => true,
  releaseComponent: () => { released += 1; },
};
ctx.gdUIComponentFailed = () => { failed += 1; };
const fallback = ctx.gdAcctGraphSecurity('tokens', false, tokens);
if (released !== 1 || failed !== 1 || fallback.querySelectorAll('.gd-acct-row').length !== 2) {
  throw new Error('Invalid selected graph did not fall back to shipped security view');
}

const mount = document.createElement('div');
mount.id = 'gd-acct-tfa';
document.body.appendChild(mount);
ctx.gdAccount = {id: 'alice'};
let resolve;
ctx.gdAcctGet = () => new Promise(done => { resolve = done; });
const pending = ctx.gdAcctLoadTfa();
ctx.gdAccount = {id: 'bob'};
resolve([200, {enabled: true}]);
pending.then(() => {
  if (mount.querySelector('[data-tfa-action]')) {
    throw new Error('Late 2FA state crossed the principal boundary');
  }
  console.log('account security graph, controls and principal guard: passed');
}).catch(error => { console.error(error); process.exitCode = 1; });
