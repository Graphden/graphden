// Review-workflow UI e2e — the whole propose → protect → approve →
// comment → merge funnel through the branch popover + diff modal.
//
// Coverage (audit-2 gap: the review UI had ZERO e2e):
//   • propose a branch (📤) → it shows in the "N proposals awaiting
//     review" inbox header
//   • ⚙ protection menu: set "Required approvals" to 1 on the target
//   • n/N badge on the proposed row reads 0/1, then 1/1 (+ .ok) after ✅
//   • merge blocked at 0/1 is implied by the badge; approve unblocks
//   • comment thread under the Δ diff: post a comment, it renders; an
//     HTML body renders as TEXT (no injected element) — the XSS choice
//   • merge (⇢) into the target clears the proposal's review-state so it
//     leaves the inbox
//
// Target is a NON-main branch on purpose: merging into main restarts the
// editor's own web-server service (severs the response), a demo artifact
// unrelated to what this test asserts.
//
// Run from this directory:  node edit-branch-review.test.js
// Exit 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, nodeApiJson, waitFor, waitForServerHealthy,
       openBranchPopover} = require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const TGT = 'review-tgt' + RUN_ID;
const SRC = 'review-src' + RUN_ID;


async function cleanup() {
  for (const b of [SRC, TGT]) {
    try { await api(null, 'DELETE', '/api/branches/' + encodeURIComponent(b)); }
    catch (_) {}
  }
}


// Re-open the popover and read the proposed row's state. Resilient to a
// concurrent reload (the merge handler navigates on success): a mid-flight
// evaluate throws "Execution context was destroyed" — treat that as
// not-ready so the caller's waitFor retries after the page settles.
async function readRow(page, name) {
  try {
    await openBranchPopover(page);
    return await page.evaluate((n) => {
      const row = document.querySelector(
        '.branch-row[data-branch-name="' + n + '"]');
      if (!row) return {present: false};
      const badge = row.querySelector('.branch-appr-count');
      return {
        present: true,
        proposed: !!row.querySelector('.branch-row-propose.on'),
        badge: badge ? badge.textContent : null,
        badgeOk: badge ? badge.classList.contains('ok') : false,
        header: document.querySelector('.branch-proposals-header')?.textContent || null,
      };
    }, name);
  } catch (_) {
    return {present: false, navigating: true};
  }
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => { d.accept(); });
  console.log('edit-branch-review — propose / protect / approve / comment / merge');

  try {
    await cleanup();
    await waitForServerHealthy();

    // --- API setup: target ← main, source ← target, propose source.
    //     (Branch/fn plumbing is covered elsewhere; this test is about the
    //     REVIEW affordances, so seed the tree via API. An empty diff is
    //     fine — the comment thread and merge don't depend on diff content.)
    await nodeApiJson('POST', '/api/branches', {name: TGT, 'base-branch-id': 'main'});
    await nodeApiJson('POST', '/api/branches', {name: SRC, 'base-branch-id': TGT});
    await nodeApiJson('POST', '/api/branches/' + SRC + '/propose', {proposed: true});

    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});

    // ================================================================
    // Phase A: the proposal shows in the inbox header.
    // ================================================================
    let row = await readRow(page, SRC);
    assert(row.present, 'source branch row present in popover');
    assert(row.proposed, 'source row shows 📤 proposed (lit)');
    assert(row.header && /proposal/.test(row.header),
           'inbox header counts the proposal: ' + JSON.stringify(row.header));

    // ================================================================
    // Phase B: ⚙ protection menu — set Required approvals = 1 on TGT.
    // ================================================================
    await openBranchPopover(page);
    await page.click('.branch-row[data-branch-name="' + TGT + '"] .branch-row-protect');
    await page.waitForSelector('#gd-protect-pop select', {timeout: 10000});
    await page.selectOption('#gd-protect-pop select', '1');
    // the change handler POSTs /review-policy; wait for the badge to appear
    const gotBadge = await waitFor(async () => {
      const r = await readRow(page, SRC);
      return r.badge === '0/1';
    }, 20000);
    assert(gotBadge, 'after ⚙ sets required=1, source row badge reads 0/1');

    // ================================================================
    // Phase C: approve (✅) → badge 1/1 + .ok.
    // ================================================================
    await openBranchPopover(page);
    await page.click('.branch-row[data-branch-name="' + SRC + '"] .branch-row-approve');
    const approved = await waitFor(async () => {
      const r = await readRow(page, SRC);
      return r.badge === '1/1' && r.badgeOk;
    }, 20000);
    assert(approved, 'after ✅ approve, badge reads 1/1 and is marked satisfied');

    // ================================================================
    // Phase D: comment thread under the Δ diff — renders, and an HTML
    // body is shown as TEXT (no injected element).
    // ================================================================
    await openBranchPopover(page);
    await page.click('.branch-row[data-branch-name="' + SRC + '"] .branch-row-diff');
    await page.waitForSelector('.branch-comments', {timeout: 45000});
    const XSS = 'looks good <b>NOPE</b>';
    await page.fill('.branch-comment-input', XSS);
    await page.click('.branch-comment-send');
    const posted = await waitFor(async () => {
      return page.evaluate(() => {
        const bodies = [...document.querySelectorAll('.branch-comment-body')];
        return bodies.some((b) => b.textContent.includes('NOPE'));
      });
    }, 15000);
    assert(posted, 'comment appears in the thread after send');
    const xssSafe = await page.evaluate(() => {
      const body = [...document.querySelectorAll('.branch-comment-body')]
        .find((b) => b.textContent.includes('NOPE'));
      return {
        text: body?.textContent,
        injectedEl: !!body?.querySelector('b'),
      };
    });
    assert(xssSafe.text.includes('<b>NOPE</b>'),
           'HTML body rendered verbatim as text');
    assert(!xssSafe.injectedEl,
           'no <b> element injected — comment body is textContent, not HTML');
    // close the diff modal
    await page.keyboard.press('Escape');

    // ================================================================
    // Phase E: switch to TGT so ⇢ on the source merges INTO it, then
    // merge — the proposal's review-state clears (leaves the inbox).
    // ================================================================
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')
                    + '/?branch=' + encodeURIComponent(TGT));
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});
    await openBranchPopover(page);
    // The merge handler reloads the page on success; let that navigation
    // land before polling the row (readRow tolerates a mid-reload race too).
    await Promise.all([
      page.waitForNavigation({timeout: 30000}).catch(() => {}),
      page.click('.branch-row[data-branch-name="' + SRC + '"] .branch-row-merge'),
    ]);
    await page.waitForSelector('#branch-chip-btn', {timeout: 15000}).catch(() => {});
    const cleared = await waitFor(async () => {
      const r = await readRow(page, SRC);
      // merged proposal: no longer proposed, and (only proposals get a
      // badge) the badge is gone.
      return r.present && !r.proposed && !r.badge;
    }, 30000);
    assert(cleared,
           'after merge, the source is no longer proposed — proposal left the inbox');

    console.log('✓ review workflow verified — propose / ⚙ / n-of-N / approve / comment / merge-clears-inbox');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup().catch(() => {});
    await browser.close();
  }
})();
