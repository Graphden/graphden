'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const graphRenderer = require('./tour-catalog-renderer-fixture');

const root = path.join(__dirname, '..', '..', 'resources', 'packages', 'app');
const document = createDocument();
const context = vm.createContext({document, console});
context.window = context;
function load(file) { vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context, {filename: file}); }
load('ui-preview/browser-runtime.js');
load('ui-preview/builtin-plans.js');
context.GraphdenRenderer = graphRenderer(document, context.GraphdenBrowser);
context.GraphdenPreact = {render(node, host) {
  host.replaceChildren();
  const rendered = context.GraphdenRenderer.element(node);
  if (rendered) host.appendChild(rendered);
}};
context.gdEditorRoot = {componentNow: () => null};
load('editor/editor-tour-session-graph.js');

const facts = {surface: 'step', lesson: '07', index: 2, total: 4,
  title: 'Find the function', body: 'Click [[Open]] then type `add`.',
  manual: false, 'next-label': '', 'next-note': ''};
const step = context.gdTourSessionGraph.view(facts);
assert.equal(step.head.textContent, 'Lesson 07 · step 2/4');
assert.equal(step.title.id, 'gd-tour-title');
assert.equal(step.body.textContent, facts.body);
assert.equal(step.foot.textContent, 'Advances automatically when done');
assert.equal(step.next, null);

const end = context.gdTourSessionGraph.view({...facts, surface: 'end', manual: true,
  title: 'Lesson finished', 'next-label': 'Next up', 'next-note': 'Cleanup first'});
assert.equal(end.head, null);
assert.equal(end.title.textContent, 'Lesson finished');
assert.equal(end.foot.textContent, '');
assert.equal(end.next.textContent, 'Next upCleanup first');

context.gdEditorRoot.componentNow = () => ({query: true, run: () => new Map()});
assert.throws(() => context.gdTourSessionGraph.view(facts), /Invalid tutorial session view/);
console.log('tour session graph: 10 assertions passed');
