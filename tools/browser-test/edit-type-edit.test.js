// Type-row edit e2e — exercises PUT /api/types/record-update via
// the editor's pencil-action on a record's sidebar entry.
//
// Coverage:
//   • Seed a `record` type-row with 2 fields via POST /api/types/record.
//   • Open the edit popover by calling `window.openTypeEditForm` —
//     same code path the pencil-action invokes.
//   • Verify the form prefills with current fields (`name: type` lines).
//   • Append a third field line + Save → PUT /api/types/record-update.
//   • Verify storage now has 3 fn-slot rows for the record, with the
//     new field's slot name + type-fn-id linked to `:int`.
//
// Run from this directory:  node edit-type-edit.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName, waitFor} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const REC_FN = 'edit-rec-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, REC_FN); } catch (_) {}
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
  console.log('edit-type-edit — record-row edit / add field / save / verify');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: a record type-row with 2 fields.
    // ===================================================================
    const seedResp = await page.evaluate(async ({base, auth, name}) => {
      const r = await fetch(base + '/api/types/record', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ' + auth,
        },
        body: JSON.stringify({
          name,
          fields: [
            {name: 'a', type: 'int'},
            {name: 'b', type: 'text'},
          ],
        }),
      });
      return r.json();
    }, {base: (process.env.GRAPHDEN_URL || 'http://localhost:9002')+'', auth: (process.env.AUTH_TOKEN || 'test123'), name: REC_FN});
    assert(seedResp.ok && seedResp.id,
           'record type-row created: ' + JSON.stringify(seedResp).slice(0, 120));

    const ents = await getEntities(page);
    const recFn = ents.fns.find((f) => f.name === REC_FN);
    assert(recFn, 'record fn-row resolves: id=' + recFn.id);
    const slotsById = Object.fromEntries(ents.slots.map((s) => [s.id, s]));
    const fnSlots0 = ents['fn-slots']
      .filter((fs) => fs['fn-id'] === recFn.id)
      .map((fs) => slotsById[fs['slot-id']]);
    assert(fnSlots0.length === 2,
           'record has 2 fn-slots after seed: ' + fnSlots0.length);
    const slotNames0 = fnSlots0.map((s) => s.name).sort();
    assert(JSON.stringify(slotNames0) === '["a","b"]',
           'fields are ["a","b"]: ' + JSON.stringify(slotNames0));

    // ===================================================================
    // Navigate to ANY fn so the editor mounts, then open the type-edit
    // popover via the public entry point (same as the sidebar pencil).
    // ===================================================================
    // Hard reload — initial `newContext` already loaded `/` which ran
    // initGraph BEFORE the record was seeded; a hash-only navigation
    // wouldn't refetch.
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + REC_FN,
                    {waitUntil: 'networkidle'});
    await page.waitForFunction(
      () => typeof openTypeEditForm === 'function'
            && typeof initGraph === 'function'
            && typeof lookups === 'object'
            && lookups?.fnMap?.size > 50,
      null,
      {timeout: 30000});
    // Force a refetch in case the page's first initGraph fired before
    // the seed reached storage.
    await page.evaluate(async () => { await initGraph(); });
    await page.waitForFunction(
      (fnId) => !!lookups?.fnMap?.get(fnId),
      recFn.id, {timeout: 8000, polling: 100});
    const inLookups = await page.evaluate(
      (fnId) => !!lookups?.fnMap?.get(fnId), recFn.id);
    assert(inLookups, 'seeded record is in editor lookups after initGraph');
    // Wait for the subtree to load — scope=index gives us fns/
    // namespaces but the prefill code reads `fnSlotsByFn` + `slotMap`
    // which only populate from the subtree fetch (`ensureSubtreeFor`)
    // that `initGraph`'s hash-navigation kicks off asynchronously and
    // doesn't await. Without this gate the form opens against an
    // empty subtree and renders empty pair-rows.
    await page.waitForFunction(
      (fnId) => (lookups?.fnSlotsByFn?.get(fnId) || []).length >= 2,
      recFn.id, {timeout: 8000, polling: 100});

    await page.evaluate((fnId) => {
      window.openTypeEditForm(fnId, document.body);
    }, recFn.id);
    await page.waitForSelector('.type-create-popover', {timeout: 5000});
    // Wait until the form has its prefilled pair-rows wired up.
    await page.waitForFunction(
      () => {
        const el = document.querySelector('.type-create-popover');
        const rows = el?.querySelectorAll('.type-create-pair-row') || [];
        return rows.length >= 2;
      },
      null,
      {timeout: 5000});

    // ===================================================================
    // Phase A: form prefills with existing fields (pair-row UI, NOT
    // a textarea — records take name/type from per-row inputs).
    // ===================================================================
    const prefillState = await page.evaluate(() => {
      const el = document.querySelector('.type-create-popover');
      const nameInput = el?.querySelector('input[type=text].type-create-input:not(.type-create-pair-key):not(.type-create-pair-val)');
      const rows = Array.from(el?.querySelectorAll('.type-create-pair-row') || []);
      const pairs = rows.map((r) => ({
        name: r.querySelector('.type-create-pair-key')?.value,
        type: r.querySelector('.type-create-pair-val')?.value,
      }));
      const submit = el?.querySelector('.type-create-submit');
      return {
        popoverVisible: !!el,
        name: nameInput?.value,
        pairs,
        hasSubmit: !!submit,
        submitText: submit?.textContent?.trim(),
      };
    });
    assert(prefillState.popoverVisible, 'edit popover renders');
    assert(prefillState.name === REC_FN,
           'name prefilled: ' + JSON.stringify(prefillState.name));
    assert(prefillState.pairs.some((p) => p.name === 'a' && p.type === 'int'),
           'pair-row "a: int" prefilled: '
           + JSON.stringify(prefillState.pairs));
    assert(prefillState.pairs.some((p) => p.name === 'b' && p.type === 'text'),
           'pair-row "b: text" prefilled: '
           + JSON.stringify(prefillState.pairs));
    assert(prefillState.hasSubmit && /Save/i.test(prefillState.submitText),
           'Save button present: '
           + JSON.stringify(prefillState.submitText));

    // ===================================================================
    // Phase B: click "+ add row", fill name + type, click Save.
    // ===================================================================
    // The popover positions under the anchor (document.body); when the
    // sidebar covers it, real pointer clicks get intercepted. Use a
    // direct DOM .click() — the button's event handler doesn't rely
    // on pointer state.
    await page.evaluate(() => {
      document.querySelector('.type-create-popover .type-create-pair-add')?.click();
    });
    await page.waitForFunction(
      () => document.querySelectorAll('.type-create-popover .type-create-pair-row').length >= 3,
      null,
      {timeout: 3000});
    // Fill the LAST row's name + type inputs.
    await page.evaluate(() => {
      const rows = document.querySelectorAll('.type-create-popover .type-create-pair-row');
      const last = rows[rows.length - 1];
      const keyIn = last.querySelector('.type-create-pair-key');
      const valIn = last.querySelector('.type-create-pair-val');
      const set = (input, v) => {
        const proto = Object.getPrototypeOf(input);
        const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
        setter.call(input, v);
        input.dispatchEvent(new Event('input', {bubbles: true}));
        input.dispatchEvent(new Event('change', {bubbles: true}));
      };
      set(keyIn, 'c');
      set(valIn, 'int');
    });
    await page.evaluate(() => {
      document.querySelector('.type-create-popover .type-create-submit')?.click();
    });

    // Storage write completes before popover hides itself.
    // hideTypeCreatePopover leaves the DOM node but sets display:none
    // and clears children, so check for the hidden state directly.
    await page.waitForFunction(
      () => {
        const el = document.querySelector('.type-create-popover');
        return !el || el.style.display === 'none';
      },
      null,
      {timeout: 15000});
    // Poll storage until the record grows to 3 fn-slots (b add).
    const settled = await waitFor(async () => {
      const e = await getEntities(page);
      return e['fn-slots'].filter((fs) => fs['fn-id'] === recFn.id)
                          .length === 3;
    }, 5000);
    assert(settled, 'record did not grow to 3 fn-slots in 5s');

    // ===================================================================
    // Phase C: storage reflects the new field.
    // ===================================================================
    const ents2 = await getEntities(page);
    const slotsById2 = Object.fromEntries(ents2.slots.map((s) => [s.id, s]));
    const fnSlots1 = ents2['fn-slots']
      .filter((fs) => fs['fn-id'] === recFn.id)
      .map((fs) => slotsById2[fs['slot-id']]);
    assert(fnSlots1.length === 3,
           'record now has 3 fn-slots: ' + fnSlots1.length);
    const slotNames1 = fnSlots1.map((s) => s.name).sort();
    assert(JSON.stringify(slotNames1) === '["a","b","c"]',
           'fields now include "c": ' + JSON.stringify(slotNames1));
    const newSlot = fnSlots1.find((s) => s.name === 'c');
    const intFn = ents2.fns.find(
      (f) => f.name === 'int' && (f['parent-ids'] || []).length === 0);
    assert(newSlot && newSlot['type-fn-id'] === intFn.id,
           'new field c\'s type-fn-id points at :int: '
           + JSON.stringify(newSlot?.['type-fn-id']));

    console.log('✓ record-edit verified — open / prefill / add field / PUT / storage');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.stack || e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
