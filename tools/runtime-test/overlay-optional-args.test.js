// Unit tests for appendOptionalArgsStrip (editor-overlay-strips.js) — the
// `?name` chips on a fn card.
//
// The chips are the ONLY way to bind an arg that reaches a fn by propagation
// (a component's `{:as :label}` inside its parent's hiccup children). Such an
// arg has no `fn_slot` row, so it grows no `+` placeholder — before the chips
// became binders, `{:parent :button :args {:label "Run"}}` was expressible in
// a fns.edn and through MCP but not in the editor at all.
//
// Two things are worth pinning: WHO gets a binder (the same gate the `+`
// placeholders use — signed in, not a package-synced fn) and WHERE a click
// goes (a list-typed chip holds items, so it must reach the sequence-append
// flow; binding one ref there would type-fail).
//
// The function is a pure DOM builder, so it runs in a node vm over a stub
// document — no browser, no stack.
//
// Run:  node tools/runtime-test/overlay-optional-args.test.js
// Exit: 0 on pass, 1 on failure.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor',
            'editor-overlay-strips.js'),
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

// --- the smallest document that satisfies the builder ------------------------

function makeNode(tag) {
  return {
    tagName: tag,
    className: '',
    textContent: '',
    title: '',
    children: [],
    listeners: {},
    appendChild(c) { this.children.push(c); return c; },
    addEventListener(type, fn) { this.listeners[type] = fn; },
  };
}

const stubDocument = {
  createElement: (tag) => makeNode(tag),
  createTextNode: (t) => ({ tagName: '#text', textContent: t }),
};

// `state` picks the session: {authed, packageOwned, listSlots: Set(slotId)}.
// Returns {strip, calls} — `calls` records which edit flow a click reached.
function buildStrip(entries, state) {
  const s = state || {};
  const calls = { bind: [], append: [] };
  const overlay = makeNode('div');
  const slotMap = new Map([
    ['slot-label', { id: 'slot-label', 'type-fn-id': 'fn-text', description: 'The caption' }],
    ['slot-children', { id: 'slot-children', 'type-fn-id': 'fn-list' }],
  ]);
  const fnMap = new Map([
    ['fn-1', { id: 'fn-1', name: 'my-button' }],
    ['fn-text', { id: 'fn-text', name: 'text' }],
    ['fn-list', { id: 'fn-list', name: 'sequence' }],
  ]);
  const ctx = vm.createContext({
    console,
    document: stubDocument,
    lookups: { slotMap, fnMap },
    richTypes: { 'my-button': { args: { label: 'text' } } },
    isAuthenticated: () => !!s.authed,
    isPackageOwnedFn: () => !!s.packageOwned,
    formatTypeHint: (t) => String(t),
    findSlotDeclaringFn: () => null,
    // A list-typed slot answers with its element type; everything else null.
    seqElemType: (arg) => ((s.listSlots || new Set()).has(arg['slot-id']) ? 'any' : null),
    enterFreeArgBindEditMode: (argRow, anchor) => calls.bind.push({ argRow, anchor }),
    appendSequenceItem: (fnId, anchor, elemT) => calls.append.push({ fnId, anchor, elemT }),
  });
  vm.runInContext(source, ctx);
  ctx.appendOptionalArgsStrip(overlay, entries, s.fnId === undefined ? 'fn-1' : s.fnId);
  return { strip: overlay.children[0], calls, overlay };
}

const LABEL = { name: 'label', 'slot-id': 'slot-label' };
const CHILDREN = { name: 'children', 'slot-id': 'slot-children' };

// --- cases ------------------------------------------------------------------

test('no chips at all when there are no optional args', () => {
  assert(buildStrip([], { authed: true }).overlay.children.length === 0,
         'an empty list appends nothing');
  assert(buildStrip(null, { authed: true }).overlay.children.length === 0,
         'a missing list appends nothing');
});

test('signed out: chips stay inert spans', () => {
  const { strip } = buildStrip([LABEL], { authed: false });
  const chip = strip.children[0];
  assert(chip.tagName === 'span', 'a chip an anonymous reader cannot use is not a button');
  assert(chip.className !== 'optional-arg-binder', 'and carries no binder class');
  assert(strip.title.startsWith('Optional args (unset, using defaults)'),
         'the strip title describes, it does not invite: ' + strip.title);
});

test('a package-synced fn is not bindable — the boot sync owns its bindings', () => {
  const { strip } = buildStrip([LABEL], { authed: true, packageOwned: true });
  assert(strip.children[0].tagName === 'span', 'no binder on a package-owned fn');
});

test('signed in on an own fn: each chip is a binder button', () => {
  const { strip } = buildStrip([LABEL], { authed: true });
  const chip = strip.children[0];
  assert(chip.tagName === 'button', 'the chip is a button, so it is keyboard-reachable');
  assert(chip.type === 'button', 'and never submits a form');
  assert(chip.className === 'optional-arg-binder', 'carries the binder class');
  assert(chip.textContent === '?label', 'still reads as the arg name');
  assert(chip.title.endsWith(' — click to bind'), 'says what a click does: ' + chip.title);
  assert(strip.title.startsWith('Unset args — click one to bind it'),
         'the strip title invites: ' + strip.title);
});

test('a plain-string entry (older payload) has no slot — nothing to bind', () => {
  const { strip } = buildStrip(['label'], { authed: true });
  const chip = strip.children[0];
  assert(chip.tagName === 'span', 'no slot-id means no binder');
  assert(chip.textContent === '?label', 'but the chip still renders');
});

test('a scalar chip opens the literal / fn-ref chooser with the origin slot', () => {
  const { strip, calls } = buildStrip([LABEL], { authed: true });
  strip.children[0].listeners.click({ stopPropagation() {} });
  assert(calls.append.length === 0, 'a scalar does not reach the sequence flow');
  assert(calls.bind.length === 1, 'the bind flow opened');
  const row = calls.bind[0].argRow;
  assert(row['fn-id'] === 'fn-1' && row['slot-id'] === 'slot-label',
         'the write targets (this fn, the ORIGIN slot) — the row package sync would write');
  assert(row['binding-id'] === null, 'no binding exists yet — that is the point');
  assert(row.name === 'label' && row.type === 'text',
         'the chooser gets the name and the slot type');
  assert(row.description === 'The caption', 'and the slot description');
  assert(calls.bind[0].anchor === strip.children[0], 'the popover anchors on the chip');
});

test('a list-typed chip appends an ITEM instead — a container holds items', () => {
  const { strip, calls } = buildStrip([CHILDREN], {
    authed: true, listSlots: new Set(['slot-children']),
  });
  strip.children[0].listeners.click({ stopPropagation() {} });
  assert(calls.bind.length === 0, 'binding one ref into a list would type-fail');
  assert(calls.append.length === 1, 'the sequence-append flow opened');
  assert(calls.append[0].fnId === 'fn-1', 'on this fn');
  assert(calls.append[0].elemT === 'any', 'carrying the element type');
});

test('the click never reaches the card underneath', () => {
  let stopped = false;
  const { strip } = buildStrip([LABEL], { authed: true });
  strip.children[0].listeners.click({ stopPropagation() { stopped = true; } });
  assert(stopped, 'propagation is stopped — otherwise the card selects instead');
});

test('mixed chips keep their separators and each get their own flow', () => {
  const { strip, calls } = buildStrip([LABEL, CHILDREN], {
    authed: true, listSlots: new Set(['slot-children']),
  });
  const kinds = strip.children.map((c) => c.tagName);
  assert(kinds.join(',') === 'button,#text,button',
         'chips are separated by a text node: ' + kinds.join(','));
  strip.children[0].listeners.click({ stopPropagation() {} });
  strip.children[2].listeners.click({ stopPropagation() {} });
  assert(calls.bind.length === 1 && calls.append.length === 1,
         'the scalar bound, the list appended');
});

console.log(failures ? `\n✗ overlay-optional-args: ${failures} failed, ${passes} passed`
                     : `\n✓ overlay-optional-args: ${passes} assertions`);
process.exit(failures ? 1 : 0);
