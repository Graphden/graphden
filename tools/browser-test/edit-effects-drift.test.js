// Effects drift visualisation e2e — fn-card's bottom-strip effect
// chips visually distinguish three states:
//
//   computed ∩ declared           — solid chip (normal)
//   computed NOT declared (drift) — solid + `.effects-chip-drift` (red outline)
//   declared NOT computed (over)  — outlined `.effects-chip-ghost`
//
// Coverage:
//   • Drift case: seed an `:env`-parented probe with
//     `:expects-effects []` (empty declared, but `:env` effect
//     computes). Verify the `:env` chip carries `effects-chip-drift`.
//   • Ghost case: seed an `:identity`-parented probe with
//     `:expects-effects [:network]` (declared network, but pure
//     fn). Verify a `:network` chip with `effects-chip-ghost`.
//
// Run from this directory:  node edit-effects-drift.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const DRIFT_FN = 'effects-drift-probe' + RUN_ID;
const GHOST_FN = 'effects-ghost-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, DRIFT_FN); } catch (_) {}
  try { await deleteFnByName(page, GHOST_FN); } catch (_) {}
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
  console.log('edit-effects-drift — drift (red) + ghost (outlined) chips');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed both probes.
    // ===================================================================
    const ents = await getEntities(page);
    const envFn = ents.fns.find(
      (f) => f.name === 'env' && (f['parent-ids'] || []).length === 0);
    const identity = ents.fns.find((f) => f.name === 'identity');
    assert(envFn && identity, ':env + :identity baselines resolved');

    // DRIFT — env-parented with empty declared. The form-parser
    // treats blank value as "no declaration" (nil); the literal
    // string "[]" is the wire-format for "explicitly empty".
    await api(page, 'POST', '/api/entities/fn',
              'name=' + DRIFT_FN + '&parent-ids=' + envFn.id
              + '&expects-effects=' + encodeURIComponent('[]'));
    const driftProbe = (await getEntities(page)).fns.find(
      (f) => f.name === DRIFT_FN);
    assert(driftProbe, 'drift probe created');
    // Bind :name slot so the fn has zero free args (clean render).
    const slotsById = Object.fromEntries(
      ents.slots.map((s) => [s.id, s]));
    const envNameSlot = ents['fn-slots']
      .filter((fs) => fs['fn-id'] === envFn.id)
      .map((fs) => slotsById[fs['slot-id']])
      .find((s) => s.name === 'name');
    await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + driftProbe.id + '&slot-id=' + envNameSlot.id
              + '&value=' + encodeURIComponent('"MY_VAR"'));

    // GHOST — identity-parented with declared `:network` (no actual
    // network call).
    await api(page, 'POST', '/api/entities/fn',
              'name=' + GHOST_FN + '&parent-ids=' + identity.id
              + '&expects-effects=' + encodeURIComponent('network'));
    const ghostProbe = (await getEntities(page)).fns.find(
      (f) => f.name === GHOST_FN);
    assert(ghostProbe, 'ghost probe created');

    // ===================================================================
    // Phase A: drift probe. :env chip should carry effects-chip-drift.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + DRIFT_FN);
    await page.waitForTimeout(800);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForSelector('.effects-chip-env', {timeout: 15000});
    const driftState = await page.evaluate(() => {
      const chips = Array.from(document.querySelectorAll('.effects-chip-env'));
      return {
        chipCount: chips.length,
        hasDrift: chips.some((c) => c.classList.contains('effects-chip-drift')),
        anyTitle: chips.find((c) => c.title)?.title || '',
      };
    });
    assert(driftState.chipCount >= 1,
           ':env effect chip rendered: ' + driftState.chipCount);
    assert(driftState.hasDrift,
           ':env chip carries .effects-chip-drift (computed ⊃ declared)');
    assert(/DRIFT|undeclared/i.test(driftState.anyTitle),
           'chip title flags drift / undeclared: '
           + JSON.stringify(driftState.anyTitle).slice(0, 200));

    // ===================================================================
    // Phase B: ghost probe. :network chip should carry
    // effects-chip-ghost.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + GHOST_FN);
    await page.waitForTimeout(800);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForSelector('.effects-chip-network', {timeout: 15000});
    const ghostState = await page.evaluate(() => {
      const chips = Array.from(document.querySelectorAll('.effects-chip-network'));
      return {
        chipCount: chips.length,
        hasGhost: chips.some((c) => c.classList.contains('effects-chip-ghost')),
        anyTitle: chips.find((c) => c.title)?.title || '',
      };
    });
    assert(ghostState.chipCount >= 1,
           ':network effect chip rendered: ' + ghostState.chipCount);
    assert(ghostState.hasGhost,
           ':network chip carries .effects-chip-ghost (declared but not computed)');
    assert(/declared but not computed|declared/i.test(ghostState.anyTitle),
           'chip title flags over-declared / declared-not-computed: '
           + JSON.stringify(ghostState.anyTitle).slice(0, 200));

    console.log('✓ effects drift verified — drift chip + ghost chip + tooltips');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
