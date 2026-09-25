'use strict';

// editor-inline-row.js — an open inline create / rename row survives an
// Explorer rebuild. The tree is rebuilt on the network's schedule too (a
// namespace's fns landing after an expand, a cache prime, the auth probe);
// before, a rename row vanished mid-typing and a create row came back EMPTY,
// so Enter answered "Name required" for a name the user had typed.
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument, MiniElement } = require('./mini-dom');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-inline-row.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

const doc = createDocument();
MiniElement.prototype.focus = function focus() { doc.activeElement = this; };
MiniElement.prototype.setSelectionRange = function setSel(a, b) {
  this.selectionStart = a;
  this.selectionEnd = b;
};
const ctx = vm.createContext({ console, document: doc, setTimeout: () => 0 });
vm.runInContext(SRC, ctx);

function el(tag, cls, attrs) {
  const e = doc.createElement(tag);
  if (cls) e.className = cls;
  for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
  return e;
}
const newRow = () => ctx.buildInlineInputRow({
  placeholder: 'x', onSubmit: async () => {}, onCancel: () => {},
});
const inputOf = (row) => row.querySelector('.inline-input');

// A tree: one namespace header + its children group, holding `extra`.
function tree(list, nsPath, extra) {
  const header = el('div', 'ns-header', { 'data-ns-path': nsPath });
  header.appendChild(el('span', 'ns-label'));
  header.appendChild(el('span', 'ns-row-actions'));
  const group = el('div', 'ns-children');
  const item = el('div', 'entity-item', { 'data-fn-id': 'f-1' });
  item.appendChild(el('span', 'name'));
  group.appendChild(item);
  if (extra) group.appendChild(extra);
  list.appendChild(header);
  list.appendChild(group);
  return { header, group, item };
}
function rebuild(list, nsPath, extra) {
  list.replaceChildren();
  return tree(list, nsPath, extra);
}

console.log(' a create row keeps what was typed through a rebuild');
{
  const list = el('div');
  const row = newRow();
  tree(list, 'a.b', row);
  inputOf(row).value = 'adder';
  inputOf(row).focus();
  inputOf(row).setSelectionRange(2, 3);
  const kept = ctx.gdKeepInlineRow(list);
  doc.activeElement = null;                 // the teardown drops focus
  const fresh = newRow();                   // the create marker re-injects one
  rebuild(list, 'a.b', fresh);
  ctx.gdRestoreInlineRow(list, kept);
  const rows = list.querySelectorAll('.inline-input-row');
  assert(rows.length === 1 && rows[0] === row, 'the SAME row is back, the fresh one gone');
  assert(inputOf(row).value === 'adder', 'the typed text is kept');
  assert(doc.activeElement === inputOf(row), 'focus is back in the input');
  assert(inputOf(row).selectionStart === 2 && inputOf(row).selectionEnd === 3, 'and the caret');
}

console.log(' a create row that ended is not resurrected');
{
  const list = el('div');
  tree(list, 'a.b', newRow());
  const kept = ctx.gdKeepInlineRow(list);
  rebuild(list, 'a.b', null);               // no create marker → no fresh row
  ctx.gdRestoreInlineRow(list, kept);
  assert(list.querySelectorAll('.inline-input-row').length === 0, 'no row after the create ended');
}

console.log(' a namespace rename row moves into the rebuilt header');
{
  const list = el('div');
  const { header } = tree(list, 'a.b', null);
  const row = newRow();
  ctx.gdMarkRenameRow(row, '.ns-header[data-ns-path="a.b"]', '.ns-label, .ns-row-actions');
  header.appendChild(row);
  inputOf(row).value = 'renamed';
  const kept = ctx.gdKeepInlineRow(list);
  const rebuilt = rebuild(list, 'a.b', null);
  ctx.gdRestoreInlineRow(list, kept);
  assert(row.parentNode === rebuilt.header, 'the row sits in the NEW header');
  assert(inputOf(row).value === 'renamed', 'with its text');
  assert(rebuilt.header.querySelector('.ns-label').style.display === 'none',
    'and the new label it replaces is hidden');
}

console.log(' a graph rename row moves into the rebuilt row');
{
  const list = el('div');
  const { item } = tree(list, 'a.b', null);
  const row = newRow();
  ctx.gdMarkRenameRow(row, '.entity-item[data-fn-id="f-1"]', '.name');
  item.appendChild(row);
  const kept = ctx.gdKeepInlineRow(list);
  const rebuilt = rebuild(list, 'a.b', null);
  ctx.gdRestoreInlineRow(list, kept);
  assert(row.parentNode === rebuilt.item, 'the row sits in the NEW fn row');
  assert(rebuilt.item.querySelector('.name').style.display === 'none', 'its name hidden');
}

console.log(' a rename whose row the rebuild no longer shows is dropped');
{
  const list = el('div');
  const { header } = tree(list, 'a.b', null);
  const row = newRow();
  ctx.gdMarkRenameRow(row, '.ns-header[data-ns-path="a.b"]', '.ns-label');
  header.appendChild(row);
  const kept = ctx.gdKeepInlineRow(list);
  rebuild(list, 'other', null);
  ctx.gdRestoreInlineRow(list, kept);
  assert(list.querySelectorAll('.inline-input-row').length === 0, 'nothing re-mounted');
}

console.log(' a row its flow removed (cancel / committed) is not kept');
{
  const list = el('div');
  const { header } = tree(list, 'a.b', null);
  const row = newRow();
  header.appendChild(row);
  row.remove();
  assert(ctx.gdKeepInlineRow(list) === null, 'nothing to keep');
}

console.log(' updateEntityList brackets its rebuild with keep / restore');
{
  const SIDEBAR = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
    'app', 'editor', 'editor-sidebar.js'), 'utf8');
  const d = createDocument();
  const list = d.createElement('div');
  list.id = 'entity-list';
  d.body.appendChild(list);
  Object.defineProperty(list, 'innerHTML', { get() { return this._html || ''; }, set(v) { this._html = v; } });
  const calls = [];
  const token = { kept: true };
  const sctx = vm.createContext({
    console, Promise, document: d,
    graphData: { namespaces: [], fns: [] },
    setTimeout: () => 0, clearTimeout() {},
    searchFns: () => new Promise(() => {}),
    syncKindFilterBar() {}, primeServiceCacheOnce() {}, primeAppsCacheOnce() {},
    primeSecretsOnce() {}, primeTestStatusesOnce() {}, primeProblemsOnce() {},
    gdKeepInlineRow: (scope) => { calls.push(['keep', scope, list.innerHTML]); return token; },
    gdRestoreInlineRow: (scope, kept) => { calls.push(['restore', scope, list.innerHTML, kept]); },
  });
  sctx.window = sctx;
  vm.runInContext(SIDEBAR, sctx);
  sctx.onSearchInput('adder');              // repaints into "Searching…"
  assert(calls.length === 2 && calls[0][0] === 'keep' && calls[1][0] === 'restore',
    'keep then restore: ' + JSON.stringify(calls.map((c) => c[0])));
  assert(calls[0][1] === list && calls[1][1] === list, 'both over #entity-list');
  assert(calls[0][2] === '' && /Searching/.test(calls[1][2]), 'keep BEFORE the rebuild, restore AFTER');
  assert(calls[1][3] === token, 'restore gets what keep returned');
}

console.log(passes + ' passed, ' + fails + ' failed');
process.exit(fails ? 1 : 0);
