// Description-edit-via-tooltip e2e — click the i badge on a fn-row →
// sticky tooltip with ✎ Edit → textarea → save → PUT updates state.
//
// Coverage:
//   • Seed a fn-def with a known description.
//   • Hover over the i badge in the sidebar → tooltip appears in
//     read-only mode (no Edit button).
//   • Click the i badge → sticky tooltip + ✎ Edit affordance.
//   • Click ✎ Edit → textarea pre-fills with current description.
//   • Type new text + click Save → PUT lands, tooltip re-renders
//     with the new description AND the local graphData record is
//     patched.
//   • Verify the fn's description in storage.
//
// Run from this directory:  node edit-description.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN_NAME = 'description-edit-probe' + RUN_ID;
const SEED_DESC = 'seed description ' + RUN_ID;
const NEW_DESC = 'updated description for the probe';


async function cleanup(page) {
  try { await deleteFnByName(page, FN_NAME); } catch (_) {}
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
  console.log('edit-description — i badge → sticky tooltip → Edit → save');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: a fn-def with the SEED_DESC description, scoped under the
    // `app` namespace so the sidebar tree auto-expands to it on
    // hash-nav (root-level fns live under a collapsed `_types`
    // pseudo-group and would stay invisible until the user expanded
    // it — masks the badge we want to click).
    // ===================================================================
    const ents = await getEntities(page);
    const identity = ents.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity parent resolved');
    const appNs = (ents.namespaces || []).find((n) => n.name === 'app');
    assert(appNs, ':app namespace resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + FN_NAME + '&parent-ids=' + identity.id
              + '&namespace-id=' + appNs.id
              + '&description=' + encodeURIComponent(SEED_DESC));
    const fn = (await getEntities(page)).fns.find((f) => f.name === FN_NAME);
    assert(fn, 'probe fn-def created');

    // ===================================================================
    // Phase A: navigate, hover the i badge → tooltip in read-only mode.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#app.' + FN_NAME);
    await page.waitForFunction(
      () => graphReady()
            && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null,
      {timeout: 20000, polling: 100});
    await page.evaluate(() => initGraph && initGraph());
    // The fn's sidebar row carries the description-badge.
    await page.waitForFunction(
      (name) => {
        const items = Array.from(document.querySelectorAll('.entity-item'));
        return items.some(
          (el) => el.textContent.includes(name)
                  && !!el.querySelector('.description-badge'));
      },
      FN_NAME,
      {timeout: 15000});
    // Hover.
    await page.evaluate((name) => {
      const item = Array.from(document.querySelectorAll('.entity-item'))
        .find((el) => el.textContent.includes(name));
      const badge = item?.querySelector('.description-badge');
      badge?.dispatchEvent(
        new MouseEvent('mouseenter', {bubbles: true, clientX: 10, clientY: 10}));
    }, FN_NAME);
    await page.waitForFunction(
      () => {
        const t = document.querySelector('.description-tooltip');
        return t && t.style.display !== 'none';
      },
      null,
      {timeout: 5000});
    const hoverState = await page.evaluate(() => {
      const t = document.querySelector('.description-tooltip');
      const body = t?.querySelector('.description-tooltip-body');
      const editBtn = Array.from(t?.querySelectorAll('.description-tooltip-btn') || [])
        .find((b) => /Edit/.test(b.textContent || ''));
      return {
        visible: !!t && t.style.display !== 'none',
        bodyText: body?.textContent?.trim(),
        hasEditBtn: !!editBtn,
      };
    });
    assert(hoverState.visible, 'tooltip appears on hover');
    assert(hoverState.bodyText?.includes(SEED_DESC),
           'tooltip shows seed description: ' + JSON.stringify(hoverState.bodyText));
    assert(!hoverState.hasEditBtn,
           'hover-mode tooltip has NO Edit button (read-only)');

    // ===================================================================
    // Phase B: click the badge → sticky mode + Edit button appears.
    // ===================================================================
    await page.evaluate((name) => {
      const item = Array.from(document.querySelectorAll('.entity-item'))
        .find((el) => el.textContent.includes(name));
      const badge = item?.querySelector('.description-badge');
      badge?.click();
    }, FN_NAME);
    await page.waitForFunction(
      () => {
        const t = document.querySelector('.description-tooltip');
        const btn = Array.from(t?.querySelectorAll('.description-tooltip-btn') || [])
          .find((b) => /Edit/.test(b.textContent || ''));
        return !!btn;
      },
      null,
      {timeout: 5000});
    const stuckState = await page.evaluate(() => {
      const t = document.querySelector('.description-tooltip');
      const btn = Array.from(t?.querySelectorAll('.description-tooltip-btn') || [])
        .find((b) => /Edit/.test(b.textContent || ''));
      return {hasEditBtn: !!btn};
    });
    assert(stuckState.hasEditBtn,
           'sticky tooltip exposes ✎ Edit button');

    // ===================================================================
    // Phase C: click Edit → textarea, save new description.
    // ===================================================================
    await page.evaluate(() => {
      const t = document.querySelector('.description-tooltip');
      const btn = Array.from(t?.querySelectorAll('.description-tooltip-btn') || [])
        .find((b) => /Edit/.test(b.textContent || ''));
      btn?.click();
    });
    await page.waitForSelector('.description-tooltip-textarea',
                               {timeout: 5000});
    const editForm = await page.evaluate(() => {
      const ta = document.querySelector('.description-tooltip-textarea');
      const t = document.querySelector('.description-tooltip');
      const saveBtn = Array.from(t?.querySelectorAll('.description-tooltip-btn') || [])
        .find((b) => !b.classList.contains('description-tooltip-btn-secondary'));
      return {
        textareaVisible: !!ta,
        prefilled: ta?.value,
        hasSaveBtn: !!saveBtn,
      };
    });
    assert(editForm.textareaVisible, 'textarea mounts in edit mode');
    assert(editForm.prefilled?.includes(SEED_DESC),
           'textarea pre-fills with current description: '
           + JSON.stringify(editForm.prefilled).slice(0, 200));
    assert(editForm.hasSaveBtn, 'Save button present');

    // Fill new description + click Save.
    await page.fill('.description-tooltip-textarea', NEW_DESC);
    await page.evaluate(() => {
      const t = document.querySelector('.description-tooltip');
      const saveBtn = Array.from(t?.querySelectorAll('.description-tooltip-btn') || [])
        .find((b) => !b.classList.contains('description-tooltip-btn-secondary'));
      saveBtn?.click();
    });
    // Tooltip re-renders in read-only mode with the new text.
    await page.waitForFunction(
      (newDesc) => {
        const t = document.querySelector('.description-tooltip');
        const body = t?.querySelector('.description-tooltip-body');
        return body?.textContent.includes(newDesc);
      },
      NEW_DESC,
      {timeout: 10000});

    const after = await page.evaluate(() => {
      const t = document.querySelector('.description-tooltip');
      const body = t?.querySelector('.description-tooltip-body');
      return {bodyText: body?.textContent?.trim()};
    });
    assert(after.bodyText?.includes(NEW_DESC),
           'tooltip re-rendered with new description: '
           + JSON.stringify(after.bodyText));

    // ===================================================================
    // Phase D: storage carries the new description.
    // ===================================================================
    const finalEnts = await getEntities(page);
    const updated = finalEnts.fns.find((f) => f.id === fn.id);
    assert(updated?.description === NEW_DESC,
           'fn row in storage has new description: '
           + JSON.stringify(updated?.description));

    console.log('✓ description edit verified — hover / sticky / edit / save');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
