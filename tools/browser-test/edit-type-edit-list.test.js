// List type-row edit e2e — open via pencil, change element type,
// Save → PUT /api/entities/fn/:id with element-type=<name>.
//
// Coverage:
//   • Seed a list with element :int via POST /api/types/list.
//   • Open the edit popover.
//   • Verify `element` input is prefilled with "int".
//   • Replace with "text" and Save.
//   • Verify storage's `:element-fn-id` now points at :text.
//
// Run from this directory:  node edit-type-edit-list.test.js

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const LIST_FN = 'edit-list-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, LIST_FN); } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => d.accept());
  console.log('edit-type-edit-list — open / change element-type / save');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed via /api/types/list (JSON body).
    // ===================================================================
    const seedResp = await page.evaluate(async ({base, auth, name}) => {
      const r = await fetch(base + '/api/types/list', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ' + auth,
        },
        body: JSON.stringify({name, 'element-type': 'int'}),
      });
      return r.json();
    }, {base: 'http://localhost:9002', auth: 'test123', name: LIST_FN});
    assert(seedResp.ok && seedResp.id,
           'list type-row created: ' + JSON.stringify(seedResp).slice(0, 120));

    const ents = await getEntities(page);
    const listFn = ents.fns.find((f) => f.name === LIST_FN);
    const intFn = ents.fns.find(
      (f) => f.name === 'int' && (f['parent-ids'] || []).length === 0);
    const textFn = ents.fns.find(
      (f) => f.name === 'text' && (f['parent-ids'] || []).length === 0);
    assert(listFn && intFn && textFn,
           'list + int + text baselines resolved');
    assert(listFn['element-fn-id'] === intFn.id,
           'seeded element-fn-id points at :int');

    // ===================================================================
    // Navigate + open the edit popover.
    // ===================================================================
    await page.goto('http://localhost:9002/#' + LIST_FN,
                    {waitUntil: 'networkidle'});
    await page.waitForFunction(
      () => typeof openTypeEditForm === 'function'
            && lookups?.fnMap?.size > 50,
      {timeout: 30000});
    await page.evaluate(async () => { await initGraph(); });
    await page.waitForTimeout(1500);
    const inLookups = await page.evaluate(
      (fnId) => !!lookups?.fnMap?.get(fnId), listFn.id);
    assert(inLookups, 'list in editor lookups');

    await page.evaluate((fnId) => {
      window.openTypeEditForm(fnId, document.body);
    }, listFn.id);
    await page.waitForSelector('.type-create-popover', {timeout: 5000});

    // ===================================================================
    // Phase A: element input prefilled with "int".
    // ===================================================================
    await page.waitForFunction(() => {
      const inputs = document.querySelectorAll(
        '.type-create-popover input.type-create-input');
      return Array.from(inputs).some((i) => i.value === 'int');
    }, {timeout: 5000});

    const prefill = await page.evaluate(() => {
      const el = document.querySelector('.type-create-popover');
      const inputs = Array.from(el?.querySelectorAll('input.type-create-input') || []);
      // First is the name input, last is the element input — but
      // it's simpler to find by current value.
      const elementInput = inputs.find((i) => i.value === 'int');
      return {
        elementValue: elementInput?.value,
        submit: el?.querySelector('.type-create-submit')?.textContent?.trim(),
      };
    });
    assert(prefill.elementValue === 'int',
           'element prefilled as "int": ' + JSON.stringify(prefill.elementValue));
    assert(/Save/i.test(prefill.submit || ''),
           'Save button: ' + JSON.stringify(prefill.submit));

    // ===================================================================
    // Phase B: change "int" → "text", Save.
    // ===================================================================
    // The element input is the one with the datalist attribute.
    await page.evaluate(() => {
      const el = document.querySelector('.type-create-popover');
      const elementInput = Array.from(el?.querySelectorAll('input.type-create-input') || [])
        .find((i) => i.getAttribute('list'));
      elementInput.value = '';
      elementInput.value = 'text';
      elementInput.dispatchEvent(new Event('input', {bubbles: true}));
      elementInput.dispatchEvent(new Event('change', {bubbles: true}));
    });
    const reqPromise = page.waitForRequest(
      (r) => r.url().includes('/api/entities/fn/') && r.method() === 'PUT',
      {timeout: 10000});
    await page.evaluate(() => {
      document.querySelector('.type-create-popover .type-create-submit')?.click();
    });
    await reqPromise.catch(() => {});
    await page.waitForFunction(
      () => {
        const el = document.querySelector('.type-create-popover');
        return !el || el.style.display === 'none';
      },
      {timeout: 15000});
    await page.waitForTimeout(800);

    // ===================================================================
    // Phase C: storage — element-fn-id now points at :text.
    // ===================================================================
    const ents2 = await getEntities(page);
    const listFn2 = ents2.fns.find((f) => f.id === listFn.id);
    assert(listFn2['element-fn-id'] === textFn.id,
           'element-fn-id now points at :text: '
           + JSON.stringify(listFn2['element-fn-id'])
           + ' (expected ' + textFn.id + ')');

    console.log('✓ list-edit verified — open / prefill / retype / save / storage');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.stack || e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
