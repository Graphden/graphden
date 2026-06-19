// Phase 5 — sequence add/remove + empty-sequence first-item flow.
// Covers the riskiest paths: backend body-slurp + URI-fallback for the
// /api/sequence routes, and the layout's empty-anchor placeholder.
//
// Run from this directory:  node edit-phase5-sequence.test.js
// Exit code 0 = PASS, 1 = FAIL.

const { chromium } = require('playwright');
const { assert, newContext, api, getEntities, synthArgs, deleteFnByName } =
  require('./edit-test-helpers');

const TEST_NAME = 'test-edit-phase5';

(async () => {
  const { browser, page } = await newContext(chromium);
  console.log('Phase 5 — sequence add/remove + empty-anchor');
  try {
    await deleteFnByName(page, TEST_NAME);

    const ents = await getEntities(page);
    const add = ents.fns.find(f => f.name === 'add');
    const addNums = synthArgs(ents).find(a => a['fn-id'] === add.id && !a['source-id']);
    assert(add && addNums, 'baseline ids resolved');

    // 1. Create test fn — the :nums sequence slot is inherited
    //    automatically; the empty anchor placeholder appears in the
    //    layout because no own binding overrides it yet.
    await api(page, 'POST', '/api/entities/fn',
              'name=' + TEST_NAME + '&parent-ids=' + add.id);
    const created = (await getEntities(page)).fns.find(f => f.name === TEST_NAME);
    assert(created, 'test fn created');

    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + TEST_NAME);
    await page.waitForTimeout(2500);
    // Force a refresh so the just-POSTed fn is in lookups.fnMap.
    await page.evaluate(() => initGraph());
    await page.waitForTimeout(500);

    // 2. Verify the empty-sequence placeholder — the button now shows
    //    a bare `+` glyph with the descriptive copy in `title=`.
    const emptyAnchor = await page.evaluate(() => {
      const btn = document.querySelector('.placeholder-binder.is-seq-anchor');
      return btn ? { text: btn.textContent, title: btn.title } : null;
    });
    assert(emptyAnchor && emptyAnchor.text === '+'
           && emptyAnchor.title === 'Add the first item',
           'empty anchor renders + with "Add the first item" title');

    // 3. Click → "Append literal" → "1" → save.
    await page.evaluate(() => {
      document.querySelector('.placeholder-binder.is-seq-anchor').click();
    });
    await page.waitForTimeout(300);
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
        .find(b => b.textContent === 'Append literal').click();
    });
    await page.waitForTimeout(300);
    await page.evaluate(() => {
      document.querySelector('.arg-value-edit-popover input').value = '1';
    });
    await page.evaluate(() => {
      const btns = document.querySelectorAll('.arg-value-edit-popover .arg-value-edit-btn');
      btns[btns.length - 1].click();
    });
    // Save click fires POST /api/sequence/append → initGraph re-fetch.
    // Under e2e load the popover-close-after-save chain can exceed 2s.
    // Wait for the popover to be gone before continuing.
    await page.waitForFunction(
      () => !document.querySelector('.arg-value-edit-popover'),
      {timeout: 15000});
    await page.waitForTimeout(500);

    function chainOf(ents) {
      const fnBindings = (ents.bindings || []).filter(b => b['fn-id'] === created.id);
      const ids = new Set(fnBindings.map(b => b.id));
      return (ents['list-items'] || [])
              .filter(it => ids.has(it['binding-id']) && it.value !== null)
              .sort((a, b) => (a.position || 0) - (b.position || 0))
              .map(it => it.value);
    }
    let chain = chainOf(await getEntities(page));
    assert(JSON.stringify(chain) === '[1]', 'first item appended as value=1');

    // 4. Append a second item via the tail `+` button.
    await page.waitForSelector('.arg-seq-btn-add', {timeout: 10000});
    await page.evaluate(() => {
      document.querySelector('.arg-seq-btn-add').click();
    });
    await page.waitForFunction(
      () => document.querySelector('.free-arg-bind-chooser'),
      {timeout: 5000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
        .find(b => b.textContent === 'Append literal').click();
    });
    await page.waitForFunction(
      () => document.querySelector('.arg-value-edit-popover input'),
      {timeout: 5000});
    await page.evaluate(() => {
      document.querySelector('.arg-value-edit-popover input').value = '2';
    });
    await page.evaluate(() => {
      const btns = document.querySelectorAll('.arg-value-edit-popover .arg-value-edit-btn');
      btns[btns.length - 1].click();
    });
    await page.waitForFunction(
      () => !document.querySelector('.arg-value-edit-popover'),
      {timeout: 15000});
    await page.waitForTimeout(500);

    chain = chainOf(await getEntities(page)).slice().sort();
    assert(JSON.stringify(chain) === '[1,2]', 'tail-append added value=2');

    // 5. Remove one item via `×`. Either 1 or 2 may go (DOM order is
    //    not guaranteed across the chain) — assert chain shrinks.
    await page.evaluate(() => {
      document.querySelector('.arg-seq-btn-remove').click();
    });
    await page.waitForTimeout(2000);

    chain = chainOf(await getEntities(page));
    assert(chain.length === 1, '× button removed exactly one item');

    // 6. namespace-move smoke. The bottom "ns:" strip moved into the
    //    row-actions popover as a clickable `ns` badge — drive the
    //    same code path via `enterNamespaceMoveEditMode` so we don't
    //    depend on hover/popover timing. Verify both the set and the
    //    clear-back-to-root directions.
    const firstNsId = await page.evaluate(() => {
      const ns = (graphData.namespaces || []).find(n => true);
      return ns ? ns.id : null;
    });
    assert(firstNsId, 'at least one namespace exists in graphData');

    await page.evaluate(({ fnId, nsId }) => new Promise(resolve => {
      const fn = lookups.fnMap.get(fnId);
      const origOpen = openNamespacePicker;
      // Stub the picker so we don't depend on popover anchoring.
      openNamespacePicker = (opts) => {
        opts.onPick({ id: nsId }).then(resolve);
      };
      enterNamespaceMoveEditMode(fn, document.body);
      // Restore eventually.
      setTimeout(() => { openNamespacePicker = origOpen; }, 100);
    }), { fnId: created.id, nsId: firstNsId });
    await page.waitForTimeout(1500);

    const movedNs = (await getEntities(page)).fns.find(f => f.id === created.id)['namespace-id'];
    assert(movedNs === firstNsId, 'fn now has the picked namespace-id');

    await page.evaluate((fnId) => new Promise(resolve => {
      const fn = lookups.fnMap.get(fnId);
      const origOpen = openNamespacePicker;
      openNamespacePicker = (opts) => {
        opts.onPick({ id: null }).then(resolve);
      };
      enterNamespaceMoveEditMode(fn, document.body);
      setTimeout(() => { openNamespacePicker = origOpen; }, 100);
    }), created.id);
    await page.waitForTimeout(1500);

    const backToRoot = (await getEntities(page)).fns.find(f => f.id === created.id)['namespace-id'];
    assert(backToRoot === null || backToRoot === undefined, 'ns cleared back to root');
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('Phase 5 — PASS');
})().catch(e => { console.error('Phase 5 — FAIL:', e.message); process.exit(1); });
