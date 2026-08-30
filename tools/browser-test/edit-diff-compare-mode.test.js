// Diff v2 e2e — compare mode + anchored comments + suggestions.
//
// Flow:
//   1. Seed: feat branch off main; a probe fn on feat (so the diff has
//      an "Only in feat" group); propose feat.
//   2. From main, enter COMPARE MODE vs feat (gdEnterDiffMode) —
//      the ◐ chip renders, the Explorer badges the changed ns.
//   3. Compare mode survives a reload (localStorage) and exits via ×.
//   4. Δ modal: post an ANCHORED comment on the probe's group row →
//      it renders as an inline thread + count badge; the general
//      thread does NOT contain it; reopening shows it again.
//   5. Suggestions: a proposed CHILD of feat shows in feat's diff
//      modal suggestions section with Δ view + apply buttons.
//
// Run from this directory:  node edit-diff-compare-mode.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, openBranchPopover} =
  require('./edit-test-helpers');

const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FEAT = 'cmp-feat' + RUN_ID;
const SUGG = 'suggest-cmp' + RUN_ID;
const PROBE_FN = 'cmp-probe' + RUN_ID;

async function cleanup(page) {
  try {
    const ents = await page.evaluate(async (branch) => {
      const r = await window.authFetch('/api/graph/entities',
                                       {headers: {'X-Graphden-Branch': branch}});
      return r.ok ? r.json() : null;
    }, FEAT);
    const probe = (ents?.fns || []).find((f) => f.name === PROBE_FN);
    if (probe) {
      await page.evaluate(async ({id, branch}) => {
        await window.authFetch('/api/entities/fn/' + id, {
          method: 'DELETE',
          headers: {'X-Graphden-Branch': branch},
        });
      }, {id: probe.id, branch: FEAT});
    }
  } catch (_) {}
  for (const b of [SUGG, FEAT]) {
    try {
      await api(page, 'DELETE', '/api/branches/' + encodeURIComponent(b));
    } catch (_) {}
  }
  try { await page.evaluate(() => localStorage.removeItem('graphden.diffAgainst')); }
  catch (_) {}
}

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => { d.accept(); });
  console.log('edit-diff-compare-mode — compare mode / anchored comments / suggestions');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});
    await cleanup(page);

    // =================================================================
    // Seed: feat + probe fn on feat + propose feat + suggestion child.
    // =================================================================
    assert((await api(page, 'POST', '/api/branches', {name: FEAT}))?.ok,
           'feat branch created');
    const mainEnts = await api(page, 'GET', '/api/graph/entities');
    const identity = mainEnts.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity baseline resolved');
    const created = await page.evaluate(async ({name, parentId, branch}) => {
      const body = new URLSearchParams();
      body.set('name', name);
      body.set('parent-ids', parentId);
      const r = await window.authFetch('/api/entities/fn', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Graphden-Branch': branch,
        },
        body: body.toString(),
      });
      return {status: r.status, body: await r.text()};
    }, {name: PROBE_FN, parentId: identity.id, branch: FEAT});
    assert(created.body.includes('created successfully'),
           'probe fn created on feat');
    assert((await api(page, 'POST',
                      '/api/branches/' + encodeURIComponent(FEAT) + '/propose',
                      {proposed: true}))?.ok, 'feat proposed');
    assert((await api(page, 'POST', '/api/branches',
                      {name: SUGG, 'base-branch-id': FEAT}))?.ok,
           'suggestion child branch created off feat');
    assert((await api(page, 'POST',
                      '/api/branches/' + encodeURIComponent(SUGG) + '/propose',
                      {proposed: true}))?.ok, 'suggestion proposed');

    // =================================================================
    // Phase A: compare mode from main vs feat.
    // =================================================================
    await page.evaluate((other) => window.gdEnterDiffMode(other), FEAT);
    await page.waitForSelector('#gd-diff-chip', {timeout: 20000});
    const chipText = await page.evaluate(
      () => document.querySelector('.gd-diff-chip-label')?.textContent);
    assert(chipText === '◐ vs ' + FEAT,
           '◐ chip renders the compared branch: ' + JSON.stringify(chipText));

    // The probe lives only on feat → a "−1" aggregate should land on a
    // namespace header (root fns land on the pseudo-root, which carries
    // no data-ns-path — so assert the MODE data instead + any ns badge
    // when present).
    const modeInfo = await page.evaluate(() => ({
      active: window.gdDiffModeActive(),
      branch: window.gdDiffModeBranch(),
    }));
    assert(modeInfo.active && modeInfo.branch === FEAT,
           'compare mode active vs feat: ' + JSON.stringify(modeInfo));

    // =================================================================
    // Phase B: mode survives reload; × exits.
    // =================================================================
    await page.reload();
    await page.waitForSelector('#branch-chip-btn', {timeout: 15000});
    await page.waitForSelector('#gd-diff-chip', {timeout: 40000});
    assert(true, 'compare mode restored after reload (chip present)');
    // JS click — the Explorer keeps re-rendering around the chip while
    // the freshly reloaded page settles, which can fail Playwright's
    // stability check even though the chip is visible and on top.
    await page.evaluate(() => document.querySelector('.gd-diff-chip-off').click());
    const chipGone = await page.evaluate(
      () => !document.getElementById('gd-diff-chip'));
    assert(chipGone, '× exits compare mode (chip removed)');
    const cleared = await page.evaluate(
      () => localStorage.getItem('graphden.diffAgainst') === null);
    assert(cleared, 'localStorage cleared on exit');

    // =================================================================
    // Phase C: anchored comment in the Δ modal.
    // =================================================================
    let opened = await openBranchPopover(page);
    assert(opened, 'branch popover opens');
    await page.click(
      '.branch-row[data-branch-name="' + FEAT + '"] .branch-row-diff');
    await page.waitForSelector('.branch-diff-modal:not(.hidden)', {timeout: 20000});
    await page.waitForFunction(() => {
      const m = document.querySelector('.branch-diff-modal');
      return m && !m.querySelector('.branch-diff-loading')
        && m.querySelector('.branch-comments');
    }, {timeout: 45000});

    // The probe's group row carries data-anchor attrs; open its composer.
    const rowSel = '.branch-diff-row[data-diff-fn-name="' + PROBE_FN + '"]';
    await page.waitForSelector(rowSel, {timeout: 15000});
    await page.evaluate((sel) => {
      document.querySelector(sel + ' .branch-diff-comment-btn').click();
    }, rowSel);
    await page.waitForSelector('.branch-diff-anchor-thread textarea', {timeout: 10000});
    await page.fill('.branch-diff-anchor-thread textarea', 'pin: rename this');
    await page.click('.branch-diff-anchor-thread .branch-comment-send');
    await page.waitForFunction(() => {
      const t = document.querySelector('.branch-diff-anchor-thread');
      return t && /pin: rename this/.test(t.textContent);
    }, {timeout: 20000});
    assert(true, 'anchored comment renders inline under its diff row');
    const badgeCount = await page.evaluate(() => {
      const b = document.querySelector(
        '.branch-diff-comment-btn.has-comments .bd-comment-count');
      return b?.textContent;
    });
    assert(badgeCount === '1', '💬 carries the thread count: '
           + JSON.stringify(badgeCount));
    const inGeneral = await page.evaluate(() => {
      const list = document.querySelector('.branch-comments-list');
      return list ? /pin: rename this/.test(list.textContent) : null;
    });
    assert(inGeneral === false,
           'anchored comment does NOT duplicate into the general thread');

    // =================================================================
    // Phase D: suggestions section lists the proposed child + actions.
    // =================================================================
    await page.waitForFunction(() => {
      const m = document.querySelector('.branch-diff-suggestions');
      return m && m.querySelector('.branch-diff-suggestion-row, .branch-diff-suggestions-empty');
    }, {timeout: 20000});
    const sugg = await page.evaluate(() => {
      const row = document.querySelector('.branch-diff-suggestion-row');
      if (!row) return null;
      return {
        name: row.querySelector('.branch-diff-suggestion-name')?.textContent,
        buttons: Array.from(row.querySelectorAll('button')).map((b) => b.textContent),
      };
    });
    assert(sugg && sugg.name === SUGG,
           'suggestion row lists the proposed child branch: '
           + JSON.stringify(sugg));
    assert(sugg.buttons.includes('Δ view') && sugg.buttons.includes('⇢ apply'),
           'suggestion row carries Δ view + ⇢ apply');
    const newBtn = await page.evaluate(() =>
      !!document.querySelector('.branch-diff-suggest-new'));
    assert(newBtn, '"+ Suggest a change" affordance present');

    console.log('✓ compare mode / anchored comments / suggestions verified');
    await cleanup(page);
    await browser.close();
    process.exit(0);
  } catch (err) {
    console.error('✗ test failed:', err.message);
    try { await cleanup(page); } catch (_) {}
    await browser.close();
    process.exit(1);
  }
})();
