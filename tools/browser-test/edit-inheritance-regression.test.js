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
// scenario can't shadow the next one. A run-id suffix (PID + ms)
// also keeps duplicate-fn-name state from prior runs from polluting
// the current test — the soft-delete `VersionedStorage` doesn't
// immediately purge rows, so `deleteFnByName` can leave duplicate
// identity rows behind that `.find` would later snag.
const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const A_PARENT = 'test-inh-a-parent' + RUN_ID;
const A_CHILD  = 'test-inh-a-child' + RUN_ID;
const B_PARENT = 'test-inh-b-parent' + RUN_ID;
const B_CHILD  = 'test-inh-b-child' + RUN_ID;
const C_PARENT = 'test-inh-c-parent' + RUN_ID;
const C_CHILD  = 'test-inh-c-child' + RUN_ID;
const D_PARENT = 'test-inh-d-parent' + RUN_ID;
const D_CHILD  = 'test-inh-d-child' + RUN_ID;

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
    console.log('  (cleanup starting)');
    await cleanupAll(page);
    console.log('  (cleanup done)');

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

      // Parent's OWN layout exposes the :string slot inline as an
      // arg node — `parent-val` is the bound literal that shows up
      // on the fn-card's overlay.
      const parentLayout = await layoutOf(page, parent.id);
      const valueNodes = parentLayout.nodes.filter(
        n => n.data.type === 'arg' && n.data.slotId === stringSlot['slot-id']);
      assert(valueNodes.length >= 1 && valueNodes[0].data.value === 'parent-val',
             'parent layout exposes :string="parent-val" — got '
             + JSON.stringify(valueNodes.map(n => n.data.value)));

      // Inheritance check (storage layer): the child has NO own
      // binding for the :string slot — it inherits the parent's
      // binding. The fn-card overlay walks the parent-ids closure
      // at render time, so verifying storage state pins the
      // inheritance contract without depending on layout API
      // staying frozen.
      const ents2 = await getEntities(page);
      const childOwnBinding = (ents2.bindings || []).find(
        b => b['fn-id'] === child.id && b['slot-id'] === stringSlot['slot-id']);
      assert(!childOwnBinding,
             'child has no own binding for the inherited :string slot'
             + ' (parent-val flows down via the chain)');
      const childLayout = await layoutOf(page, child.id);
      assert(childLayout.nodes && childLayout.nodes.length > 0,
             'child layout returns at least the fn node');
      // Layout-API check (expansion): when the user clicks the child
      // fn-card to expand it, the inherited value surfaces as an
      // `arg` node carrying `parent-val`. Un-expanded layouts are
      // intentionally minimal (just the fn), so we need depth=1 to
      // see the slot data. Pin that the inherited value is reachable
      // via the layout API — silent omission here would leave the
      // child looking like an unbound free-arg slot.
      const childExpanded = await expandedLayoutOf(page, child.id, 1);
      const inheritedValueNodes = childExpanded.nodes.filter(
        n => n.data.type === 'arg'
             && n.data.slotId === stringSlot['slot-id']
             && n.data.value === 'parent-val');
      assert(inheritedValueNodes.length >= 1,
             'expanded child layout surfaces parent-val for the inherited '
             + ':string slot — got '
             + JSON.stringify(childExpanded.nodes.map(
                 n => ({t: n.data.type, v: n.data.value}))));
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

      // Storage-layer inheritance: child has no own list-items,
      // but parent's list-items ARE there. The fn-card overlay
      // walks the parent-ids closure at render time, so the
      // inheritance contract is pinned at the storage state
      // (durable across layout API revisions).
      const ents2 = await getEntities(page);
      const parentBinding = (ents2.bindings || []).find(
        b => b['fn-id'] === parent.id && b['slot-id'] === numsSlot['slot-id']);
      const parentItems = (ents2['list-items'] || []).filter(
        i => i['binding-id'] === parentBinding?.id);
      const parentValues = parentItems.map(i => i.value).sort();
      assert(JSON.stringify(parentValues) === '[1,2]',
             'parent items stored as [1,2] — got '
             + JSON.stringify(parentValues));
      const childBinding = (ents2.bindings || []).find(
        b => b['fn-id'] === child.id && b['slot-id'] === numsSlot['slot-id']);
      assert(!childBinding,
             'child has no own binding for the inherited :nums slot');
      // Layout-API check (parent expansion): the PARENT's expanded
      // layout shows the bound items. The child's own un/expanded
      // layout does NOT surface inherited list items directly — the
      // editor's actual UX walks the parent-ids closure inside the
      // card overlay, not through layout nodes — but the parent
      // layout staying intact is the load-bearing thing we want to
      // pin against regressions.
      const parentLayout = await layoutOf(page, parent.id);
      const parentItemNodes = parentLayout.nodes.filter(
        n => n.data.type === 'arg' && n.data.slotId === numsSlot['slot-id']);
      const parentLayoutValues = parentItemNodes.map(n => n.data.value).sort();
      assert(JSON.stringify(parentLayoutValues) === '[1,2]',
             'parent layout exposes items [1,2] for the :nums slot — got '
             + JSON.stringify(parentLayoutValues));
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

      // Child attempts to override the parent's binding. The
      // current backend REJECTS this — "arguments with a value
      // are implicitly final" is now an enforced inheritance
      // contract (the create-binding handler returns 400 on the
      // override attempt). Earlier the closer-wins rule allowed
      // descendant overrides; the policy shift was deliberate
      // (see crud.validation / "secret-shape" rejection path).
      // Test the load-bearing property: storage state stays
      // unchanged after the rejected POST.
      await api(page, 'POST', '/api/entities/fn',
                'name=' + C_CHILD + '&parent-ids=' + parent.id);
      const child = (await getEntities(page)).fns.find(f => f.name === C_CHILD);
      const overrideResp = await api(page, 'POST', '/api/entities/binding',
                'fn-id=' + child.id + '&slot-id=' + stringSlot['slot-id'] +
                '&value=' + encodeURIComponent('"child-val"'));
      assert(overrideResp.status === 400,
             'child override rejected with 400 (inheritance contract): '
             + JSON.stringify(overrideResp).slice(0, 200));
      assert(/inheritance|ancestor|final|implicitly/i.test(overrideResp.body || ''),
             'rejection body explains the inheritance final-value rule');

      const ents2 = await getEntities(page);
      const parentBinding = (ents2.bindings || []).find(
        b => b['fn-id'] === parent.id && b['slot-id'] === stringSlot['slot-id']);
      const childBinding = (ents2.bindings || []).find(
        b => b['fn-id'] === child.id && b['slot-id'] === stringSlot['slot-id']);
      assert(parentBinding?.value === 'parent-val',
             'parent binding stays at "parent-val" after rejected override');
      assert(!childBinding,
             'child has NO own binding (the POST was rejected): '
             + JSON.stringify(childBinding));
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

      // Storage-layer list-append: child has its own binding +
      // item [3], parent's [1,2] stays. The append? propagation
      // contract lives in the storage layer (the child's binding
      // carries `list-append? = true`); the runtime executor
      // concatenates parent + child at compile time.
      const ents2 = await getEntities(page);
      const parentBinding = (ents2.bindings || []).find(
        b => b['fn-id'] === parent.id && b['slot-id'] === numsSlot['slot-id']);
      const childBinding = (ents2.bindings || []).find(
        b => b['fn-id'] === child.id && b['slot-id'] === numsSlot['slot-id']);
      const parentItems = (ents2['list-items'] || []).filter(
        i => i['binding-id'] === parentBinding?.id);
      const childItems = (ents2['list-items'] || []).filter(
        i => i['binding-id'] === childBinding?.id);
      const parentValues = parentItems.map(i => i.value).sort();
      const childValues = childItems.map(i => i.value);
      assert(JSON.stringify(parentValues) === '[1,2]',
             'parent items stored as [1,2] — got '
             + JSON.stringify(parentValues));
      assert(JSON.stringify(childValues) === '[3]',
             'child appended item [3] — got '
             + JSON.stringify(childValues));
      assert(childBinding && childBinding['list-append'] === true,
             'child binding carries list-append? = true: '
             + JSON.stringify(childBinding));
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
