// `editor-shortcuts.js` — the registry and the dispatch rules.
//
// The e2e suite proves the keys work in a browser. This covers the decision
// logic underneath, where the bugs are cheap to introduce and invisible from
// the outside: which binding a key sequence resolves to, when a binding is
// inert, and — the two that actually broke during development — whether a
// key is ours at all.
//
// Those two guards are the whole risk of a global dispatcher:
//   - a key another handler already consumed must not fire a shortcut
//     (Escape closing a dialog would otherwise ALSO end the tour);
//   - a bare letter belongs to whatever the user is typing into.
//
// Run:  node tools/runtime-test/shortcuts.test.js
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

/**
 * Load the module with a document whose keydown listeners we can fire by
 * hand, and an activeElement we control.
 */
function makeCtx() {
  const base = createDocument();
  const winListeners = {};
  const docListeners = {};
  // Capture the ORIGINAL factory before the Object.assign below overwrites
  // it — assign mutates `base` in place, so a wrapper that reads
  // `base.createElement` at call time ends up calling itself.
  const nativeCreate = base.createElement;
  const create = (tag) => {
    const node = nativeCreate(tag);
    // mini-dom has no replaceChildren; the which-key menu rebuilds itself
    // with it on every keystroke.
    node.replaceChildren = function replaceChildren() { this.children.length = 0; };
    return node;
  };
  const body = create('body');

  const doc = Object.assign(base, {
    addEventListener(type, fn) { (docListeners[type] = docListeners[type] || []).push(fn); },
    activeElement: null,
    getElementById: () => null,
    querySelector: () => null,
    querySelectorAll: () => [],
    readyState: 'complete',
    createElement: create,
    body,
  });

  const win = {
    addEventListener(type, fn) { (winListeners[type] = winListeners[type] || []).push(fn); },
  };

  const ctx = vm.createContext({
    console,
    document: doc,
    window: win,
    requestAnimationFrame: (fn) => fn(),
    // The module reaches for these only inside `run`/`when` of the built-in
    // bindings, which these tests do not fire.
    focusSafely: () => true,
    navZoom: () => {},
    navResetZoom: () => {},
    navGoToRoot: () => {},
    navResetPositions: () => {},
    installTabTrap: () => {},
    setSiblingsInert: () => {},
    focusIntoDialog: () => {},
    returnFocusTo: () => {},
  });
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-shortcuts.js'), 'utf8'),
                  ctx, { filename: 'editor-shortcuts.js' });

  // Fire a key at the window handler the module installed.
  const press = (key, opts = {}) => {
    const e = {
      key,
      shiftKey: false, metaKey: false, ctrlKey: false, altKey: false,
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; },
      ...opts,
    };
    for (const fn of winListeners.keydown || []) fn(e);
    return e;
  };
  const focusOn = (el) => { doc.activeElement = el; };
  return { ctx, doc, press, focusOn };
}

function el(tag, attrs = {}) {
  return {
    tagName: tag.toUpperCase(),
    isContentEditable: false,
    getAttribute: (k) => attrs[k] ?? null,
    hasAttribute: (k) => k in attrs,
    closest: () => null,
    classList: { contains: () => false },
  };
}


test('a bare key runs its binding, and only when it is not typing', () => {
  const { ctx, press, focusOn } = makeCtx();
  let ran = 0;
  ctx.window.registerShortcut({ id: 't-bare', keys: 'z', leader: false, group: 'T',
                         description: 'probe', run: () => { ran += 1; } });

  focusOn(el('div'));
  press('z');
  assert(ran === 1, 'fires with focus on a plain element, ran ' + ran);

  focusOn(el('input'));
  press('z');
  assert(ran === 1, 'does NOT fire while typing in an input, ran ' + ran);

  focusOn(el('textarea'));
  press('z');
  assert(ran === 1, 'nor in a textarea, ran ' + ran);
});


test('a key another handler consumed is left alone', () => {
  const { ctx, press, focusOn } = makeCtx();
  let ran = 0;
  ctx.window.registerShortcut({ id: 't-consumed', keys: 'q', leader: false, group: 'T',
                         description: 'probe', run: () => { ran += 1; } });
  focusOn(el('div'));
  press('q', { defaultPrevented: true });
  assert(ran === 0,
         'defaultPrevented means someone already acted on it, ran ' + ran);
});


test('modifiers are not ours', () => {
  const { ctx, press, focusOn } = makeCtx();
  let ran = 0;
  ctx.window.registerShortcut({ id: 't-mod', keys: 'k', leader: false, group: 'T',
                         description: 'probe', run: () => { ran += 1; } });
  focusOn(el('div'));
  press('k', { metaKey: true });
  press('k', { ctrlKey: true });
  press('k', { altKey: true });
  assert(ran === 0, 'Cmd/Ctrl/Alt combinations belong to the browser, ran ' + ran);
});


test('leader sequences resolve one key at a time', () => {
  const { ctx, press, focusOn } = makeCtx();
  let ran = 0;
  ctx.window.registerShortcut({ id: 't-seq', keys: 'g z', group: 'T',
                         description: 'probe', run: () => { ran += 1; } });
  focusOn(el('div'));

  press(' ');                       // leader
  assert(ran === 0, 'the leader alone runs nothing');
  press('g');                       // still a prefix
  assert(ran === 0, 'a prefix alone runs nothing');
  press('z');                       // completes it
  assert(ran === 1, 'the full sequence runs it, ran ' + ran);

  // And the sequence is NOT reachable without the leader.
  press('g');
  press('z');
  assert(ran === 1, 'the same keys without the leader do nothing, ran ' + ran);
});


test('a dead end leaves the leader rather than swallowing keys', () => {
  const { ctx, press, focusOn } = makeCtx();
  let ran = 0;
  ctx.window.registerShortcut({ id: 't-dead', keys: 'g z', group: 'T',
                         description: 'probe', run: () => { ran += 1; } });
  focusOn(el('div'));
  press(' ');
  press('x');                       // no binding starts with x
  // The menu is closed now, so a bare key works again immediately.
  ctx.window.registerShortcut({ id: 't-after', keys: 'y', leader: false, group: 'T',
                         description: 'probe', run: () => { ran += 10; } });
  press('y');
  assert(ran === 10, 'the dispatcher recovered after the dead end, ran ' + ran);
});


test('Space activates a focused control instead of opening the menu', () => {
  const { ctx, press, focusOn } = makeCtx();
  ctx.window.registerShortcut({ id: 't-leader', keys: 'b', group: 'T',
                         description: 'probe', run: () => {} });
  focusOn(el('button'));
  const e = press(' ');
  assert(!e.defaultPrevented,
         'Space on a button is left for the button — the leader must not claim it');

  focusOn(el('div', { role: 'button' }));
  const e2 = press(' ');
  assert(!e2.defaultPrevented, 'same for role="button"');
});


test('a `when` predicate makes a binding inert', () => {
  const { ctx, press, focusOn } = makeCtx();
  let ran = 0;
  let available = false;
  ctx.window.registerShortcut({ id: 't-when', keys: 'w', leader: false, group: 'T',
                         description: 'probe', when: () => available,
                         run: () => { ran += 1; } });
  focusOn(el('div'));
  press('w');
  assert(ran === 0, 'inert while `when` is false, ran ' + ran);
  available = true;
  press('w');
  assert(ran === 1, 'live once it is true, ran ' + ran);
});


test('re-registering an id replaces rather than duplicates', () => {
  const { ctx, press, focusOn } = makeCtx();
  let a = 0;
  let b = 0;
  ctx.window.registerShortcut({ id: 't-dup', keys: 'd', leader: false, group: 'T',
                         description: 'first', run: () => { a += 1; } });
  ctx.window.registerShortcut({ id: 't-dup', keys: 'd', leader: false, group: 'T',
                         description: 'second', run: () => { b += 1; } });
  focusOn(el('div'));
  press('d');
  assert(a === 0 && b === 1, 'the later registration wins (a=' + a + ' b=' + b + ')');

  const groups = ctx.window.gdShortcutGroups();
  const list = groups.get('T') || [];
  assert(list.filter((s) => s.id === 't-dup').length === 1,
         'and the registry holds one entry, not two');
});


test('the built-in Surfaces group reaches gdShellSurface — and is inert without it', () => {
  const { ctx, press, focusOn } = makeCtx();
  focusOn(el('div'));

  // Shell not loaded yet: `when` gates the whole group off.
  press(' ');
  press('v');
  press('s');
  // Nothing to assert directly (no run target exists) — but the dispatcher
  // must have recovered; a bare probe still works.
  let probe = 0;
  ctx.window.registerShortcut({ id: 't-probe', keys: 'y', leader: false, group: 'T',
                         description: 'probe', run: () => { probe += 1; } });
  press('y');
  assert(probe === 1, 'dispatcher recovered from the gated-off sequence');

  // Shell present: each sequence lands on the right surface name.
  const calls = [];
  ctx.window.gdShellSurface = (name) => calls.push(name);
  for (const [key, want] of [['s', 'settings'], ['o', 'operate'], ['b', 'build']]) {
    press(' ');
    press('v');
    press(key);
    assert(calls[calls.length - 1] === want,
           'Space v ' + key + ' → ' + want + ' (got ' + calls[calls.length - 1] + ')');
  }
  // Platform stays gated behind the capability even with the shell present.
  press(' ');
  press('v');
  press('p');
  assert(!calls.includes('platform'), 'Space v p is inert without the platform right');
});


test('the cheatsheet groups come from the registry', () => {
  const { ctx } = makeCtx();
  ctx.window.registerShortcut({ id: 't-grouped', keys: 'p', leader: false, group: 'Probe group',
                         description: 'a probe binding', run: () => {} });
  const groups = ctx.window.gdShortcutGroups();
  assert(groups.has('Probe group'), 'a new group appears without touching the cheatsheet');
  const entry = groups.get('Probe group').find((s) => s.id === 't-grouped');
  assert(entry && entry.description === 'a probe binding',
         'carrying the description the cheatsheet renders');
});


console.log(failures === 0
  ? `\n✓ shortcuts: ${passes} assertions`
  : `\n✗ shortcuts: ${failures} failed, ${passes} passed`);
process.exit(failures === 0 ? 0 : 1);
