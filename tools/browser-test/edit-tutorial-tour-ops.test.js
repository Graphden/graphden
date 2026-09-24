// Lessons 23, 24, 16 — branches, review, effects
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
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-ops — lessons 23 / 24 / 16');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- lesson 23 — branches (fork, edit, come back) ----------
    await page.goto(BASE + '/?tutorial=23');
    await waitTourTitle(page, 'Branches are views, not copies', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 23 Next');
    await waitTourTitle(page, 'Find str-upper');
    await filterAndSelect(page, 'str-upper', 'str-upper');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'branch-demo', 'str-upper');
    await waitTourTitle(page, 'Give it a value on main', 150000);
    await bindFirstPlaceholder(page, 'main version');
    await waitTourTitle(page, 'Fork a branch', 150000);
    await createBranchViaChip(page, 'tutorial-branch');
    // Page reloaded on the branch — the tour resumes from localStorage.
    await waitTourTitle(page, 'Change the value here', 150000);
    await editBoundValue(page, 'branch version');
    await waitTourTitle(page, 'Go back to main', 150000);
    await switchBranchViaChip(page, 'main');
    await waitTourTitle(page, 'main never saw it', 150000);
    // main still reads the original literal — the whole point of the lesson.
    const mainValue = await page.evaluate(() => {
      const fn = Array.from(lookups.fnMap.values()).find((f) => f.name === 'branch-demo');
      const bs = fn ? (lookups.bindingsByFn.get(fn.id) || []) : [];
      return bs.map((b) => b.value);
    });
    assert(mainValue.includes('main version'),
      'main still reads "main version" after the branch edit');
    assert(await clickTourButton(page, 'Next'), 'lesson 23 back-on-main Next');
    // 📍 (2026-09-20): the strip is a toggle on a fn the reader owns.
    await waitTourTitle(page, 'Pin a fn to its branch', 150000);
    await setBranchLocalViaStrip(page, 'branch-demo', true);
    await waitTourTitle(page, 'Compare the branches', 150000);
    const demoIdx = await api(page, 'GET', '/api/graph/entities?scope=search&q=branch-demo');
    const demo = (demoIdx.fns || []).find((f) => f.name === 'branch-demo');
    const demoRow = demo
      ? ((await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + demo.id)).fns || [])
        .find((f) => f.id === demo.id)
      : null;
    assert(demoRow && demoRow['branch-local?'] === true,
      'branch-demo\'s row carries branch-local? true (got: ' + JSON.stringify(demoRow?.['branch-local?']) + ')');
    // Δ on the tutorial-branch row → COMPARE MODE (UX-v3): the Δ chip
    // appears by the branch chip and the tour's dom-check passes.
    await waitClickable(page, '#branch-chip-btn');
    await page.evaluate(() => document.getElementById('branch-chip-btn').click());
    await page.waitForSelector('.branch-row-diff[data-diff-source="tutorial-branch"]',
      {timeout: 15000});
    await page.evaluate(() => document.querySelector(
      '.branch-row-diff[data-diff-source="tutorial-branch"]').click());
    await page.waitForSelector('#gd-diff-chip', {timeout: 60000});
    await waitTourTitle(page, 'Read it, then exit', 150000);
    // The lesson stops short of merging on purpose: a branch merged into
    // main becomes part of main's history (merge is by-reference) and can
    // no longer be deleted — cleanup would leave it behind forever.
    await page.evaluate(() => document.querySelector('.gd-diff-chip-off').click());
    await page.waitForFunction(() => !document.getElementById('gd-diff-chip'),
      null, {timeout: 15000});
    await waitTourTitle(page, 'When both sides touched the same thing', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 23 conflict-notes Next');
    await waitTourTitle(page, "That's branching", 150000);
    await finishAndDelete(page);
    // The cleanup must have removed the lesson's BRANCH too, not just the fn.
    const branches = await api(page, 'GET', '/api/branches');
    const names = (Array.isArray(branches) ? branches : (branches.branches || []))
      .map((b) => b.name);
    assert(!names.includes('tutorial-branch'),
      'tour cleanup deleted the lesson branch');
    console.log('  lesson 23: walked + cleaned (branch too, compare mode entered)');

    // ---------- lesson 24 — review: refusal → propose → approve → land ----------
    await page.goto(BASE + '/?tutorial=24');
    await waitTourTitle(page, "Review is the target's policy", 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 24 Next');
    await waitTourTitle(page, 'Something to change');
    await filterAndSelect(page, 'const', 'const');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'review-demo', 'const');
    await waitTourTitle(page, 'review-demo is open', 150000);
    await waitTourTitle(page, 'Give it a value', 150000);
    await bindFirstPlaceholder(page, '1');
    await waitTourTitle(page, 'A release branch', 150000);
    await createBranchViaChip(page, 'tutorial-release');
    await waitTourTitle(page, 'Protect it', 150000);
    // ⚙ on the tutorial-release row → Required approvals = 1. The policy
    // POST reloads the popover; the ⚙ button's data-attr reflects it.
    await waitClickable(page, '#branch-chip-btn');
    await page.evaluate(() => document.getElementById('branch-chip-btn').click());
    // ⚙ lives in the row's ⋯ menu now (readability redesign).
    await page.waitForSelector(
      '.branch-row-more[data-more-branch="tutorial-release"]', {timeout: 15000});
    await page.evaluate(() => document.querySelector(
      '.branch-row-more[data-more-branch="tutorial-release"]').click());
    await page.waitForSelector(
      '.branch-row-more-menu.open .branch-row-protect[data-protect-branch="tutorial-release"]',
      {timeout: 15000});
    await page.evaluate(() => document.querySelector(
      '.branch-row-protect[data-protect-branch="tutorial-release"]').click());
    await page.waitForSelector('#gd-protect-pop .gd-protect-seg', {timeout: 15000});
    await page.evaluate(() => {
      // Segmented 0…3 control (diff-v2 redesign) — "1" is the 2nd button.
      document.querySelectorAll('#gd-protect-pop .gd-protect-seg-btn')[1].click();
    });
    await page.waitForSelector(
      '.branch-row-protect[data-protect-branch="tutorial-release"][data-reqappr="1"]',
      {timeout: 30000, state: 'attached'});   // lives inside the closed ⋯ menu
    await waitTourTitle(page, 'A feature branch', 150000);
    // Close the ⚙ menu. Whether its scrim click also dismissed the branch
    // popover depends on event order — the chip TOGGLES, so click it only
    // when the create row is actually hidden.
    await page.evaluate(() => document.getElementById('gd-protect-scrim')?.click());
    for (let i = 0; i < 3; i++) {
      const createRowUp = await page.evaluate(() => {
        const el = document.getElementById('branch-create-input');
        return !!el && el.offsetParent !== null;
      });
      if (createRowUp) break;
      await page.evaluate(() => document.getElementById('branch-chip-btn').click());
      await new Promise((r) => setTimeout(r, 300));
    }
    await page.fill('#branch-create-input', 'tutorial-feature');
    await page.evaluate(() => document.getElementById('branch-create-btn').click());
    await page.waitForFunction(
      () => new URLSearchParams(location.search).get('branch') === 'tutorial-feature',
      null, {timeout: 120000, polling: 300});
    await waitTourTitle(page, 'Change the value here', 150000);
    await editBoundValue(page, '2');
    await waitTourTitle(page, 'Back to the release branch', 150000);
    await switchBranchViaChip(page, 'tutorial-release');
    await waitTourTitle(page, 'Try to merge — refused', 150000);
    await waitClickable(page, '#branch-chip-btn');
    await page.evaluate(() => document.getElementById('branch-chip-btn').click());
    await page.waitForSelector('.branch-row-merge[data-merge-source="tutorial-feature"]',
      {timeout: 15000});
    await page.evaluate(() => document.querySelector(
      '.branch-row-merge[data-merge-source="tutorial-feature"]').click());
    await page.waitForFunction(() => {
      const e = document.getElementById('branch-popover-error');
      return e && !e.classList.contains('hidden') && /approval/i.test(e.textContent || '');
    }, null, {timeout: 60000, polling: 200});
    await waitTourTitle(page, 'Propose it', 150000);
    await page.evaluate(() => document.querySelector(
      '.branch-row-propose[data-propose-branch="tutorial-feature"]').click());
    await page.waitForSelector('.branch-row-propose.on[data-propose-branch="tutorial-feature"]',
      {timeout: 30000, state: 'attached'});   // inside the closed ⋯ menu
    await waitTourTitle(page, 'Approve it', 150000);
    await page.evaluate(() => document.querySelector(
      '.branch-row-approve[data-approve-branch="tutorial-feature"]').click());
    await page.waitForSelector('.branch-appr-count.ok', {timeout: 30000});
    await waitTourTitle(page, 'Ready to land — and why we stop', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 24 ready-to-land Next');
    await waitTourTitle(page, 'Back to main', 150000);
    await switchBranchViaChip(page, 'main');
    await waitTourTitle(page, "That's review", 150000);
    await finishAndDelete(page);
    const reviewBranches = await api(page, 'GET', '/api/branches');
    const reviewNames = (Array.isArray(reviewBranches) ? reviewBranches
      : (reviewBranches.branches || [])).map((b) => b.name);
    assert(!reviewNames.includes('tutorial-release')
           && !reviewNames.includes('tutorial-feature'),
      'tour cleanup deleted both review branches');
    console.log('  lesson 24: walked + cleaned (refused → proposed → approved; branches cleaned)');

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
