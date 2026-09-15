// Unit tests for editor-undo.js — the 30-second Undo journal. The module is
// loaded into a node vm with the editor globals it touches stubbed
// (document, window, performance, gdToast, registerShortcut), so the
// journal's contract can be pinned without a browser: record → available,
// the window expiry, newest-first, verify-refusal drops the entry, a refused
// inverse KEEPS it, a landed inverse pops it, and the toast wiring.
//
// Run:  node tools/runtime-test/undo-journal.test.js
// Exit: 0 on pass, 1 on failure.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor',
            'editor-undo.js'),
  'utf8');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes++; return; }
  failures++;
  console.error('  ✗ ' + msg);
}

async function test(name, fn) {
  console.log(' ' + name);
  try { await fn(); }
  catch (e) { failures++; console.error('  ✗ threw: ' + (e.stack || e.message)); }
}

// A tiny element stub: enough for the toast to build and toggle its class.
function el(tag) {
  const node = {
    tag, children: [], attrs: {}, listeners: {}, textContent: '',
    classes: new Set(),
    classList: {
      add: (c) => node.classes.add(c),
      remove: (c) => node.classes.delete(c),
      contains: (c) => node.classes.has(c),
    },
    set className(v) { node.classes = new Set(String(v).split(/\s+/).filter(Boolean)); },
    get className() { return [...node.classes].join(' '); },
    setAttribute: (k, v) => { node.attrs[k] = v; },
    appendChild: (c) => { node.children.push(c); return c; },
    addEventListener: (ev, fn) => { (node.listeners[ev] ||= []).push(fn); },
    click: () => { for (const fn of (node.listeners.click || [])) fn(); },
    querySelector: (sel) => {
      const cls = sel.replace(/^\./, '');
      const walk = (n) => {
        for (const c of n.children) {
          if (c.classes.has(cls)) return c;
          const deeper = walk(c);
          if (deeper) return deeper;
        }
        return null;
      };
      return walk(node);
    },
  };
  return node;
}

// One sandbox per case. `clock` is a mutable {now} the module reads through
// performance.now, so a test can move time without sleeping.
function load(opts = {}) {
  const clock = { now: 1000 };
  const toasts = [];
  const shortcuts = [];
  const body = el('body');
  const timers = [];
  const ctx = vm.createContext({
    console,
    performance: { now: () => clock.now },
    Date: { now: () => clock.now },
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    clearTimeout: () => {},
    document: { body, createElement: (tag) => el(tag) },
    window: {},
    gdToast: (msg, kind) => { toasts.push({ msg, kind }); },
    registerShortcut: (spec) => { shortcuts.push(spec); },
    authFetch: opts.authFetch,
    authMutate: opts.authMutate,
    extractResponseError: async (r) => r?.error || 'refused',
    API: { api_graph_entities: '/api/graph/entities',
           api_entities_type: (t) => '/api/entities/' + t,
           api_entities_type_id: (t, id) => '/api/entities/' + t + '/' + id },
    lookups: opts.lookups || null,
    initGraph: async () => { ctx.__inits = (ctx.__inits || 0) + 1; },
    selectFnByName: async (name) => { ctx.__selected = name; },
    gdClearSelection: () => { ctx.__cleared = (ctx.__cleared || 0) + 1; },
  });
  vm.runInContext(source, ctx);
  return { ctx, clock, toasts, shortcuts, body, timers };
}

(async () => {
  await test('record → available, label, toast shown; the leader key is declared', () => {
    const t = load();
    assert(t.ctx.gdUndoAvailable() === false, 'empty journal → nothing to undo');
    t.ctx.gdUndoRecord({ label: 'Created foo', undo: async () => ({ ok: true }) });
    assert(t.ctx.gdUndoAvailable() === true, 'a recorded gesture is undoable');
    assert(t.ctx.gdUndoLastLabel() === 'Created foo', 'the newest label is reported');
    const toast = t.body.children.find((c) => c.attrs.id === 'gd-undo-toast'
      || c.classes.has('gd-undo-toast'));
    assert(toast && toast.classes.has('gd-undo-toast-visible'), 'the undo toast is visible');
    assert(toast.querySelector('.gd-undo-toast-label').textContent === 'Created foo',
           'the toast names the gesture');
    assert(t.timers.some((x) => x.ms === 10000), 'the toast hides itself after 10 s (the entry outlives it)');
    const sc = t.shortcuts.find((s) => s.id === 'undo');
    assert(sc && sc.keys === 'u' && sc.leader !== false, 'Space u is registered behind the leader');
    assert(sc.when() === true, 'the binding is live while an entry is');
    const chord = t.shortcuts.find((s) => s.id === 'undo-chord');
    assert(chord && chord.keys === 'Mod+z' && chord.leader === false, 'Mod+z is registered as a bare chord');
  });

  await test('the toast holds under the pointer and resumes with the time left', () => {
    const t = load();
    t.ctx.gdUndoRecord({ label: 'Created foo', undo: async () => ({ ok: true }) });
    const toast = t.body.children.find((c) => c.classes.has('gd-undo-toast'));
    assert(t.timers.length === 1 && t.timers[0].ms === 10000, 'armed for 10 s');
    t.clock.now += 4000;
    for (const fn of (toast.listeners.mouseenter || [])) fn();
    assert(t.timers.length === 1, 'pausing arms nothing new');
    t.clock.now += 60000;
    for (const fn of (toast.listeners.mouseleave || [])) fn();
    assert(t.timers.length === 2 && t.timers[1].ms === 6000,
           'leaving re-arms with the 6 s that were left: ' + JSON.stringify(t.timers.map((x) => x.ms)));
    assert(toast.classes.has('gd-undo-toast-visible'), 'still visible while held');
  });

  await test('entries expire after 30 s', () => {
    const t = load();
    t.ctx.gdUndoRecord({ label: 'Created foo', undo: async () => ({ ok: true }) });
    t.clock.now += 29000;
    assert(t.ctx.gdUndoAvailable() === true, 'still live at 29 s');
    t.clock.now += 2000;
    assert(t.ctx.gdUndoAvailable() === false, 'gone at 31 s');
    assert(t.ctx.gdUndoLastLabel() === null, 'no label once expired');
  });

  await test('undo runs the NEWEST entry, pops it, and toasts "Undone"', async () => {
    const t = load();
    const ran = [];
    t.ctx.gdUndoRecord({ label: 'first', undo: async () => { ran.push('first'); return { ok: true }; } });
    t.ctx.gdUndoRecord({ label: 'second', undo: async () => { ran.push('second'); return { ok: true }; } });
    assert(await t.ctx.gdUndoLast() === true, 'the inverse landed');
    assert(ran.join() === 'second', 'newest first: ' + ran.join());
    assert(t.ctx.gdUndoLastLabel() === 'first', 'the older entry is next');
    assert(t.toasts.some((x) => x.msg === 'Undone: second' && !x.kind), 'plain "Undone" toast');
    assert(await t.ctx.gdUndoLast() === true, 'and the older one undoes too');
    assert(t.ctx.gdUndoAvailable() === false, 'journal drained');
  });

  await test('a refused inverse keeps the entry and reports the reason as an error', async () => {
    const t = load();
    t.ctx.gdUndoRecord({ label: 'Created foo',
                         undo: async () => ({ ok: false, error: 'in use by bar' }) });
    assert(await t.ctx.gdUndoLast() === false, 'refused');
    assert(t.ctx.gdUndoAvailable() === true, 'entry kept for a retry within the window');
    const err = t.toasts.find((x) => x.kind === 'error');
    assert(err && /in use by bar/.test(err.msg), 'the server\'s reason is shown: ' + err?.msg);
  });

  await test('a throwing inverse is a refusal, not a crash', async () => {
    const t = load();
    t.ctx.gdUndoRecord({ label: 'x', undo: async () => { throw new Error('boom'); } });
    assert(await t.ctx.gdUndoLast() === false, 'refused');
    assert(t.toasts.some((x) => x.kind === 'error' && /boom/.test(x.msg)), 'the error is reported');
  });

  await test('verify: a stale entry is dropped with a message, its inverse never runs', async () => {
    const t = load();
    let ran = false;
    t.ctx.gdUndoRecord({ label: 'Renamed a → b',
                         verify: async () => 'the fn was renamed again since (now c)',
                         undo: async () => { ran = true; return { ok: true }; } });
    assert(await t.ctx.gdUndoLast() === false, 'not undone');
    assert(ran === false, 'the inverse did not run');
    assert(t.ctx.gdUndoAvailable() === false, 'the stale entry is gone');
    assert(t.toasts.some((x) => x.kind === 'error' && /renamed again/.test(x.msg)), 'the reason is shown');
  });

  await test('rename recorder: verify reads the live name, undo PUTs the old one back', async () => {
    const puts = [];
    const t = load({
      lookups: { fnMap: new Map([['f1', { id: 'f1', name: 'new-name' }]]) },
      authMutate: async (method, url, body) => { puts.push({ method, url, body }); return { status: 200 }; },
    });
    t.ctx.gdUndoRecordRename('f1', 'old-name', 'new-name');
    assert(t.ctx.gdUndoLastLabel() === 'Renamed old-name → new-name', 'label');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(puts.length === 1 && puts[0].method === 'PUT' && puts[0].url === '/api/entities/fn/f1'
           && puts[0].body.name === 'old-name', 'PUT name=old-name: ' + JSON.stringify(puts));
    assert(t.ctx.__inits === 1, 'the graph is reloaded after the inverse');
    // Renamed again by someone else → stale.
    t.ctx.gdUndoRecordRename('f1', 'x', 'y');
    t.ctx.lookups.fnMap.set('f1', { id: 'f1', name: 'z' });
    assert(await t.ctx.gdUndoLast() === false, 'stale when the live name differs');
  });

  await test('created-fn recorder: finds the row by name + ns and DELETEs it; 409 is a refusal', async () => {
    const calls = [];
    const t = load({
      authFetch: async (url) => {
        calls.push(url);
        return { json: async () => ({ fns: [
          { id: 'other', name: 'foo', 'namespace-id': 'ns-b' },
          { id: 'mine', name: 'foo', 'namespace-id': 'ns-a' },
        ] }) };
      },
      authMutate: async (method, url) => {
        calls.push(method + ' ' + url);
        return url.endsWith('/mine') ? { status: 200 } : { status: 409, error: 'in use' };
      },
    });
    t.ctx.gdUndoRecordCreatedFn('foo', 'ns-a', 'core.parent');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls.some((c) => c === 'DELETE /api/entities/fn/mine'), 'the ns-qualified match was deleted: ' + calls.join(' | '));
    assert(t.ctx.__inits === 1 && t.ctx.__selected === 'core.parent',
           'the editor goes back to the parent the child was made from');
    // A fn made from the Explorer has no parent to return to: the selection
    // is dropped outright and the graph reloaded.
    t.ctx.gdUndoRecordCreatedFn('foo', 'ns-a');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(t.ctx.__cleared === 1 && t.ctx.__inits === 2, 'no parent → selection cleared + reload');
    // A fn that is now in use: the server refuses, the entry stays.
    t.ctx.gdUndoRecordCreatedFn('foo', 'ns-b');
    assert(await t.ctx.gdUndoLast() === false, 'refused');
    assert(t.ctx.gdUndoAvailable() === true, 'kept for a retry');
    assert(t.toasts.some((x) => x.kind === 'error' && /in use/.test(x.msg)), 'reason shown');
  });

  await test('deleted-fn recorder: undo POSTs the revive and reopens the fn', async () => {
    const calls = [];
    const t = load({
      authMutate: async (method, url) => { calls.push([method, url]); return { status: url.endsWith('/gone/revive') ? 404 : 200, error: 'Nothing to revive' }; },
    });
    t.ctx.gdUndoRecordDeletedFn('f1', 'core.foo');
    assert(t.ctx.gdUndoLastLabel() === 'Deleted core.foo', 'label names the fn');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[0][0] === 'POST' && calls[0][1] === '/api/entities/fn/f1/revive', 'POST …/revive: ' + JSON.stringify(calls[0]));
    assert(t.ctx.__inits === 1 && t.ctx.__selected === 'core.foo', 'the graph reloads and the fn is reopened');
    t.ctx.gdUndoRecordDeletedFn('gone', 'core.gone');
    assert(await t.ctx.gdUndoLast() === false, 'a purged tombstone is a refusal');
    assert(t.toasts.some((x) => x.kind === 'error' && /Nothing to revive/.test(x.msg)), 'the server\'s reason is shown');
  });

  await test('namespace recorders: a rename PUTs the old name back; a delete re-creates it under its parent', async () => {
    const calls = [];
    const t = load({
      lookups: { nsMap: new Map([['ns-1', { id: 'ns-1', name: 'new', 'parent-id': 'ns-0' }]]) },
      authMutate: async (method, url, body) => { calls.push([method, url, body]); return { status: 200 }; },
    });
    t.ctx.gdUndoRecordNsRenamed('ns-1', 'old', 'new');
    assert(t.ctx.gdUndoLastLabel() === 'Renamed namespace old → new', 'label');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[0][0] === 'PUT' && calls[0][1] === '/api/entities/ns/ns-1' && calls[0][2].name === 'old', 'PUT name=old: ' + JSON.stringify(calls[0]));
    t.ctx.gdUndoRecordNsRenamed('ns-1', 'a', 'b');
    t.ctx.lookups.nsMap.set('ns-1', { id: 'ns-1', name: 'c', 'parent-id': 'ns-0' });
    assert(await t.ctx.gdUndoLast() === false, 'renamed again since → stale');
    t.ctx.gdUndoRecordDeletedNs('gone', 'ns-0');
    assert(t.ctx.gdUndoLastLabel() === 'Deleted namespace gone', 'label');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[1][0] === 'POST' && calls[1][1] === '/api/entities/ns' && calls[1][2].name === 'gone' && calls[1][2]['parent-id'] === 'ns-0',
           'POST re-creates it under its parent: ' + JSON.stringify(calls[1]));
    t.ctx.gdUndoRecordDeletedNs('root-ns', null);
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[2][2]['parent-id'] === '', 'a root namespace re-creates with no parent');
  });

  await test('ns-move recorder: undo PUTs the old namespace; root is the bare key', async () => {
    const puts = [];
    const t = load({
      lookups: { fnMap: new Map([['f1', { id: 'f1', name: 'foo', 'namespace-id': 'ns-b' }]]),
                 nsPathMap: new Map([['ns-a', 'core.a'], ['ns-b', 'core.b']]) },
      authMutate: async (method, url, body) => { puts.push(body); return { status: 200 }; },
    });
    t.ctx.gdUndoRecordNsMove('f1', 'foo', 'ns-a', 'ns-b');
    assert(t.ctx.gdUndoLastLabel() === 'Moved foo to core.b', 'label names the destination');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(puts[0] && puts[0]['namespace-id'] === 'ns-a', 'moved back to ns-a');
    t.ctx.lookups.fnMap.set('f1', { id: 'f1', name: 'foo', 'namespace-id': 'ns-a' });
    t.ctx.gdUndoRecordNsMove('f1', 'foo', null, 'ns-a');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(puts[1] === 'namespace-id=', 'root spelled as the bare key: ' + JSON.stringify(puts[1]));
  });

  await test('binding write recorder: POST → DELETE the row; PUT → the pre-image back, both columns', async () => {
    const calls = [];
    const slot = { id: 's1', name: 'string' };
    const bindingRow = { id: 'b1', 'fn-id': 'f1', 'slot-id': 's1', value: 'old', 'ref-fn-id': null };
    const t = load({
      lookups: { slotMap: new Map([['s1', slot]]),
                 bindingByFnSlot: new Map([['f1|s1', bindingRow]]),
                 bindingMap: new Map([['b1', bindingRow]]) },
      authMutate: async (method, url, body) => { calls.push([method, url, body]); return { status: 200 }; },
    });
    t.ctx.loadGraphData = async () => { t.ctx.__loads = (t.ctx.__loads || 0) + 1; };
    // A fresh binding (no pre-image): undo deletes it.
    t.ctx.gdUndoRecordBindingWrite({ 'fn-id': 'f1', 'slot-id': 's1' }, { value: '"x"' }, null);
    assert(t.ctx.gdUndoLastLabel() === 'Bound :string', 'label names the slot');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[0][0] === 'DELETE' && calls[0][1] === '/api/entities/binding/b1', 'the binding row was deleted: ' + JSON.stringify(calls[0]));
    assert(t.ctx.__loads === 1, 'bindings reload (loadGraphData) after the inverse');
    // A changed binding: verify against the live row, then PUT value + ref back.
    bindingRow.value = 'new';
    t.ctx.gdUndoRecordBindingWrite({ 'fn-id': 'f1', 'slot-id': 's1', 'binding-id': 'b1' }, { value: '"new"' },
                                   { id: 'b1', 'fn-id': 'f1', 'slot-id': 's1', value: 'old', 'ref-fn-id': null });
    assert(t.ctx.gdUndoLastLabel() === 'Changed :string', 'label');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    const put = calls[1];
    assert(put[0] === 'PUT' && put[1] === '/api/entities/binding/b1' && /value=%22old%22/.test(put[2]) && /ref-fn-id=(&|$)/.test(put[2]),
           'PUT restores the old value and clears the ref: ' + JSON.stringify(put));
    // Changed again by someone else → stale, no write.
    t.ctx.gdUndoRecordBindingWrite({ 'fn-id': 'f1', 'slot-id': 's1', 'binding-id': 'b1' }, { value: '"new"' },
                                   { id: 'b1', 'fn-id': 'f1', 'slot-id': 's1', value: 'old' });
    bindingRow.value = 'newer';
    assert(await t.ctx.gdUndoLast() === false, 'stale');
    assert(calls.length === 2, 'no write for a stale entry');
  });

  await test('deleted-binding recorder: undo re-creates it with its value or ref', async () => {
    const calls = [];
    const t = load({
      lookups: { slotMap: new Map([['s1', { id: 's1', name: 'x' }]]) },
      authMutate: async (method, url, body) => { calls.push([method, url, body]); return { status: 200 }; },
    });
    t.ctx.loadGraphData = async () => {};
    t.ctx.gdUndoRecordBindingDeleted({ id: 'b1', 'fn-id': 'f1', 'slot-id': 's1', value: 42 });
    assert(t.ctx.gdUndoLastLabel() === 'Unbound :x', 'label');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[0][0] === 'POST' && calls[0][2] === 'fn-id=f1&slot-id=s1&value=42', 'POST with the value: ' + calls[0][2]);
    t.ctx.gdUndoRecordBindingDeleted({ id: 'b2', 'fn-id': 'f1', 'slot-id': 's1', 'ref-fn-id': 'other' });
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[1][2] === 'fn-id=f1&slot-id=s1&ref-fn-id=other', 'POST with the ref: ' + calls[1][2]);
  });

  await test('sequence recorders: append → delete the matching item; remove → append back; value; move', async () => {
    const calls = [];
    const items = [{ id: 'i1', 'binding-id': 'b1', position: 0, value: 1 }, { id: 'i2', 'binding-id': 'b1', position: 1, value: 7 }];
    const t = load({
      lookups: { bindingsByFn: new Map([['f1', [{ id: 'b1', 'fn-id': 'f1', 'slot-id': 's1' }]]]),
                 itemsByBinding: new Map([['b1', items]]),
                 itemByItemId: new Map(items.map((it) => [it.id, it])),
                 bindingMap: new Map([['b1', { id: 'b1', 'fn-id': 'f1', 'slot-id': 's1' }]]) },
      authMutate: async (method, url, body) => { calls.push([method, url, body]); return { status: 200 }; },
      authFetch: async (url, opts) => { calls.push([opts?.method || 'GET', url, opts?.body]); return { status: 200 }; },
    });
    t.ctx.API.api_sequence_item_item_id = (id) => '/api/sequence/item/' + id;
    t.ctx.API.api_sequence_append_fn_id = (id) => '/api/sequence/append/' + id;
    t.ctx.API.api_sequence_move_item_id = (id) => '/api/sequence/move/' + id;
    t.ctx.loadGraphData = async () => {};
    t.ctx.gdUndoRecordSeqAppend('f1', { value: 7 });
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[0][0] === 'DELETE' && calls[0][1] === '/api/sequence/item/i2', 'the newest item carrying 7 is deleted: ' + JSON.stringify(calls[0]));
    t.ctx.gdUndoRecordSeqAppend('f1', { value: 99 });
    assert(await t.ctx.gdUndoLast() === false, 'no item carries 99 → refused');
    t.ctx.gdUndoRecordSeqRemoved({ id: 'i1', 'binding-id': 'b1', position: 0, value: 1 });
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[1][0] === 'POST' && calls[1][1] === '/api/sequence/append/f1' && JSON.parse(calls[1][2]).position === 0,
           'appended back at its old position: ' + JSON.stringify(calls[1]));
    t.ctx.gdUndoRecordSeqValue('i1', 1, 5);
    items[0].value = 5;
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[2][0] === 'PUT' && JSON.parse(calls[2][2]).value === 1, 'old item value written back: ' + JSON.stringify(calls[2]));
    t.ctx.gdUndoRecordSeqMove('i2', 'up');
    assert(await t.ctx.gdUndoLast() === true, 'undone');
    assert(calls[3][1] === '/api/sequence/move/i2' && JSON.parse(calls[3][2]).direction === 'down', 'moved back down: ' + JSON.stringify(calls[3]));
  });

  console.log(failures === 0
    ? '\n✓ undo-journal: ' + passes + ' assertions'
    : '\n✗ undo-journal: ' + failures + ' failed, ' + passes + ' passed');
  process.exit(failures === 0 ? 0 : 1);
})();
