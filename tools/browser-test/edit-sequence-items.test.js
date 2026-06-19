// Sequence binding add/remove e2e — `+` and `×` buttons on the chain
// edge-labels of a list-typed slot.
//
// Coverage:
//   • Seed a `:do`-parented probe and POST two sequence items
//     (literal values "a" and "b") into its `:steps` slot.
//   • Navigate to probe. Verify the chain renders with `×` on every
//     item and `+` on the tail only.
//   • Click `+` → literal-vs-ref chooser popover opens; pick
//     literal → enter value → submit → tail moves to the new item.
//   • Click `×` on the first item → that item disappears; rest of
//     chain renumbers via position.
//
// Run from this directory:  node edit-sequence-items.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'sequence-items-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, PROBE_FN); } catch (_) {}
}


async function postSequenceAppend(page, fnId, body) {
  return page.evaluate(async ({id, body}) => {
    const r = await window.authFetch(
      '/api/sequence/append/' + encodeURIComponent(id), {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body),
      });
    return {status: r.status, body: await r.text().catch(() => '')};
  }, {id: fnId, body});
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 200));
    d.accept();
  });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-sequence-items — chain + / × buttons on list-typed slot');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: probe parented from :do.
    // ===================================================================
    const ents = await getEntities(page);
    const doFn = ents.fns.find(
      (f) => f.name === 'do' && (f['parent-ids'] || []).length === 0);
    assert(doFn, ':do baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + doFn.id);
    const probe = (await getEntities(page)).fns.find(
      (f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created');

    // Two sequence items via API. The first append also creates the
    // seq-anchor binding under the hood.
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/');
    await page.waitForSelector('#auth-lock-btn', {timeout: 10000});
    const append1 = await postSequenceAppend(page, probe.id, {value: 'a'});
    assert(append1.status >= 200 && append1.status < 300,
           'first sequence item appended ("a"): '
           + JSON.stringify(append1).slice(0, 200));
    const append2 = await postSequenceAppend(page, probe.id, {value: 'b'});
    assert(append2.status >= 200 && append2.status < 300,
           'second sequence item appended ("b"): '
           + JSON.stringify(append2).slice(0, 200));

    // ===================================================================
    // Phase A: render the probe. Wait for the edge-label overlays.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + PROBE_FN);
    await page.waitForTimeout(800);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForFunction(
      () => document.querySelectorAll('.arg-seq-btn-remove').length >= 2,
      {timeout: 15000});

    const initial = await page.evaluate(() => ({
      removeBtnCount: document.querySelectorAll('.arg-seq-btn-remove').length,
      addBtnCount: document.querySelectorAll('.arg-seq-btn-add').length,
    }));
    assert(initial.removeBtnCount === 2,
           'two × Remove buttons (one per item): '
           + initial.removeBtnCount);
    assert(initial.addBtnCount === 1,
           'exactly one + Add button (on the tail item): '
           + initial.addBtnCount);

    // ===================================================================
    // Phase B: click + → "Append literal" / "Append fn-ref" chooser.
    // ===================================================================
    await page.click('.arg-seq-btn-add');
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('button'))
        .some((b) => /Append literal/.test(b.textContent || '')),
      {timeout: 5000});
    const chooser = await page.evaluate(() => {
      const all = Array.from(document.querySelectorAll('button'));
      return {
        hasLit: all.some((b) => /Append literal/.test(b.textContent || '')),
        hasRef: all.some((b) => /Append fn-ref/.test(b.textContent || '')),
      };
    });
    assert(chooser.hasLit && chooser.hasRef,
           'chooser popover shows "Append literal" + "Append fn-ref"');

    // ===================================================================
    // Phase C: pick "Append literal" → fill + save → tail moves.
    // ===================================================================
    await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll('button'))
        .find((b) => /Append literal/.test(b.textContent || ''));
      btn?.click();
    });
    // promptLiteralForAppend renders a plain `.arg-value-edit-input`
    // directly into the popover root — no value-form fetch.
    await page.waitForSelector(
      '.arg-value-edit-popover .arg-value-edit-input',
      {timeout: 5000});
    // Value is JSON-decoded in doSave — submit `"c"` (a JSON string)
    // so the parser sees text.
    await page.fill(
      '.arg-value-edit-popover .arg-value-edit-input', '"c"');
    // Save button — the secondary classes are "secondary" / "danger";
    // the primary save lacks those modifiers.
    await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll(
        '.arg-value-edit-btn'))
        .find((b) => !b.classList.contains('arg-value-edit-btn-secondary')
                  && !b.classList.contains('arg-value-edit-btn-danger'));
      btn?.click();
    });
    await page.waitForFunction(
      () => document.querySelectorAll('.arg-seq-btn-remove').length === 3,
      {timeout: 10000});
    const afterAppend = await page.evaluate(() => ({
      removeBtnCount: document.querySelectorAll('.arg-seq-btn-remove').length,
      addBtnCount: document.querySelectorAll('.arg-seq-btn-add').length,
    }));
    assert(afterAppend.removeBtnCount === 3,
           'three × Remove buttons after append: '
           + afterAppend.removeBtnCount);
    assert(afterAppend.addBtnCount === 1,
           'still exactly one + Add (moved to new tail): '
           + afterAppend.addBtnCount);

    // ===================================================================
    // Phase D: click the FIRST × → 2 items remain.
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector('.arg-seq-btn-remove')?.click();
    });
    await page.waitForFunction(
      () => document.querySelectorAll('.arg-seq-btn-remove').length === 2,
      {timeout: 10000});
    const afterRemove = await page.evaluate(() => ({
      removeBtnCount: document.querySelectorAll('.arg-seq-btn-remove').length,
      addBtnCount: document.querySelectorAll('.arg-seq-btn-add').length,
    }));
    assert(afterRemove.removeBtnCount === 2,
           'two × Remove buttons after removing one: '
           + afterRemove.removeBtnCount);
    assert(afterRemove.addBtnCount === 1,
           'still one + Add: ' + afterRemove.addBtnCount);

    // ===================================================================
    // Phase E: verify storage — 2 binding-list-items left, in order.
    // ===================================================================
    const finalEnts = await getEntities(page);
    const probeBindings = (finalEnts.bindings || [])
      .filter((b) => b['fn-id'] === probe.id);
    const bindingIds = new Set(probeBindings.map((b) => b.id));
    const items = (finalEnts['list-items'] || [])
      .filter((it) => bindingIds.has(it['binding-id']))
      .sort((a, b) => (a.position || 0) - (b.position || 0));
    assert(items.length === 2,
           'storage has 2 binding-list-items: ' + items.length);

    console.log('✓ sequence add/remove verified — chain + / × buttons + value form');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
