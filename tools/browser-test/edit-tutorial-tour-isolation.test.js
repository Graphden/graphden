// Tutorial branch isolation, rollback, and lesson 02 handoff.
// Each suite owns its browser context and cleans tutorial fixtures.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  NS_NAME, FN_NAME, hardCleanup, waitTourTitle, clickTourButton,
  bindFirstPlaceholder, runViaRowActions, appendSeqItemViaEdge,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  tourTitle, tourWhere,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-isolation — Tutorial branch isolation, rollback, and lesson 02 handoff.');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- Branch isolation × LESSON STEPS ----------
    // The intersection the reparent bug and the lesson-11 breakage both
    // escaped through: branch-mode e2e used to be env-gated
    // (GRAPHDEN_TOUR_BRANCH_E2E=1) and covered only entry/exit, while
    // the step walks all ran in-place on main. The historic reason for
    // the gate — a deterministic 240s stall of the `?branch=` load on
    // THIS stack (5/5, twice) — no longer reproduces after the
    // stale-branch + reparent + branch-router fixes (re-measured
    // 2026-08-26 in the same testcontainers environment: the whole
    // create→switch→resume flow completes in ~10s). So this now runs
    // UNGATED and walks every lesson-01 step ON the tour branch, then
    // asserts the full rollback: branch gone, main untouched, no
    // tutorial-* leak.
    // This file owns a fresh page and starts on the clean main branch.
    await page.goto(BASE + '/');
    await page.waitForFunction(() => typeof window.startTutorialIsolated === 'function',
      null, {timeout: 60000, polling: 200});
    const isoT0 = Date.now();
    const startedIso = await page.evaluate(async () => {
      return await window.startTutorialIsolated('01');
    });
    assert(startedIso, 'startTutorialIsolated returned true');
    try {
      await page.waitForFunction(() => {
        return /[?&]branch=tutorial-01-/.test(location.search)
          && !!document.querySelector('#gd-tour-pop .gd-tour-title');
      }, null, {timeout: 120000, polling: 300});
    } catch (e) {
      // Evidence first, failure second.
      const diag = await page.evaluate(() => ({
        url: location.href,
        storedBranch: (() => {
          try { return localStorage.getItem('graphden.branch'); }
          catch (_) { return 'unreadable'; }
        })(),
        tourTitle: document.querySelector('#gd-tour-pop .gd-tour-title')
          ?.textContent.trim() || null,
      }));
      console.error('  branch-isolation diagnostics: ' + JSON.stringify(diag));
      throw e;
    }
    const isoBranch = await page.evaluate(() =>
      new URLSearchParams(location.search).get('branch'));
    console.log('  branch isolation: on ' + isoBranch
      + ' after ' + (Date.now() - isoT0) + 'ms');

    // The full lesson-01 walk, on the branch — the same real-UI steps
    // the in-place suite drives, incl. the set-parent REPARENT that the
    // 2026-08-26 cross-branch gate bug blocked.
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    assert(await clickTourButton(page, 'Next'), 'welcome Next');
    await waitTourTitle(page, 'Create a namespace');
    await createRootNamespace(page, NS_NAME);
    await waitTourTitle(page, 'Add a function', 150000);
    await createFnInNamespace(page, NS_NAME, FN_NAME);
    await waitTourTitle(page, 'Set the parent', 150000);
    await setParentViaStrip(page, 'add');
    await waitTourTitle(page, 'The first number', 150000);
    await bindFirstPlaceholder(page, '1');
    await waitTourTitle(page, 'And the second', 150000);
    await appendSeqItemViaEdge(page, '1');
    await waitTourTitle(page, 'Run it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Two', 150000);
    assert(await clickTourButton(page, 'Next'), 'branch isolation: look-step Next');
    await waitTourTitle(page, "That's the whole loop", 150000);
    console.log('  branch isolation: all lesson-01 steps walked on the branch');

    // Finish → the branch-rollback dialog (not the per-item cleanup).
    assert(await clickTourButton(page, 'Finish'), 'Finish button');
    await waitTourTitle(page, 'Delete the tutorial branch?');
    // "What now?" is a real question at the end of a lesson, and the dialog
    // that asks about the branch is where the reader is already looking.
    const nextUp = await page.evaluate(() => {
      const box = document.querySelector('.gd-tour-next');
      return box ? {
        text: box.textContent,
        buttons: Array.from(box.querySelectorAll('.gd-tour-btn'))
          .map((b) => b.textContent.trim()),
      } : null;
    });
    assert(nextUp, 'the rollback dialog offers what to read next');
    assert((nextUp.buttons || []).some((b) => /^Start 02 · /.test(b)),
      'naming the lesson that follows (got: '
      + JSON.stringify(nextUp && nextUp.buttons) + ')');
    assert(/Deletes this branch first/.test(nextUp.text),
      'and saying what continuing does to this one');
    assert(await clickTourButton(page, 'Delete branch & return'),
      'Delete branch & return button');
    await page.waitForFunction(() => !/[?&]branch=/.test(location.search),
      null, {timeout: 120000, polling: 300});

    // Rollback is COMPLETE: no tutorial-* branch survives (the lesson-11
    // leak class), and main never saw the lesson's rows.
    const branchesAfter = await api(page, 'GET', '/api/branches');
    const leakedTutorial = (branchesAfter.branches || [])
      .filter((b) => /^tutorial-/.test(b.name || ''));
    assert(leakedTutorial.length === 0,
      'no tutorial-* branch leaked (got: '
      + JSON.stringify(leakedTutorial.map((b) => b.name)) + ')');
    const mainTree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    assert(!(mainTree.namespaces || []).some((n) => n.name === NS_NAME),
      'main never saw the lesson namespace');
    console.log('  branch isolation: rolled back — branch deleted, main clean');

    // ---------- and straight into the next lesson --------------------------
    // Continuing from that dialog cannot be a function call: the rollback ends
    // in a branch switch, which RELOADS. The id is parked in
    // `graphden.tour.next` and picked up on the other side — this is that
    // other side, the half no unit test can reach (it needs a real load, a
    // real branch create and the boot hook). The parking half is asserted
    // above (the button) and in tools/runtime-test/tour-end.test.js.
    await page.evaluate(() => localStorage.setItem('graphden.tour.next', '02'));
    await page.goto(BASE + '/');
    await page.waitForFunction(() => /[?&]branch=tutorial-02-/.test(location.search)
      && !!document.querySelector('#gd-tour-pop .gd-tour-title'),
      null, {timeout: 120000, polling: 300});
    const handoff = await page.evaluate(() => ({
      branch: new URLSearchParams(location.search).get('branch'),
      step: document.querySelector('#gd-tour-pop .gd-tour-progress')
        ?.textContent.trim() || null,
      queued: localStorage.getItem('graphden.tour.next'),
    }));
    assert(handoff.queued === null,
      'the parked lesson is consumed, not left to re-fire on a later visit'
      + ' (got: ' + handoff.queued + ')');
    assert(/^Lesson 02 · step 1\//.test(handoff.step || ''),
      'and it opens at lesson 02\'s FIRST step (got: ' + handoff.step + ')');
    // Leave nothing behind: end the tour, drop the branch it made, and take
    // the browser OFF that branch before the next load — a page booted on a
    // deleted branch is the 2026-08-20 dead-editor 400, not a test failure
    // worth debugging twice.
    await page.keyboard.press('Escape');
    await page.evaluate(() => {
      localStorage.removeItem('graphden.tour');
      localStorage.removeItem('graphden.tour.done');
      localStorage.removeItem('graphden.branch');
    });
    // Off the branch FIRST, delete it SECOND. The other order raced: with the
    // page still standing on the branch, its next request after the DELETE
    // came back 400 (dead branch), `maybeRecoverFromDeletedBranch` answered
    // with its own reload to main, and that reload aborted this goto
    // (`net::ERR_ABORTED`) — a 1-in-2 flake that blamed nothing real.
    await page.goto(BASE + '/');
    await page.waitForSelector('#entity-list', {timeout: 20000}).catch(() => {});
    await api(page, 'DELETE', '/api/branches/' + handoff.branch);
    const leftOver = await api(page, 'GET', '/api/branches');
    assert(!(leftOver.branches || []).some((b) => /^tutorial-/.test(b.name || '')),
      'and the handoff leaks no branch either (got: '
      + JSON.stringify((leftOver.branches || []).map((b) => b.name)) + ')');
    console.log('  handoff: a parked lesson opens isolated on the next load, once');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-isolation-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-isolation-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
