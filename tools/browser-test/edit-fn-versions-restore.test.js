// fn-versions restore e2e — extends the switch-only flow tested in
// edit-fn-versions.test.js with the Restore action that promotes a
// historic version's fn-level fields onto the current branch.
//
// Coverage:
//   • Create a fn-def on main, mutate its description three times so
//     storage carries 3 distinct versions (v1 = "v1-seed", v2 =
//     "v2-mid", v3 = "v3-latest").
//   • Open the ⌛ popover. Verify ≥ 3 version rows + a Restore button
//     on the historic ones (v1 / v2).
//   • Click Restore on the v1 row → confirm() auto-accepted →
//     /api/entities/fn PUT lands with v1's data → page reloads.
//   • After reload, /api/graph/entities reports the fn's current
//     description as "v1-seed" — the restored value.
//
// Run from this directory:  node edit-fn-versions-restore.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN_NAME = 'fn-versions-restore-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, FN_NAME); } catch (_) {}
}


async function putDescription(page, fnId, desc) {
  return page.evaluate(async ({id, d}) => {
    const body = new URLSearchParams();
    body.set('description', d);
    const r = await window.authFetch('/api/entities/fn/' + id, {
      method: 'PUT',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: body.toString(),
    });
    return {status: r.status};
  }, {id: fnId, d: desc});
}


(async () => {
  const {browser, page} = await newContext(chromium);
  // No global dialog handler — restoreFnVersion's confirm() is awaited
  // explicitly via page.waitForEvent('dialog') below.
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-fn-versions-restore — ⌛ popover Restore on historic version');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: fn on main + 3 description mutations (v1 → v2 → v3).
    // ===================================================================
    const ents = await getEntities(page);
    const identity = ents.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity parent resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + FN_NAME + '&parent-ids=' + identity.id
              + '&description=v1-seed');
    const fn = (await getEntities(page)).fns.find((f) => f.name === FN_NAME);
    assert(fn, 'probe fn-def created with description=v1-seed');

    await page.goto('http://localhost:9002/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});

    let put = await putDescription(page, fn.id, 'v2-mid');
    assert(put.status === 200, 'PUT v2-mid');
    put = await putDescription(page, fn.id, 'v3-latest');
    assert(put.status === 200, 'PUT v3-latest');

    // Sanity — storage reports latest description.
    let current = (await getEntities(page)).fns.find((f) => f.id === fn.id);
    assert(current?.description === 'v3-latest',
           'pre-restore fn.description = "v3-latest": '
           + JSON.stringify(current?.description));

    // ===================================================================
    // Phase A: open ⌛ popover. Verify ≥ 3 rows + Restore buttons on
    // historic ones.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#' + FN_NAME);
    await page.waitForTimeout(800);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
    await page.waitForTimeout(500);
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForTimeout(500);
    await page.evaluate(() => {
      const popover = document.querySelector('.row-actions-popover');
      const btn = Array.from(popover?.querySelectorAll('button') || [])
        .find((b) => b.textContent.trim() === '⌛');
      btn?.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await page.waitForFunction(
      () => {
        const p = document.getElementById('fn-versions-popover');
        return p && !p.classList.contains('hidden')
               && p.querySelectorAll('.fn-versions-restore').length >= 1;
      },
      {timeout: 10000});

    const popoverState = await page.evaluate(() => {
      const p = document.getElementById('fn-versions-popover');
      const restoreBtns = Array.from(
        p?.querySelectorAll('.fn-versions-restore') || []);
      return {
        visible: !!p && !p.classList.contains('hidden'),
        restoreBtnCount: restoreBtns.length,
        restoreVersionIds: restoreBtns.map(
          (b) => b.getAttribute('data-fn-version-id')),
      };
    });
    assert(popoverState.visible, '⌛ popover visible');
    assert(popoverState.restoreBtnCount >= 2,
           '≥ 2 Restore buttons (on historic v1 + v2): '
           + popoverState.restoreBtnCount);

    // ===================================================================
    // Phase B: click Restore on the OLDEST historic version (last row
    // — versions are rendered latest-first per the editor).
    //
    // Restore triggers a confirm() (auto-accepted by the dialog
    // handler) then PUTs the fn + location.reload(). We wait for the
    // reload via branch-chip re-appearance.
    // ===================================================================
    const oldestVersionId = popoverState.restoreVersionIds[
      popoverState.restoreVersionIds.length - 1];
    assert(oldestVersionId, 'oldest version id resolved');

    // Click the Restore button. `restoreFnVersion` re-fetches the
    // versions list, shows confirm() (auto-accepted), PUTs, then
    // `location.reload()`. Capture the dialog and wait for the
    // resulting navigation.
    const dialogPromise = page.waitForEvent('dialog', {timeout: 10000});
    const navPromise = page.waitForNavigation({timeout: 15000}).catch(() => {});
    await page.evaluate((vid) => {
      const btn = document.querySelector(
        '.fn-versions-restore[data-fn-version-id="' + vid + '"]');
      btn?.click();
    }, oldestVersionId);
    const dialog = await dialogPromise;
    assert(/Restore fn/.test(dialog.message()),
           'confirm() dialog asks about restoring: '
           + JSON.stringify(dialog.message()).slice(0, 200));
    await dialog.accept();
    await navPromise;
    await page.waitForSelector('#branch-chip-btn', {timeout: 15000});
    await page.waitForTimeout(800);

    // ===================================================================
    // Phase C: storage now reports the restored description (v1-seed).
    // The restore writes a NEW version row carrying v1's data; the
    // resolved fn.description on current branch reads from the latest
    // version, which is now the restored one.
    // ===================================================================
    const final = await api(page, 'GET', '/api/graph/entities');
    const restored = final.fns.find((f) => f.id === fn.id);
    assert(restored?.description === 'v1-seed',
           'after restore, fn.description = "v1-seed" (historic data): '
           + JSON.stringify(restored?.description));

    console.log('✓ fn-versions restore verified — confirm / PUT / reload / state');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
