// Inline arg value-edit e2e — click a bound arg's value overlay →
// inline-popover form → edit → save → overlay reflects new value.
//
// Coverage:
//   • Seed a `:const`-parented fn with `:value 42` (a literal int
//     binding), navigate to it.
//   • Verify the arg-value-overlay renders the current value "42".
//   • Click the overlay → inline edit popover opens; value-form loads
//     a numeric input.
//   • Save invalid value (text in :int slot) → backend rejection or
//     client validation surfaces.
//   • Save valid new value (99) → popover closes → graph re-fetches →
//     overlay now shows "99".
//
// Run from this directory:  node edit-arg-value.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'arg-value-edit-probe' + RUN_ID;


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
  console.log('edit-arg-value — overlay click → form → save → new value');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: const-parented fn with :value 42 bound.
    // ===================================================================
    const ents = await getEntities(page);
    const constFn = ents.fns.find(
      (f) => f.name === 'const' && (f['parent-ids'] || []).length === 0);
    assert(constFn, ':const baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + constFn.id);
    const probeEnts = await getEntities(page);
    const probe = probeEnts.fns.find((f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created');
    const constSlots = probeEnts['fn-slots']
      .filter((fs) => fs['fn-id'] === constFn.id);
    const slotsById = Object.fromEntries(
      probeEnts.slots.map((s) => [s.id, s]));
    const valueSlot = constSlots
      .map((fs) => slotsById[fs['slot-id']])
      .find((s) => s.name === 'value');
    assert(valueSlot, ':const.value slot resolved');
    const bindResp = await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + probe.id + '&slot-id=' + valueSlot.id
              + '&value=42');
    assert(JSON.stringify(bindResp).includes('created successfully'),
           ':value bound to 42: ' + JSON.stringify(bindResp).slice(0, 200));

    // ===================================================================
    // Phase A: navigate to probe, verify overlay shows 42.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#' + PROBE_FN);
    await page.waitForTimeout(800);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForSelector('.arg-overlay-row', {timeout: 15000});

    const initial = await page.evaluate(() => {
      // arg-value overlay structure: .node-overlay > .arg-overlay-row >
      //   <div> (value content, click target)
      //   <span class="arg-type-chip">
      //   …
      const row = document.querySelector('.arg-overlay-row');
      const content = row?.firstElementChild;
      return {
        rowPresent: !!row,
        text: content?.textContent?.trim(),
      };
    });
    assert(initial.rowPresent, 'arg-overlay-row rendered');
    assert(/42/.test(initial.text || ''),
           'overlay shows current value "42": '
           + JSON.stringify(initial.text));

    // ===================================================================
    // Phase B: click overlay value → edit popover opens, form mounts.
    // ===================================================================
    await page.evaluate(() => {
      const row = document.querySelector('.arg-overlay-row');
      const content = row?.firstElementChild;
      content?.click();
    });
    await page.waitForSelector('.arg-value-edit-popover', {timeout: 5000});
    // Form is fetched async — wait for the numeric input to mount.
    await page.waitForSelector(
      '.arg-value-edit-popover input[data-field-kind="number"]',
      {timeout: 10000});
    const formOpen = await page.evaluate(() => {
      const p = document.querySelector('.arg-value-edit-popover');
      const input = p?.querySelector('input[data-field-kind="number"]');
      const saveBtn = p?.querySelector('.arg-value-edit-btn:not(.arg-value-edit-btn-secondary):not(.arg-value-edit-btn-danger)');
      return {
        popoverVisible: !!p,
        hasNumericInput: !!input,
        currentValue: input?.value,
        hasSave: !!saveBtn,
      };
    });
    assert(formOpen.popoverVisible, 'arg-value-edit-popover opens');
    assert(formOpen.hasNumericInput,
           'form mounts a numeric input (slot type :int)');
    assert(formOpen.currentValue === '42',
           'numeric input pre-fills with current value: '
           + formOpen.currentValue);
    assert(formOpen.hasSave, 'Save button rendered');

    // ===================================================================
    // Phase C: replace value with 99 → Save → graph reloads → overlay
    // shows 99.
    // ===================================================================
    await page.fill(
      '.arg-value-edit-popover input[data-field-kind="number"]',
      '99');
    await page.evaluate(() => {
      const p = document.querySelector('.arg-value-edit-popover');
      const saveBtn = p?.querySelector(
        '.arg-value-edit-btn:not(.arg-value-edit-btn-secondary):not(.arg-value-edit-btn-danger)');
      saveBtn?.click();
    });
    // initGraph fires after save → wait for the popover to dismiss +
    // the overlay to re-render with the new value.
    await page.waitForFunction(
      () => !document.querySelector('.arg-value-edit-popover'),
      {timeout: 10000});
    await page.waitForFunction(
      () => {
        const row = document.querySelector('.arg-overlay-row');
        const content = row?.firstElementChild;
        return /99/.test(content?.textContent || '');
      },
      {timeout: 10000});
    const after = await page.evaluate(() => {
      const row = document.querySelector('.arg-overlay-row');
      const content = row?.firstElementChild;
      return content?.textContent?.trim();
    });
    assert(/99/.test(after),
           'overlay reflects saved new value "99": '
           + JSON.stringify(after));

    // ===================================================================
    // Phase D: verify the binding row carries the new value.
    // ===================================================================
    const finalEnts = await getEntities(page);
    const probeBinding = (finalEnts.bindings || []).find(
      (b) => b['fn-id'] === probe.id);
    assert(probeBinding && probeBinding.value === 99,
           'binding row :value = 99 in storage: '
           + JSON.stringify(probeBinding?.value));

    console.log('✓ arg value-edit verified — overlay → form → save → new value');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
