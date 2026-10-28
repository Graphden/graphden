'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');

const ROOT = path.join(__dirname, '..', '..', 'resources', 'packages');

function load() {
  const document = createDocument();
  const ctx = vm.createContext({document, console, Map, Set});
  ctx.window = ctx;
  for (const file of [
    'web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js',
    'app/ui-preview/graph-renderer.js', 'app/ui-preview/builtin-plans.js',
    'app/editor/editor-authoring-graph.js',
  ]) vm.runInContext(fs.readFileSync(path.join(ROOT, file), 'utf8'), ctx, {filename: file});
  return ctx;
}

const choices = [
  {key: 'root', path: '(root)'},
  {key: 'a-uuid', path: 'app.alpha'},
  {key: 'b-uuid', path: 'core.beta'},
];
const assert = (condition, message) => { if (!condition) throw new Error(message); };

const ctx = load();
const matching = ctx.gdAuthoringNamespaceRows(choices, '  APP ', '');
const rows = [...matching.querySelectorAll('.fn-picker-row')];
assert(rows.length === 1 && rows[0].dataset.nsKey === 'a-uuid', 'graph filters paths without losing identity');
assert(rows[0].textContent === 'app.alpha', 'graph presents the matched path');
assert(ctx.gdAuthoringNamespaceRows(choices, 'missing', '').textContent === 'No matches', 'empty search is explicit');

const createShell = ctx.gdAuthoringTypeShell('record', false, 'app.types', 'user', 'A person');
assert(createShell.querySelector('.type-create-title')?.textContent === 'New record in app.types',
  'graph names the create surface and its namespace');
assert(createShell.querySelectorAll('.type-create-tab').length === 5
  && createShell.querySelector('.type-create-tab-active')?.dataset.kind === 'record',
  'graph presents all type kinds and marks the selected kind');
assert(createShell.querySelector('.type-create-extra-host') != null,
  'native kind editor has a distinct insertion host');
const editShell = ctx.gdAuthoringTypeShell('record', true, '', 'user', 'A person');
assert(editShell.querySelectorAll('.type-create-tab').length === 0
  && editShell.querySelector('.type-create-title')?.textContent === 'Edit record',
  'editing retains the type kind and has no conversion tabs');
const inline = ctx.gdAuthoringInlineRow('New graph name', 'sample');
assert(inline.querySelector('input.inline-input')?.getAttribute('placeholder') === 'New graph name'
  && inline.querySelectorAll('button').length === 2,
  'graph supplies the reusable create and rename row');
const menu = ctx.gdAuthoringCreateMenu();
assert([...menu.querySelectorAll('.create-menu-item')].map(item => item.dataset.type).join(',') === 'ns,fn,type',
  'graph supplies the bounded namespace, function and type create actions');
const chooser = ctx.gdAuthoringNamespaceChooser(choices);
assert([...chooser.querySelectorAll('option')].map(option => option.getAttribute('value')).join(',') === ',root,a-uuid,b-uuid',
  'graph presents exact namespace identities in the function edit chooser');

let released = 0;
ctx.gdEditorRoot = {
  componentNow: () => ({run: () => new Map([[ctx.GraphdenBrowser.keyword('tree'), null]])}),
  acceptComponent: () => true,
  releaseComponent: () => { released += 1; },
};
const fallback = ctx.gdAuthoringNamespaceRows(choices, 'core', 'b-uuid');
assert(released === 1, 'invalid selected component is released');
assert(fallback.querySelector('.fn-picker-row')?.dataset.nsKey === 'b-uuid',
  'builtin fallback retains exact authorized candidate');

console.log('authoring graph: namespace choices, create menu, type shell and invalid selected fallback passed');
