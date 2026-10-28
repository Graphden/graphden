// The same shipped graph renders both publish dialogs; the host accepts only
// the expected control set and never takes an endpoint from the graph.
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');

const root = path.join(__dirname, '..', '..', 'resources', 'packages');
const document = createDocument();
const ctx = vm.createContext({console, document, Map, Set, WeakSet, Symbol});
ctx.window = ctx;
for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js',
  'app/ui-preview/graph-renderer.js', 'app/ui-preview/builtin-plans.js',
  'app/editor/editor-publish-graph.js']) {
  vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), ctx, {filename: file});
}

const ns = ctx.gdPublishGraphForm('fns', false, 'my.project');
if (ns.querySelector('#gd-nspub-name')?.tagName !== 'INPUT'
    || ns.querySelector('#gd-nspub-public')
    || ns.querySelector('#gd-nspub-go')?.textContent !== 'Publish'
    || !ns.textContent.includes('my.project')) {
  throw new Error('Namespace publish graph lost required controls');
}
const privateNs = ctx.gdPublishGraphForm('fns', true, 'my.project');
if (privateNs.querySelector('#gd-nspub-public')?.getAttribute('type') !== 'checkbox') {
  throw new Error('Tenant namespace publish graph lost public opt-in');
}
for (const kind of ['theme', 'keymap']) {
  const form = ctx.gdPublishGraphForm(kind, true);
  if (form.querySelector('#gd-mkpub-name')?.tagName !== 'INPUT'
      || form.querySelector('#gd-mkpub-public')?.getAttribute('type') !== 'checkbox'
      || form.querySelector('#gd-mkpub-cancel')?.tagName !== 'BUTTON'
      || form.querySelector('#gd-mkpub-go')?.textContent !== 'Save') {
    throw new Error(kind + ' publish graph lost required controls');
  }
}

let released = 0;
let failed = 0;
ctx.gdEditorRoot = {
  componentNow: () => ({run: () => new Map([[ctx.GraphdenBrowser.keyword('tree'),
    [ctx.GraphdenBrowser.keyword('button'), new Map([[ctx.GraphdenBrowser.keyword('id'), 'evil']]), 'Publish']]])}),
  acceptComponent: () => true,
  releaseComponent: () => { released += 1; },
};
ctx.gdUIComponentFailed = () => { failed += 1; };
const fallback = ctx.gdPublishGraphForm('theme', false);
if (released !== 1 || failed !== 1 || !fallback.querySelector('#gd-mkpub-go')) {
  throw new Error('Invalid selected graph did not fall back to the shipped form');
}
console.log('publish form graph and bounded controls: passed');
