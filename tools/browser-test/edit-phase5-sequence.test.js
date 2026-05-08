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
    await page.goto('http://localhost:9002/#' + TEST_NAME);
    await page.waitForTimeout(2500);

    // 2. Verify the empty-sequence "+ first item" placeholder.
    const emptyLabel = await page.evaluate(() => {
      const overlays = Array.from(document.querySelectorAll('.node-overlay'));
      const ph = overlays.find(o => o.style.border && o.style.border.includes('dashed'));
      return ph ? ph.firstElementChild.textContent : null;
    });
    assert(emptyLabel === '+ first item', 'empty anchor renders "+ first item"');

    // 3. Click → "Append literal" → "1" → save.
    await page.evaluate(() => {
      const overlays = Array.from(document.querySelectorAll('.node-overlay'));
      const ph = overlays.find(o => o.style.border && o.style.border.includes('dashed'));
      ph.firstElementChild.click();
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
    await page.waitForTimeout(2000);

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
    await page.evaluate(() => {
      const add = document.querySelector('.arg-seq-btn-add');
      if (add) add.click();
    });
    await page.waitForTimeout(300);
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
        .find(b => b.textContent === 'Append literal').click();
    });
    await page.waitForTimeout(300);
    await page.evaluate(() => {
      document.querySelector('.arg-value-edit-popover input').value = '2';
    });
    await page.evaluate(() => {
      const btns = document.querySelectorAll('.arg-value-edit-popover .arg-value-edit-btn');
      btns[btns.length - 1].click();
    });
    await page.waitForTimeout(2000);

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

    // 6. namespace-move smoke — pick first non-root namespace and back.
    const nsStripText = await page.evaluate(() => {
      const s = Array.from(document.querySelectorAll('.reparent-strip'))
                     .find(x => x.textContent.startsWith('ns:'));
      return s ? s.textContent : null;
    });
    assert(nsStripText === 'ns: (root)', 'ns strip starts at (root)');

    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.reparent-strip'))
        .find(x => x.textContent.startsWith('ns:')).click();
    });
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('.fn-picker-row'));
      // First non-(root) row.
      const target = rows.find(r => !r.textContent.includes('(root)'));
      target.click();
    });
    await page.waitForTimeout(2000);

    const movedNs = (await getEntities(page)).fns.find(f => f.id === created.id)['namespace-id'];
    assert(movedNs, 'fn now has a namespace-id');

    // Move back to (root).
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.reparent-strip'))
        .find(x => x.textContent.startsWith('ns:')).click();
    });
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('.fn-picker-row'));
      rows.find(r => r.textContent.includes('(root)')).click();
    });
    await page.waitForTimeout(2000);

    const backToRoot = (await getEntities(page)).fns.find(f => f.id === created.id)['namespace-id'];
    assert(backToRoot === null || backToRoot === undefined, 'ns cleared back to root');
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('Phase 5 — PASS');
})().catch(e => { console.error('Phase 5 — FAIL:', e.message); process.exit(1); });
