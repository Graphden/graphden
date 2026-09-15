// The 30-second Undo (editor-undo.js), end to end against the live editor:
//   A. Extend → the toast offers "Undo: Created X" → click → the fn is gone.
//   B. Rename → Undo → the old name is back.
//   C. Extend, then reference the child from another fn → Undo is REFUSED
//      with the server's reason (409 in use) and the entry stays.
//   D. `Space u` runs the newest entry from the keyboard.
//   E. A literal bound on a slot → Undo → the slot is free again; an item
//      appended to a list → Undo → the item is gone.
const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName, waitForServerHealthy, BASE}
  = require('./edit-test-helpers');
const {bindNamedPlaceholder} = require('./tutorial-tour-helpers');

const RUN_ID = String(Date.now()).slice(-6);
const CHILD = 'undo-child' + RUN_ID;
const RENAMED = 'undo-renamed' + RUN_ID;
const USER = 'undo-user' + RUN_ID;
const SCALAR = 'undo-scalar' + RUN_ID;
const LISTY = 'undo-listy' + RUN_ID;

// The ids of `fnId`'s own bindings in an entities payload.
function getEntitiesBindingIds(ents, fnId) {
  return (ents.bindings || []).filter((b) => b['fn-id'] === fnId).map((b) => b.id);
}

async function cleanup(page) {
  for (const n of [USER, RENAMED, CHILD, SCALAR, LISTY]) {
    try { await deleteFnByName(page, n); } catch (_) {}
  }
}

async function openRowActionsOn(page, ownerName) {
  await page.waitForFunction((name) =>
    Array.from(document.querySelectorAll('.node-overlay')).some((ov) =>
      ov.textContent.trim().startsWith(name) && ov.querySelector('button.more-actions-trigger')),
  ownerName, {timeout: 30000, polling: 200});
  await page.evaluate((name) => {
    const ov = Array.from(document.querySelectorAll('.node-overlay')).find((o) =>
      o.textContent.trim().startsWith(name) && o.querySelector('button.more-actions-trigger'));
    ov.querySelector('button.more-actions-trigger')
      .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
  }, ownerName);
  await page.waitForSelector('.row-actions-popover', {timeout: 15000});
}

async function extendVia(page, ownerName, childName) {
  await openRowActionsOn(page, ownerName);
  await page.waitForSelector('.row-actions-popover [data-action="extend-fn"]', {timeout: 15000});
  await page.evaluate(() => document.querySelector('.row-actions-popover [data-action="extend-fn"]')
    .dispatchEvent(new MouseEvent('click', {bubbles: true})));
  await page.waitForSelector('.arg-value-edit-popover .arg-value-edit-input', {timeout: 10000});
  await page.evaluate((name) => {
    const pop = document.querySelector('.arg-value-edit-popover');
    const input = pop.querySelector('.arg-value-edit-input');
    input.value = name;
    input.dispatchEvent(new Event('input', {bubbles: true}));
    Array.from(pop.querySelectorAll('.arg-value-edit-btn')).find((b) => b.textContent.trim() === 'Save').click();
  }, childName);
  await page.waitForFunction((name) => (graphData?.fns || []).some((f) => f.name === name),
    childName, {timeout: 30000, polling: 200});
}

const undoToast = (page) => page.evaluate(() => {
  const t = document.getElementById('gd-undo-toast');
  return t ? { visible: t.classList.contains('gd-undo-toast-visible'),
               label: t.querySelector('.gd-undo-toast-label')?.textContent } : null;
});

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-undo — the 30-second Undo: extend / rename / refused-in-use / Space u');

  try {
    await cleanup(page);
    await waitForServerHealthy();

    // ------------------------------------------------------------ A
    await page.goto(BASE + '/#core.arithmetic.add');
    await page.waitForFunction(() => typeof graphData !== 'undefined'
      && (graphData?.fns || []).some((f) => f.name === 'add'), null, {timeout: 30000, polling: 100});
    await extendVia(page, 'add', CHILD);
    let toast = await undoToast(page);
    assert(toast && toast.visible && toast.label === 'Created ' + CHILD,
      'the toast offers to undo the create: ' + JSON.stringify(toast));
    assert(await page.evaluate(() => gdUndoAvailable() && gdUndoLastLabel()) === 'Created ' + CHILD,
      'the journal holds the create');
    await page.click('#gd-undo-toast .gd-undo-toast-btn');
    await page.waitForFunction((name) => !(graphData?.fns || []).some((f) => f.name === name),
      CHILD, {timeout: 30000, polling: 200});
    const gone = (await getEntities(page, CHILD)).fns.find((f) => f.name === CHILD);
    assert(!gone, 'the created fn is deleted by Undo');
    assert(await page.evaluate(() => gdUndoAvailable()) === false, 'the entry is consumed');
    console.log('  A: extend → Undo → gone ✓');

    // ------------------------------------------------------------ B
    await extendVia(page, 'add', CHILD);
    // Rename through the ⋯ → ✎ Rename popover on the child's own card.
    await page.goto(BASE + '/#core.arithmetic.' + CHILD);
    await page.waitForFunction((name) => (graphData?.fns || []).some((f) => f.name === name),
      CHILD, {timeout: 30000, polling: 200});
    await openRowActionsOn(page, CHILD);
    await page.waitForSelector('.row-actions-popover [data-action="rename-fn"]', {timeout: 15000});
    await page.evaluate(() => document.querySelector('.row-actions-popover [data-action="rename-fn"]')
      .dispatchEvent(new MouseEvent('click', {bubbles: true})));
    await page.waitForSelector('.arg-value-edit-popover .arg-value-edit-input', {timeout: 10000});
    await page.evaluate((name) => {
      const pop = document.querySelector('.arg-value-edit-popover');
      const input = pop.querySelector('.arg-value-edit-input');
      input.value = name;
      input.dispatchEvent(new Event('input', {bubbles: true}));
      Array.from(pop.querySelectorAll('.arg-value-edit-btn')).find((b) => b.textContent.trim() === 'Save').click();
    }, RENAMED);
    await page.waitForFunction((name) => (graphData?.fns || []).some((f) => f.name === name),
      RENAMED, {timeout: 30000, polling: 200});
    toast = await undoToast(page);
    assert(toast && toast.visible && toast.label === 'Renamed ' + CHILD + ' → ' + RENAMED,
      'the toast offers to undo the rename: ' + JSON.stringify(toast));
    await page.click('#gd-undo-toast .gd-undo-toast-btn');
    await page.waitForFunction((name) => (graphData?.fns || []).some((f) => f.name === name),
      CHILD, {timeout: 30000, polling: 200});
    const back = (await getEntities(page, CHILD)).fns.find((f) => f.name === CHILD);
    assert(back, 'the old name is back after Undo');
    console.log('  B: rename → Undo → old name ✓');

    // ------------------------------------------------------------ C
    // A second fn referencing the child makes the child "in use": the undo
    // of its create must be refused with the server's reason, and stay.
    await page.goto(BASE + '/#core.arithmetic.add');
    await page.waitForFunction(() => (graphData?.fns || []).some((f) => f.name === 'add'),
      null, {timeout: 30000, polling: 100});
    await extendVia(page, 'add', USER);
    const child = (await getEntities(page, CHILD)).fns.find((f) => f.name === CHILD);
    const user = (await getEntities(page, USER)).fns.find((f) => f.name === USER);
    // Re-record a create entry for CHILD (the journal only holds this tab's
    // gestures; C exercises the refusal path, not the recording).
    await page.evaluate(([name, nsId]) => gdUndoRecordCreatedFn(name, nsId), [CHILD, child['namespace-id']]);
    // USER's :nums gets an item referencing CHILD → CHILD is referenced.
    const ents = await getEntities(page, 'add');
    const add = ents.fns.find((f) => f.name === 'add');
    const slots = new Map((ents.slots || []).map((s) => [s.id, s]));
    const numsSlot = (ents['fn-slots'] || []).find((x) => x['fn-id'] === add.id
      && slots.get(x['slot-id'])?.name === 'nums');
    assert(numsSlot, ':add nums slot resolved');
    await api(page, 'POST', '/api/entities/binding',
      'fn-id=' + user.id + '&slot-id=' + numsSlot['slot-id'] + '&ref-fn-id=' + child.id);
    const refused = await page.evaluate(() => gdUndoLast());
    assert(refused === false, 'undo of an in-use create is refused');
    const errToast = await page.evaluate(() => {
      const t = document.querySelector('.gd-toast');
      return t ? t.textContent : '';
    });
    assert(/Could not undo/.test(errToast) && /Created/.test(errToast),
      'the refusal names the gesture and the reason: ' + JSON.stringify(errToast));
    assert(await page.evaluate(() => gdUndoAvailable()) === true, 'the refused entry stays for a retry');
    console.log('  C: in-use create → Undo refused with reason, entry kept ✓');

    // ------------------------------------------------------------ D
    // Drop the reference, then Space u undoes from the keyboard.
    await api(page, 'DELETE', '/api/entities/fn/' + user.id);
    await page.evaluate(() => document.activeElement?.blur?.());
    await page.keyboard.press(' ');
    await page.keyboard.press('u');
    await page.waitForFunction((name) => !(graphData?.fns || []).some((f) => f.name === name),
      CHILD, {timeout: 30000, polling: 200});
    assert(!(await getEntities(page, CHILD)).fns.find((f) => f.name === CHILD),
      'Space u undid the create once the reference was gone');
    console.log('  D: Space u ✓');
    // …and the chord: Ctrl+Z undoes a fresh create from the canvas, while
    // the same chord inside a text field is left to the browser. (The
    // create undone above had no parent recorded — the test recorded it by
    // hand — so the editor is on NO selection now: go back to add first.)
    await page.goto(BASE + '/#core.arithmetic.add');
    await page.waitForFunction(() => (graphData?.fns || []).some((f) => f.name === 'add'),
      null, {timeout: 30000, polling: 100});
    await extendVia(page, 'add', CHILD);
    await page.waitForFunction(() => !document.body.classList.contains('editor-busy'),
      null, {timeout: 30000, polling: 100});
    await page.focus('#search-input');
    await page.keyboard.press('Control+z');
    await new Promise((r) => setTimeout(r, 800));
    assert(await page.evaluate(() => gdUndoAvailable()) === true,
      'Ctrl+Z inside a text field does not run the editor undo');
    await page.evaluate(() => document.activeElement?.blur?.());
    await page.keyboard.press('Control+z');
    await page.waitForFunction((name) => !(graphData?.fns || []).some((f) => f.name === name),
      CHILD, {timeout: 30000, polling: 200});
    console.log('  D2: Ctrl+Z ✓');

    // ------------------------------------------------------------ E
    // Bindings and list items record their inverse too. A child of
    // str-upper has one scalar slot (:string); a child of add has a list
    // slot (:nums) whose `+` appends items.
    await page.goto(BASE + '/#core.strings.str-upper');
    await page.waitForFunction(() => (graphData?.fns || []).some((f) => f.name === 'str-upper'),
      null, {timeout: 30000, polling: 100});
    await extendVia(page, 'str-upper', SCALAR);
    await bindNamedPlaceholder(page, 'string', 'literal', 'abc');
    await page.waitForFunction(() => (typeof gdUndoLastLabel === 'function') && gdUndoLastLabel() === 'Bound :string',
      null, {timeout: 15000, polling: 100});
    const scalar = (await getEntities(page, SCALAR)).fns.find((f) => f.name === SCALAR);
    const boundBefore = (await getEntities(page, SCALAR)).bindings.filter((b) => b['fn-id'] === scalar.id);
    assert(boundBefore.length === 1, 'the literal landed as one binding');
    await page.evaluate(() => gdUndoLast());
    await page.waitForFunction((id) => !(lookups?.bindingsByFn?.get(id) || []).length, scalar.id,
      {timeout: 30000, polling: 200});
    const boundAfter = (await getEntities(page, SCALAR)).bindings.filter((b) => b['fn-id'] === scalar.id);
    assert(boundAfter.length === 0, 'Undo removed the binding — the slot is free again');
    console.log('  E1: bound :string → Undo → free ✓');

    await page.goto(BASE + '/#core.arithmetic.add');
    await page.waitForFunction(() => (graphData?.fns || []).some((f) => f.name === 'add'),
      null, {timeout: 30000, polling: 100});
    await extendVia(page, 'add', LISTY);
    await bindNamedPlaceholder(page, 'nums', 'literal', '7');
    await page.waitForFunction(() => (typeof gdUndoLastLabel === 'function') && gdUndoLastLabel() === 'Appended an item',
      null, {timeout: 15000, polling: 100});
    const listyEnts = await getEntities(page, LISTY);
    const listy = listyEnts.fns.find((f) => f.name === LISTY);
    const listyBindings = getEntitiesBindingIds(listyEnts, listy.id);
    const itemsBefore = (listyEnts['list-items'] || [])
      .filter((it) => listyBindings.includes(it['binding-id']));
    assert(itemsBefore.length === 1, 'the literal landed as one list item');
    await page.evaluate(() => gdUndoLast());
    await page.waitForFunction((id) => {
      for (const b of (lookups?.bindingsByFn?.get(id) || [])) {
        if ((lookups.itemsByBinding?.get(b.id) || []).length) return false;
      }
      return true;
    }, listy.id, {timeout: 30000, polling: 200});
    console.log('  E2: appended to :nums → Undo → item gone ✓');

    console.log('PASS');
  } catch (e) {
    console.error('FAIL:', e.message);
    try {
      await page.screenshot({path: '/tmp/edit-undo-fail.png'});
      console.error('  overlays: ' + JSON.stringify(await page.evaluate(() =>
        Array.from(document.querySelectorAll('.node-overlay')).map((o) =>
          [o.dataset.fnName, o.textContent.trim().slice(0, 30), !!o.querySelector('button.more-actions-trigger')]))));
      console.error('  hash: ' + await page.evaluate(() => location.hash));
    } catch (_) { /* best effort */ }
    process.exitCode = 1;
  } finally {
    await cleanup(page);
    await browser.close();
  }
})();
