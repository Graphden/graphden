// fn-row flags + literal list-item order e2e — the setters the 2026-09-20 gap
// audit found missing from the editor (every concept a lesson teaches must be
// visible AND settable on the card):
//   • λ chip on the return-type strip (editor-edit-modes-flags.js): derived →
//     named → [] → derived, each state on the chip and on the fn row.
//   • 📍 branch-local strip: off → own (toggle) → off; under a sticky-local
//     ancestor the popover is read-only and names the seed, and the server
//     refuses the widening write (`crud.validation/branch-local-rej`).
//   • A LITERAL list item's own ↑ / ↓ / + insert-before / × on its edge
//     overlay (editor-overlay-edge-label.js) — fn-card items had these in
//     their ⋯, literal items had only ×.
//   • + Add-MI from a single-parent card's parent row reaches a compatible
//     axis that is NOT loaded on the canvas (the old client gate disabled it).
//
// Run from this directory:  node edit-fn-flags.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName, BASE} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const SHOUT = 'flags-shout' + RUN_ID;
const SUM = 'flags-sum' + RUN_ID;
const STICKY_CHILD = 'flags-sticky-child' + RUN_ID;
const JSON_OK = 'flags-json-ok' + RUN_ID;


async function cleanup(page) {
  for (const n of [STICKY_CHILD, SHOUT, SUM, JSON_OK]) {
    try { await deleteFnByName(page, n); } catch (_) {}
  }
}


async function fnNamed(page, name) {
  return (await getEntities(page, name)).fns.find((f) => f.name === name);
}


async function createFn(page, name, parentId) {
  await api(page, 'POST', '/api/entities/fn', 'name=' + name + '&parent-ids=' + parentId);
  const fn = await fnNamed(page, name);
  assert(fn, name + ' created');
  return fn;
}


// The full fn ROW (the search scope is a light projection).
async function rowOf(page, fn) {
  const sub = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + fn.id);
  return (sub.fns || []).find((f) => f.id === fn.id) || null;
}


async function openCanvas(page, name, waitSelector) {
  await page.goto(BASE + '/?t=' + Date.now().toString(36) + '#' + name);
  await page.waitForFunction(
    (n) => typeof graphReady === 'function' && graphReady()
           && !!document.querySelector('.node-overlay[data-fn-name="' + n + '"]') && !graph.animating,
    name, {timeout: 30000, polling: 100});
  await page.waitForSelector(waitSelector, {timeout: 15000});
}


async function saveInlinePopover(page) {
  await page.click('.arg-value-edit-popover .arg-value-edit-btn:not(.arg-value-edit-btn-secondary):not(.arg-value-edit-btn-danger)');
  await page.waitForFunction(
    () => !document.querySelector('.arg-value-edit-popover')
          || document.querySelector('.arg-value-edit-popover .arg-value-edit-error.visible'),
    null, {timeout: 10000});
  return page.evaluate(() =>
    document.querySelector('.arg-value-edit-popover .arg-value-edit-error.visible')?.textContent || '');
}


async function lambdaChip(page, name) {
  return page.evaluate((n) => {
    const c = document.querySelector('.node-overlay[data-fn-name="' + n + '"] .lambda-params-chip');
    return c ? {text: c.textContent, declared: c.dataset.declared, tag: c.tagName} : null;
  }, name);
}


// Open the λ popover and pick a state: 'derived' | 'none' | [names].
async function setLambda(page, name, state) {
  await page.click('.node-overlay[data-fn-name="' + name + '"] .lambda-params-chip');
  await page.waitForSelector('.arg-value-edit-popover .lambda-params-edit', {timeout: 5000});
  await page.evaluate((st) => {
    const pop = document.querySelector('.arg-value-edit-popover');
    const mode = Array.isArray(st) ? 'named' : st;
    const r = pop.querySelector('input[name="lp-mode"][value="' + mode + '"]');
    r.checked = true;
    r.dispatchEvent(new Event('change', {bubbles: true}));
    if (Array.isArray(st)) {
      for (const n of st) {
        const cb = pop.querySelector('input[data-lambda-name="' + n + '"]');
        if (cb && !cb.checked) { cb.checked = true; cb.dispatchEvent(new Event('change', {bubbles: true})); }
      }
    }
  }, state);
  return saveInlinePopover(page);
}


async function waitChip(page, name, declared) {
  await page.waitForFunction(([n, d]) => {
    const c = document.querySelector('.node-overlay[data-fn-name="' + n + '"] .lambda-params-chip');
    return c && c.dataset.declared === d;
  }, [name, declared], {timeout: 30000, polling: 150});
}


async function stripOf(page, name) {
  return page.evaluate((n) => {
    const s = document.querySelector('.node-overlay[data-fn-name="' + n + '"] .branch-local-strip');
    return s ? {state: s.dataset.state, text: s.textContent, title: s.title,
                editable: s.classList.contains('branch-local-strip-editable')} : null;
  }, name);
}


async function waitStrip(page, name, state) {
  await page.waitForFunction(([n, st]) => {
    const s = document.querySelector('.node-overlay[data-fn-name="' + n + '"] .branch-local-strip');
    return s && s.dataset.state === st;
  }, [name, state], {timeout: 30000, polling: 150});
}


async function itemsOf(page, name) {
  return page.evaluate((n) => {
    const fn = Array.from(lookups.fnMap.values()).find((f) => f.name === n);
    const b = (lookups.bindingsByFn.get(fn.id) || []).find((x) => x['list-append'] || x.value == null);
    return (lookups.itemsByBinding.get(b?.id) || []).map((i) => String(i.value));
  }, name);
}


async function waitItems(page, name, expected) {
  await page.waitForFunction(([n, exp]) => {
    const fn = Array.from(lookups.fnMap.values()).find((f) => f.name === n);
    if (!fn) return false;
    const b = (lookups.bindingsByFn.get(fn.id) || [])[0];
    const rows = lookups.itemsByBinding.get(b?.id) || [];
    const items = rows.map((i) => String(i.value));
    if (JSON.stringify(items) !== JSON.stringify(exp)) return false;
    // The overlays are rebuilt by the render that FOLLOWS the data reload —
    // until then the buttons on screen still carry the old positions, and a
    // click would land on the wrong item. Fresh = each position's overlay
    // names the item the data puts there.
    const overlays = Array.from(document.querySelectorAll('.edge-seq-item'));
    return overlays.length === exp.length
      && rows.every((row, i) => overlays.some((ov) => ov.dataset.index === String(i) && ov.dataset.itemId === row.id));
  }, [name, expected], {timeout: 30000, polling: 150}).catch(async (e) => {
    const state = await page.evaluate((n) => {
      const fn = Array.from(lookups.fnMap.values()).find((f) => f.name === n);
      const b = fn ? (lookups.bindingsByFn.get(fn.id) || [])[0] : null;
      return {items: (lookups.itemsByBinding.get(b?.id) || []).map((i) => [i.value, i.position, i.id.slice(0, 8)]),
              overlays: Array.from(document.querySelectorAll('.edge-seq-item')).map((ov) => [ov.dataset.index, ov.dataset.position, (ov.dataset.itemId || '').slice(0, 8)]),
              popovers: document.querySelectorAll('.arg-value-edit-popover').length,
              chooser: !!document.querySelector('.free-arg-bind-chooser'),
              err: document.querySelector('.arg-value-edit-popover .arg-value-edit-error.visible')?.textContent || ''};
    }, name);
    throw new Error('waitItems(' + JSON.stringify(expected) + ') timed out; state: ' + JSON.stringify(state) + ' — ' + e.message);
  });
}


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept(); });
  console.log('edit-fn-flags — λ chip, 📍 strip, literal item order, + Add-MI');

  try {
    await cleanup(page);
    await page.goto(BASE + '/');
    await page.waitForSelector('#auth-lock-btn', {timeout: 10000});

    // ===================================================================
    // λ — call-site parameters on a fn of one's own (extends :str-upper).
    // ===================================================================
    const strUpper = (await getEntities(page, 'str-upper')).fns.find((f) => f.name === 'str-upper');
    assert(strUpper, 'str-upper found');
    const shout = await createFn(page, SHOUT, strUpper.id);
    await openCanvas(page, SHOUT, '.node-overlay[data-fn-name="' + SHOUT + '"] .lambda-params-chip');

    let chip = await lambdaChip(page, SHOUT);
    assert(chip && chip.declared === 'derived' && /λ derived/.test(chip.text) && chip.tag === 'BUTTON',
      'a fresh composed fn shows a clickable "λ derived" chip: ' + JSON.stringify(chip));

    let err = await setLambda(page, SHOUT, ['string']);
    assert(err === '', 'naming the free arg saves: ' + err);
    await waitChip(page, SHOUT, JSON.stringify(['string']));
    chip = await lambdaChip(page, SHOUT);
    assert(/λ string/.test(chip.text), 'the chip reads the declaration: ' + chip.text);
    let row = await rowOf(page, shout);
    assert(JSON.stringify(row['lambda-params']) === '["string"]',
      'the fn row carries ["string"] (got: ' + JSON.stringify(row['lambda-params']) + ')');

    err = await setLambda(page, SHOUT, 'none');
    assert(err === '', '"None — []" saves: ' + err);
    await waitChip(page, SHOUT, '[]');
    row = await rowOf(page, shout);
    assert(JSON.stringify(row['lambda-params']) === '[]',
      '[] is stored as an EMPTY vector, not cleared (got: ' + JSON.stringify(row['lambda-params']) + ')');

    err = await setLambda(page, SHOUT, 'derived');
    assert(err === '', 'back to derived saves: ' + err);
    await waitChip(page, SHOUT, 'derived');
    row = await rowOf(page, shout);
    assert(row['lambda-params'] == null,
      'derived CLEARS the column (got: ' + JSON.stringify(row['lambda-params']) + ')');

    // "These, in order" with nothing ticked is refused in the popover.
    await page.click('.node-overlay[data-fn-name="' + SHOUT + '"] .lambda-params-chip');
    await page.waitForSelector('.arg-value-edit-popover .lambda-params-edit', {timeout: 5000});
    await page.evaluate(() => {
      const r = document.querySelector('.arg-value-edit-popover input[name="lp-mode"][value="named"]');
      r.checked = true;
      r.dispatchEvent(new Event('change', {bubbles: true}));
    });
    err = await saveInlinePopover(page);
    assert(/at least one/i.test(err), 'an empty named list is refused with a reason: ' + err);
    await page.keyboard.press('Escape');
    console.log('  λ: derived → string → [] → derived, empty named list refused');

    // ===================================================================
    // 📍 — branch-local: off → own → off on the same fn; inherited is
    // read-only and the server refuses the widening.
    // ===================================================================
    let strip = await stripOf(page, SHOUT);
    assert(strip && strip.state === 'off' && strip.editable && /merges across branches/.test(strip.text),
      'an editable root card shows the dimmed "merges across branches" strip: ' + JSON.stringify(strip));
    await page.click('.node-overlay[data-fn-name="' + SHOUT + '"] .branch-local-strip');
    await page.waitForSelector('.arg-value-edit-popover input[data-branch-local="toggle"]', {timeout: 5000});
    await page.click('.arg-value-edit-popover input[data-branch-local="toggle"]');
    err = await saveInlinePopover(page);
    assert(err === '', 'ticking Branch-local saves: ' + err);
    await waitStrip(page, SHOUT, 'own');
    row = await rowOf(page, shout);
    assert(row['branch-local?'] === true, 'the fn row carries branch-local? true');
    strip = await stripOf(page, SHOUT);
    assert(/branch-local/.test(strip.text) && /sticky-local/.test(strip.title),
      'the strip reads branch-local and explains it: ' + JSON.stringify(strip));

    // A child of the now-sticky fn inherits it: read-only, seed named, and
    // the API refuses `false` under it.
    const child = await createFn(page, STICKY_CHILD, shout.id);
    await openCanvas(page, STICKY_CHILD, '.node-overlay[data-fn-name="' + STICKY_CHILD + '"] .branch-local-strip');
    strip = await stripOf(page, STICKY_CHILD);
    assert(strip.state === 'inherited' && new RegExp(SHOUT).test(strip.title),
      'the child shows an INHERITED strip naming the seed: ' + JSON.stringify(strip));
    await page.click('.node-overlay[data-fn-name="' + STICKY_CHILD + '"] .branch-local-strip');
    await page.waitForSelector('.arg-value-edit-popover input[data-branch-local="toggle"]', {timeout: 5000});
    const inheritedBox = await page.evaluate(() => {
      const cb = document.querySelector('.arg-value-edit-popover input[data-branch-local="toggle"]');
      return {checked: cb.checked, disabled: cb.disabled,
              hint: document.querySelector('.arg-value-edit-popover .branch-local-edit-hint')?.textContent || ''};
    });
    assert(inheritedBox.checked && inheritedBox.disabled && /Inherited from/.test(inheritedBox.hint),
      'the inherited popover is read-only and says so: ' + JSON.stringify(inheritedBox));
    await page.keyboard.press('Escape');
    const refused = await api(page, 'PUT', '/api/entities/fn/' + child.id, 'branch-local=false');
    assert(refused && (refused.status === 400 || /branch-local/.test(JSON.stringify(refused))),
      'the server refuses branch-local=false under a sticky ancestor: ' + JSON.stringify(refused).slice(0, 200));
    row = await rowOf(page, child);
    assert(row['branch-local?'] !== false, 'the refused write left the row alone');

    // …and back off on the seed itself.
    await openCanvas(page, SHOUT, '.node-overlay[data-fn-name="' + SHOUT + '"] .branch-local-strip');
    await page.click('.node-overlay[data-fn-name="' + SHOUT + '"] .branch-local-strip');
    await page.waitForSelector('.arg-value-edit-popover input[data-branch-local="toggle"]', {timeout: 5000});
    await page.click('.arg-value-edit-popover input[data-branch-local="toggle"]');
    err = await saveInlinePopover(page);
    assert(err === '', 'unticking saves: ' + err);
    await waitStrip(page, SHOUT, 'off');
    row = await rowOf(page, shout);
    assert(row['branch-local?'] === false, 'the fn row is back to false');
    console.log('  📍: off → own → (child inherited, read-only, server refuses false) → off');

    // ===================================================================
    // Literal list items — ↑ / ↓ / + / × on the item's own overlay.
    // ===================================================================
    const add = (await getEntities(page, 'add')).fns
      .find((f) => f.name === 'add' && !(f['parent-ids'] || []).length);
    assert(add, 'add found');
    const sum = await createFn(page, SUM, add.id);
    for (const v of ['1', '2', '3']) {
      await api(page, 'POST', '/api/sequence/append/' + sum.id, {value: Number(v)});
    }
    await openCanvas(page, SUM, '.edge-seq-item[data-index="0"] .arg-seq-btn-up');
    const buttons = await page.evaluate(() => Array.from(document.querySelectorAll('.edge-seq-item'))
      .map((ov) => ({idx: ov.dataset.index,
                     btns: Array.from(ov.querySelectorAll('.arg-seq-btn')).map((b) => b.textContent)})));
    assert(buttons.length === 3 && buttons.every((b) => b.btns.join('') === '↑↓+×'),
      'each literal item carries ↑ ↓ + ×: ' + JSON.stringify(buttons));
    assert(JSON.stringify(await itemsOf(page, SUM)) === '["1","2","3"]', 'seeded 1, 2, 3');

    await page.click('.edge-seq-item[data-index="2"] .arg-seq-btn-up');
    await waitItems(page, SUM, ['1', '3', '2']);
    await page.click('.edge-seq-item[data-index="0"] .arg-seq-btn-down');
    await waitItems(page, SUM, ['3', '1', '2']);
    await page.click('.edge-seq-item[data-index="0"] .arg-seq-btn-up');
    await waitItems(page, SUM, ['3', '1', '2']);
    console.log('  items: ↑ / ↓ reorder; a move past the end is a no-op');

    // The no-op move still reloads the graph; a chooser opened while that
    // render lands is wiped by it. Let the canvas settle first.
    await page.waitForTimeout(2500);
    await page.waitForFunction(() => typeof graphReady === 'function' && graphReady() && !graph.animating,
      null, {timeout: 30000, polling: 100});
    await page.click('.edge-seq-item[data-index="1"] .arg-seq-btn-insert');
    await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
      .some((b) => b.textContent.trim() === 'Insert literal'), null, {timeout: 8000});
    await page.evaluate(() => Array.from(document.querySelectorAll('button'))
      .find((b) => b.textContent.trim() === 'Insert literal').click());
    await page.waitForSelector('.arg-value-edit-popover .arg-value-edit-input, .arg-value-edit-popover [data-form-field]', {timeout: 8000});
    await page.evaluate(() => {
      const pop = document.querySelector('.arg-value-edit-popover');
      const f = pop.querySelector('.arg-value-edit-input') || pop.querySelector('[data-form-field]');
      f.value = '9';
      f.dispatchEvent(new Event('input', {bubbles: true}));
      f.dispatchEvent(new Event('change', {bubbles: true}));
      Array.from(pop.querySelectorAll('.arg-value-edit-btn')).find((b) => b.textContent.trim() === 'Save').click();
    });
    await waitItems(page, SUM, ['3', '9', '1', '2']);
    await page.click('.edge-seq-item[data-index="1"] .arg-seq-btn-remove');
    await waitItems(page, SUM, ['3', '1', '2']);
    console.log('  items: + inserts before, × removes');

    // ===================================================================
    // + Add-MI from a single-parent card reaches an axis not on the canvas.
    // ===================================================================
    const okResp = (await getEntities(page, 'ok-response')).fns.find((f) => f.name === 'ok-response');
    assert(okResp, 'ok-response found');
    const jsonOk = await createFn(page, JSON_OK, okResp.id);
    await openCanvas(page, JSON_OK,
      '.node-overlay[data-fn-name="' + JSON_OK + '"] .ancestor-line[data-level="1"] button.more-actions-trigger');
    await page.evaluate((n) => {
      document.querySelector('.node-overlay[data-fn-name="' + n + '"] .ancestor-line[data-level="1"] button.more-actions-trigger')
        .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    }, JSON_OK);
    await page.waitForSelector('.row-actions-popover [data-action="add-mi-parent"]', {timeout: 15000});
    const addBtn = await page.evaluate(() => {
      const b = document.querySelector('.row-actions-popover [data-action="add-mi-parent"]');
      return {disabled: b.disabled, title: b.title};
    });
    assert(!addBtn.disabled, 'the + is NOT disabled although json-content-type is not loaded: ' + JSON.stringify(addBtn));
    await page.evaluate(() => document.querySelector('.row-actions-popover [data-action="add-mi-parent"]')
      .dispatchEvent(new MouseEvent('click', {bubbles: true})));
    await page.waitForSelector('.fn-picker-popover .fn-picker-search', {timeout: 15000});
    await page.fill('.fn-picker-popover .fn-picker-search', 'json-content-type');
    await page.waitForSelector('.fn-picker-popover .fn-picker-row[data-fn-name$="json-content-type"]', {timeout: 30000});
    await page.click('.fn-picker-popover .fn-picker-row[data-fn-name$="json-content-type"]');
    await page.waitForFunction((n) => {
      const fn = (graphData?.fns || []).find((f) => f.name === n);
      return fn && (fn['parent-ids'] || []).length === 2;
    }, JSON_OK, {timeout: 90000, polling: 250});
    const mi = await fnNamed(page, JSON_OK);
    assert((mi['parent-ids'] || []).length === 2, 'the fn carries two parents: ' + JSON.stringify(mi['parent-ids']));
    console.log('  MI: + Add-MI from a single-parent card picked an unloaded axis');

    await cleanup(page);
    console.log('PASS');
    await browser.close();
    process.exit(0);
  } catch (e) {
    console.error('FAIL', e && e.stack || e);
    try { await cleanup(page); } catch (_) {}
    await browser.close();
    process.exit(1);
  }
})();
