// Unit tests for editor-tour-checks.js — the tour's step-completion
// vocabulary. Pure predicates over the editor's own state, so they run in a
// node vm with `graphData` / `lookups` / a stub `document` seeded per case.
// No browser, no stack: the browser guards walk whole lessons (minutes each,
// gate-only) and cannot say what a single check does with an odd input.
//
// Run:  node tools/runtime-test/tour-checks.test.js
// Exit: 0 on pass, 1 on failure.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor',
            'editor-tour-checks.js'),
  'utf8');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes++; return; }
  failures++;
  console.error('  ✗ ' + msg);
}

function test(name, fn) {
  console.log(' ' + name);
  try { fn(); }
  catch (e) { failures++; console.error('  ✗ threw: ' + e.message); }
}

// One sandbox per case: the checks read script-scope globals, so the state
// under test IS the context.
function checkIn(state, check) {
  const labels = state.edgeLabels || [];
  const domHits = state.dom || {};
  // `state.overlays` — canvas cards by fn name: `{name: [nodeId, …]}`, each
  // rendered as a `.node-overlay[data-fn-name=…]` carrying its node id.
  const overlays = state.overlays || {};
  const ctx = vm.createContext({
    console,
    graphData: state.graphData || null,
    lookups: state.lookups || null,
    selectedFnId: state.selectedFnId || null,
    expansionState: state.expansionState || new Map(),
    window: { location: { search: state.search || '' } },
    URLSearchParams,
    document: {
      // A hit may carry the element itself (`{ value: 'add' }` for a form
      // control) — `input-value` reads that; the boolean form is the bare
      // "it exists" the other kinds need.
      querySelector: (sel) => (domHits[sel]
        ? (typeof domHits[sel] === 'object' ? domHits[sel] : {}) : null),
      // A `dom` check measures its matches — `state.dom[sel]` is `true` for a
      // visible element and `'hidden'` for one that is in the document at
      // zero size (a mounted-but-closed surface, which is how the editor
      // keeps its Organization panels).
      querySelectorAll: (sel) => {
        if (sel === '.edge-label-overlay span') {
          return labels.map((t) => ({ textContent: t }));
        }
        const card = /^\.node-overlay\[data-fn-name="([^"]+)"\]$/.exec(sel);
        if (card) {
          return (overlays[card[1]] || []).map((nodeId) => ({ dataset: { nodeId } }));
        }
        const hit = domHits[sel];
        if (!hit) return [];
        const size = hit === 'hidden' ? 0 : 10;
        return [{ getBoundingClientRect: () => ({ width: size, height: size }) }];
      },
    },
  });
  vm.runInContext(source, ctx);
  return ctx._tourCheckPasses(check);
}

// --- shared fixtures --------------------------------------------------------

const FN = { id: 'fn-1', name: 'greet', 'parent-ids': ['fn-parent'] };
const PARENT = { id: 'fn-parent', name: 'const', 'parent-ids': [] };
const SLOT = { id: 'slot-1', name: 'value' };

function withFn(extra) {
  const fnMap = new Map([[FN.id, FN], [PARENT.id, PARENT]]);
  const slotMap = new Map([[SLOT.id, SLOT]]);
  return Object.assign({
    graphData: { fns: [FN, PARENT], namespaces: [] },
    lookups: {
      fnMap,
      slotMap,
      bindingsByFn: new Map(),
      itemsByBinding: new Map(),
    },
  }, extra || {});
}

// --- cases ------------------------------------------------------------------

test('manual never auto-passes — it is the reader\'s Next button', () => {
  assert(checkIn(withFn(), { kind: 'manual' }) === false, 'manual is false');
  assert(checkIn(withFn(), null) === false, 'a missing check is false');
});

test('an unknown kind is false, not a crash', () => {
  assert(checkIn(withFn(), { kind: 'no-such-kind', name: 'greet' }) === false,
         'unknown kind returns false');
});

test('fn-exists / fn-parent read the graph, not the DOM', () => {
  assert(checkIn(withFn(), { kind: 'fn-exists', name: 'greet' }) === true, 'fn found');
  assert(checkIn(withFn(), { kind: 'fn-exists', name: 'nope' }) === false, 'fn absent');
  assert(checkIn(withFn(), { kind: 'fn-parent', name: 'greet', parent: 'const' }) === true,
         'parent matches');
  assert(checkIn(withFn(), { kind: 'fn-parent', name: 'greet', parent: 'other' }) === false,
         'parent differs');
});

test('fn-parent resolves either client source and waits for unknown ids', () => {
  const state = withFn();
  state.lookups.fnMap.delete(PARENT.id);
  assert(checkIn(state, { kind: 'fn-parent', name: 'greet', parent: 'const' }) === true,
         'graph payload resolves a parent absent from the lazy cache');
  state.graphData.fns = [FN];
  assert(checkIn(state, { kind: 'fn-parent', name: 'greet', parent: 'const' }) === false,
         'an unresolved id cannot prove the requested parent');
  assert(checkIn(state, { kind: 'fn-parent', name: 'greet', parent: 'other' }) === false,
         'an unknown MI parent cannot prove a second parent');
});

test('binding-flag waits for the actual own row and intended mutation', () => {
  const state = withFn();
  const check = { kind: 'binding-flag', name: FN.name, slot: SLOT.name, field: 'terminal', value: true };
  assert(!checkIn(state, check), 'unknown bindings do not prove a seal');
  state.lookups.bindingsByFn.set(FN.id, []);
  assert(!checkIn(state, { ...check, value: false }), 'absence is not the copied own row');
  const own = { id: 'seal', 'fn-id': FN.id, 'slot-id': SLOT.id, terminal: false, required: true };
  state.lookups.bindingsByFn.set(FN.id, [own]);
  assert(!checkIn(state, check), 'before reseal the lesson stays on this step');
  own.terminal = true;
  assert(checkIn(state, check), 'reseal completed in actual binding state');
  assert(!checkIn(state, { ...check, value: false }), 'copied seal must actually be removed');
  own.terminal = false;
  assert(checkIn(state, { ...check, value: false }), 'copy own seal removed');
  assert(checkIn(state, { ...check, field: 'required' }), 'requiredness remained true');
  own['fn-id'] = PARENT.id;
  assert(!checkIn(state, { ...check, value: false }), 'an inherited row is not an own binding');
});

test('list-values rejects append, wrong value and wrong order', () => {
  const state = withFn();
  state.lookups.bindingsByFn.set(FN.id, [{ id: 'seq', 'slot-id': SLOT.id }]);
  const check = { kind: 'list-values', name: 'greet', slot: 'value', values: [2, 0, 1] };
  const probe = (values) => {
    state.lookups.itemsByBinding.set('seq', values.map((value) => ({ value })));
    return checkIn(state, check);
  };
  assert(probe([2, 1]) === false, 'before insertion');
  assert(probe([2, 1, 0]) === false, 'wrong append does not count as insert-before');
  assert(probe([0, 2, 1]) === false, 'wrong insertion position');
  assert(probe([2, 9, 1]) === false, 'wrong inserted value');
  assert(probe([2, 0, 1, 3]) === false, 'extra item');
  assert(probe(['2', '0', '1']) === true, 'JSON string literals round-trip');
  assert(probe([2, 0, 1]) === true, 'exact intended order');
  state.lookups.itemsByBinding.get('seq')[1]['ref-fn-id'] = 'fn-ref';
  assert(checkIn(state, check) === false, 'a fn-ref is not the requested literal');
});

test('fn-field holds description edits and restoration until the intended text lands', () => {
  const state = withFn();
  const check = { kind: 'fn-field', name: 'greet', field: 'description', value: 'first draft' };
  assert(checkIn(state, check) === false, 'an unsaved description does not complete');
  state.lookups.fnMap.get(FN.id).description = 'second draft';
  assert(checkIn(state, check) === false, 'the current second draft does not complete restoration');
  state.lookups.fnMap.get(FN.id).description = 'first draft';
  assert(checkIn(state, check) === true, 'the intended saved or restored draft completes');
  delete state.lookups.fnMap.get(FN.id).description;
});

test('ns-exists matches ROOT namespaces only', () => {
  const nested = { name: 'tutorial', 'parent-id': 'ns-root' };
  const root = { name: 'tutorial', 'parent-id': null };
  assert(checkIn({ graphData: { namespaces: [nested] } },
                 { kind: 'ns-exists', name: 'tutorial' }) === false,
         'a nested namespace of the same name does not pass');
  assert(checkIn({ graphData: { namespaces: [root] } },
                 { kind: 'ns-exists', name: 'tutorial' }) === true,
         'the root namespace passes');
});

test('binding-bound accepts a value, a ref, or list items — and nothing else', () => {
  const base = withFn();
  const bind = (b) => {
    const s = withFn();
    s.lookups.bindingsByFn = new Map([[FN.id, [b]]]);
    if (b.items) s.lookups.itemsByBinding = new Map([[b.id, b.items]]);
    return s;
  };
  const check = { kind: 'binding-bound', name: 'greet', slot: 'value' };
  assert(checkIn(base, check) === false, 'no binding at all');
  assert(checkIn(bind({ id: 'b1', 'slot-id': SLOT.id, value: 'x' }), check) === true,
         'a literal counts');
  assert(checkIn(bind({ id: 'b2', 'slot-id': SLOT.id, 'ref-fn-id': 'fn-9' }), check) === true,
         'a fn-ref counts');
  assert(checkIn(bind({ id: 'b3', 'slot-id': SLOT.id, items: [{ id: 'i1' }] }), check) === true,
         'sequence content counts — the binding row itself carries no value');
  assert(checkIn(bind({ id: 'b4', 'slot-id': SLOT.id, items: [] }), check) === false,
         'an empty sequence is not bound');
  assert(checkIn(bind({ id: 'b5', 'slot-id': 'other-slot', value: 1 }), check) === false,
         'a binding on a different slot does not satisfy this one');
});

test('binding-value compares as TEXT — jsonb round-trips change the type', () => {
  const s = withFn();
  s.lookups.bindingsByFn = new Map([[FN.id, [{ id: 'b', 'slot-id': SLOT.id, value: 42 }]]]);
  assert(checkIn(s, { kind: 'binding-value', name: 'greet', slot: 'value', value: '42' }) === true,
         'number 42 matches the string "42"');
  assert(checkIn(s, { kind: 'binding-value', name: 'greet', slot: 'value', value: '43' }) === false,
         'a different value does not match');
});

test('bindings-count counts BOUND slots, order-independent', () => {
  const s = withFn();
  s.lookups.bindingsByFn = new Map([[FN.id, [
    { id: 'b1', 'slot-id': SLOT.id, value: 'x' },
    { id: 'b2', 'slot-id': 'slot-2' },
  ]]]);
  assert(checkIn(s, { kind: 'bindings-count', name: 'greet', count: 1 }) === true,
         'one bound slot meets count 1');
  assert(checkIn(s, { kind: 'bindings-count', name: 'greet', count: 2 }) === false,
         'the unbound one is not counted');
});

test('expanded reads the COMMITTED expansion of the named card, not the preview', () => {
  const s = withFn();
  s.overlays = { greet: ['fn-root_fn-1'] };
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 1 }) === false,
         'a card with no committed expansion is folded');
  s.expansionState = new Map([['fn-root_fn-1', { fullDepth: 1, partialFns: new Set() }]]);
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 1 }) === true,
         'fullDepth 1 unfolds depth 1');
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 2 }) === false,
         'depth 2 asks for more than is unfolded');
  s.expansionState = new Map([['fn-root_fn-1', { fullDepth: 0, partialFns: new Set(['fn-parent']) }]]);
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 1 }) === true,
         'a partial expansion at the next level counts for that level');
  s.expansionState = new Map([['some-other-node', { fullDepth: 3, partialFns: new Set() }]]);
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 1 }) === false,
         'another card\'s expansion does not count');
  assert(checkIn(s, { kind: 'expanded', name: 'nobody', depth: 1 }) === false,
         'no such card on the canvas');
});

test('expanded depth 0 is the FOLDED card — on the canvas, nothing committed', () => {
  const s = withFn();
  s.overlays = { greet: ['fn-root_fn-1'] };
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 0 }) === true,
         'no committed expansion = folded');
  s.expansionState = new Map([['fn-root_fn-1', { fullDepth: 1, partialFns: new Set() }]]);
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 0 }) === false,
         'an unfolded card is not folded');
  s.expansionState = new Map([['fn-root_fn-1', { fullDepth: 0, partialFns: new Set(['fn-parent']) }]]);
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 0 }) === false,
         'a partial MI expansion is not folded either');
  s.expansionState = new Map([['fn-root_fn-1', { fullDepth: 0, partialFns: new Set() }]]);
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 0 }) === true,
         'an emptied spec counts as folded');
  s.overlays = {};
  assert(checkIn(s, { kind: 'expanded', name: 'greet', depth: 0 }) === false,
         'a card that is not on the canvas is not "folded" — the step wants it drawn');
});

test('list-items counts the ITEMS under a sequence slot, not the binding', () => {
  // `binding-bound` is true after the first append — a lesson that asks for
  // a second number (1 + 1, 2 + 2) needs the binding-list-item rows counted.
  const s = withFn();
  s.lookups.bindingsByFn = new Map([[FN.id, [{ id: 'b1', 'slot-id': SLOT.id }]]]);
  s.lookups.itemsByBinding = new Map([['b1', [{ id: 'i1', value: 1 }]]]);
  assert(checkIn(s, { kind: 'list-items', name: 'greet', slot: SLOT.name, count: 1 }) === true,
         'one item meets count 1');
  assert(checkIn(s, { kind: 'list-items', name: 'greet', slot: SLOT.name, count: 2 }) === false,
         'one item does not meet count 2');
  s.lookups.itemsByBinding = new Map([['b1', [{ id: 'i1', value: 1 }, { id: 'i2', value: 1 }]]]);
  assert(checkIn(s, { kind: 'list-items', name: 'greet', slot: SLOT.name, count: 2 }) === true,
         'two items meet count 2');
  assert(checkIn(s, { kind: 'list-items', name: 'greet', slot: 'other', count: 1 }) === false,
         'a different slot is not counted');
});

test('selected reads the current selection', () => {
  const s = withFn({ selectedFnId: FN.id });
  assert(checkIn(s, { kind: 'selected', name: 'greet' }) === true, 'selected fn matches');
  assert(checkIn(s, { kind: 'selected', name: 'const' }) === false, 'another name does not');
  assert(checkIn(withFn(), { kind: 'selected', name: 'greet' }) === false,
         'nothing selected');
});

test('on-branch treats "no ?branch=" as main', () => {
  assert(checkIn({ search: '' }, { kind: 'on-branch', name: 'main' }) === true,
         'default branch is main');
  assert(checkIn({ search: '?branch=feature' }, { kind: 'on-branch', name: 'main' }) === false,
         'on a feature branch, main does not pass');
  assert(checkIn({ search: '?branch=feature' }, { kind: 'on-branch', name: 'feature' }) === true,
         'the named branch passes');
});

test('dom / dom-absent are each other\'s inverse', () => {
  const present = { dom: { '.thing': true } };
  assert(checkIn(present, { kind: 'dom', selector: '.thing' }) === true, 'dom sees it');
  assert(checkIn(present, { kind: 'dom-absent', selector: '.thing' }) === false,
         'dom-absent does not');
  assert(checkIn({}, { kind: 'dom', selector: '.thing' }) === false, 'dom misses it');
  assert(checkIn({}, { kind: 'dom-absent', selector: '.thing' }) === true,
         'dom-absent passes on an empty page');
});

test('dom means VISIBLE — a mounted-but-hidden surface is not "open"', () => {
  // The editor keeps the Organization panels mounted from boot. Matching on
  // presence alone completed lesson 27's "open the Organization surface"
  // before the reader touched anything, and the tour walked on without them.
  const hidden = { dom: { '#gd-operate-nav button': 'hidden' } };
  assert(checkIn(hidden, { kind: 'dom', selector: '#gd-operate-nav button' }) === false,
         'a zero-sized match does not count as shown');
  assert(checkIn(hidden, { kind: 'dom-absent', selector: '#gd-operate-nav button' }) === true,
         'and for the reader it is absent — which is what dom-absent means');
});

test('input-value reads a control\'s LIVE value — what no selector can match', () => {
  // "Clear the filter" completes when #search-input reads "": the value is a
  // property, not an attribute, so `dom` could never see it change.
  const typed = { dom: { '#search-input': { value: 'str-len' } } };
  const clear = { dom: { '#search-input': { value: '' } } };
  assert(checkIn(typed, { kind: 'input-value', selector: '#search-input', value: '' }) === false,
         'a filter still holding text is not clear');
  assert(checkIn(clear, { kind: 'input-value', selector: '#search-input', value: '' }) === true,
         'an emptied filter passes');
  assert(checkIn(typed, { kind: 'input-value', selector: '#search-input', value: 'str-len' }) === true,
         'and the same kind can wait for a specific text');
  assert(checkIn({}, { kind: 'input-value', selector: '#search-input', value: '' }) === false,
         'no such control — never passes, rather than passing on an accident');
});

test('result-value verifies the current result, including the values in its rows', () => {
  const pane = '.execute-popover.visible .execute-result-pane';
  const raw = '.execute-popover.visible .execute-result-host .execute-result-raw pre';
  const state = (json, visible = true) => ({dom: {
    [pane]: visible ? true : 'hidden', [raw]: {textContent: json},
  }});
  const first = {kind: 'result-value', value: ['tick']};
  const second = {kind: 'result-value', value: ['tick', 'tick']};
  assert(checkIn(state('["tick"]'), first), 'first tick passes');
  assert(!checkIn(state('[null]'), first), 'a null row does not pass');
  assert(!checkIn(state('["wrong"]'), first), 'a different value does not pass');
  assert(!checkIn(state('["tick"]'), second), 'the previous one-row result cannot pass the second run');
  assert(checkIn(state('["tick", "tick"]'), second), 'two ticks pass');
  assert(!checkIn(state('[null, null]'), second), 'two null rows do not pass');
  assert(!checkIn(state('["tick"]', false), first), 'a hidden result does not pass');
  assert(!checkIn(state('not JSON'), first), 'an invalid result does not pass');
  assert(!checkIn({}, first), 'no result does not pass');
  assert(!checkIn(state('["tick"]'), {kind: 'result-value'}), 'a missing expected value does not pass');
});

test('successful Run checks reject errors and wrong deterministic results', () => {
  const pane = '.execute-popover.visible .execute-result-pane';
  const raw = '.execute-popover.visible .execute-result-host .execute-result-raw pre';
  const error = '.execute-popover.visible .execute-result-host .execute-error-pane';
  for (const value of [2, 10, 'ALPHA', 'BETA', '{"a":1}', 6, 'den', ['GRAPH', 'DEN']]) {
    const check = {kind: 'result-value', value};
    assert(!checkIn({dom: {[error]: true}}, check), 'an error cannot complete ' + JSON.stringify(value));
    assert(!checkIn({dom: {[pane]: true, [raw]: {textContent: 'null'}}}, check),
      'a wrong value cannot complete ' + JSON.stringify(value));
    assert(checkIn({dom: {[pane]: true, [raw]: {textContent: JSON.stringify(value)}}}, check),
      'the promised value completes ' + JSON.stringify(value));
  }
});

test('result-value accepts production scalar panes without Raw details', () => {
  const pane = '.execute-popover.visible .execute-result-pane';
  const scalar = '.execute-popover.visible .execute-result-host .execute-result-scalar';
  for (const value of [2, 10, 'ALPHA', 'BETA', '{"a":1}', 6, 'den']) {
    const state = {dom: {[pane]: true, [scalar]: {textContent: String(value)}}};
    assert(checkIn(state, {kind: 'result-value', value}), 'plain scalar ' + JSON.stringify(value));
    assert(!checkIn(state, {kind: 'result-value', value: 'wrong'}), 'wrong scalar rejected');
  }
  const nilPane = {dom: {[pane]: true, [scalar]: {textContent: 'nil'}}};
  assert(!checkIn(nilPane, {kind: 'result-value', value: ['nil']}), 'lists require Raw JSON');
});

test('review completion is scoped to the requested approved branch', () => {
  const selector = '.branch-row-approve[data-approve-branch="tutorial-feature"][data-approved="1"] + .branch-appr-count.ok';
  const check = {kind: 'dom', selector};
  assert(!checkIn({dom: {'.branch-appr-count.ok': true}}, check),
    'an approval on another branch does not complete the lesson');
  assert(checkIn({dom: {[selector]: true}}, check), 'the requested approved branch completes');
});

test('arg-named reads the edge label — the rename has no other client trace', () => {
  assert(checkIn({ edgeLabels: ['nums', 'greeting'] },
                 { kind: 'arg-named', arg: 'greeting' }) === true, 'label found');
  assert(checkIn({ edgeLabels: ['nums'] },
                 { kind: 'arg-named', arg: 'greeting' }) === false, 'label absent');
});

test('_tourFnRowHidden spots a row the LENS is hiding, not one absent', () => {
  // The dead end this exists for: the fn is in the Explorer, `hidden` because
  // the reader left the `tests` lens on, and the step's check waits forever
  // while the popover says "advances automatically when done".
  const rowSet = (name, hidden) => ({
    querySelectorAll: () => [{
      querySelector: () => ({ textContent: name }),
      hasAttribute: (a) => a === 'hidden' && hidden,
    }],
  });
  const probe = (state) => {
    const ctx = vm.createContext({
      console,
      document: {
        getElementById: () => state,
        querySelector: () => null,
        querySelectorAll: () => [],
      },
      window: { location: { search: '' } },
      URLSearchParams,
    });
    vm.runInContext(source, ctx);
    return ctx._tourFnRowHidden('greet');
  };
  assert(probe(rowSet('greet', true)) === true, 'a hidden row reports hidden');
  assert(probe(rowSet('greet', false)) === false, 'a visible row does not');
  assert(probe(rowSet('other', true)) === false,
         'a DIFFERENT fn being hidden is not this fn\'s problem');
  assert(probe(null) === false, 'no Explorer at all → false, never a throw');
});

console.log(failures ? `\n✗ tour-checks: ${failures} failed, ${passes} passed`
                     : `\n✓ tour-checks: ${passes} assertions`);
process.exit(failures ? 1 : 0);
