// `buildFnUsagesSection` (editor-fn-usages.js) — the inspector's
// "Used by" block: who extends / references the selected fn, grouped
// by kind, one row per (kind, fn, slot), capped per group, rows
// navigating via the injected callback.
//
// Pure DOM construction from the `/api/fns/usages` payload — mini-dom
// supplies the element APIs, so this runs under plain `node`.
//
// Run:  node tools/runtime-test/fn-usages-section.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}

function test(name, fn) {
  console.log(' ' + name);
  try { fn(); } catch (e) { failures += 1; console.error('  ✗ threw: ' + e.message); }
}

function usagesCtx() {
  const document = createDocument();
  const ctx = vm.createContext({ console, document, window: {} });
  vm.runInContext(
    fs.readFileSync(path.join(EDITOR, 'editor-fn-usages.js'), 'utf8'),
    ctx, { filename: 'editor-fn-usages.js' });
  return { ctx, document };
}

function walk(el, out) {
  out.push(el);
  for (const c of (el.children || [])) {
    if (c.tagName !== undefined) walk(c, out);
  }
  return out;
}

function byClass(root, cls) {
  return walk(root, []).filter((e) => String(e.className || '').includes(cls));
}

function texts(root, cls) {
  return byClass(root, cls).map((e) => e.textContent);
}

test('groups by kind with labels, one row per fn, slot shown for refs', () => {
  const { ctx } = usagesCtx();
  const payload = {
    ok: true,
    usages: [
      { 'fn-id': 'a', 'fn-name': 'add-10', kind: 'parent-of' },
      { 'fn-id': 'b', 'fn-name': 'caller', kind: 'ref-of', 'slot-name': 'handler' },
      { 'fn-id': 'c', 'fn-name': 'other', kind: 'ref-of', 'slot-name': 'body' },
    ],
  };
  const sec = ctx.buildFnUsagesSection(payload, {});
  assert(sec, 'section built');
  const glabels = texts(sec, 'gd-insp-usage-glabel');
  assert(glabels[0] === 'Extended by 1', 'parent-of group first, counted (got: ' + glabels[0] + ')');
  assert(glabels[1] === 'Referenced by 2', 'ref-of group second (got: ' + glabels[1] + ')');
  const rows = byClass(sec, 'gd-insp-usage-row');
  assert(rows.length === 3, 'three rows (got ' + rows.length + ')');
  const slots = texts(sec, 'gd-insp-usage-slot');
  assert(slots.includes(':handler') && slots.includes(':body'),
    'ref rows carry their slot names');
  const head = byClass(sec, 'gd-insp-usages-count')[0];
  assert(head && head.textContent === '3', 'head count is deduped total');
});

test('dedupes (kind, fn, slot) — a binding ref + list-item ref is one row', () => {
  const { ctx } = usagesCtx();
  const payload = {
    ok: true,
    usages: [
      { 'fn-id': 'b', 'fn-name': 'caller', kind: 'ref-of', 'slot-name': 'items' },
      { 'fn-id': 'b', 'fn-name': 'caller', kind: 'ref-of', 'slot-name': 'items' },
    ],
  };
  const sec = ctx.buildFnUsagesSection(payload, {});
  assert(byClass(sec, 'gd-insp-usage-row').length === 1, 'duplicate collapsed');
});

test('empty payload → null (section absent, not an empty box)', () => {
  const { ctx } = usagesCtx();
  assert(ctx.buildFnUsagesSection({ ok: true, usages: [] }, {}) === null, 'null for no usages');
  assert(ctx.buildFnUsagesSection(null, {}) === null, 'null for no payload');
});

test('row click navigates with the whole usage row', () => {
  const { ctx } = usagesCtx();
  const seen = [];
  const payload = {
    ok: true,
    usages: [{ 'fn-id': 'nav-1', 'fn-name': 'child', 'fn-namespace': 'app.web', kind: 'parent-of' }],
  };
  const sec = ctx.buildFnUsagesSection(payload, { onNavigate: (u) => seen.push(u) });
  const row = byClass(sec, 'gd-insp-usage-row')[0];
  row.click();
  assert(seen.length === 1 && seen[0]['fn-id'] === 'nav-1',
    'click → onNavigate(usage) carrying fn-id');
  const ns = texts(sec, 'gd-insp-usage-ns');
  assert(ns.length === 1 && ns[0] === 'app.web', 'row shows the namespace path');
});

test('anonymous users fold into a count line, named rows stay clickable', () => {
  const { ctx } = usagesCtx();
  const payload = {
    ok: true,
    usages: [
      { 'fn-id': 'n1', 'fn-name': 'real-caller', kind: 'parent-of' },
      { 'fn-id': 'a1', 'fn-name': '(anonymous)', kind: 'parent-of', anonymous: true },
      { 'fn-id': 'a2', 'fn-name': '(anonymous)', kind: 'parent-of', anonymous: true },
    ],
  };
  const sec = ctx.buildFnUsagesSection(payload, {});
  assert(byClass(sec, 'gd-insp-usage-row').length === 1, 'only the named row renders');
  const glabel = texts(sec, 'gd-insp-usage-glabel')[0];
  assert(glabel === 'Extended by 3', 'group count includes the anons (got ' + glabel + ')');
  const more = texts(sec, 'gd-insp-usage-more')[0];
  assert(more === '… 2 anonymous', 'anons folded into the tail line (got ' + more + ')');
});

test('a group over the cap folds into a "more" line', () => {
  const { ctx } = usagesCtx();
  const usages = [];
  for (let i = 0; i < 25; i++) {
    usages.push({ 'fn-id': 'c' + i, 'fn-name': 'child-' + String(i).padStart(2, '0'), kind: 'parent-of' });
  }
  const sec = ctx.buildFnUsagesSection({ ok: true, usages }, {});
  assert(byClass(sec, 'gd-insp-usage-row').length === 20, 'capped at 20 rows');
  const more = texts(sec, 'gd-insp-usage-more');
  assert(more.length === 1 && more[0] === '… and 5 more', 'fold line says how many were dropped');
});

if (failures) {
  console.error('FAIL: ' + failures + ' failed, ' + passes + ' passed');
  process.exit(1);
}
console.log('PASS: ' + passes + ' assertions');
