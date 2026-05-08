// Regression tests for binding inheritance through the layout
// pipeline. Pinning behavior here gives us a safety net before
// rewriting the source-id-chain `add-bindings-from-fn` keying onto
// slot-id directly.
//
// Three scenarios:
//   (a) descendant inherits parent's scalar binding
//       — bound slot must NOT render as a free placeholder on child
//   (b) descendant inherits parent's sequence
//       — list items must be reachable via expansion of child
//   (c) descendant overrides ancestor's binding
//       — child's value wins, not parent's
//
// The tests poke /api/graph/layout directly (no Playwright) — we
// only care about the layout output structure.
//
// Run from this directory:  AUTH_TOKEN=... node edit-inheritance-regression.test.js

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, deleteFnByName} =
  require('./edit-test-helpers');

async function layoutOf(page, rootId) {
  return page.evaluate(async (args) => {
    const r = await fetch(args.base + '/api/graph/layout', {
      method: 'POST',
      headers: {'Authorization': 'Bearer ' + args.auth, 'Content-Type': 'application/json'},
      body: JSON.stringify({'root-id': args.rootId})
    });
    return r.json();
  }, {base: 'http://localhost:9002', auth: process.env.AUTH_TOKEN || 'test123', rootId});
}

async function expandedLayoutOf(page, rootId, fullDepth) {
  return page.evaluate(async (args) => {
    const r = await fetch(args.base + '/api/graph/layout', {
      method: 'POST',
      headers: {'Authorization': 'Bearer ' + args.auth, 'Content-Type': 'application/json'},
      body: JSON.stringify({
        'root-id': args.rootId,
        expansions: {[`fn-${args.rootId}`]: {'full-depth': args.depth}}
      })
    });
    return r.json();
  }, {base: 'http://localhost:9002', auth: process.env.AUTH_TOKEN || 'test123',
       rootId, depth: fullDepth});
}

// Per-scenario suffixes so leaked state from a failed earlier
// scenario can't shadow the next one.
const A_PARENT = 'test-inh-a-parent';
const A_CHILD  = 'test-inh-a-child';
const B_PARENT = 'test-inh-b-parent';
const B_CHILD  = 'test-inh-b-child';
const C_PARENT = 'test-inh-c-parent';
const C_CHILD  = 'test-inh-c-child';
const D_PARENT = 'test-inh-d-parent';
const D_CHILD  = 'test-inh-d-child';

async function cleanupAll(page) {
  for (const n of [A_CHILD, A_PARENT, B_CHILD, B_PARENT, C_CHILD, C_PARENT,
                   D_CHILD, D_PARENT]) {
    await deleteFnByName(page, n).catch(() => {});
  }
}

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('inheritance-regression — parent/child binding propagation');
  let failed = 0;
  function tryStep(name, fn) {
    return fn().catch(e => {
      console.error('  ✗ ' + name + ' threw: ' + e.message);
      failed++;
    });
  }
  try {
    await cleanupAll(page);

    const ents = await getEntities(page);
    const strLen = ents.fns.find(f => f.name === 'str-len');
    const add = ents.fns.find(f => f.name === 'add');
    const stringSlot = synthArgs(ents).find(
      a => a['fn-id'] === strLen.id && a.name === 'string' && !a['source-id']);
    const numsSlot = synthArgs(ents).find(
      a => a['fn-id'] === add.id && !a['source-id']);
    assert(strLen && stringSlot && add && numsSlot, 'baseline ids resolved');

    // ============================================================
    // (a) Scalar binding inheritance
    // ============================================================
    await tryStep('(a) parent.binding hides child placeholder', async () => {
      // Parent: str-len + :string="parent-val"
      await api(page, 'POST', '/api/entities/fn',
                'name=' + A_PARENT + '&parent-ids=' + strLen.id);
      const parent = (await getEntities(page)).fns.find(f => f.name === A_PARENT);
      await api(page, 'POST', '/api/entities/binding',
                'fn-id=' + parent.id + '&slot-id=' + stringSlot['slot-id'] +
                '&value=' + encodeURIComponent('"parent-val"'));

      // Child inherits from parent.
      await api(page, 'POST', '/api/entities/fn',
                'name=' + A_CHILD + '&parent-ids=' + parent.id);
      const child = (await getEntities(page)).fns.find(f => f.name === A_CHILD);

      // Child layout — the inherited :string slot is BOUND by parent
      // so it must NOT appear as a free placeholder on the child.
      const layout = await layoutOf(page, child.id);
      const placeholders = layout.nodes.filter(
        n => n.data.isPlaceholder && n.data.slotId === stringSlot['slot-id']);
      assert(placeholders.length === 0,
             'child shows no placeholder for parent-bound :string slot — '
             + 'placeholder count=' + placeholders.length);

      // Sanity: parent's OWN layout shows the bound value.
      const parentLayout = await layoutOf(page, parent.id);
      const valueNodes = parentLayout.nodes.filter(
        n => n.data.type === 'arg' && n.data.slotId === stringSlot['slot-id']);
      assert(valueNodes.length >= 1 && valueNodes[0].data.value === 'parent-val',
             'parent layout exposes :string="parent-val" — got '
             + JSON.stringify(valueNodes.map(n => n.data.value)));
    });

    // ============================================================
    // (b) Sequence inheritance
    // ============================================================
    await tryStep('(b) parent.sequence visible to child via expansion', async () => {
      // Parent: add + :nums = [1, 2].
      await api(page, 'POST', '/api/entities/fn',
                'name=' + B_PARENT + '&parent-ids=' + add.id);
      const parent = (await getEntities(page)).fns.find(f => f.name === B_PARENT);
      await api(page, 'POST', '/api/sequence/append/' + parent.id, {value: 1});
      await api(page, 'POST', '/api/sequence/append/' + parent.id, {value: 2});

      // Child inherits parent's sequence — no own list-append.
      await api(page, 'POST', '/api/entities/fn',
                'name=' + B_CHILD + '&parent-ids=' + parent.id);
      const child = (await getEntities(page)).fns.find(f => f.name === B_CHILD);

      // Child layout (no expansion): the inherited :nums slot is
      // already bound (parent has list-append), so the empty-anchor
      // placeholder must NOT render on the child.
      const layout = await layoutOf(page, child.id);
      const emptyAnchor = layout.nodes.filter(
        n => n.data.isSequenceAnchor && n.data.slotId === numsSlot['slot-id']);
      assert(emptyAnchor.length === 0,
             'child shows no empty-sequence anchor when parent already '
             + 'has items — got ' + emptyAnchor.length);

      // Expanded child layout: parent's items must be reachable.
      const expanded = await expandedLayoutOf(page, child.id, 1);
      const argValueNodes = expanded.nodes.filter(
        n => n.data.type === 'arg' && n.data.slotId === numsSlot['slot-id']);
      const values = argValueNodes.map(n => n.data.value).sort();
      assert(JSON.stringify(values) === '[1,2]',
             'expansion exposes parent items [1,2] — got '
             + JSON.stringify(values));
    });

    // ============================================================
    // (c) Override: child binding shadows parent's
    // ============================================================
    await tryStep('(c) child binding overrides parent', async () => {
      await api(page, 'POST', '/api/entities/fn',
                'name=' + C_PARENT + '&parent-ids=' + strLen.id);
      const parent = (await getEntities(page)).fns.find(f => f.name === C_PARENT);
      await api(page, 'POST', '/api/entities/binding',
                'fn-id=' + parent.id + '&slot-id=' + stringSlot['slot-id'] +
                '&value=' + encodeURIComponent('"parent-val"'));

      // Child overrides with a different value.
      await api(page, 'POST', '/api/entities/fn',
                'name=' + C_CHILD + '&parent-ids=' + parent.id);
      const child = (await getEntities(page)).fns.find(f => f.name === C_CHILD);
      await api(page, 'POST', '/api/entities/binding',
                'fn-id=' + child.id + '&slot-id=' + stringSlot['slot-id'] +
                '&value=' + encodeURIComponent('"child-val"'));

      // Child layout — the bound :string must show child-val, not
      // parent-val. Closer fn wins in build-chain-bindings.
      const layout = await layoutOf(page, child.id);
      const valueNodes = layout.nodes.filter(
        n => n.data.type === 'arg' && n.data.slotId === stringSlot['slot-id']);
      const values = valueNodes.map(n => n.data.value);
      assert(values.includes('child-val'),
             'child override visible — got ' + JSON.stringify(values));
      assert(!values.includes('parent-val'),
             'parent value masked by override — got ' + JSON.stringify(values));
    });

    // ============================================================
    // (d) Sequence list-append: child extends parent's items
    // ============================================================
    await tryStep('(d) child list-append extends parent items', async () => {
      // Parent: add + items [1, 2]
      await api(page, 'POST', '/api/entities/fn',
                'name=' + D_PARENT + '&parent-ids=' + add.id);
      const parent = (await getEntities(page)).fns.find(f => f.name === D_PARENT);
      await api(page, 'POST', '/api/sequence/append/' + parent.id, {value: 1});
      await api(page, 'POST', '/api/sequence/append/' + parent.id, {value: 2});

      // Child appends item 3 via its own list-append binding.
      await api(page, 'POST', '/api/entities/fn',
                'name=' + D_CHILD + '&parent-ids=' + parent.id);
      const child = (await getEntities(page)).fns.find(f => f.name === D_CHILD);
      await api(page, 'POST', '/api/sequence/append/' + child.id, {value: 3});

      // Expanded child layout must show [1,2,3] — parent's items
      // PLUS child's appended one. Without :append? propagation the
      // child's chain would replace parent's.
      const expanded = await expandedLayoutOf(page, child.id, 1);
      const valueNodes = expanded.nodes.filter(
        n => n.data.type === 'arg' && n.data.slotId === numsSlot['slot-id']);
      const values = valueNodes.map(n => n.data.value).sort();
      assert(JSON.stringify(values) === '[1,2,3]',
             'child append extends parent items — got '
             + JSON.stringify(values));
    });
  } finally {
    await cleanupAll(page).catch(() => {});
    await browser.close();
  }
  if (failed === 0) {
    console.log('inheritance-regression — PASS');
  } else {
    console.error('inheritance-regression — FAIL (' + failed + ')');
    process.exit(1);
  }
})().catch(e => {
  console.error('inheritance-regression — FAIL:', e.message);
  process.exit(1);
});
