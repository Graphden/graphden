// Shipped account methods graph and the host's bounded action targets.
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');

const root = path.join(__dirname, '..', '..', 'resources', 'packages');
const document = createDocument();
const ctx = vm.createContext({console, document, Map, Set, WeakSet, Symbol, Date, URLSearchParams});
ctx.window = ctx;
ctx.location = {href: '/'};
for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js',
  'app/ui-preview/graph-renderer.js', 'app/ui-preview/builtin-plans.js',
  'app/editor/editor-account.js']) {
  vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), ctx, {filename: file});
}

const identities = [
  {provider: 'password', email: 'one@example.test', verified: false, 'can-unlink': true},
  {provider: 'github', email: 'two@example.test', verified: true, 'can-unlink': true},
];
const view = ctx.gdAcctGraphMethods(identities, ['github', 'google']);
if (view.querySelectorAll('.gd-acct-row').length !== 2
    || view.querySelectorAll('[data-unlink-provider]').length !== 2
    || view.querySelectorAll('[data-link-provider]').length !== 2
    || !view.textContent.includes('unverified')
    || !view.textContent.includes('Link GitHub')
    || !view.textContent.includes('Link Google')) {
  throw new Error('Shipped sign-in methods graph lost rows, unlink targets or status');
}
view.querySelector('[data-link-provider="google"]').click();
if (ctx.location.href !== '/auth/google/start') throw new Error('OAuth link did not use the fixed host route');
let unlinked;
ctx.gdAcctUnlink = provider => { unlinked = provider; };
view.querySelector('[data-unlink-provider="github"]').click();
if (unlinked !== 'github') throw new Error('Unlink action lost its validated provider');

let released = 0;
let failed = 0;
ctx.gdEditorRoot = {
  componentNow: () => ({run: () => new Map([[ctx.GraphdenBrowser.keyword('tree'),
    [ctx.GraphdenBrowser.keyword('a'), new Map(), 'bad']]])}),
  acceptComponent: () => true,
  releaseComponent: () => { released += 1; },
};
ctx.gdUIComponentFailed = () => { failed += 1; };
const fallback = ctx.gdAcctGraphMethods(identities, ['github']);
if (released !== 1 || failed !== 1
    || fallback.querySelectorAll('[data-link-provider]').length !== 1) {
  throw new Error('Invalid selected graph did not fall back to shipped account methods');
}
console.log('account methods graph and action targets: passed');

const mount = document.createElement('div');
mount.id = 'gd-acct-methods-mount';
document.body.appendChild(mount);
ctx.gdAccount = {id: 'alice'};
const answers = [];
ctx.gdAcctGet = () => new Promise(resolve => { answers.push(resolve); });
const pending = ctx.gdAcctLoadMethods();
ctx.gdAccount = {id: 'bob'};
answers[0]([200, {identities: [{provider: 'password', email: 'alice@example.test'}]}]);
answers[1]([200, {providers: {github: true}}]);
pending.then(() => {
  if (mount.querySelector('.gd-acct-row') || mount.textContent.includes('alice@example.test')) {
    throw new Error('Late account response crossed the principal boundary');
  }
  console.log('account methods late principal response: passed');
}).catch(error => { console.error(error); process.exitCode = 1; });
