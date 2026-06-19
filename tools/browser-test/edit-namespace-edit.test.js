// Namespace rename + delete e2e — ✎ rename + × delete actions on a
// namespace's hover-actions group in the sidebar tree.
//
// Coverage:
//   • Seed a test namespace via /api/entities/ns. Sidebar lists it
//     after re-fetch.
//   • Click ✎ → inline-input-row appears with the current name
//     pre-filled; type new name + ✓ Save → PUT lands, sidebar
//     re-renders with the renamed header.
//   • Click × → confirm() auto-accepted → DELETE → sidebar drops
//     the row.
//
// Run from this directory:  node edit-namespace-edit.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities} = require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const INITIAL_NAME = 'ns-edit-probe' + RUN_ID;
const RENAMED = 'ns-edit-renamed' + RUN_ID;


async function findNsId(page, name) {
  const ents = await getEntities(page);
  return (ents.namespaces || []).find((n) => n.name === name)?.id || null;
}


async function cleanup(page) {
  for (const name of [INITIAL_NAME, RENAMED]) {
    const id = await findNsId(page, name);
    if (id) {
      try { await api(page, 'DELETE', '/api/entities/ns/' + id); } catch (_) {}
    }
  }
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
  console.log('edit-namespace-edit — ✎ rename + × delete on a sidebar ns row');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: create test namespace via API.
    // ===================================================================
    const createResp = await api(page, 'POST', '/api/entities/ns',
                                 'name=' + INITIAL_NAME);
    assert(JSON.stringify(createResp).includes('created successfully'),
           'test namespace created: '
           + JSON.stringify(createResp).slice(0, 200));

    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/');
    await page.waitForSelector('#search-input', {timeout: 10000});
    // Refresh sidebar so the new ns shows up.
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForFunction(
      (name) => Array.from(document.querySelectorAll('.ns-header'))
        .some((h) => h.querySelector('.ns-label')?.textContent.trim() === name),
      INITIAL_NAME,
      {timeout: 15000});

    // ===================================================================
    // Phase A: click ✎ on the ns row → inline input mounted, pre-
    // filled with current name.
    // ===================================================================
    await page.evaluate((name) => {
      const target = Array.from(document.querySelectorAll('.ns-header'))
        .find((h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      target?.querySelector('.ns-edit-btn')?.click();
    }, INITIAL_NAME);
    await page.waitForSelector('.inline-input-row', {timeout: 5000});
    const renameForm = await page.evaluate(() => ({
      value: document.querySelector('.inline-input-row .inline-input')?.value,
      placeholder: document.querySelector('.inline-input-row .inline-input')?.placeholder,
    }));
    assert(renameForm.value === INITIAL_NAME,
           'rename input pre-fills with current name: '
           + JSON.stringify(renameForm.value));
    assert(/namespace/i.test(renameForm.placeholder || ''),
           'placeholder hints at namespace: '
           + JSON.stringify(renameForm.placeholder));

    // ===================================================================
    // Phase B: type new name + save → sidebar re-renders with the
    // renamed header.
    // ===================================================================
    await page.fill('.inline-input-row .inline-input', RENAMED);
    await page.click('.inline-input-row .inline-btn-save');
    // Save fires PUT /api/entities/ns/:id, then editor calls
    // initGraph which re-fetches `/api/graph/entities` (5 MB JSON)
    // and rebuilds the sidebar. Cold cache + JSON.parse + DOM
    // rebuild can take 15-20s under e2e suite load. Bump to 30s.
    await page.waitForFunction(
      (name) => Array.from(document.querySelectorAll('.ns-header'))
        .some((h) => h.querySelector('.ns-label')?.textContent.trim() === name),
      RENAMED,
      {timeout: 30000});
    const oldGone = await page.evaluate((name) => {
      return !Array.from(document.querySelectorAll('.ns-header'))
        .some((h) => h.querySelector('.ns-label')?.textContent.trim() === name);
    }, INITIAL_NAME);
    assert(oldGone,
           'sidebar no longer lists the old name');

    // ===================================================================
    // Phase C: click × on the renamed ns → confirm() auto-accepted →
    // DELETE → sidebar drops the row.
    // ===================================================================
    await page.evaluate((name) => {
      const target = Array.from(document.querySelectorAll('.ns-header'))
        .find((h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      target?.querySelector('.ns-delete-btn')?.click();
    }, RENAMED);
    // Delete triggers `initGraph` which re-fetches the full graph
    // (5 MB JSON) — JSON.parse + sidebar rebuild can exceed 15s under
    // e2e suite load. Bump to 25s.
    await page.waitForFunction(
      (name) => !Array.from(document.querySelectorAll('.ns-header'))
        .some((h) => h.querySelector('.ns-label')?.textContent.trim() === name),
      RENAMED,
      {timeout: 25000});
    const apiCheck = await getEntities(page);
    const stillThere = (apiCheck.namespaces || []).find(
      (n) => n.name === RENAMED);
    assert(!stillThere,
           'namespace gone from /api/graph/entities after × Delete');

    console.log('✓ namespace rename + delete verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
