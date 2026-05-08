// Phase 3 — re-parent cascade. Verifies the orphan-delete +
// parent-ids replace + new-arg-create sequence end-to-end.
//
// Run from this directory:  node edit-phase3-reparent.test.js
// Exit code 0 = PASS, 1 = FAIL.

const { chromium } = require('playwright');
const { assert, newContext, api, getEntities, synthArgs, deleteFnByName } =
  require('./edit-test-helpers');

const TEST_NAME = 'test-edit-phase3';

(async () => {
  const { browser, page } = await newContext(chromium);
  console.log('Phase 3 — re-parent cascade');
  try {
    // Cleanup any prior leftovers before starting.
    await deleteFnByName(page, TEST_NAME);

    const ents = await getEntities(page);
    const add = ents.fns.find(f => f.name === 'add');
    const mul = ents.fns.find(f => f.name === 'mul');
    const baseSynth = synthArgs(ents);
    const addNums = baseSynth.find(a => a['fn-id'] === add.id && !a['source-id']);
    const mulNums = baseSynth.find(a => a['fn-id'] === mul.id && !a['source-id']);
    assert(add && mul && addNums && mulNums, 'baseline ids resolved');

    // 1. Create test fn (parent=[add]). The :nums slot is inherited
    //    automatically — no explicit "POST inheriting arg" step.
    await api(page, 'POST', '/api/entities/fn',
              'name=' + TEST_NAME + '&parent-ids=' + add.id);
    const created = (await getEntities(page)).fns.find(f => f.name === TEST_NAME);
    assert(created, 'test fn created');
    const seeded = synthArgs(await getEntities(page))
                     .filter(a => a['fn-id'] === created.id);
    assert(seeded.length === 1 && seeded[0]['slot-id'] === addNums['slot-id'],
           'inheriting arg points at add.nums');

    // 2. Open editor, exercise the parent-set editor end-to-end.
    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#' + TEST_NAME);
    await page.waitForTimeout(2500);

    const stripText = await page.evaluate(() => {
      const s = Array.from(document.querySelectorAll('.reparent-strip'))
                     .find(x => x.textContent.startsWith('parents:'));
      return s ? s.textContent : null;
    });
    assert(stripText === 'parents: 1', 'parents strip shows count=1');

    // Open editor → remove `add` → `+ add parent` → pick `mul` → save.
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.reparent-strip'))
        .find(x => x.textContent.startsWith('parents:')).click();
    });
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      document.querySelector('.parent-set-editor-chip-remove').click();
    });
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.parent-set-editor button'))
        .find(b => b.textContent.includes('+ add parent')).click();
    });
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      const s = document.querySelector('.fn-picker-search');
      s.value = 'mul';
      s.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await page.waitForTimeout(150);
    await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('.fn-picker-row'));
      const r = rows.find(x => /\bmul\b\s*$/.test(
        x.querySelector('.fn-picker-row-name').textContent.trim()));
      r.click();
    });
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      const e = document.querySelector('.parent-set-editor');
      Array.from(e.querySelectorAll('.arg-value-edit-buttons .arg-value-edit-btn'))
        .find(b => b.textContent === 'Save').click();
    });
    await page.waitForTimeout(2000);

    // 3. Verify cascade outcome.
    const after = await getEntities(page);
    const fnAfter = after.fns.find(f => f.id === created.id);
    const argsAfter = synthArgs(after).filter(a => a['fn-id'] === created.id);
    assert(JSON.stringify(fnAfter['parent-ids']) === JSON.stringify([mul.id]),
           'parent-ids replaced with [mul]');
    assert(argsAfter.length === 1, 'exactly one arg after cascade');
    assert(argsAfter[0]['slot-id'] === mulNums['slot-id'],
           'new arg points at mul.nums (orphan add.nums was deleted)');
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('Phase 3 — PASS');
})().catch(e => { console.error('Phase 3 — FAIL:', e.message); process.exit(1); });
