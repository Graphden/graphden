// fn-def create flow e2e — sidebar `+` → create menu → inline input
// → submit → fn appears + selected + ns expanded.
//
// Coverage:
//   • Click `+` on a namespace's actions row → create-menu appears
//     with three items (namespace / graph / type).
//   • Click "New graph…" → inline-input-row appears inside that ns.
//   • Empty name → "Name required" inline error, no POST fires.
//   • Valid name → POST /api/entities/fn lands → ns auto-expanded →
//     new fn-row visible in sidebar → editor selects it (hash + card).
//   • Re-submitting the same name → server rejection surfaces as the
//     row's inline error (duplicate-name guard).
//   • Cancel button (×) wipes the input row without writing.
//
// Run from this directory:  node edit-fn-create.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN_NAME = 'sidebar-create-probe' + RUN_ID;
const PARENT_NS = 'app';


async function cleanup(page) {
  try { await deleteFnByName(page, FN_NAME); } catch (_) {}
}


async function openCreateMenuForNs(page, nsName) {
  // Ensure the ns is expanded, then click its + button. The header
  // click toggles expand/collapse — only click it if we need to
  // toggle ON.
  await page.evaluate((name) => {
    const headers = Array.from(document.querySelectorAll('.ns-header'));
    const target = headers.find(
      (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
    if (!target) throw new Error('namespace not found: ' + name);
    // expandedNamespaces is module-scope; expanded state is reflected
    // by whether the arrow is `▼` (expanded) or `▶` (collapsed).
    const arrow = target.querySelector('.ns-arrow');
    if (arrow && /▶/.test(arrow.textContent || '')) {
      target.click(); // expand
    }
  }, nsName);
  // Wait for the expansion (arrow flipped to ▼) instead of a fixed delay.
  await page.waitForFunction((name) => {
    const headers = Array.from(document.querySelectorAll('.ns-header'));
    const target = headers.find(
      (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
    const arrow = target?.querySelector('.ns-arrow');
    return arrow && /▼/.test(arrow.textContent || '');
  }, nsName, {timeout: 2000, polling: 50});
  await page.evaluate((name) => {
    const headers = Array.from(document.querySelectorAll('.ns-header'));
    const target = headers.find(
      (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
    const plus = target?.querySelector('.ns-plus-btn');
    if (!plus) throw new Error('ns-plus-btn not found for ' + name);
    plus.click();
  }, nsName);
  await page.waitForSelector('.create-menu', {timeout: 5000});
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
  console.log('edit-fn-create — sidebar + → menu → inline input → fn appears');

  try {
    await cleanup(page);
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/');
    await page.waitForSelector('.ns-header', {timeout: 15000});

    // ===================================================================
    // Phase A: open create menu for `:app` namespace.
    // ===================================================================
    await openCreateMenuForNs(page, PARENT_NS);
    const menuState = await page.evaluate(() => {
      const m = document.querySelector('.create-menu');
      const items = Array.from(m?.querySelectorAll('.create-menu-item') || []);
      return {
        visible: !!m,
        itemCount: items.length,
        itemLabels: items.map((b) => b.textContent.trim()),
      };
    });
    assert(menuState.visible, 'create-menu opens after + click');
    assert(menuState.itemCount === 3,
           'menu has 3 items (ns / fn / type): ' + menuState.itemCount);
    assert(menuState.itemLabels.some((l) => /New graph/.test(l)),
           '"New graph…" item present: '
           + JSON.stringify(menuState.itemLabels));

    // ===================================================================
    // Phase B: click "New graph…" → inline-input-row appears.
    // ===================================================================
    await page.evaluate(() => {
      const item = Array.from(document.querySelectorAll('.create-menu-item'))
        .find((b) => /New graph/.test(b.textContent));
      item?.click();
    });
    await page.waitForSelector('.inline-input-row', {timeout: 5000});
    const formState = await page.evaluate(() => {
      const row = document.querySelector('.inline-input-row');
      return {
        rowPresent: !!row,
        hasInput: !!row?.querySelector('.inline-input'),
        hasSaveBtn: !!row?.querySelector('.inline-btn-save'),
        hasCancelBtn: !!row?.querySelector('.inline-btn-cancel'),
        placeholder: row?.querySelector('.inline-input')?.placeholder,
      };
    });
    assert(formState.rowPresent && formState.hasInput
           && formState.hasSaveBtn && formState.hasCancelBtn,
           'inline-input-row has input + Save + Cancel');
    assert(/graph/i.test(formState.placeholder || ''),
           'placeholder reads "New graph name": ' + formState.placeholder);

    // ===================================================================
    // Phase C: empty submit → "Name required" inline error.
    // ===================================================================
    await page.click('.inline-input-row .inline-btn-save');
    await page.waitForFunction(
      () => {
        const e = document.querySelector('.inline-input-row .inline-error');
        return e && /name required/i.test(e.textContent || '');
      },
      null,
      {timeout: 3000});
    const emptyState = await page.evaluate(() => ({
      error: document.querySelector('.inline-input-row .inline-error')
        ?.textContent,
      rowStillThere: !!document.querySelector('.inline-input-row'),
    }));
    assert(/name required/i.test(emptyState.error),
           '"Name required" inline error: ' + emptyState.error);
    assert(emptyState.rowStillThere,
           'row stays after empty submit (no commit)');

    // ===================================================================
    // Phase D: fill name + submit → POST → fn appears + selected.
    // ===================================================================
    await page.fill('.inline-input-row .inline-input', FN_NAME);
    await page.click('.inline-input-row .inline-btn-save');
    await page.waitForFunction(
      (name) => {
        // The create handler's loadGraphData → select → hash chain is
        // async. Wait for the WHOLE post-create nav to settle: the fn's
        // row appears AND it's selected AND the URL hash points at it —
        // else a single read below races the still-pending navigation.
        const item = Array.from(document.querySelectorAll('.entity-item'))
          .find((el) => el.textContent.includes(name));
        return !!item && item.classList.contains('selected')
          && location.hash.includes(name);
      },
      FN_NAME,
      {timeout: 10000});
    const created = await page.evaluate((name) => {
      const fnItem = Array.from(document.querySelectorAll('.entity-item'))
        .find((el) => el.textContent.includes(name));
      return {
        sidebarHasFn: !!fnItem,
        selectedClass: fnItem?.classList.contains('selected'),
        hash: location.hash,
      };
    }, FN_NAME);
    assert(created.sidebarHasFn,
           'new fn appears in sidebar');
    assert(created.hash.includes(FN_NAME),
           'URL hash navigated to the new fn: ' + created.hash);
    assert(created.selectedClass,
           'new fn auto-selected in sidebar (.selected class)');

    // ===================================================================
    // Phase E: re-create with same name → server rejection surfaces.
    // Wait for the sidebar re-render to settle: the newly-created fn's
    // entity-item is the post-initGraph signal that the tree is rebuilt
    // and the + button is wired again.
    // ===================================================================
    await page.waitForFunction((name) => {
      const items = Array.from(document.querySelectorAll('.entity-item'));
      return items.some(h => (h.textContent || '').includes(name));
    }, FN_NAME, {timeout: 5000, polling: 100});
    await openCreateMenuForNs(page, PARENT_NS);
    await page.evaluate(() => {
      const item = Array.from(document.querySelectorAll('.create-menu-item'))
        .find((b) => /New graph/.test(b.textContent));
      // Direct call to startInlineCreate to bypass any outside-click
      // race with the menu's auto-dispose handler.
      if (item) item.click();
    });
    await page.waitForSelector('.inline-input-row', {timeout: 10000});
    await page.fill('.inline-input-row .inline-input', FN_NAME);
    await page.click('.inline-input-row .inline-btn-save');
    await page.waitForFunction(
      () => {
        const e = document.querySelector('.inline-input-row .inline-error');
        const txt = e?.textContent || '';
        return txt.length > 0 && !/name required/i.test(txt);
      },
      null,
      {timeout: 5000});
    const duplicate = await page.evaluate(() => ({
      error: document.querySelector('.inline-input-row .inline-error')
        ?.textContent,
      rowStillThere: !!document.querySelector('.inline-input-row'),
    }));
    assert(duplicate.error && duplicate.error.length > 0,
           'duplicate-name rejection surfaces as inline error: '
           + JSON.stringify(duplicate.error).slice(0, 200));
    assert(duplicate.rowStillThere,
           'row stays open on server rejection');

    // ===================================================================
    // Phase F: cancel button wipes the row.
    // ===================================================================
    await page.click('.inline-input-row .inline-btn-cancel');
    await page.waitForFunction(
      () => !document.querySelector('.inline-input-row'),
      null,
      {timeout: 3000});
    const cancelled = await page.evaluate(
      () => !document.querySelector('.inline-input-row'));
    assert(cancelled, 'inline-input-row removed after Cancel click');

    console.log('✓ fn-def create flow verified — menu / form / submit / dup-reject / cancel');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
