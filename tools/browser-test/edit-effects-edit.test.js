// Declared-effects ✎ editor e2e — the full edit flow through the
// server-rendered form (`GET /partials/expects-effects-form`):
//
//   • open ✎ on a no-contract fn → form loads async; the category
//     roster is the CANONICAL set (8 items — the old client grid
//     listed six and made :process / :raw-sql undeclarable);
//   • "no contract" pre-selected, checkboxes disabled;
//   • switch to "explicit contract" → tick :db + :process → Save →
//     the fn row's `expects-effects` persists ["db","process"];
//   • reopen → server pre-fills the contract mode + ticks;
//   • switch back to "no contract" → Save → contract cleared (nil).
//
// This is the one newly-async edit flow no other test drove — the
// drift/badges tests only assert the pencil exists or write the
// field via direct API calls.
//
// Run from this directory:  node edit-effects-edit.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE = 'effects-edit-probe' + RUN_ID;
const CANONICAL = ['db', 'env', 'io', 'network', 'process',
                   'random', 'raw-sql', 'time'];


async function cleanup(page) {
  try { await deleteFnByName(page, PROBE); } catch (_) {}
}


// Open the ✎ effects editor on the current card and wait for the
// server form to land (radio inputs present ⇒ fetch resolved).
async function openEffectsEditor(page) {
  await page.waitForSelector('.effects-strip-edit', {timeout: 30000});
  await page.click('.effects-strip-edit');
  await page.waitForSelector('.expects-effects-edit input[name="ee-mode"]',
                             {timeout: 30000});
}


async function readFormState(page) {
  return page.evaluate(() => {
    const wrap = document.querySelector('.expects-effects-edit');
    const boxes = Array.from(wrap.querySelectorAll(
      '.expects-effects-grid input[type="checkbox"]'));
    return {
      categories: boxes.map((b) => b.value).sort(),
      checked: boxes.filter((b) => b.checked).map((b) => b.value).sort(),
      disabled: boxes.every((b) => b.disabled),
      noneChecked: wrap.querySelector(
        'input[name="ee-mode"][value="none"]').checked,
      contractChecked: wrap.querySelector(
        'input[name="ee-mode"][value="contract"]').checked,
    };
  });
}


async function saveAndSettle(page) {
  await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll(
      '.arg-value-edit-popover .arg-value-edit-btn'));
    btns.find((b) => b.textContent === 'Save').click();
  });
  // Save closes the popover on success.
  await page.waitForFunction(
    () => !document.querySelector('.expects-effects-edit'),
    null, {timeout: 30000, polling: 100});
}


async function gotoProbe(page) {
  await page.goto('about:blank');
  await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')
                  + '/#' + PROBE);
  await page.waitForFunction(
    () => graphReady()
          && !!document.querySelector('button.more-actions-trigger')
          && !graph.animating,
    null,
    {timeout: 45000, polling: 100});
  await page.evaluate(() => initGraph && initGraph());
  await page.waitForSelector('.effects-strip', {timeout: 30000});
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => { d.accept(); });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-effects-edit — declared-effects form roundtrip');

  try {
    await cleanup(page);

    // Seed: identity-parented probe, NO expects-effects (no contract).
    const ents = await getEntities(page);
    const identity = ents.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE + '&parent-ids=' + identity.id);
    assert((await getEntities(page, PROBE)).fns.some((f) => f.name === PROBE),
           'probe created');

    // =================================================================
    // Phase A: open — canonical roster, no-contract prefill.
    // =================================================================
    await gotoProbe(page);
    await openEffectsEditor(page);
    let st = await readFormState(page);
    assert(JSON.stringify(st.categories) === JSON.stringify(CANONICAL),
           'canonical 8-category roster (incl. process + raw-sql): '
           + st.categories.join(','));
    assert(st.noneChecked && !st.contractChecked,
           '"no contract" pre-selected');
    assert(st.disabled, 'checkboxes disabled while no contract');

    // =================================================================
    // Phase B: declare {db, process} and save.
    // =================================================================
    await page.click('.expects-effects-edit input[name="ee-mode"][value="contract"]');
    await page.click('.expects-effects-grid input[value="db"]');
    await page.click('.expects-effects-grid input[value="process"]');
    await saveAndSettle(page);
    const probeRow = (await getEntities(page, PROBE)).fns.find(
      (f) => f.name === PROBE);
    assert(JSON.stringify((probeRow['expects-effects'] || []).sort())
           === JSON.stringify(['db', 'process']),
           'expects-effects persisted as [db process]: '
           + JSON.stringify(probeRow['expects-effects']));

    // =================================================================
    // Phase C: reopen — server pre-fills contract mode + ticks.
    // =================================================================
    await gotoProbe(page);
    await openEffectsEditor(page);
    st = await readFormState(page);
    assert(st.contractChecked && !st.noneChecked,
           'contract mode pre-selected on reopen');
    assert(JSON.stringify(st.checked) === JSON.stringify(['db', 'process']),
           'previously declared categories ticked: ' + st.checked.join(','));
    assert(!st.disabled, 'checkboxes enabled in contract mode');

    // =================================================================
    // Phase D: back to no-contract → save → cleared.
    // =================================================================
    await page.click('.expects-effects-edit input[name="ee-mode"][value="none"]');
    await saveAndSettle(page);
    const cleared = (await getEntities(page, PROBE)).fns.find(
      (f) => f.name === PROBE);
    assert(cleared['expects-effects'] == null,
           'contract cleared back to nil: '
           + JSON.stringify(cleared['expects-effects']));

    console.log('✓ declared-effects form roundtrip verified');
    await cleanup(page);
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
    try { await cleanup(page); } catch (_) {}
  } finally {
    await browser.close();
  }
})();
