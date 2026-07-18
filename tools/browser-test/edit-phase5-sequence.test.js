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

    const ents = await getEntities(page, 'add');
    const add = ents.fns.find(f => f.name === 'add');
    const addNums = synthArgs(ents).find(a => a['fn-id'] === add.id && !a['source-id']);
    assert(add && addNums, 'baseline ids resolved');

    // 1. Create test fn — the :nums sequence slot is inherited
    //    automatically; the empty anchor placeholder appears in the
    //    layout because no own binding overrides it yet.
    await api(page, 'POST', '/api/entities/fn',
              'name=' + TEST_NAME + '&parent-ids=' + add.id);
    const created = (await getEntities(page, TEST_NAME)).fns.find(f => f.name === TEST_NAME);
    assert(created, 'test fn created');

    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + TEST_NAME);
    // Wait for cytoscape + cards rendered and fit-animation drained.
    await page.waitForFunction(
      () => graphReady()
            && !graph.animating,
      null,
      {timeout: 20000, polling: 100});
    // Force a refresh so the just-POSTed fn is in lookups.fnMap.
    await page.evaluate(() => initGraph());
    // Same gate after initGraph rebuild — also wait for the empty-
    // sequence placeholder which is what step 2 immediately checks.
    await page.waitForFunction(
      () => graphReady()
            && !graph.animating
            && !!document.querySelector('.placeholder-binder.is-seq-anchor'),
      null,
      {timeout: 20000, polling: 100});

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
    await page.waitForFunction(
      () => !!document.querySelector('.free-arg-bind-chooser'),
      null,
      {timeout: 5000, polling: 50});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
        .find(b => b.textContent === 'Append literal').click();
    });
    await page.waitForFunction(
      () => !!document.querySelector('.arg-value-edit-popover input'),
      null,
      {timeout: 5000, polling: 50});
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
      null,
      {timeout: 15000});
    // Storage poll — wait until the new list-item with value=1 is in
    // the binding-list-items table. Node-side loop (no async-predicate
    // in browser context).
    {
      const deadline = Date.now() + 15000;
      let appeared = false;
      while (Date.now() < deadline) {
        const ents = await getEntities(page, created.id);
        const fnBindings = (ents.bindings || []).filter((b) => b['fn-id'] === created.id);
        const ids = new Set(fnBindings.map((b) => b.id));
        const items = (ents['list-items'] || [])
          .filter((it) => ids.has(it['binding-id']) && it.value !== null);
        if (items.length >= 1) { appeared = true; break; }
        await new Promise((r) => setTimeout(r, 200));
      }
      if (!appeared) throw new Error('first item never appeared in storage');
    }

    function chainOf(ents) {
      const fnBindings = (ents.bindings || []).filter(b => b['fn-id'] === created.id);
      const ids = new Set(fnBindings.map(b => b.id));
      return (ents['list-items'] || [])
              .filter(it => ids.has(it['binding-id']) && it.value !== null)
              .sort((a, b) => (a.position || 0) - (b.position || 0))
              .map(it => it.value);
    }
    let chain = chainOf(await getEntities(page, created.id));
    assert(JSON.stringify(chain) === '[1]', 'first item appended as value=1');

    // 4. Append a second item via the tail `+` button.
    await page.waitForSelector('.arg-seq-btn-add', {timeout: 10000});
    await page.evaluate(() => {
      document.querySelector('.arg-seq-btn-add').click();
    });
    await page.waitForFunction(
      () => document.querySelector('.free-arg-bind-chooser'),
      null,
      {timeout: 5000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
        .find(b => b.textContent === 'Append literal').click();
    });
    await page.waitForFunction(
      () => document.querySelector('.arg-value-edit-popover input'),
      null,
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
      null,
      {timeout: 15000});
    // Wait for chain to grow to 2 items.
    {
      const deadline = Date.now() + 15000;
      let grew = false;
      while (Date.now() < deadline) {
        if (chainOf(await getEntities(page, created.id)).length >= 2) { grew = true; break; }
        await new Promise((r) => setTimeout(r, 200));
      }
      if (!grew) throw new Error('chain never reached 2 items');
    }

    chain = chainOf(await getEntities(page, created.id)).slice().sort();
    assert(JSON.stringify(chain) === '[1,2]', 'tail-append added value=2');

    // 5. Remove one item via `×`. Either 1 or 2 may go (DOM order is
    //    not guaranteed across the chain) — assert chain shrinks.
    await page.evaluate(() => {
      document.querySelector('.arg-seq-btn-remove').click();
    });
    // Wait for chain to shrink to 1.
    {
      const deadline = Date.now() + 15000;
      let shrunk = false;
      while (Date.now() < deadline) {
        if (chainOf(await getEntities(page, created.id)).length === 1) { shrunk = true; break; }
        await new Promise((r) => setTimeout(r, 200));
      }
      if (!shrunk) throw new Error('chain never shrunk to 1 item');
    }

    chain = chainOf(await getEntities(page, created.id));
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
    // Wait until the fn's namespace-id flips to the picked ns.
    {
      const deadline = Date.now() + 15000;
      let moved = false;
      while (Date.now() < deadline) {
        const ns = (await getEntities(page, created.id)).fns
          .find((f) => f.id === created.id)?.['namespace-id'];
        if (ns === firstNsId) { moved = true; break; }
        await new Promise((r) => setTimeout(r, 200));
      }
      if (!moved) throw new Error('ns-move never settled to ' + firstNsId);
    }

    const movedNs = (await getEntities(page, created.id)).fns.find(f => f.id === created.id)['namespace-id'];
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
    // Wait for the ns to clear back to root (null / undefined).
    {
      const deadline = Date.now() + 15000;
      let cleared = false;
      while (Date.now() < deadline) {
        const ns = (await getEntities(page, created.id)).fns
          .find((f) => f.id === created.id)?.['namespace-id'];
        if (ns === null || ns === undefined) { cleared = true; break; }
        await new Promise((r) => setTimeout(r, 200));
      }
      if (!cleared) throw new Error('ns never cleared back to root');
    }

    const backToRoot = (await getEntities(page, created.id)).fns.find(f => f.id === created.id)['namespace-id'];
    assert(backToRoot === null || backToRoot === undefined, 'ns cleared back to root');
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('Phase 5 — PASS');
})().catch(e => { console.error('Phase 5 — FAIL:', e.message); process.exit(1); });
