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
  const ctx = vm.createContext({
    console,
    graphData: state.graphData || null,
    lookups: state.lookups || null,
    selectedFnId: state.selectedFnId || null,
    window: { location: { search: state.search || '' } },
    URLSearchParams,
    document: {
      querySelector: (sel) => (domHits[sel] ? {} : null),
      // A `dom` check measures its matches — `state.dom[sel]` is `true` for a
      // visible element and `'hidden'` for one that is in the document at
      // zero size (a mounted-but-closed surface, which is how the editor
      // keeps its Organization panels).
      querySelectorAll: (sel) => {
        if (sel === '.edge-label-overlay span') {
          return labels.map((t) => ({ textContent: t }));
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

test('fn-parent accepts an unresolved parent id — the row may not be cached', () => {
  const state = withFn();
  state.lookups.fnMap.delete(PARENT.id);
  assert(checkIn(state, { kind: 'fn-parent', name: 'greet', parent: 'const' }) === true,
         'structurally parented is enough when the parent row is not loaded');
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
  // presence alone completed lesson 23's "open the Organization surface"
  // before the reader touched anything, and the tour walked on without them.
  const hidden = { dom: { '#gd-operate-nav button': 'hidden' } };
  assert(checkIn(hidden, { kind: 'dom', selector: '#gd-operate-nav button' }) === false,
         'a zero-sized match does not count as shown');
  assert(checkIn(hidden, { kind: 'dom-absent', selector: '#gd-operate-nav button' }) === true,
         'and for the reader it is absent — which is what dom-absent means');
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
