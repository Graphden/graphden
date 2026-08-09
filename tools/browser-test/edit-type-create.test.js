// Type-row creation popover e2e — sidebar `+` → "New type…" → tabs
// (refinement / record / union / variant / list) → submit → type-row
// appears in the namespace.
//
// Coverage:
//   • Open the popover on the `app` namespace.
//   • Verify all 5 kind tabs present; "Refinement" is active.
//   • Switch to Record tab → form changes (field-list controls
//     appear; refinement builder is gone).
//   • Switch back to Refinement tab → fill name + base=int +
//     operator >= 1 + submit. Verify the new type-row appears in
//     the sidebar AND `richTypes` carries the canonical form.
//   • Cancel button dismisses the popover.
//
// Run from this directory:  node edit-type-create.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const TYPE_NAME = 'tc-positive-int' + RUN_ID;
const PARENT_NS = 'app';


async function cleanup(page) {
  try { await deleteFnByName(page, TYPE_NAME); } catch (_) {}
}


async function openTypeCreate(page, nsName) {
  await page.evaluate((name) => {
    const headers = Array.from(document.querySelectorAll('.ns-header'));
    const target = headers.find(
      (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
    if (!target) throw new Error('namespace not found: ' + name);
    const arrow = target.querySelector('.ns-arrow');
    if (arrow && /▶/.test(arrow.textContent || '')) target.click();
  }, nsName);
  // Wait for the arrow to flip to ▼ instead of guessing 300 ms.
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
    target?.querySelector('.ns-plus-btn')?.click();
  }, nsName);
  await page.waitForSelector('.create-menu', {timeout: 5000});
  await page.evaluate(() => {
    const item = Array.from(document.querySelectorAll('.create-menu-item'))
      .find((b) => /New type/.test(b.textContent || ''));
    item?.click();
  });
  await page.waitForSelector('.type-create-popover', {timeout: 5000});
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
  console.log('edit-type-create — kind tabs + refinement create + cancel');

  try {
    await cleanup(page);
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/');
    await page.waitForSelector('.ns-header', {timeout: 15000});

    // ===================================================================
    // Phase A: open popover. Verify 5 tabs, refinement active.
    // ===================================================================
    await openTypeCreate(page, PARENT_NS);
    const initial = await page.evaluate(() => {
      const p = document.querySelector('.type-create-popover');
      const tabs = Array.from(p?.querySelectorAll('.type-create-tab') || []);
      const active = tabs.find(
        (t) => t.classList.contains('type-create-tab-active'));
      return {
        popoverVisible: !!p,
        tabCount: tabs.length,
        tabLabels: tabs.map((t) => t.textContent.trim()),
        activeLabel: active?.textContent.trim(),
        hasRefinementBuilder: !!p?.querySelector('.refinement-builder'),
      };
    });
    assert(initial.popoverVisible, 'type-create-popover opens');
    assert(initial.tabCount === 5,
           '5 kind tabs (refinement / record / union / variant / list): '
           + initial.tabCount);
    assert(initial.tabLabels.includes('Refinement')
           && initial.tabLabels.includes('Record')
           && initial.tabLabels.includes('Union')
           && initial.tabLabels.includes('Variant')
           && initial.tabLabels.includes('List'),
           'all 5 labels present: '
           + JSON.stringify(initial.tabLabels));
    assert(initial.activeLabel === 'Refinement',
           'Refinement tab active by default');
    assert(initial.hasRefinementBuilder,
           '.refinement-builder present in default form');

    // ===================================================================
    // Phase B: switch to Record tab → builder disappears, field list
    // controls appear.
    // ===================================================================
    await page.evaluate(() => {
      const tab = Array.from(document.querySelectorAll('.type-create-tab'))
        .find((t) => t.textContent.trim() === 'Record');
      tab?.click();
    });
    await page.waitForFunction(
      () => {
        const t = Array.from(document.querySelectorAll('.type-create-tab'))
          .find((tab) => tab.textContent.trim() === 'Record');
        return t?.classList.contains('type-create-tab-active');
      },
      null,
      {timeout: 3000});
    const recordState = await page.evaluate(() => {
      const p = document.querySelector('.type-create-popover');
      return {
        hasRefinementBuilder: !!p?.querySelector('.refinement-builder'),
        hasAddBtn: !!p?.querySelector('.type-create-pair-add'),
      };
    });
    assert(!recordState.hasRefinementBuilder,
           'Record tab hides .refinement-builder');
    assert(recordState.hasAddBtn,
           'Record tab shows a + add-field button');

    // ===================================================================
    // Phase C: switch back to Refinement → fill name + base + op +
    // value → submit.
    // ===================================================================
    await page.evaluate(() => {
      const tab = Array.from(document.querySelectorAll('.type-create-tab'))
        .find((t) => t.textContent.trim() === 'Refinement');
      tab?.click();
    });
    await page.waitForSelector('.refinement-builder', {timeout: 3000});

    // Fill name (input that's required + has no datalist; the FIRST
    // text input in the form per the build order).
    await page.evaluate((name) => {
      const inputs = Array.from(
        document.querySelectorAll('.type-create-form input[type="text"]'));
      // Order: [name, base, refinement-val rows...]. name is first.
      if (inputs[0]) {
        inputs[0].value = name;
        inputs[0].dispatchEvent(new Event('input', {bubbles: true}));
      }
    }, TYPE_NAME);

    // Base should already default to "int" empty. The placeholder
    // suggests int. Set explicitly so refinementOpsFor picks the
    // right ops list.
    await page.evaluate(() => {
      const inputs = Array.from(
        document.querySelectorAll('.type-create-form input[type="text"]'));
      // inputs[1] = base
      if (inputs[1]) {
        inputs[1].value = 'int';
        inputs[1].dispatchEvent(new Event('input', {bubbles: true}));
      }
    });

    // The refinement form starts with a single row already wired
    // (some versions); add one if missing.
    await page.evaluate(() => {
      const rows = document.querySelectorAll('.refinement-row');
      if (rows.length === 0) {
        document.querySelector('.type-create-pair-add')?.click();
      }
    });
    await page.waitForSelector('.refinement-row', {timeout: 3000});

    // Pick op `>=` (the value attr is `:>=` per build code).
    await page.evaluate(() => {
      const op = document.querySelector('.refinement-row .refinement-op');
      op.value = ':>=';
      op.dispatchEvent(new Event('change', {bubbles: true}));
    });
    await page.fill('.refinement-row .refinement-val', '1');

    // Submit.
    await page.click('.type-create-submit');
    // The submit handler awaits POST, then initGraph(), then selectFnByName.
    // Wait for the new type to land in BOTH the sidebar AND the richTypes
    // registry. These refresh from separate fetches, so polling only the
    // sidebar and then reading richTypes once is a race: under load the
    // sidebar item can appear a beat before richTypes refetches, and the
    // single read below then reads stale-empty (the "rich-types registry
    // carries the new type" flake). Poll for both — the happy path still
    // returns immediately.
    await page.waitForFunction(
      (name) => {
        const inSidebar = Array.from(document.querySelectorAll('.entity-item'))
          .some((el) => el.textContent.includes(name));
        const inRich = typeof richTypes === 'object' && richTypes
                       && Object.prototype.hasOwnProperty.call(richTypes, name);
        return inSidebar && inRich;
      },
      TYPE_NAME,
      {timeout: 20000, polling: 100});

    const createdState = await page.evaluate((name) => {
      const item = Array.from(document.querySelectorAll('.entity-item'))
        .find((el) => el.textContent.includes(name));
      const inRich = typeof richTypes === 'object' && richTypes
                     && Object.prototype.hasOwnProperty.call(richTypes, name);
      return {
        sidebarHasType: !!item,
        inRichTypes: inRich,
      };
    }, TYPE_NAME);
    assert(createdState.sidebarHasType,
           'new type-row appears in sidebar');
    assert(createdState.inRichTypes,
           'rich-types registry carries the new type');

    // ===================================================================
    // Phase D: verify storage carries a refinement fn-row.
    // ===================================================================
    const finalEnts = await getEntities(page, TYPE_NAME);
    const typeFn = finalEnts.fns.find((f) => f.name === TYPE_NAME);
    assert(typeFn, 'fn-row in storage');
    assert(typeFn['base-fn-id'],
           'refinement carries :base-fn-id (points at :int)');
    assert(typeFn.constraint,
           ':constraint payload present');

    // ===================================================================
    // Phase E: re-open + Cancel dismisses without writing.
    // ===================================================================
    await openTypeCreate(page, PARENT_NS);
    // Programmatic click — Playwright's auto-wait sometimes flags the
    // sidebar-resizer as intercepting the type-create-back button.
    await page.evaluate(() => {
      document.querySelector('.type-create-back')?.click();
    });
    // Wait for the popover to dismiss instead of guessing 500 ms.
    const dismissed = await page.waitForFunction(() => {
      const p = document.querySelector('.type-create-popover');
      if (!p) return true;
      if (p.classList.contains('hidden')) return true;
      const style = window.getComputedStyle(p);
      return style.display === 'none' || style.visibility === 'hidden';
    },null,  {timeout: 2000, polling: 50}).then(() => true).catch(() => false);
    assert(dismissed, 'Cancel dismisses the popover');

    console.log('✓ type-create verified — tabs / refinement submit / sidebar / cancel');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
