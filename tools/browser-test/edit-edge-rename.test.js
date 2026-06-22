// Edge-label inline rename e2e — click an arg-name label on a slot
// edge → input → save → binding row carries `:rename-to`.
//
// Coverage:
//   • Seed a `:add`-parented probe (inherits `:nums` slot).
//   • Navigate. Verify the `:nums` edge-label renders.
//   • Click the label → arg-value-edit-popover with text input pre-
//     filled with current name.
//   • Type new name "items" + save → writes a binding with
//     `:rename-to "items"`.
//   • Verify storage's binding row + sidebar's free-arg strip.
//
// Run from this directory:  node edit-edge-rename.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'edge-rename-probe' + RUN_ID;
const NEW_NAME = 'items';


async function cleanup(page) {
  try { await deleteFnByName(page, PROBE_FN); } catch (_) {}
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
  console.log('edit-edge-rename — edge-label click → input → save → rename-to');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: probe parented `:add`. Inherits a `:nums` slot.
    // ===================================================================
    const ents = await getEntities(page);
    const addFn = ents.fns.find(
      (f) => f.name === 'add' && (f['parent-ids'] || []).length === 0);
    assert(addFn, ':add baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + addFn.id);
    const probe = (await getEntities(page)).fns.find(
      (f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created');

    // ===================================================================
    // Phase A: navigate; verify the :nums edge-label visible.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + PROBE_FN);
    await page.waitForFunction(
      () => typeof cy !== 'undefined' && cy && cy.nodes().length > 0
            && !!document.querySelector('button.more-actions-trigger')
            && !cy.animated(),
      {timeout: 20000, polling: 100});
    await page.evaluate(() => initGraph && initGraph());
    // The edge-label name span has class .edge-label-name (label-span
    // wraps the arg's current display name).
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('.edge-label-overlay, [class*="edge-label"]'))
        .some((el) => /nums/.test(el.textContent || '')),
      {timeout: 15000});
    const initialLabel = await page.evaluate(() => {
      // Find the label span containing "nums".
      const all = Array.from(document.querySelectorAll('span, div'));
      const match = all.find(
        (el) => el.textContent?.trim() === 'nums' && el.style.cursor === 'pointer');
      return {
        labelText: match?.textContent?.trim(),
        labelCount: all.filter((el) =>
          el.textContent?.trim() === 'nums' && el.style.cursor === 'pointer').length,
      };
    });
    assert(initialLabel.labelText === 'nums',
           'edge label reads "nums": ' + JSON.stringify(initialLabel.labelText));

    // ===================================================================
    // Phase B: click the label → rename popover.
    // ===================================================================
    await page.evaluate(() => {
      const all = Array.from(document.querySelectorAll('span, div'));
      const match = all.find(
        (el) => el.textContent?.trim() === 'nums' && el.style.cursor === 'pointer');
      match?.click();
    });
    await page.waitForSelector(
      '.arg-value-edit-popover .arg-value-edit-input',
      {timeout: 5000});
    const formState = await page.evaluate(() => {
      const p = document.querySelector('.arg-value-edit-popover');
      const input = p?.querySelector('.arg-value-edit-input');
      return {
        popoverVisible: !!p,
        currentValue: input?.value,
        ariaLabel: p?.getAttribute('aria-label'),
      };
    });
    assert(formState.popoverVisible, 'rename popover opens');
    assert(formState.currentValue === 'nums',
           'input pre-filled with "nums": '
           + JSON.stringify(formState.currentValue));
    assert(/rename/i.test(formState.ariaLabel || ''),
           'aria-label names "Rename arg": '
           + JSON.stringify(formState.ariaLabel));

    // ===================================================================
    // Phase C: type new name + save.
    // ===================================================================
    await page.fill('.arg-value-edit-popover .arg-value-edit-input', NEW_NAME);
    await page.evaluate(() => {
      const p = document.querySelector('.arg-value-edit-popover');
      const btn = Array.from(p?.querySelectorAll('.arg-value-edit-btn') || [])
        .find((b) => !b.classList.contains('arg-value-edit-btn-secondary')
                  && !b.classList.contains('arg-value-edit-btn-danger'));
      btn?.click();
    });
    // Wait for the popover to dismiss + the edge-label to reflect the
    // new name.
    await page.waitForFunction(
      (newName) => {
        const popoverGone = !document.querySelector('.arg-value-edit-popover');
        if (!popoverGone) return false;
        const all = Array.from(document.querySelectorAll('span, div'));
        return all.some(
          (el) => el.textContent?.trim() === newName && el.style.cursor === 'pointer');
      },
      NEW_NAME,
      {timeout: 10000});

    // ===================================================================
    // Phase D: storage carries the rename. Post Phase 6e the
    // `:rename-to` field on `:binding` was retired; renames now
    // materialise as a NEW `:slot` row owned by the descendant
    // fn-def with `:source-slot-id` linking back to the original
    // slot. Verify both: the new slot exists with the chosen name,
    // and it's wired to the probe via a fn-slot junction.
    // ===================================================================
    const finalEnts = await getEntities(page);
    // Slot identity is a UUIDv5 derived from (fn-id, slot-name) —
    // multiple `:items` slots can exist across the graph; only the
    // one owned by the probe matters here. Filter via the
    // probe's :fn-slot junctions to find slots owned-or-attached at
    // probe-level.
    const probeFnSlots = (finalEnts['fn-slots'] || []).filter(
      (fs) => fs['fn-id'] === probe.id);
    const probeSlotIds = new Set(probeFnSlots.map((fs) => fs['slot-id']));
    const renameSlot = (finalEnts.slots || []).find(
      (s) => probeSlotIds.has(s.id) && s.name === NEW_NAME);
    assert(renameSlot,
           'new rename slot exists with name="' + NEW_NAME
           + '" attached to probe. probe fn-slots: '
           + probeFnSlots.length);
    // The slot row points back to the source slot via :source-slot-id —
    // the FK that lets resolution walk the rename chain.
    assert(renameSlot['source-slot-id'],
           'rename slot carries :source-slot-id linking to the original');

    console.log('✓ edge-label rename verified — click / form / save / storage');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
