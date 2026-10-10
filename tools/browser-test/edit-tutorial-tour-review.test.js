// Lesson 24 — anchored review, approval, stale content and renewed approval
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// Run from this directory:  node edit-tutorial-tour-review.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  NS_NAME, FN_NAME, hardCleanup, waitTourTitle, settleTourRing, clickTourButton,
  filterAndSelect, extendViaRowActions, bindFirstPlaceholder,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding, waitClickable,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions,
  appendSeqItemViaEdge, bindFnRefPlaceholder,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, tourTitle, tourWhere,
  waitUntil, waitTourClosed, setBranchLocalViaStrip, openMarkerFormViaPlaceholder,
  compareBranchViaChip, exitBranchCompare, cleanupRecordedTutorialBranches,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-review — lesson 24');
  let failed = false;
  const scrollBranches = [];
  let releaseComments;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- lesson 24 — review: refusal → propose → approve → land ----------
    // Keep the release row below the initial viewport on the feature
    // branch, as it is for a reader with many existing branches.
    const scrollPrefix = 'a-tour-scroll-' + process.pid + '-' + Date.now().toString(36);
    for (let i = 0; i < 16; i++) {
      const name = scrollPrefix + '-' + i;
      scrollBranches.push(name);
      const created = await api(page, 'POST', '/api/branches', {name, 'base-branch-id': 'main'});
      assert(created.ok === true, 'scroll fixture branch created');
    }
    await page.goto(BASE + '/?tutorial=24');
    await waitTourTitle(page, "Review is the target's policy", 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 24 Next');
    await waitTourTitle(page, 'A release branch', 150000);
    await createBranchViaChip(page, 'tutorial-release');
    await waitTourTitle(page, 'Something to change');
    await filterAndSelect(page, 'const', 'const');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'review-demo', 'const');
    await waitTourTitle(page, 'review-demo is open', 150000);
    await waitTourTitle(page, 'Give it a value', 150000);
    await bindFirstPlaceholder(page, '1');
    await waitTourTitle(page, 'Protect it', 150000);
    // ⚙ on the tutorial-release row → Required approvals = 1. The policy
    // POST reloads the popover; the ⚙ button's data-attr reflects it.
    await waitClickable(page, '#branch-chip-btn');
    await page.evaluate(() => document.getElementById('branch-chip-btn').click());
    // ⚙ lives in the row's ⋯ menu now (readability redesign).
    await page.waitForSelector(
      '.branch-row-more[data-more-branch="tutorial-release"]', {timeout: 15000});
    await page.locator('.branch-row-more[data-more-branch="tutorial-release"]').click();
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
    await switchBranchViaChip(page, 'tutorial-release', {expectClipped: true});
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
    await waitTourTitle(page, 'Read the proposal', 150000);
    await filterAndSelect(page, 'review-demo', 'review-demo');
    const holdInitialComments = process.env.GRAPHDEN_COMMENT_RELOAD_PROOF === '1';
    const heldComments = [];
    let commentReads = 0;
    const commentsGate = new Promise((resolve) => { releaseComments = resolve; });
    if (holdInitialComments) {
      await page.route('**/api/branches/*/comments', async (route) => {
        if (route.request().method() !== 'GET'
          || ++commentReads === 1) { await route.continue(); return; }
        console.log('[composer trace] holding comments read', commentReads);
        const response = await route.fetch();
        const completion = commentsGate.then(() => route.fulfill({response}));
        heldComments.push(completion);
        await completion;
      });
    }
    await compareBranchViaChip(page, 'tutorial-feature');
    await waitTourTitle(page, 'Comment on this function', 150000);
    await page.click('#gd-diff-insp .gd-diff-insp-head .branch-diff-comment-btn');
    if (holdInitialComments) console.log('[composer trace] opened', await page.locator('#gd-diff-insp textarea').count(), 'reads', commentReads);
    const thread = '#gd-diff-insp .branch-diff-anchor-thread';
    await page.fill(thread + ' .branch-comment-input', 'Please keep this value explicit.');
    if (holdInitialComments) {
      assert(heldComments.length > 0, 'initial comments response held while composer is active');
      const textarea = await page.locator(thread + ' .branch-comment-input').elementHandle();
      releaseComments();
      await Promise.all(heldComments);
      await page.unroute('**/api/branches/*/comments');
      await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)));
      const draftState = await textarea.evaluate((input) => ({
        connected: input.isConnected, draft: input.value === 'Please keep this value explicit.',
        focused: document.activeElement === input,
      }));
      console.log('[composer trace] after real responses', JSON.stringify(draftState));
      assert(draftState.connected && draftState.draft && draftState.focused,
        'late real comments response retains the focused draft');
    }
    await page.click(thread + ' .branch-comment-send');
    await waitTourTitle(page, 'Reopen the same thread', 150000);
    // Posting reloads the thread without its composer; reopen it through
    // the same function's ordinary comment action before answering.
    await page.click('#gd-diff-insp .gd-diff-insp-head .branch-diff-comment-btn');
    await waitTourTitle(page, 'Answer in the same thread', 150000);
    await page.fill(thread + ' .branch-comment-input', 'It stays at 2 in this proposal.');
    // The composer appears after the title; let the real ring be recorded
    // before posting reloads the thread and removes its target again.
    await settleTourRing(page, 20000);
    await page.click(thread + ' .branch-comment-send');
    await waitTourTitle(page, 'Return to approval', 150000);
    await exitBranchCompare(page);
    await waitTourTitle(page, 'Approve it', 150000);
    await page.click('#branch-chip-btn');
    const approve = page.locator('.branch-row-approve[data-approve-branch="tutorial-feature"]');
    await approve.waitFor({state: 'visible', timeout: 30000});
    await approve.click();
    await page.waitForSelector('.branch-row-approve[data-approve-branch="tutorial-feature"][data-approved="1"] + .branch-appr-count.ok', {timeout: 30000});
    await waitTourTitle(page, 'Edit after approval', 150000);
    await switchBranchViaChip(page, 'tutorial-feature');
    await waitTourTitle(page, 'A new proposal value', 150000);
    await filterAndSelect(page, 'review-demo', 'review-demo');
    await editBoundValue(page, '3');
    await waitTourTitle(page, 'Return to the reviewer', 150000);
    await switchBranchViaChip(page, 'tutorial-release');
    await waitTourTitle(page, 'The earlier approval is stale', 150000);
    await page.click('#branch-chip-btn');
    await waitTourTitle(page, 'Read the new value', 150000);
    const stale = await page.locator('.branch-row-approve[data-approve-branch="tutorial-feature"]').getAttribute('aria-pressed');
    assert(stale === 'false', 'stale own approval no longer makes the button a withdrawal');
    await compareBranchViaChip(page, 'tutorial-feature');
    await filterAndSelect(page, 'review-demo', 'review-demo');
    await page.click('#gd-diff-insp .gd-diff-insp-head .branch-diff-comment-btn');
    await page.waitForFunction(() => {
      const bodies = [...document.querySelectorAll('#gd-diff-insp .branch-comment-body')].map(el => el.textContent);
      return bodies.includes('Please keep this value explicit.') && bodies.includes('It stays at 2 in this proposal.');
    }, null, {timeout: 30000});
    assert((await page.locator('#gd-diff-insp').innerText()).includes('3'), 'current proposal comparison shows value 3');
    await exitBranchCompare(page);
    assert(await clickTourButton(page, 'Next'), 'read changed proposal Next');
    await waitTourTitle(page, 'Approve the changed content', 150000);
    await page.click('#branch-chip-btn');
    await page.click('.branch-row-approve[data-approve-branch="tutorial-feature"]');
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
    console.log('  lesson 24: walked + cleaned (refused → comment/reply → approved → stale → reapproved; branches cleaned)');

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
    releaseComments?.();
    await page.unroute('**/api/branches/*/comments');
    try { await cleanupRecordedTutorialBranches(page); }
    catch (error) { failed = true; console.error('Owned cleanup failed:', error.message); }
    for (const name of scrollBranches.reverse()) {
      try {
        const removed = await api(page, 'DELETE', '/api/branches/' + encodeURIComponent(name));
        assert(removed.ok === true || removed.reason === 'not-found', 'scroll fixture branch removed');
      } catch (err) {
        failed = true;
        console.error('Scroll fixture cleanup failed:', err.message);
      }
    }
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
