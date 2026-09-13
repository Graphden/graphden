// The fn-picker's ranking — editor-fn-picker-rank.js, loaded into a node vm.
//
// What the picker shows FIRST is decided here, from the candidate's whole
// signature: a `(item:a) → b` slot puts one-free-arg fns under "Exact fit",
// nullary constants under "Ignores the input"; a value slot puts finished
// values under "Ready". The server computes the same verdict
// (`candidate-fit`, types_api_test); this file pins the client mirror and
// the namespace grouping around it.
//
// Run:  node tools/runtime-test/fn-picker-rank.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');
const ctx = vm.createContext({ console });
vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-fn-picker-rank.js'), 'utf8'),
  ctx, { filename: 'editor-fn-picker-rank.js' });

let failures = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

const MAP_FUNC = ['fn', { item: 'a' }, 'b'];
const REDUCE_FUNC = ['fn', { acc: 'a', item: 'b' }, 'a'];
const FUTURE_BODY = ['fn', {}, 'any'];

// --- pickerSlotArity ------------------------------------------------------
assert(ctx.pickerSlotArity(MAP_FUNC) === 1, 'map :func passes one arg per call');
assert(ctx.pickerSlotArity(REDUCE_FUNC) === 2, 'reduce :func passes two');
assert(ctx.pickerSlotArity(FUTURE_BODY) === 0, 'future :body passes none');
assert(ctx.pickerSlotArity('text') === null, 'a value slot has no call arity');
assert(ctx.pickerSlotArity(['list', 'a']) === null, 'a list slot neither');

// --- pickerFitTier: callable slots --------------------------------------
assert(ctx.pickerFitTier(MAP_FUNC, 1) === 'exact', 'str-upper (1 free arg) fits map exactly');
assert(ctx.pickerFitTier(MAP_FUNC, 0) === 'ignores', 'a constant in map\'s slot ignores the item');
assert(ctx.pickerFitTier(MAP_FUNC, 3) === 'captures', 'a 3-arg fn captures two from the host');
assert(ctx.pickerFitTier(REDUCE_FUNC, 2) === 'exact', 'two names for a two-arg slot');
assert(ctx.pickerFitTier(FUTURE_BODY, 0) === 'exact', 'nullary in a nullary slot');
assert(ctx.pickerFitTier(FUTURE_BODY, 2) === 'captures', 'frees in a nullary slot are captured');
assert(ctx.pickerFitTier('fn-ref', 7) === 'exact', 'an identity slot has no arity question');
assert(ctx.pickerFitTier(MAP_FUNC, null) === 'exact', 'unknown arity is not demoted');

// --- pickerFitTier: value slots ----------------------------------------
assert(ctx.pickerFitTier('text', 0) === 'exact', 'a finished text value is ready');
assert(ctx.pickerFitTier('text', 2) === 'captures', 'a template with frees needs inputs');
assert(ctx.pickerFitTier(['list', 'int'], 0) === 'exact', 'so for a list slot');

// --- labels ----------------------------------------------------------------
assert(ctx.pickerTierLabel(MAP_FUNC, 'exact') === 'Exact fit', 'callable vocabulary');
assert(ctx.pickerTierLabel('text', 'exact') === 'Ready', 'value vocabulary');
assert(ctx.pickerTierLabel('text', 'captures') === 'Needs inputs', 'value: needs inputs');
assert(ctx.pickerTierLabel(MAP_FUNC, 'captures') === 'Extra inputs', 'callable: extra inputs');
assert(ctx.pickerTierLabel(MAP_FUNC, 'ignores') === 'Ignores the input', 'ignores');

// --- pickerTiersOf ----------------------------------------------------------
const rows = [
  { name: 'str-upper', ns: 'core.strings', arity: 1 },
  { name: 'auth-css', ns: 'app.auth-pages', arity: 0 },
  { name: 'identity', ns: 'core.hof', arity: 1, fit: 'exact' },
  { name: 'greeter', ns: 'tutorial', arity: 3, fit: 'captures' },
  { name: '_abase-handler', ns: 'app-base', arity: 0, fit: 'ignores' },
];
const tiers = ctx.pickerTiersOf(MAP_FUNC, rows);
assert(eq(tiers.map((t) => t.tier), ['exact', 'captures', 'ignores']),
  'tiers come in fixed order, empties dropped: ' + JSON.stringify(tiers.map((t) => t.tier)));
assert(eq(tiers[0].rows.map((r) => r.name).sort(), ['identity', 'str-upper']),
  'exact = the one-free-arg fns (server fit or local arity)');
assert(eq(tiers[2].rows.map((r) => r.name).sort(), ['_abase-handler', 'auth-css']),
  'ignores = the constants');
assert(eq(ctx.pickerTiersOf(MAP_FUNC, rows.slice(0, 1)).map((t) => t.tier), ['exact']),
  'a single populated tier is the only entry');

// --- groupPickerRows ---------------------------------------------------------
const many = [
  { name: 'z-late', ns: 'core.strings' },
  { name: '_priv', ns: 'core.strings' },
  { name: 'a-first', ns: 'core.strings' },
  { name: 'mine', ns: 'tutorial', sameNs: true },
  { name: 'b-other', ns: 'app.pages' },
];
const g = ctx.groupPickerRows(many, { cap: 50 });
assert(eq(g.groups.map((x) => x.ns), ['tutorial', 'app.pages', 'core.strings']),
  'same-namespace group first, then namespaces alphabetical: ' + JSON.stringify(g.groups.map((x) => x.ns)));
assert(eq(g.groups[2].rows.map((r) => r.name), ['a-first', 'z-late', '_priv']),
  'within a namespace: public names alphabetical, private (_) last');
assert(g.shown === 5 && g.total === 5, 'nothing capped under the cap');

const capped = ctx.groupPickerRows(many, { cap: 2 });
assert(capped.shown === 2 && capped.total === 5, 'cap trims rows, total stays honest');

const single = ctx.groupPickerRows(many.slice(0, 3), { cap: 50 });
assert(single.groups.length === 1 && single.groups[0].ns === null,
  'one namespace → one header-less group');

const typed = ctx.groupPickerRows([
  { name: 'str-upper-case', ns: 'core.strings' },
  { name: 'str-upper', ns: 'zz.late' },
  { name: 'other', ns: 'core.strings' },
], { cap: 50, q: 'str-upper' });
assert(typed.groups[0].rows[0].name === 'str-upper',
  'an exact name match leads even from a later namespace');

console.log(failures === 0
  ? `fn-picker-rank: ${passes} passed`
  : `fn-picker-rank: ${passes} passed, ${failures} FAILED`);
process.exit(failures === 0 ? 0 : 1);
