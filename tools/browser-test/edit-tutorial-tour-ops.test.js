// Lesson 16 — effects and explicit secret forms
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// Run from this directory:  node edit-tutorial-tour-ops.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  NS_NAME, FN_NAME, hardCleanup, waitTourTitle, clickTourButton,
  filterAndSelect, extendViaRowActions, bindFirstPlaceholder,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding, waitClickable,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions,
  appendSeqItemViaEdge, bindFnRefPlaceholder,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, tourTitle, tourWhere,
  waitUntil, waitTourClosed, setBranchLocalViaStrip, openMarkerFormViaPlaceholder,
  compareBranchViaChip, exitBranchCompare,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-ops — lesson 16');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- Lesson 16 — effects (chip, ack gate, run) ----------
    await page.goto(BASE + '/?tutorial=16');
    await waitTourTitle(page, 'Effects are declared, then they spread', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 16 Next');
    await waitTourTitle(page, 'Find env');
    await filterAndSelect(page, 'env', 'env');
    await waitTourTitle(page, 'Read the effect chip', 150000);
    // The step's title lands as soon as the fn is SELECTED; the Inspector
    // Overview (the step's target — the card's strip is hidden on compact
    // cards, and its chips sit in the DOM unseen) paints a beat later.
    await page.waitForSelector('.gd-insp-effects .effects-chip', {timeout: 60000});
    const effChip = await page.evaluate(
      () => document.querySelector('.gd-insp-effects .effects-chip')?.className);
    assert(/effects-chip-env/.test(effChip || ''),
      'the env card carries an :env effect chip (got: ' + effChip + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 16 chip Next');
    await waitTourTitle(page, 'Open Run');
    // runWithEffectAck asserts the disabled-until-acknowledged gate itself.
    await runWithEffectAck(page, 'PATH');
    await waitTourTitle(page, 'The value — or the refusal', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 16 look-step Next');
    await waitTourTitle(page, 'Two gates, one vocabulary', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 16 gates Next');
    // Secrets (2026-09-21): where a secret is bound — the secret-typed slot's
    // + says "Bind secret" and opens the vault-path form; nothing is stored.
    await waitTourTitle(page, 'Where a secret goes', 150000);
    await filterAndSelect(page, 'sql-exec', 'sql-exec');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-db-call', 'sql-exec');
    await waitTourTitle(page, 'A secret-typed slot', 150000);
    await page.waitForSelector('.placeholder-binder[data-arg-name="password"]',
      {timeout: 60000});
    const hint = await page.evaluate(() =>
      document.querySelector('.placeholder-binder[data-arg-name="password"]')?.title || '');
    assert(/secret-typed/.test(hint), 'the + on :password says it is secret-typed (got: ' + hint + ')');
    const label = await openMarkerFormViaPlaceholder(page, 'password', 'secret');
    assert(label === 'Bind secret', 'the chooser names the action after the marker (got: ' + label + ')');
    await page.waitForSelector('.arg-value-edit-secret-form', {timeout: 15000});
    await waitTourTitle(page, 'Read it, then Cancel', 150000);
    await page.evaluate(() => document.querySelector('.arg-value-edit-popover .arg-value-edit-btn-secondary').click());
    await waitTourTitle(page, 'Secrets ride the same rails', 150000);
    // A plain slot on the same card never offers a secret.
    await page.evaluate(() => document.querySelector('.placeholder-binder[data-arg-name="sql"]').click());
    await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
      .some((b) => b.textContent.trim() === 'Bind literal'), null, {timeout: 15000, polling: 100});
    const plain = await page.evaluate(() => Array.from(document.querySelectorAll('button'))
      .map((b) => b.textContent.trim()).filter((t) => /^Bind /.test(t)));
    assert(!plain.includes('Bind secret') && plain.includes('Bind literal'),
      ':sql offers a literal, never a secret (got: ' + JSON.stringify(plain) + ')');
    await page.keyboard.press('Escape');
    await finishAndDelete(page);
    console.log('  lesson 16: walked + cleaned (secret-typed slot)');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
