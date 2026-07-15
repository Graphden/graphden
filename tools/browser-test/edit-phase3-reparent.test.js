// Phase 3 — re-parent cascade. Verifies the orphan-delete +
// parent-ids replace + new-arg-create sequence end-to-end.
//
// Drives the new UI flow: per-row `⋯` popover for parent removal,
// then `.reparent-strip` ("set parent…") for picking the replacement.
//
// Run from this directory:  node edit-phase3-reparent.test.js
// Exit code 0 = PASS, 1 = FAIL.

const { chromium } = require('playwright');
const { assert, newContext, api, getEntities, synthArgs, waitFor,
        deleteFnByName } = require('./edit-test-helpers');

const TEST_NAME = 'test-edit-phase3';

(async () => {
  const { browser, page } = await newContext(chromium);
  console.log('Phase 3 — re-parent cascade');
  try {
    await deleteFnByName(page, TEST_NAME);

    const ents = await getEntities(page);
    const add = ents.fns.find(f => f.name === 'add');
    const mul = ents.fns.find(f => f.name === 'mul');
    const baseSynth = synthArgs(ents);
    const addNums = baseSynth.find(a => a['fn-id'] === add.id && !a['source-id']);
    const mulNums = baseSynth.find(a => a['fn-id'] === mul.id && !a['source-id']);
    assert(add && mul && addNums && mulNums, 'baseline ids resolved');

    // 1. Create test fn (parent=[add]). The :nums slot is inherited
    //    automatically — no explicit "POST inheriting arg" step.
    await api(page, 'POST', '/api/entities/fn',
              'name=' + TEST_NAME + '&parent-ids=' + add.id);
    const created = (await getEntities(page)).fns.find(f => f.name === TEST_NAME);
    assert(created, 'test fn created');
    const seeded = synthArgs(await getEntities(page))
                     .filter(a => a['fn-id'] === created.id);
    assert(seeded.length === 1 && seeded[0]['slot-id'] === addNums['slot-id'],
           'inheriting arg points at add.nums');

    // 2. Open editor and drive the new UI flow. Confirm dialogs from
    //    `removeParentInline` are auto-accepted.
    page.on('dialog', d => d.accept());
    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + TEST_NAME);
    await page.waitForFunction(
      () => graphReady()
            && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null,
      {timeout: 20000, polling: 100});
    // The editor's initial /api/graph/entities load may race against
    // the just-POSTed fn. Force a refresh so `lookups.fnMap` sees it.
    await page.evaluate(() => initGraph());
    await page.waitForFunction(
      () => graphReady()
            && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null,
      {timeout: 20000, polling: 100});
    // The fn loads lazily now — wait until its subtree is actually in
    // lookups.fnMap before driving removeParentInline against it (which
    // no-ops on an undefined fn / one whose parent-ids aren't loaded).
    await page.waitForFunction(
      (id) => typeof lookups !== 'undefined'
              && (lookups?.fnMap?.get(id)?.['parent-ids'] || []).length > 0,
      created.id,
      {timeout: 20000, polling: 100});

    // 3. Remove parent `add` via the depth-1 row's row-actions popover.
    //    The editor exposes `removeParentInline(fn, parentId)` as the
    //    same entry point the `×` button in the popover calls; driving
    //    it directly is more reliable than synthesising hover/click on
    //    the floating popover that re-anchors with cy.pan / cy.zoom.
    await page.evaluate(({ fnId, parentId }) => {
      const fn = lookups.fnMap.get(fnId);
      return removeParentInline(fn, parentId);
    }, { fnId: created.id, parentId: add.id });

    const droppedParents = await waitFor(async () => {
      const e = await getEntities(page);
      const f = e.fns.find(x => x.id === created.id);
      return f && (!f['parent-ids'] || f['parent-ids'].length === 0);
    }, 5000);
    assert(droppedParents, 'parent-ids cleared after × click');

    // 4. Set new parent via the "set parent…" strip flow. We drive
    //    `setInitialParentInline` directly (same code path the strip's
    //    onClick uses) so we don't fight the fn-picker popover anchor.
    //    The picker's `onPick` callback runs the cascade — invoke it.
    await page.evaluate(({ fnId, newParentId }) => {
      const fn = lookups.fnMap.get(fnId);
      return _runCascadeWithBusy(fn, [newParentId], 'Setting parent of');
    }, { fnId: created.id, newParentId: mul.id });

    const reparented = await waitFor(async () => {
      const e = await getEntities(page);
      const f = e.fns.find(x => x.id === created.id);
      return f && JSON.stringify(f['parent-ids']) === JSON.stringify([mul.id]);
    }, 5000);
    assert(reparented, 'parent-ids replaced with [mul]');

    // 5. Verify cascade outcome — synth arg now derives from mul.nums.
    const after = await getEntities(page);
    const argsAfter = synthArgs(after).filter(a => a['fn-id'] === created.id);
    assert(argsAfter.length === 1, 'exactly one arg after cascade');
    assert(argsAfter[0]['slot-id'] === mulNums['slot-id'],
           'new arg points at mul.nums (orphan add.nums was deleted)');
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('Phase 3 — PASS');
})().catch(e => { console.error('Phase 3 — FAIL:', e.message); process.exit(1); });
