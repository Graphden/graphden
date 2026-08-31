// Compare-mode effect analysis — the pure halves of
// editor-diff-mode.js, with the registry written down as a fixture.
//
// Covers what the e2e deliberately doesn't: the STRUCTURAL fallback
// (`gdDiffEffectsTouched` — which effect-carrying fns a change wires
// in/out, used when a fn has no stable name on both branches), the
// cosmetic classifier behind the "substantive only" lens, and the
// full effect-set delta formatting. The e2e asserts only the primary
// full-delta path against a live pair of branches.
//
// Run:  node tools/runtime-test/diff-effects.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const MODULE = path.join(__dirname, '..', '..', 'resources', 'packages',
                         'app', 'editor', 'editor-diff-mode.js');

// Minimal ambient the module touches at LOAD time: the lens reads
// localStorage, the boot IIFE reads the persisted branch (null → it
// returns before touching anything else).
const sandbox = {
  window: {},
  localStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
  document: {
    getElementById: () => null,
    querySelector: () => null,
    querySelectorAll: () => [],
  },
  console,
  setInterval: () => 0,
  clearInterval: () => {},
  setTimeout: () => 0,
  clearTimeout: () => {},
  // The registry fixture — the same lean shape /api/types serves.
  richTypes: {
    'current-time-ms': { effects: ['time'] },
    'pg-query': { effects: ['db', 'raw-sql'] },
    'to-str': { effects: [] },
  },
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(MODULE, 'utf8'), sandbox, { filename: 'editor-diff-mode.js' });

let failures = 0;
function is(actual, expected, label) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (!ok) {
    failures += 1;
    console.error('✗ ' + label + '\n  expected: ' + JSON.stringify(expected)
      + '\n  actual:   ' + JSON.stringify(actual));
  } else {
    console.log('✓ ' + label);
  }
}

const g = sandbox;

// --- cosmetic classifier (the "substantive only" lens) ---
is(g.gdDiffEntryCosmetic({ change: 'modified',
                           fields: [{ field: 'description' }, { field: 'name' }] }),
   true, 'name+description-only modification is cosmetic');
is(g.gdDiffEntryCosmetic({ change: 'modified',
                           fields: [{ field: 'description' }, { field: 'value' }] }),
   false, 'a value change is substantive');
is(g.gdDiffEntryCosmetic({ change: 'added-in-source', preview: 'x' }),
   false, 'one-sided entries are never cosmetic');
is(g.gdDiffGroupSubstantive({ entries: [
     { change: 'modified', fields: [{ field: 'name' }] },
     { change: 'modified', fields: [{ field: 'ref-fn-id' }] }] }),
   true, 'group with one substantive entry is substantive');

// --- structural fallback: effects touched by changed refs ---
is(g.gdDiffEffectsTouched({ entries: [
     { change: 'modified', 'slot-name': 'value',
       fields: [{ field: 'ref-fn-id', source: ':current-time-ms', target: ':pg-query' }] }] }),
   'effects touched: +time −db,−raw-sql',
   'rewired ref: gains the new target\'s effects, drops the old\'s');
is(g.gdDiffEffectsTouched({ entries: [
     { change: 'added-in-source', 'slot-name': 'value',
       preview: 'ref → :current-time-ms' }] }),
   'effects touched: +time',
   'one-sided ref preview counts toward the compared side');
is(g.gdDiffEffectsTouched({ entries: [
     { change: 'modified',
       fields: [{ field: 'value', source: '2', target: '1' }] }] }),
   null, 'a pure value edit touches no effects');
is(g.gdDiffEffectsTouched({ entries: [
     { change: 'modified',
       fields: [{ field: 'ref-fn-id', source: ':to-str', target: ':to-str' }] }] }),
   null, 'same ref on both sides touches nothing');

// --- full effect-set delta + formatting ---
const here = { f: { effects: ['db'] } };
const there = { f: { effects: ['db', 'time'] } };
is(g.gdDiffEffectSetDelta(here, there, 'f'),
   { here: ['db'], there: ['db', 'time'] }, 'differing sets → delta');
is(g.gdDiffEffectSetDelta(here, here, 'f'), null, 'equal sets → null');
is(g.gdDiffEffectSetDelta(here, there, 'absent'), null,
   'unknown on either side → null (fallback path takes over)');
is(g.gdDiffEffectSetLabel({ here: [], there: ['time'] }),
   'effects: pure here · time there', 'label spells pure for the empty set');
is(g.gdDiffShowEffects(['db', 'network']), 'db,network', 'joiner');

// --- kind mapping (current-branch perspective) ---
is(g.gdDiffModeKind({ change: 'added-in-target' }), 'added', 'added here');
is(g.gdDiffModeKind({ change: 'added-in-source' }), 'missing', 'only there');
is(g.gdDiffModeKind({ change: 'modified' }), 'modified', 'modified');

if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('diff-effects: all assertions passed');
