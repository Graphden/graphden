// Lessons 13, 19, 20, 14 — effects, branches, review, tests (+ branch isolation)
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
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, tourTitle,
  waitUntil,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-ops — lessons 13 / 19 / 20 / 14 + branch isolation');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- lesson 20 — branches (fork, edit, come back) ----------
    await page.goto(BASE + '/?tutorial=20');
    await waitTourTitle(page, 'Branches are views, not copies', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 20 Next');
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
    assert(await clickTourButton(page, 'Next'), 'lesson 20 back-on-main Next');
    await waitTourTitle(page, 'Open the diff', 150000);
    // Δ on the tutorial-branch row → diff modal with the fn's changed row.
    await waitClickable(page, '#branch-chip-btn');
    await page.evaluate(() => document.getElementById('branch-chip-btn').click());
    await page.waitForSelector('.branch-row-diff[data-diff-source="tutorial-branch"]',
      {timeout: 15000});
    await page.evaluate(() => document.querySelector(
      '.branch-row-diff[data-diff-source="tutorial-branch"]').click());
    await page.waitForFunction(() => {
      const m = document.querySelector('.branch-diff-modal');
      // The loaded diff lists the modified BINDING row (by entity id) with a
      // difference count — the fn's NAME does not appear in the row.
      return m && !m.classList.contains('hidden')
        && !m.querySelector('.branch-diff-loading')
        && /difference/.test(m.textContent || '');
    }, null, {timeout: 60000, polling: 200});
    await waitTourTitle(page, 'Read it, then close it', 150000);
    // The lesson stops short of merging on purpose: a branch merged into
    // main becomes part of main's history (merge is by-reference) and can
    // no longer be deleted — cleanup would leave it behind forever.
    await page.evaluate(() => document.querySelector('.branch-diff-close').click());
    await waitTourTitle(page, "That's branching", 150000);
    await finishAndDelete(page);
    // The cleanup must have removed the lesson's BRANCH too, not just the fn.
    const branches = await api(page, 'GET', '/api/branches');
    const names = (Array.isArray(branches) ? branches : (branches.branches || []))
      .map((b) => b.name);
    assert(!names.includes('tutorial-branch'),
      'tour cleanup deleted the lesson branch');
    console.log('  lesson 20: walked + cleaned (branch too, diff opened)');

    // ---------- lesson 21 — review: refusal → propose → approve → land ----------
    await page.goto(BASE + '/?tutorial=21');
    await waitTourTitle(page, "Review is the target's policy", 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 21 Next');
    await waitTourTitle(page, 'Something to change');
    await filterAndSelect(page, 'const', 'const');
    await extendViaRowActions(page, 'review-demo', 'const');
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
    assert(await clickTourButton(page, 'Next'), 'lesson 21 ready-to-land Next');
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
    console.log('  lesson 21: walked + cleaned (refused → proposed → approved; branches cleaned)');

    // ---------- Lesson 13 — effects (chip, ack gate, run) ----------
    await page.goto(BASE + '/?tutorial=13');
    await waitTourTitle(page, 'Effects are declared, then they spread', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 13 Next');
    await waitTourTitle(page, 'Find env');
    await filterAndSelect(page, 'env', 'env');
    await waitTourTitle(page, 'Read the effect chip', 150000);
    // The step's title lands as soon as the fn is SELECTED; the card (and
    // its effects strip) paints a beat later.
    await page.waitForSelector('.effects-chip', {timeout: 60000});
    const effChip = await page.evaluate(
      () => document.querySelector('.effects-chip')?.className);
    assert(/effects-chip-env/.test(effChip || ''),
      'the env card carries an :env effect chip (got: ' + effChip + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 13 chip Next');
    await waitTourTitle(page, 'Open Run');
    // runWithEffectAck asserts the disabled-until-acknowledged gate itself.
    await runWithEffectAck(page, 'PATH');
    await waitTourTitle(page, 'Two gates, one vocabulary', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 13 gates Next');
    await waitTourTitle(page, 'Secrets ride the same rails');
    assert(await clickTourButton(page, 'Finish'), 'lesson 13 Finish');
    // Nothing was created — the tour closes without a cleanup dialog.
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 30000, polling: 200});
    console.log('  lesson 13: walked (no leftovers to clean)');

    // ---------- Lesson 14 — tests (ns tests → assert-eq → green dot) -------
    await page.goto(BASE + '/?tutorial=14');
    await waitTourTitle(page, 'A test is just a fn', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 14 Next');
    await waitTourTitle(page, 'Create the tests namespace');
    await createRootNamespace(page, 'tests');
    await waitTourTitle(page, 'Add the test fn', 150000);
    await createFnInNamespace(page, 'tests', 'two-plus-two');
    await waitTourTitle(page, 'Make it an assertion', 150000);
    await setParentViaStrip(page, 'assert-eq');
    await waitTourTitle(page, 'Bind one side', 150000);
    // assert-eq exposes exactly two slots — wait for BOTH placeholders to
    // paint before touching either. (The step's title lands on selection,
    // which is earlier than the card.)
    await page.waitForFunction(
      () => document.querySelectorAll('.placeholder-binder').length === 2,
      null, {timeout: 60000, polling: 150});
    await bindFirstPlaceholder(page, '4');
    await waitTourTitle(page, 'Bind the other', 150000);
    // The card repaints asynchronously after the first bind. Clicking
    // before it does hits the SAME (now bound) placeholder, and the write
    // collides on `(fn-id, slot-id)` — a genuine 409 that reads as a broken
    // lesson. Wait for exactly one placeholder to remain.
    await page.waitForFunction(
      () => document.querySelectorAll('.placeholder-binder').length === 1,
      null, {timeout: 60000, polling: 150});
    await bindFirstPlaceholder(page, '4');
    // The write itself triggers the auto-run — that IS the lesson's claim.
    await waitTourTitle(page, 'See it pass', 150000);
    // The step completes on the panel's green dot, so open the panel the
    // way the lesson tells the reader to: the diagnostics bar's Tests tab
    // (the drawer under the canvas). Matching a button whose text is
    // "tests" hit the sidebar's NAMESPACE row instead, and the step passed
    // anyway only because the mounted-but-hidden panel still matched a
    // `dom` check — until `dom` started meaning VISIBLE, which is what the
    // reader experiences.
    await page.waitForSelector('#gd-diag-nav button[data-section="tests"]',
                               {timeout: 30000});
    const testsPanelUp = () => page.evaluate(() => {
      const drawer = document.getElementById('gd-diag-drawer');
      if (!drawer || drawer.getAttribute('data-open') !== '1') return false;
      const el = document.querySelector('#gd-diag-panels > [data-section="tests"]');
      if (!el || el.hasAttribute('hidden')) return false;
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    });
    for (let attempt = 0; attempt < 10 && !(await testsPanelUp()); attempt++) {
      await page.evaluate(() => {
        document.querySelector('#gd-diag-nav button[data-section="tests"]')?.click();
      });
      await waitUntil(page, () => {
        const drawer = document.getElementById('gd-diag-drawer');
        if (!drawer || drawer.getAttribute('data-open') !== '1') return false;
        const el = document.querySelector('#gd-diag-panels > [data-section="tests"]');
        if (!el || el.hasAttribute('hidden')) return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      }, null, 1000);
    }
    assert(await testsPanelUp(), 'diagnostics drawer → Tests opened');
    // Auto-run is asynchronous (the write returns first) — poll rather than
    // read once.
    // `pending` is a status too — poll until the run REACHES a terminal
    // one, or the assert below reads a test that is still executing.
    let testRow = null;
    for (let i = 0; i < 45; i++) {
      const statuses = await api(page, 'GET', '/api/tests/status');
      testRow = (Array.isArray(statuses) ? statuses : []).find(
        (t) => t['fn-name'] === 'two-plus-two');
      if (testRow && /^(succeeded|failed)$/.test(testRow.status || '')) break;
      await new Promise((r) => setTimeout(r, 2000));
    }
    assert(testRow && testRow.status === 'succeeded',
      'the test auto-ran and passed (got: ' + JSON.stringify(testRow) + ')');
    await waitTourTitle(page, 'Tests are graph, too', 150000);
    await finishAndDelete(page);
    console.log('  lesson 14: walked + cleaned');

    // ---------- Branch isolation × LESSON STEPS ----------
    // The intersection the reparent bug and the lesson-08 breakage both
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
    await hardCleanup(page);
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
    await setParentViaStrip(page, 'const');
    await waitTourTitle(page, 'Bind :value', 150000);
    await bindFirstPlaceholder(page, '{"status": 200, "body": "Hello!"}');
    await waitTourTitle(page, 'Run it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, "That's the whole loop", 150000);
    console.log('  branch isolation: all lesson-01 steps walked on the branch');

    // Finish → the branch-rollback dialog (not the per-item cleanup).
    assert(await clickTourButton(page, 'Finish'), 'Finish button');
    await waitTourTitle(page, 'Delete the tutorial branch?');
    assert(await clickTourButton(page, 'Delete branch & return'),
      'Delete branch & return button');
    await page.waitForFunction(() => !/[?&]branch=/.test(location.search),
      null, {timeout: 120000, polling: 300});

    // Rollback is COMPLETE: no tutorial-* branch survives (the lesson-08
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

    // ---------- picker: a lesson this session cannot run is LOCKED --------
    // The org lessons drive panels that need `manage-users` / `manage-grants`.
    // On this stack (no tenancy addon) the capability probe is absent, so the
    // picker must list them disabled with the reason on the row — a catalogue
    // entry, not a dead end.
    await page.goto(BASE + '/');
    await page.waitForFunction(() => typeof window.openTutorialMenu === 'function',
      null, {timeout: 60000, polling: 200});
    await page.evaluate(() => window.openTutorialMenu());
    await page.waitForSelector('.gd-tour-lesson-list', {timeout: 20000});
    const locked = await page.evaluate(() => {
      const list = document.querySelector('.gd-tour-lesson-list');
      const row = Array.from(list.children).find((c) => /^24 ·/.test(c.textContent.trim()));
      return row ? {text: row.textContent.trim(), disabled: row.disabled === true,
                    chapter: !!Array.from(list.children).find(
                      (c) => c.className.includes('gd-tour-chapter')
                          && c.textContent.trim() === 'Your organization')}
                 : null;
    });
    assert(locked, 'lesson 24 is listed in the picker');
    assert(locked.chapter, 'its chapter heading is rendered');
    assert(locked.disabled, 'it is disabled where the capability is missing');
    assert(/needs manage-users/.test(locked.text),
      'the row says what it needs (got: ' + locked.text + ')');
    // A `:requires` value can also be a named CONDITION rather than a
    // capability. On this stack there is no tenancy addon, so the cross-org
    // lesson must read as needing an organization — and say so in those
    // words, not as a capability name.
    const orgLocked = await page.evaluate(() => {
      const list = document.querySelector('.gd-tour-lesson-list');
      const row = Array.from(list.children).find((c) => /^30 ·/.test(c.textContent.trim()));
      return row ? {text: row.textContent.trim(), disabled: row.disabled === true} : null;
    });
    assert(orgLocked, 'lesson 30 is listed');
    assert(orgLocked.disabled, 'it is disabled without organizations');
    assert(/needs an organization/.test(orgLocked.text),
      'the row names the CONDITION, not a capability (got: ' + orgLocked.text + ')');
    // The mirror case: a condition that HOLDS here. This stack is
    // single-tenant, so the Assets panel exists and lesson 22 must be
    // OFFERED, not locked — an inverted `assets` signal would hide the
    // lesson from the only sessions that can run it.
    const assetsLesson = await page.evaluate(() => {
      const list = document.querySelector('.gd-tour-lesson-list');
      const row = Array.from(list.children).find((c) => /^22 ·/.test(c.textContent.trim()));
      return row ? {text: row.textContent.trim(), disabled: row.disabled === true} : null;
    });
    assert(assetsLesson, 'lesson 22 is listed');
    assert(!assetsLesson.disabled,
      'lesson 22 is offered on a single-tenant stack (got: ' + assetsLesson.text + ')');
    console.log('  picker: org lessons locked, the assets lesson offered');

    // ---------- The catalogue as a catalogue ----------
    // Twenty-five lessons in five chapters is taller than a laptop viewport.
    // Three affordances make it usable — resume, filter, done marks — and each
    // has already broken once in a way no lesson walk would notice: the panel
    // ran off the bottom with its Cancel unreachable (a flex column that
    // squeezed its rows instead of scrolling), and opened mid-lesson it kept
    // the previous step's anchored position.
    const shape = await page.evaluate(async () => {
      localStorage.setItem('graphden.tour.done', JSON.stringify(['01']));
      localStorage.setItem('graphden.tour',
        JSON.stringify({lessonId: '03', step: 2, created: []}));
      location.reload();
    });
    await page.waitForFunction(() => typeof window.openTutorialMenu === 'function',
      null, {timeout: 60000});
    await page.evaluate(() => window.openTutorialMenu());
    await page.waitForSelector('.gd-tour-lesson-list', {timeout: 30000});
    const cat = await page.evaluate(() => {
      const pop = document.querySelector('#gd-tour-pop');
      const list = document.querySelector('.gd-tour-lesson-list');
      const foot = document.querySelector('.gd-tour-picker-foot');
      const r = pop.getBoundingClientRect();
      const fr = foot.getBoundingClientRect();
      const filter = document.querySelector('.gd-tour-filter');
      filter.value = 'branch';
      filter.dispatchEvent(new Event('input'));
      // Chapter headings are children too — count the lesson ROWS.
      const filtered = Array.from(list.children)
        .filter((c) => c.tagName === 'BUTTON')
        .map((c) => c.textContent.trim());
      filter.value = '';
      filter.dispatchEvent(new Event('input'));
      const done = Array.from(list.children)
        .find((c) => /^01 ·/.test(c.textContent.trim()));
      return {
        resume: document.querySelector('.gd-tour-btn-resume')?.textContent.trim() || null,
        fitsViewport: r.top >= 0 && r.bottom <= window.innerHeight,
        listScrolls: list.scrollHeight > list.clientHeight + 4,
        cancelReachable: fr.bottom <= window.innerHeight && fr.top >= 0,
        filtered,
        doneMarked: !!done && /done/.test(done.textContent),
        chapters: Array.from(document.querySelectorAll('.gd-tour-chapter'))
          .map((c) => c.textContent.trim()),
      };
    });
    assert(/^Continue 03 · Slots and bindings — step 3\//.test(cat.resume || ''),
      'a paused lesson resumes from where it stopped (got: ' + cat.resume + ')');
    assert(cat.fitsViewport, 'the catalogue fits the window');
    assert(cat.listScrolls, 'and the LIST scrolls rather than squeezing its rows');
    assert(cat.cancelReachable, 'Cancel stays reachable at any scroll position');
    assert(cat.filtered.length === 1 && /^20 · Branches/.test(cat.filtered[0]),
      'the filter narrows to one lesson (got: ' + JSON.stringify(cat.filtered) + ')');
    assert(cat.doneMarked, 'a finished lesson is marked done');
    assert(cat.chapters.length === new Set(cat.chapters).size,
      'each chapter heading appears once (got: ' + cat.chapters.join(', ') + ')');
    await page.evaluate(() => {
      localStorage.removeItem('graphden.tour.done');
      localStorage.removeItem('graphden.tour');
    });
    console.log('  picker: resume + filter + done marks, list scrolls, Cancel pinned');

    // ---------- Escape belongs to whatever is on TOP ----------
    // Every lesson tells the reader to open something — a picker, a panel, a
    // dialog — and Escape is how people close things. The tour also ends on
    // Escape, so "dismissed the panel" must not read as "quit the lesson".
    // It regressed once already: the Packages panel closes through the shared
    // popover helper and was missing from the tour's list of dismissible
    // surfaces, so closing it killed lesson 29 mid-walk.
    await page.goto(BASE + '/?tutorial=29');
    await waitTourTitle(page, 'Sharing more than one fn', 150000);
    await page.waitForSelector('#gd-pkg-chip', {timeout: 30000});
    await page.evaluate(() => document.getElementById('gd-pkg-chip').click());
    await page.waitForSelector('[data-packages-panel]', {timeout: 30000});
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('#gd-pkg-pop'),
      null, {timeout: 10000, polling: 200});
    assert(await tourTitle(page) === 'Sharing more than one fn',
      'the tour survives closing a panel with Escape (got: ' + await tourTitle(page) + ')');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 15000, polling: 200});
    console.log('  escape: closes the panel first, ends the tour only when alone');

    // ---------- On a phone the tour is a bottom sheet ----------
    // A 360px popover on a 390px screen lands on the Explorer, which is where
    // the early lessons tell the reader to click — lesson 01 dead-ended at
    // "click +" because + was underneath it. Checked here at BOTH sizes in
    // one process; the whole suite can also be run at a phone viewport with
    // GRAPHDEN_VIEWPORT=390x844.
    // Explicit sizes on BOTH sides: this file also runs under
    // GRAPHDEN_VIEWPORT=390x844, and "restore whatever we started at" then
    // asserts desktop behaviour at a phone size.
    const desktop = {width: 1400, height: 900};
    await page.setViewportSize({width: 390, height: 844});
    await page.goto(BASE + '/?tutorial=01');
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    const phone = await page.evaluate(() => {
      const pop = document.querySelector('#gd-tour-pop');
      const r = pop.getBoundingClientRect();
      const list = document.querySelector('#entity-list')?.getBoundingClientRect();
      return {
        sheet: pop.classList.contains('gd-tour-sheet'),
        fullWidth: Math.round(r.width) === window.innerWidth,
        atBottom: Math.abs(r.bottom - window.innerHeight) <= 1,
        leavesRoom: !!list && r.top > list.top,
        reservesScroll: getComputedStyle(document.querySelector('#side-menu'))
          .paddingBottom !== '0px',
      };
    });
    assert(phone.sheet && phone.fullWidth && phone.atBottom,
      'the step docks to the bottom edge, full width (got: ' + JSON.stringify(phone) + ')');
    assert(phone.leavesRoom, 'and leaves the panel the lesson points at visible');
    assert(phone.reservesScroll,
      'the Explorer reserves the sheet height so a row under it can scroll clear');
    await page.evaluate(() => window.openTutorialMenu());
    await page.waitForSelector('.gd-tour-lesson-list', {timeout: 15000});
    assert(await page.evaluate(() =>
      document.querySelector('#gd-tour-pop').classList.contains('gd-tour-sheet')),
      'the catalogue is a sheet too');
    await page.setViewportSize(desktop);
    await page.goto(BASE + '/?tutorial=01');
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    assert(!await page.evaluate(() =>
      document.querySelector('#gd-tour-pop').classList.contains('gd-tour-sheet')),
      'and a desktop window keeps the anchored popover');
    await page.evaluate(() => { localStorage.removeItem('graphden.tour'); });
    console.log('  mobile: step + catalogue dock as a sheet, desktop unchanged');

    // ---------- A lens the reader left on must not dead-end a step ----------
    // The Explorer's kind lens hides rows; a step waiting on a fn the lens
    // hides waits forever, under a popover that says "advances
    // automatically when done". Same trap the collapsed-Explorer arm
    // already handles, and only discoverable by walking it as a person —
    // a guard clicks rows by selector, hidden or not.
    await page.goto(BASE + '/');
    await page.waitForSelector('.kind-toggle', {timeout: 60000});
    await page.evaluate(() => {
      const tests = Array.from(document.querySelectorAll('.kind-toggle'))
        .find((c) => /tests/.test(c.textContent));
      tests.click();
    });
    await page.waitForFunction(() => {
      const all = Array.from(document.querySelectorAll('.kind-toggle'))
        .find((c) => /all/.test(c.textContent));
      return all && all.getAttribute('aria-pressed') === 'false';
    }, null, {timeout: 15000, polling: 200});
    await page.goto(BASE + '/?tutorial=05');
    await waitTourTitle(page, 'Types are fn-rows too', 150000);
    assert(await clickTourButton(page, 'Next'), 'lens probe: opening Next');
    await waitTourTitle(page, 'Find str-len', 30000);
    await page.fill('input[placeholder="Filter..."]', 'str-len');
    await page.waitForFunction(() => {
      const row = Array.from(document.querySelectorAll('#entity-list .entity-item'))
        .find((e) => e.querySelector('.name')?.textContent.trim() === 'str-len');
      return row && !row.hasAttribute('hidden');
    }, null, {timeout: 30000, polling: 300});
    const lensAfter = await page.evaluate(() => {
      const all = Array.from(document.querySelectorAll('.kind-toggle'))
        .find((c) => /all/.test(c.textContent));
      return all.getAttribute('aria-pressed');
    });
    assert(lensAfter === 'true',
      'the tour cleared the lens that was hiding the step\'s fn (all=' + lensAfter + ')');
    await page.evaluate(() => { localStorage.removeItem('graphden.tour'); });
    console.log('  lens: a kind lens hiding the step\'s fn is cleared, once');

    // ---------- The funnel actually moves ----------
    // 25 lessons and no way to know where a reader stops was the gap; the
    // tour posts three events per lesson into the counters `/metrics`
    // already exposes. A client that reports nothing looks exactly like a
    // tutorial nobody runs, so assert the number moves.
    const metricsBefore = await api(page, 'GET', '/metrics');
    const startedBefore = metricsBefore?.counters?.['tour-started-01'] || 0;
    await page.goto(BASE + '/?tutorial=01');
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    assert(await clickTourButton(page, 'Next'), 'funnel probe: first Next');
    // Both counter POSTs are fire-and-forget from the page; poll /metrics
    // until they land instead of betting 1.5s that they have.
    let metricsAfter = null;
    for (const deadline = Date.now() + 30000; Date.now() < deadline;) {
      metricsAfter = await api(page, 'GET', '/metrics');
      if ((metricsAfter?.counters?.['tour-started-01'] || 0) > startedBefore
          && (metricsAfter?.counters?.['tour-step-01-1'] || 0) >= 1) break;
      await new Promise((r) => setTimeout(r, 100));
    }
    const startedAfter = metricsAfter?.counters?.['tour-started-01'] || 0;
    const stepAfter = metricsAfter?.counters?.['tour-step-01-1'] || 0;
    assert(startedAfter === startedBefore + 1,
      'starting lesson 01 counted one start (' + startedBefore + ' → ' + startedAfter + ')');
    assert(stepAfter >= 1,
      'and the advance counted its step bucket (tour-step-01-1 = ' + stepAfter + ')');
    await page.evaluate(() => { localStorage.removeItem('graphden.tour'); });
    console.log('  funnel: started + step counters moved on /metrics');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour title at failure:', await tourTitle(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
