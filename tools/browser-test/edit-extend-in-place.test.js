// Extend IN PLACE — `⋯ → + Extend` on a card that sits on the canvas
// because a slot binds it (a use-site) creates the child AND puts it in
// that slot where its parent was, without leaving the canvas.
//
// Asserts:
//   • the use-site ⋯ menu offers `extend-fn` (editable card) and the
//     popover says it extends in place;
//   • a SLOT use-site: the binding's ref-fn-id moves from the base fn to
//     the child; the hash (selected root) does not change; the child's
//     card is drawn with its own `+`s, pinned by owner (`data-fn-name`);
//   • a LIST-ITEM use-site: the item's ref moves to the child;
//   • one Undo reverts both writes — the slot points at the base fn again
//     and the child is gone.
//
// Run from this directory:  node edit-extend-in-place.test.js
// Exit code 0 = PASS, 1 = FAIL.
const {chromium} = require('playwright');
const {assert, newContext, api, getEntities} = require('./edit-test-helpers');
const {extendInPlace, hardCleanup} = require('./tutorial-tour-helpers');

const OWNER = 'eip-owner';
const CHILD = 'eip-shout';
const LIST_OWNER = 'eip-card';
const LIST_CHILD = 'eip-button';

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  console.log('edit-extend-in-place — ⋯ → Extend on a bound card rebinds the slot in place');
  let failed = false;
  try {
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    await page.goto(BASE + '/');
    await page.waitForFunction(() => typeof authFetch === 'function' && typeof API !== 'undefined',
      null, {timeout: 60000});
    const wipe = async (name) => {
      for (let i = 0; i < 3; i++) {
        const ents = await getEntities(page, name);
        const f = (ents.fns || []).find((x) => x.name === name);
        if (!f) return;
        await api(page, 'DELETE', '/api/entities/fn/' + f.id);
      }
    };
    for (const n of [OWNER, CHILD, LIST_OWNER, LIST_CHILD]) await wipe(n);

    // ---- SLOT use-site: owner = str-join child; :coll ← map (the base fn).
    const strJoin = (await getEntities(page, 'str-join')).fns.find((f) => f.name === 'str-join');
    const mapFn = (await getEntities(page, 'map')).fns.find((f) => f.name === 'map');
    await api(page, 'POST', '/api/entities/fn', 'name=' + OWNER + '&parent-ids=' + strJoin.id);
    let owner = (await getEntities(page, OWNER)).fns.find((f) => f.name === OWNER);
    assert(owner, 'owner fn created');
    const collSlot = (await getEntities(page, 'str-join')).slots.find((sl) => sl.name === 'coll');
    assert(collSlot, 'str-join exposes :coll');
    await api(page, 'POST', '/api/entities/binding',
      'fn-id=' + owner.id + '&slot-id=' + collSlot.id + '&ref-fn-id=' + mapFn.id);

    await page.goto(BASE + '/#' + OWNER);
    // The base fn's card is on the canvas (one hop), with the owner's ⋯ context.
    await page.waitForSelector('.node-overlay[data-fn-name="map"] .ancestor-line[data-level="0"] button.more-actions-trigger',
      {timeout: 60000});
    const hashBefore = await page.evaluate(() => location.hash);
    await extendInPlace(page, 'map', CHILD);
    assert(await page.evaluate(() => location.hash) === hashBefore, 'still on the owner canvas');
    // The slot now references the child; the base fn's card is gone.
    const after = await getEntities(page, OWNER);
    owner = after.fns.find((f) => f.name === OWNER);
    const child = (await getEntities(page, CHILD)).fns.find((f) => f.name === CHILD);
    assert(child && (child['parent-ids'] || []).includes(mapFn.id), 'child is parented to :map');
    const coll = after.bindings.find((b) => b['fn-id'] === owner.id && b['slot-id'] === collSlot.id);
    assert(coll && coll['ref-fn-id'] === child.id, ':coll now references the child (' + JSON.stringify(coll) + ')');
    assert(await page.evaluate(() => !document.querySelector('.node-overlay[data-fn-name="map"]')),
      'the base fn card left the canvas');
    // The child's own `+`s are drawn, pinned by owner + slot.
    await page.waitForSelector('.placeholder-binder[data-fn-name="' + CHILD + '"][data-arg-name="func"]', {timeout: 30000});
    await page.waitForSelector('.placeholder-binder[data-fn-name="' + CHILD + '"][data-arg-name="coll"]', {timeout: 30000});
    console.log('  slot use-site: child created, :coll rebound, canvas kept');

    // ---- Undo: one entry reverts the rebind AND tombstones the child.
    const undone = await page.evaluate(async () => {
      if (typeof gdUndoLast !== 'function') return 'no-undo';
      return await gdUndoLast();
    });
    assert(undone === true, 'Undo landed (' + undone + ')');
    await page.waitForFunction((n) => !document.querySelector('.node-overlay[data-fn-name="' + n + '"]'),
      CHILD, {timeout: 30000, polling: 200});
    const afterUndo = await getEntities(page, OWNER);
    const collBack = afterUndo.bindings.find((b) => b['fn-id'] === owner.id && b['slot-id'] === collSlot.id);
    assert(collBack && collBack['ref-fn-id'] === mapFn.id, ':coll points at :map again after Undo');
    assert(!(await getEntities(page, CHILD)).fns.some((f) => f.name === CHILD), 'the child is gone after Undo');
    await page.waitForSelector('.node-overlay[data-fn-name="map"]', {timeout: 30000});
    console.log('  undo: rebind reverted, child tombstoned');

    // ---- LIST-ITEM use-site: owner = card child; :children ← [button].
    const card = (await getEntities(page, 'card')).fns.find((f) => f.name === 'card');
    const button = (await getEntities(page, 'button')).fns.find((f) => f.name === 'button');
    await api(page, 'POST', '/api/entities/fn', 'name=' + LIST_OWNER + '&parent-ids=' + card.id);
    const listOwner = (await getEntities(page, LIST_OWNER)).fns.find((f) => f.name === LIST_OWNER);
    const appended = await page.evaluate(async ({id, ref}) => {
      const r = await authFetch(API.api_sequence_append_fn_id(id), {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ref})});
      return r.status;
    }, {id: listOwner.id, ref: button.id});
    assert(appended >= 200 && appended < 300, 'button appended to :children (' + appended + ')');
    await page.goto(BASE + '/#' + LIST_OWNER);
    await page.waitForSelector('.node-overlay[data-fn-name="button"] .ancestor-line[data-level="0"] button.more-actions-trigger',
      {timeout: 60000});
    await extendInPlace(page, 'button', LIST_CHILD);
    const listChild = (await getEntities(page, LIST_CHILD)).fns.find((f) => f.name === LIST_CHILD);
    const listAfter = await getEntities(page, LIST_OWNER);
    const bindIds = listAfter.bindings.filter((b) => b['fn-id'] === listOwner.id).map((b) => b.id);
    const items = (listAfter['list-items'] || []).filter((it) => bindIds.includes(it['binding-id']));
    assert(items.length === 1 && items[0]['ref-fn-id'] === listChild.id,
      'the list item now references the child (' + JSON.stringify(items) + ')');
    console.log('  list-item use-site: item rebound to the child');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message || err);
  } finally {
    try { await hardCleanup(page); } catch (_) { /* best effort */ }
    for (const n of [CHILD, OWNER, LIST_CHILD, LIST_OWNER]) {
      try {
        const ents = await getEntities(page, n);
        const f = (ents.fns || []).find((x) => x.name === n);
        if (f) await api(page, 'DELETE', '/api/entities/fn/' + f.id);
      } catch (_) { /* best effort */ }
    }
    await browser.close();
  }
  console.log(failed ? 'FAIL' : 'PASS');
  process.exit(failed ? 1 : 0);
})();
