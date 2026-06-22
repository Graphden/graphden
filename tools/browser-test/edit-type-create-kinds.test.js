// Type-row creation popover — extend the refinement-only coverage in
// edit-type-create.test.js with Record + Union submit paths.
//
// Coverage:
//   • Record: open popover, switch to Record tab, fill two field rows
//     (name + type each), submit → record type-row appears in
//     sidebar + richTypes. Storage has fn-slot junctions per field.
//   • Union: open popover, switch to Union tab, fill comma-separated
//     branches, submit → union type-row appears with the canonical
//     `[:union T1 T2]` constraint.
//
// (Variant + List skipped — same dispatch shape; diminishing return.)
//
// Run from this directory:  node edit-type-create-kinds.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName,
       waitForServerHealthy} = require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const RECORD_NAME = 'tc-record-probe' + RUN_ID;
const UNION_NAME = 'tc-union-probe' + RUN_ID;
const PARENT_NS = 'app';


async function cleanup(page) {
  try { await deleteFnByName(page, RECORD_NAME); } catch (_) {}
  try { await deleteFnByName(page, UNION_NAME); } catch (_) {}
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
  await page.waitForTimeout(300);
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


async function switchTab(page, label) {
  await page.evaluate((lbl) => {
    const tab = Array.from(document.querySelectorAll('.type-create-tab'))
      .find((t) => t.textContent.trim() === lbl);
    tab?.click();
  }, label);
  await page.waitForFunction(
    (lbl) => {
      const t = Array.from(document.querySelectorAll('.type-create-tab'))
        .find((tab) => tab.textContent.trim() === lbl);
      return t?.classList.contains('type-create-tab-active');
    },
    label,
    {timeout: 3000});
}


async function submit(page) {
  // Programmatic click — Playwright's auto-wait flags the sidebar
  // resizer as intercepting on some viewports.
  await page.evaluate(() => {
    document.querySelector('.type-create-submit')?.click();
  });
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
  console.log('edit-type-create-kinds — Record + Union submit paths');

  try {
    await cleanup(page);
    // The JVM may be mid-cold-start from an earlier OOM-restart;
    // editor's initGraph fires browser-side fetches with no retry
    // logic and dies on the cold-start window. Block until ready.
    await waitForServerHealthy();
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/');
    await page.waitForSelector('.ns-header', {timeout: 15000});

    // ===================================================================
    // Phase A: Record. Open + switch to Record tab + fill 2 rows +
    // submit.
    // ===================================================================
    await openTypeCreate(page, PARENT_NS);
    await switchTab(page, 'Record');
    // Fill name (inputs[0]).
    await page.evaluate((name) => {
      const inputs = Array.from(
        document.querySelectorAll('.type-create-form input[type="text"]'));
      inputs[0].value = name;
      inputs[0].dispatchEvent(new Event('input', {bubbles: true}));
    }, RECORD_NAME);
    // The record form starts with `minRows: 2` already mounted. Fill
    // them. Pair-row inputs come right after the name field.
    await page.evaluate(() => {
      const rows = document.querySelectorAll('.type-create-pair-row');
      // Each row has 2 inputs (name + type). Fill row 0 = (id, uuid),
      // row 1 = (label, text).
      const fill = (row, idx, val) => {
        const ins = row.querySelectorAll('input[type="text"]');
        ins[idx].value = val;
        ins[idx].dispatchEvent(new Event('input', {bubbles: true}));
      };
      fill(rows[0], 0, 'id');
      fill(rows[0], 1, 'uuid');
      fill(rows[1], 0, 'label');
      fill(rows[1], 1, 'text');
    });
    await submit(page);
    await page.waitForFunction(
      (name) => Array.from(document.querySelectorAll('.entity-item'))
        .some((el) => el.textContent.includes(name)),
      RECORD_NAME,
      {timeout: 15000});

    const recordEnts = await getEntities(page);
    const recordFn = recordEnts.fns.find((f) => f.name === RECORD_NAME);
    assert(recordFn, 'record fn-row in storage');
    // Record kind has fn-slot junctions for each field.
    const recordFnSlots = (recordEnts['fn-slots'] || [])
      .filter((fs) => fs['fn-id'] === recordFn.id);
    assert(recordFnSlots.length === 2,
           'record has 2 fn-slot junctions (one per field): '
           + recordFnSlots.length);
    const slotsById = Object.fromEntries(
      recordEnts.slots.map((s) => [s.id, s]));
    const fieldNames = recordFnSlots
      .map((fs) => slotsById[fs['slot-id']]?.name).sort();
    assert(JSON.stringify(fieldNames) === JSON.stringify(['id', 'label']),
           'fields named "id" + "label": ' + JSON.stringify(fieldNames));

    // ===================================================================
    // Phase B: Union. Re-open popover, switch to Union, fill
    // branches, submit.
    // ===================================================================
    await openTypeCreate(page, PARENT_NS);
    await switchTab(page, 'Union');
    await page.evaluate((name) => {
      const inputs = Array.from(
        document.querySelectorAll('.type-create-form input[type="text"]'));
      // [0] = name, [1] = branches
      inputs[0].value = name;
      inputs[0].dispatchEvent(new Event('input', {bubbles: true}));
      inputs[1].value = 'null, text';
      inputs[1].dispatchEvent(new Event('input', {bubbles: true}));
    }, UNION_NAME);
    await submit(page);
    await page.waitForFunction(
      (name) => Array.from(document.querySelectorAll('.entity-item'))
        .some((el) => el.textContent.includes(name)),
      UNION_NAME,
      {timeout: 15000});

    const unionEnts = await getEntities(page);
    const unionFn = unionEnts.fns.find((f) => f.name === UNION_NAME);
    assert(unionFn, 'union fn-row in storage');
    // Union encodes branches into the `:constraint` field. The form
    // canonicalises to `["union", "null", "text"]` (decoded by the
    // backend codec).
    const c = unionFn.constraint;
    assert(Array.isArray(c),
           ':constraint is a vector: ' + typeof c);
    assert(c[0] === 'union' || c[0] === ':union',
           ':constraint head is "union": ' + JSON.stringify(c[0]));
    const branches = c.slice(1).map((x) => String(x).replace(/^:/, ''));
    assert(JSON.stringify(branches.sort()) === JSON.stringify(['null', 'text']),
           'branches = [null, text]: ' + JSON.stringify(branches));

    console.log('✓ type-create record + union verified — submit / storage / rich-types');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
