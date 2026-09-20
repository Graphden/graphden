// The fn-picker's ranking + arrangement — editor-fn-picker-rank.js, loaded
// into a node vm.
//
// What the picker shows, and in what order, is decided here: the fit tier
// from the candidate's whole signature (a `(item:a) → b` slot puts
// one-free-arg fns first, nullary constants last; a value slot puts
// finished values before templates), and the Explorer-shaped arrangement
// — an "Exact match" block when the reader typed a full name, namespace
// groups under it, incompatible rows dimmed in place, groups that fold
// only in browse mode. The server computes the same tier verdict
// (`candidate-fit`, types_api_test); this file pins the client mirror.
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
const names = (rows) => rows.map((r) => r.qualified || r.name);

const MAP_FUNC = ['fn', { item: 'a' }, 'b'];
const REDUCE_FUNC = ['fn', { acc: 'a', item: 'b' }, 'a'];
const FUTURE_BODY = ['fn', {}, 'any'];
const LIST_TEXT = ['list', 'text'];

// --- pickerSlotArity ------------------------------------------------------
assert(ctx.pickerSlotArity(MAP_FUNC) === 1, 'map :func passes one arg per call');
assert(ctx.pickerSlotArity(REDUCE_FUNC) === 2, 'reduce :func passes two');
assert(ctx.pickerSlotArity(FUTURE_BODY) === 0, 'future :body passes none');
assert(ctx.pickerSlotArity('text') === null, 'a value slot has no call arity');
assert(ctx.pickerSlotArity(LIST_TEXT) === null, 'a list slot neither');

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
assert(ctx.pickerFitTier(LIST_TEXT, 0) === 'exact', 'so for a list slot');

// --- labels ----------------------------------------------------------------
assert(ctx.pickerTierLabel(MAP_FUNC, 'exact') === 'Exact fit', 'callable vocabulary');
assert(ctx.pickerTierLabel('text', 'exact') === 'Ready', 'value vocabulary');
assert(ctx.pickerTierLabel('text', 'captures') === 'Needs inputs', 'value: needs inputs');
assert(ctx.pickerTierLabel(MAP_FUNC, 'captures') === 'Extra inputs', 'callable: extra inputs');
assert(ctx.pickerTierLabel(MAP_FUNC, 'ignores') === 'Ignores the input', 'ignores');
assert(ctx.pickerTierOf(MAP_FUNC, { fit: 'captures', arity: 1 }) === 'captures', 'the server\'s fit wins over local arity');
assert(ctx.pickerTierOf(MAP_FUNC, { arity: 0 }) === 'ignores', 'no server fit — local arity decides');
assert(ctx.pickerIsTestNs('core.tests') && ctx.pickerIsTestNs('app.registry.tests.x') && !ctx.pickerIsTestNs('core.testsuite'),
  'a `tests` SEGMENT marks a test namespace, not a substring');

// --- pickerArrange: the lesson-17 case ------------------------------------
// The reader typed `map` into a [list text] slot. Six test fns whose names
// merely contain "map" are ready values; core.hof.map itself needs inputs.
// The exact-name hit must be the first row, whatever its tier.
const c = (name, ns, extra) => Object.assign({ name, ns, qualified: ns ? ns + '.' + name : name, compatible: true, arity: 0 }, extra || {});
const lesson15 = [
  c('pref-keys-are-theme-and-keymap', 'app.common.tests'),
  c('map-applies-the-callable-to-every-item', 'core.tests'),
  c('stringify-map-keys-turns-keyword-keys-into-strings', 'core.tests'),
  c('map', 'core.hof', { arity: 2, fit: 'captures' }),
  c('_merge-resolutions-mapped', 'app.branches', { arity: 1, fit: 'captures' }),
  c('storefront-sitemap-urls', 'app.marketplace', { arity: 1, fit: 'captures' }),
  c('map-vals', 'core.collections', { arity: 2, fit: 'captures' }),
  c('keymap-list', 'app.marketplace', { compatible: false }),
];
const typed = ctx.pickerArrange(lesson15, { q: 'map', expected: LIST_TEXT });
assert(eq(names(typed.exact), ['core.hof.map']), 'the exact-name hit is the Exact match block: ' + JSON.stringify(names(typed.exact)));
assert(typed.groups.every((g) => g.open), 'a typed filter never folds a group');
assert(eq(typed.groups.map((g) => g.ns), ['app.branches', 'app.marketplace', 'core.collections', 'app.common.tests', 'core.tests']),
  'groups alphabetical with `tests` namespaces last: ' + JSON.stringify(typed.groups.map((g) => g.ns)));
const mk = typed.groups.find((g) => g.ns === 'app.marketplace');
assert(eq(mk.rows.map((r) => r.name), ['storefront-sitemap-urls', 'keymap-list']),
  'inside a group the compatible row precedes the incompatible one, which stays VISIBLE: ' + JSON.stringify(mk.rows.map((r) => r.name)));
assert(mk.compat === 1 && mk.other === 1, 'the group counts both');
assert(typed.total === 8 && typed.shown === 8 && typed.hiddenOther === 0, 'typed: everything matched is shown');
const narrow = ctx.pickerArrange(lesson15, { q: 'zzz', expected: LIST_TEXT });
assert(narrow.total === 0 && narrow.groups.length === 0 && narrow.exact.length === 0, 'no match — nothing, no headers');

// The Explorer's own spelling `core.hof/map` reaches the picker as dotted
// lowercase; a namespace-only match still lands in its group.
const qualified = ctx.pickerArrange(lesson15, { q: 'core.hof', expected: LIST_TEXT });
assert(eq(names(qualified.groups.flatMap((g) => g.rows)), ['core.hof.map']) && qualified.exact.length === 0,
  'a namespace query matches by qualified name, without an exact block');

// --- pickerArrange: browse mode --------------------------------------------
const browse = ctx.pickerArrange(lesson15, { q: '', expected: LIST_TEXT });
assert(browse.hiddenOther === 1, 'browse hides the fns of other types behind the toggle: ' + browse.hiddenOther);
assert(browse.groups.every((g) => g.open), 'a short list starts fully open');
assert(browse.exact.length === 0, 'no exact block without a query');
const withOther = ctx.pickerArrange(lesson15, { q: '', expected: LIST_TEXT, showOther: true });
assert(withOther.hiddenOther === 0 && withOther.total === 8, 'the toggle lists them, dimmed in their groups');

// A long compatible list folds every group but the fn's own namespace…
const many = [];
for (let i = 0; i < 70; i++) many.push(c('f' + String(i).padStart(2, '0'), 'ns' + (i % 7)));
many.push(c('mine', 'home', { sameNs: true }));
const long = ctx.pickerArrange(many, { q: '', expected: 'text' });
assert(eq(long.groups[0].ns, 'home') && long.groups[0].open, 'the fn\'s own namespace is first and open');
assert(long.groups.slice(1).every((g) => !g.open), 'the other groups start folded');
assert(long.shown === 1 && long.total === 71, 'folded rows are not rendered but are counted');
// …and the reader's toggles win over the default, both ways.
const toggled = ctx.pickerArrange(many, { q: '', expected: 'text', openGroups: new Set(['ns3']), closedGroups: new Set(['home']) });
assert(toggled.groups.find((g) => g.ns === 'ns3').open && !toggled.groups.find((g) => g.ns === 'home').open,
  'openGroups / closedGroups override the defaults');
// A typed filter ignores folds altogether.
const longTyped = ctx.pickerArrange(many, { q: 'f0', expected: 'text' });
assert(longTyped.groups.every((g) => g.open) && longTyped.shown === 10, 'typing opens everything that matches');

// --- pickerArrange: order inside a group ------------------------------------
const rows = [
  c('z-late', 'core.strings', { arity: 0 }),
  c('_priv', 'core.strings', { arity: 0 }),
  c('a-first', 'core.strings', { arity: 0 }),
  c('needs', 'core.strings', { arity: 2, fit: 'captures' }),
  c('nope', 'core.strings', { compatible: false }),
];
const one = ctx.pickerArrange(rows, { q: '', expected: 'text', showOther: true });
assert(eq(one.groups[0].rows.map((r) => r.name), ['a-first', 'z-late', '_priv', 'needs', 'nope']),
  'compatible → by tier → public before private → alphabetical, incompatible last: ' + JSON.stringify(one.groups[0].rows.map((r) => r.name)));
// Untyped picker (re-parent, wrap): no verdicts, plain alphabetical groups.
const untyped = ctx.pickerArrange(rows, { q: '', expected: null });
assert(untyped.hiddenOther === 0 && untyped.groups[0].rows.length === 5 && untyped.groups[0].other === 0,
  'an untyped picker lists every fn, none "other"');

// --- cap --------------------------------------------------------------------
const capped = ctx.pickerArrange(many, { q: 'f', expected: 'text', cap: 20 });
assert(capped.shown === 20 && capped.total === 70 && capped.groups.some((g) => g.truncated),
  'the cap trims rows group by group and marks the cut: shown ' + capped.shown);

console.log(failures ? ('fn-picker-rank: ' + failures + ' failure(s), ' + passes + ' passed')
                     : ('fn-picker-rank: PASS — ' + passes + ' assertions'));
process.exit(failures ? 1 : 0);
