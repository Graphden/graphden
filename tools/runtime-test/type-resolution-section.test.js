// `appendResolutionSection` (editor-type-format.js) — the inline
// "Resolved via" block of the type-provenance popover.
//
// It answers one question for the reader: WHICH of the four tiers
// decided this slot's type, and when several ancestors offered an
// override, which one closer-fn-wins picked. That decision is invisible
// in the graph, so the markers (✓ chosen / ↳ also by) are the whole
// feature — and they are pure DOM construction from a plain data map.
//
// Ported out of `tools/browser-test/type-system-ui-resolution.test.js`,
// which drove a real chromium at a live editor for it and, as a result,
// ran in no CI at all. `mini-dom` supplies the handful of element APIs
// the builder uses, so this runs under plain `node`.
//
// Run:  node tools/runtime-test/type-resolution-section.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');
const MODULES = ['editor-type-expand-render.js', 'editor-type-format.js',
                 'editor-literal-types.js'];

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

// A fresh editor context per test — `lookups` is mutated by the
// navigation case, and a leaked mock is how one test starts deciding
// another one's result.
function editorCtx(fnByName) {
  const document = createDocument();
  const ctx = vm.createContext({
    console,
    document,
    richTypes: { 'positive-int': { return: ['refine', 'int', ['>', 0]] } },
    lookups: { fnByName: fnByName || new Map(), fnMap: new Map() },
  });
  for (const f of MODULES) {
    vm.runInContext(fs.readFileSync(path.join(EDITOR, f), 'utf8'), ctx, { filename: f });
  }
  return { ctx, host: document.createElement('div') };
}

// The four tiers always ship in full; only which one carries a type
// and which key wins changes between cases.
function tiers({ override = null, unified = null, refReturn = null, slot = null } = {}) {
  return [
    { key: 'override', label: 'Binding type-override', type: override && override.type,
      source: override && override.source },
    { key: 'unified', label: 'Backward-unified return type', type: unified && unified.type,
      source: unified && unified.source },
    { key: 'ref-return', label: 'Bound fn return type', type: refReturn && refReturn.type,
      source: refReturn && refReturn.source },
    { key: 'slot', label: 'Slot declaration', type: slot && slot.type,
      source: slot && slot.source },
  ];
}


test('two overrides — the winner is marked, the shadowed one is labelled', () => {
  const { ctx, host } = editorCtx();
  ctx.appendResolutionSection(host, {
    winner: 'unified',
    tiers: tiers({
      unified: { type: 'positive-int', source: { fnName: 'child-fn', fnId: 'fake-child' } },
      slot: { type: 'int', source: { fnName: 'root-base', fnId: 'fake-root' } },
    }),
    inheritanceChain: [
      { fnId: 'fake-parent-a', fnName: 'parent-a', overrideFnId: 'fake-override-a' },
      { fnId: 'fake-parent-b', fnName: 'parent-b', overrideFnId: 'fake-override-b' },
    ],
  });

  assert(host.querySelectorAll('.type-inline-resolution-chain-link').length === 2,
         'both chain entries render a row');
  assert(host.querySelectorAll('.type-inline-resolution-chain-winner').length === 1,
         'the closer-fn-wins pick is the only winner row');
  assert(host.querySelectorAll('.type-inline-resolution-chain-also').length === 1,
         'the shadowed ancestor gets the "also" row');

  const tags = host.querySelectorAll('.type-inline-resolution-chain-tag')
                   .map((t) => t.textContent);
  assert(JSON.stringify(tags) === JSON.stringify(['(chosen)', '(also by)']),
         'tags read (chosen) then (also by), got ' + JSON.stringify(tags));

  const marks = host
    .querySelectorAll('.type-inline-resolution-chain-link .type-inline-resolution-mark')
    .map((m) => m.textContent);
  assert(marks[0] === '✓' && marks[1] === '↳',
         'the winner is ✓ and the shadowed one ↳, got ' + JSON.stringify(marks));
});


test('one override — no decision to show, so nothing is decorated', () => {
  const { ctx, host } = editorCtx();
  ctx.appendResolutionSection(host, {
    winner: 'slot',
    tiers: tiers({ slot: { type: 'int', source: { fnName: 'root-base', fnId: 'fake-root' } } }),
    inheritanceChain: [
      { fnId: 'fake-parent-a', fnName: 'parent-a', overrideFnId: 'fake-override-a' },
    ],
  });
  assert(host.querySelectorAll('.type-inline-resolution-chain-winner').length === 0,
         'no winner class with a single candidate');
  assert(host.querySelectorAll('.type-inline-resolution-chain-also').length === 0,
         'no also class with a single candidate');
  assert(host.querySelectorAll('.type-inline-resolution-chain-tag').length === 0,
         'no tag chips with a single candidate');
  assert(host.querySelectorAll('.type-inline-resolution-chain-link').length === 1,
         'the chain row itself is still rendered');
});


test('the winning tier is the one marked ✓', () => {
  const { ctx, host } = editorCtx();
  ctx.appendResolutionSection(host, {
    winner: 'override',
    tiers: tiers({
      override: { type: 'positive-int', source: { fnName: 'caller-fn', fnId: 'fake-caller' } },
      slot: { type: 'int', source: { fnName: 'root-base', fnId: 'fake-root' } },
    }),
    inheritanceChain: [],
  });
  const rows = host.querySelectorAll('.type-inline-resolution-row');
  assert(rows.length === 4, 'all four tiers are listed, got ' + rows.length);
  const active = host.querySelectorAll('.type-inline-resolution-active');
  assert(active.length === 1, 'exactly one tier is active');
  assert(active[0].textContent.includes('Binding type-override'),
         'the active tier is the declared winner, got: ' + active[0].textContent);
});


test('a tier with no source and no type still renders, as an em dash', () => {
  const { ctx, host } = editorCtx();
  ctx.appendResolutionSection(host, {
    winner: 'slot',
    tiers: tiers({ slot: { type: 'int', source: { fnName: 'root-base', fnId: 'fake-root' } } }),
    inheritanceChain: [],
  });
  const cells = host.querySelectorAll('.type-inline-resolution-type')
                    .map((c) => c.textContent);
  assert(cells.filter((c) => c === '—').length === 3,
         'the three empty tiers show —, got ' + JSON.stringify(cells));
  assert(cells.includes('int'), 'the slot tier shows its type');
});


test('onNavigate wires both the source-fn label and a known type name', () => {
  const fakeTypeId = '00000000-0000-0000-0000-0000000000aa';
  const { ctx, host } = editorCtx(
    new Map([['positive-int', { id: fakeTypeId, name: 'positive-int' }]]));
  const calls = [];
  ctx.appendResolutionSection(host, {
    winner: 'override',
    tiers: tiers({
      override: { type: 'positive-int', source: { fnName: 'caller-fn', fnId: 'fake-caller' } },
      slot: { type: 'int', source: { fnName: 'root-base', fnId: 'fake-root' } },
    }),
    inheritanceChain: [],
  }, { onNavigate: (id) => calls.push(id) });

  const fnLinks = host.querySelectorAll(
    'a.type-inline-resolution-link.type-inline-resolution-label');
  const typeLinks = host.querySelectorAll(
    'span.type-inline-resolution-type.type-inline-resolution-link');
  assert(fnLinks.length >= 1, 'source fns render as links');
  assert(typeLinks.length === 1,
         'only the type that resolves through fnByName becomes a link, got '
         + typeLinks.length);

  fnLinks[0].click();
  typeLinks[0].click();
  assert(calls.includes('fake-caller'), 'clicking a source fn navigates to its fn-id');
  assert(calls.includes(fakeTypeId), 'clicking a known type navigates to the type-row');
});


test('without onNavigate nothing is clickable', () => {
  const fakeTypeId = '00000000-0000-0000-0000-0000000000aa';
  const { ctx, host } = editorCtx(
    new Map([['positive-int', { id: fakeTypeId, name: 'positive-int' }]]));
  ctx.appendResolutionSection(host, {
    winner: 'override',
    tiers: tiers({
      override: { type: 'positive-int', source: { fnName: 'caller-fn', fnId: 'fake-caller' } },
    }),
    inheritanceChain: [],
  });
  assert(host.querySelectorAll('.type-inline-resolution-link').length === 0,
         'a read-only popover renders no links');
});


console.log(failures === 0
  ? '✓ type-resolution-section — ' + passes + ' assertions'
  : '✗ type-resolution-section — ' + failures + ' failed of ' + (passes + failures));
process.exit(failures === 0 ? 0 : 1);
