// Lessons 26 and 19 — version history, and the diagnostics bar's three panels.
//
// Both lessons are about surfaces that answer "what happened": the ⌛
// popover (every version row of a fn, across branches, with restore) and
// the Explorer's problem lenses + the Inspector (runs that failed, edits
// that don't type-check, definitions the graph already has). They were the last two shipped user surfaces with no lesson
// at all, so they get a walk like every other lesson: the steps are
// performed for real, and the tour's own checks decide whether each one
// counted.
//
// Own file rather than an addition to an existing one: the runner caps a
// file at five minutes, and lesson 26's history has to be BUILT (two edits
// before the popover shows anything) while 29 has to produce both a failed
// run and a type error.
//
// Run from this directory:  node edit-tutorial-tour-history.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, tourWhere, filterAndSelect,
  extendViaRowActions, openRowActionsFor, finishAndDelete, openOperateSection, setFnDescription,
  waitUntil,
} = require('./tutorial-tour-helpers');


let _lessonFnId = null;
async function setDescription(page, text) {
  _lessonFnId = await setFnDescription(page, 'tutorial-versioned', text);
}

async function openVersionHistory(page) {
  // The lesson fn's ⋯ — `const`'s history has rows enough to satisfy the
  // ">= 3 rows" check below for the wrong fn.
  await openRowActionsFor(page, 'tutorial-versioned', 30000);
  await page.waitForSelector('.row-actions-popover [data-action="fn-versions"]',
                             {timeout: 15000});
  await page.evaluate(() => {
    document.querySelector('.row-actions-popover [data-action="fn-versions"]')
      .dispatchEvent(new MouseEvent('click', {bubbles: true}));
  });
  await page.waitForFunction(
    () => document.querySelectorAll('.fn-versions-row').length > 0,
    null, {timeout: 30000, polling: 200});
}


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-history — lessons 26 / 19');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

    // ---------- lesson 26 — version history ----------
    await page.goto(BASE + '/?tutorial=26');
    await waitTourTitle(page, 'Every edit writes a row', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 26 opening Next');

    await waitTourTitle(page, 'Something to edit', 30000);
    await filterAndSelect(page, 'const', 'const');
    await waitTourTitle(page, 'Extend it', 150000);
    await page.evaluate(() => applyTheme(true));
    await openRowActionsFor(page, 'const', 30000);
    const menu = await page.evaluate(() => {
      const host = document.querySelector('.row-actions-popover');
      return {width: host.getBoundingClientRect().width};
    });
    assert(menu.width < 400, 'const menu stays compact: ' + JSON.stringify(menu));
    await page.keyboard.press('Escape');
    await extendViaRowActions(page, 'tutorial-versioned');
    await waitTourTitle(page, 'Give it a description', 150000);

    const parentTrigger = '.node-overlay[data-fn-name="tutorial-versioned"] '
      + '.ancestor-line[data-level="1"] button.more-actions-trigger';
    await page.waitForSelector(parentTrigger, {timeout: 30000});
    await page.evaluate((selector) => document.querySelector(selector)
      .dispatchEvent(new MouseEvent('mousedown', {bubbles: true})), parentTrigger);
    await page.waitForSelector('.row-actions-popover [data-action="add-mi-parent"]',
      {timeout: 15000});
    const inheritedMenu = await page.evaluate(() => {
      const host = document.querySelector('.row-actions-popover');
      const button = host.querySelector('[data-action="add-mi-parent"]');
      return {width: host.getBoundingClientRect().width,
        label: button.getAttribute('aria-label'),
        explanation: button.getAttribute('aria-description')};
    });
    assert(inheritedMenu.width < 400 && inheritedMenu.label === 'Add another parent'
      && /picker searches/.test(inheritedMenu.explanation),
      'inherited menu retains help without stretching: ' + JSON.stringify(inheritedMenu));
    await page.keyboard.press('Escape');

    assert(await page.evaluate(() => _tourStep().check.kind === 'fn-field'
      && !_tourCheckPasses(_tourStep().check)), 'first edit waits for the saved description');
    await setDescription(page, 'first draft');
    await waitTourTitle(page, 'And another', 30000);
    assert(await page.evaluate(() => !_tourCheckPasses(_tourStep().check)),
      'the first draft cannot complete the second edit');
    await setDescription(page, 'second draft');

    await waitTourTitle(page, 'Open the history', 30000);
    await openVersionHistory(page);
    await waitTourTitle(page, 'Same history in the Inspector', 60000);

    const before = await page.evaluate(() =>
      document.querySelectorAll('.fn-versions-row').length);
    assert(before >= 3,
      'three version rows: the create and two edits (got ' + before + ')');
    const rowText = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.fn-versions-row'))
        .map((r) => r.textContent.trim()).join(' | '));
    assert(/first draft/.test(rowText) && /second draft/.test(rowText),
      'both descriptions are in the timeline (got: ' + rowText.slice(0, 160) + ')');
    const currentRestore = await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('.fn-versions-row'))
        .find((item) => /second draft/.test(item.textContent));
      const button = row?.querySelector('.fn-versions-restore');
      return {disabled: button?.disabled, scope: button?.title,
        header: document.querySelector('.fn-versions-header')?.textContent};
    });
    assert(currentRestore.disabled && /argument bindings/i.test(currentRestore.scope)
      && /field versions/.test(currentRestore.header),
      'current fields cannot be restored again; scope is explicit: ' + JSON.stringify(currentRestore));

    // Restore through the Inspector's Versions tab. It uses the same partial
    // as the popover, so its controls must receive the same mount/action wiring.
    await page.keyboard.press('Escape');
    await page.click('#gd-insp-tab-history');
    await page.waitForSelector('#gd-insp-history .fn-versions-row', {timeout: 20000});
    await waitTourTitle(page, 'Restore the first draft', 60000);

    assert(await page.evaluate(() => _tourStep().check.kind === 'fn-field'
      && !_tourCheckPasses(_tourStep().check)), 'the second draft cannot complete restoration');

    // Restore the "first draft" row — the dialog handler accepts the confirm.
    await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('#gd-insp-history .fn-versions-row'))
        .find((r) => /first draft/.test(r.textContent) && !/second draft/.test(r.textContent));
      row.querySelector('.fn-versions-restore').click();
    });
    assert(await waitUntil(page, async (id) => {
      const r = await fetch('/api/graph/entities?scope=search&q=tutorial-versioned');
      const j = await r.json();
      return (j.fns || []).some((f) => f.id === id && f.description === 'first draft');
    }, _lessonFnId, 60000), 'restore put "first draft" back on the fn');

    await waitTourTitle(page, 'History is append-only', 30000);
    // Re-open the popover and let the NEW row land: the restore's write and
    // the popover's fetch are separate round trips, so a single read can
    // catch the pre-restore list and report "nothing was appended" about a
    // write that did happen.
    await openVersionHistory(page);
    let after = 0;
    for (let i = 0; i < 10; i++) {
      after = await page.evaluate(() =>
        document.querySelectorAll('.fn-versions-row').length);
      if (after > before) break;
      await page.keyboard.press('Escape').catch(() => {});
      // Same reason as `setDescription`'s settle above: reopening the panel
      // must not race Escape's handler.
      await page.waitForTimeout(1000);
      await openVersionHistory(page);
    }
    assert(after === before + 1,
      'the restore APPENDED a version rather than removing any ('
      + before + ' → ' + after + ')');

    assert(await clickTourButton(page, 'Next'), 'lesson 26 append-only Next');
    await waitTourTitle(page, 'What restore does not touch', 30000);
    assert(await clickTourButton(page, 'Next'), 'lesson 26 bindings Next');
    await waitTourTitle(page, "That's the timeline", 30000);
    await finishAndDelete(page);
    console.log('  lesson 26: walked — two edits, restore, and the extra row it wrote');

    // ---------- Lesson 19 — the three diagnostics panels ----------
    // A reader arrives from lesson 18 with a Recent trail; the ✕ lens must
    // read right under the chips, not under that trail.
    await page.goto(BASE + '/');
    await page.waitForFunction(() => typeof graphData !== 'undefined' && graphData
      && document.querySelector('#entity-list [role="treeitem"]'), null, {timeout: 90000});
    await filterAndSelect(page, 'str-join', 'str-join');
    await filterAndSelect(page, 'str-len', 'str-len');
    await page.goto(BASE + '/?tutorial=19');
    await waitTourTitle(page, 'Three kinds of wrong', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 19 opening Next');

    await waitTourTitle(page, 'Something that fails', 30000);
    await filterAndSelect(page, 'parse-json', 'parse-json');
    await waitTourTitle(page, 'Make it yours', 150000);
    await extendViaRowActions(page, 'tutorial-bad-json');
    await waitTourTitle(page, 'Break it on purpose', 150000);

    // Run it with a non-JSON string AND "Save to history" ticked — an
    // unticked run leaves no audit row, so the Errors panel would stay
    // empty and the lesson would be teaching something untrue.
    // Open the ⋯ of the CHILD's row, not "the first trigger on the card":
    // the card carries a row per fn in the inheritance chain, and running
    // the parent records the failure against `parse-json` — a package fn the
    // reader cannot edit, in a panel that is supposed to point at theirs.
    await page.waitForFunction(() => {
      return Array.from(document.querySelectorAll('.node-overlay-row, .node-overlay'))
        .some((r) => r.textContent.trim().startsWith('tutorial-bad-json')
                  && r.querySelector('button.more-actions-trigger'));
    }, null, {timeout: 60000, polling: 200});
    await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('.node-overlay-row, .node-overlay'))
        .find((r) => r.textContent.trim().startsWith('tutorial-bad-json')
                  && r.querySelector('button.more-actions-trigger'));
      row.querySelector('button.more-actions-trigger')
        .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    });
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '▶')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await page.waitForSelector('.execute-popover.visible .execute-run-btn', {timeout: 20000});
    // The free-arg form arrives from /api/value-form AFTER the popover — set
    // the field only once it exists, or the run goes out with `string` unset
    // and parse-json of nothing SUCCEEDS (nil in, nil out). A run that
    // succeeds teaches the opposite of this lesson.
    // …and target the `string` field BY SLOT. `parse-json` has two free
    // slots and `keywordize` (a checkbox) renders FIRST, so a bare
    // `[data-form-field]` sets the checkbox and leaves `string` empty —
    // which parse-json accepts, returning nil, succeeding, and teaching the
    // opposite of the lesson.
    await page.waitForSelector(
      '.execute-popover.visible [data-slot-name="string"] [data-form-field]',
      {timeout: 20000});
    await page.evaluate(() => {
      const pop = document.querySelector('.execute-popover.visible');
      const f = pop.querySelector('[data-slot-name="string"] [data-form-field]');
      f.value = 'not json at all';
      f.dispatchEvent(new Event('input', {bubbles: true}));
      f.dispatchEvent(new Event('change', {bubbles: true}));
      const persist = pop.querySelector('.execute-persist-checkbox');
      if (persist && !persist.checked) persist.click();
    });
    const runReady = await page.evaluate(() => {
      const pop = document.querySelector('.execute-popover.visible');
      return {
        value: pop.querySelector('[data-slot-name="string"] [data-form-field]')?.value,
        persisted: !!pop.querySelector('.execute-persist-checkbox')?.checked,
      };
    });
    assert(runReady.value === 'not json at all',
      'the arg field carries the malformed input (got: ' + runReady.value + ')');
    assert(runReady.persisted,
      '“Save to history” is ticked — an unticked failure never reaches Errors');
    // An UNTICKED run shows the same error pane but leaves no audit row — the
    // step must hold (a reader who missed the tick would otherwise reach the
    // ✕ lens with a chip that never moved). Untick, run, watch it hold; then
    // tick again for the real run.
    await page.evaluate(() => document.querySelector('.execute-popover.visible .execute-persist-checkbox').click());
    await page.click('.execute-popover.visible .execute-run-btn');
    await page.waitForSelector('.execute-popover.visible .execute-result-host .execute-error-pane', {timeout: 30000});
    await page.waitForTimeout(2500);
    const heldTitle = await page.evaluate(() => document.querySelector('#gd-tour-pop .gd-tour-title')?.textContent?.trim());
    assert(heldTitle === 'Break it on purpose',
      'an unticked failed run does not complete the run step (title: ' + heldTitle + ')');
    const chipBefore = await page.evaluate(() => document.querySelector('#kind-filters .kind-toggle[data-kind="failed"] .kind-count')?.textContent);
    await page.evaluate(() => document.querySelector('.execute-popover.visible .execute-persist-checkbox').click());
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, 'Read the message', 150000);
    // The failed chip re-reads after a persisted run — no reload, no lens
    // click needed.
    await waitUntil(page, (before) => {
      const t = document.querySelector('#kind-filters .kind-toggle[data-kind="failed"] .kind-count')?.textContent;
      return t && t !== before && parseInt(t, 10) === parseInt(before || '0', 10) + 1;
    }, chipBefore, 20000);
    const chipAfter = await page.evaluate(() => document.querySelector('#kind-filters .kind-toggle[data-kind="failed"] .kind-count')?.textContent);
    assert(parseInt(chipAfter, 10) === parseInt(chipBefore || '0', 10) + 1,
      'the ✕ failed chip counts the saved run (' + chipBefore + ' → ' + chipAfter + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 19 look-step Next');
    // The filter still says `parse-json` — clearing it is a step of its own,
    // ringed on the ×, so the failed lens is read over the whole tree.
    await waitTourTitle(page, 'Clear the filter', 150000);
    await page.evaluate(() => document.querySelector('#search-clear')?.click());
    await waitTourTitle(page, 'Find it in the tree', 150000);
    // The failed lens — the chip toggles the focus; the tour's check is the
    // pressed state.
    await page.evaluate(() => toggleKind('failed'));
    await waitTourTitle(page, 'Read the failure', 60000);
    // Under the lens: the row carries ✕1, Dismiss all is offered, and the
    // Recent trail (seeded above) steps aside so the match list sits right
    // under the chips.
    const lensView = await page.evaluate(() => ({
      ackAllHidden: document.getElementById('failed-ack-all-btn')?.hidden,
      ackAllText: document.getElementById('failed-ack-all-btn')?.textContent?.trim(),
      recentHidden: document.getElementById('gd-recent-fns')?.hidden,
      rowMarked: Array.from(document.querySelectorAll('#entity-list [role="treeitem"]'))
        .some((r) => /tutorial-bad-json/.test(r.textContent) && /✕\s*1/.test(r.textContent)),
    }));
    assert(lensView.ackAllHidden === false, 'Dismiss all is offered under the ✕ lens');
    // textContent joins the glyph and label spans without the CSS gap.
    assert(/^✕\s*Dismiss all$/.test(lensView.ackAllText || ''),
      'Dismiss all reads without a stray glyph (got: ' + lensView.ackAllText + ')');
    assert(lensView.recentHidden === true, 'the Recent trail hides while the ✕ lens owns the tree');
    assert(lensView.rowMarked, 'the tree row carries ✕1 under the lens');
    // The Runs tab of the (still selected) fn lists the unresolved failure.
    // The cache behind the failed lens re-primes after the run; the tab's
    // partial reads storage directly, so it shows the row as soon as the
    // audit row landed — re-open until it does.
    await page.waitForSelector('[data-insp-tab="stats"]', {timeout: 15000});
    let failText = '';
    for (let i = 0; i < 10; i++) {
      await page.evaluate(() => document.querySelector('[data-insp-tab="stats"]').click());
      await waitUntil(page, () => /Malformed JSON/i.test(
        document.querySelector('#gd-insp-runs .execute-history-failures')?.textContent || ''),
      null, 2000);
      failText = await page.evaluate(() =>
        document.querySelector('#gd-insp-runs .execute-history-failures')?.textContent || '');
      if (/Malformed JSON/i.test(failText)) break;
      await page.evaluate(() => document.querySelector('[data-insp-tab="overview"]')?.click());
      await page.waitForTimeout(500);
    }
    assert(/Malformed JSON/i.test(failText),
      'the Runs tab lists the unresolved failure with its message (got: ' + failText.slice(0, 160) + ')');
    const dismissBtn = await page.evaluate(() => {
      const b = document.querySelector('#gd-insp-runs .execute-history-failure button.error-log-ack');
      return b ? { text: b.textContent.trim(), w: b.getBoundingClientRect().width } : null;
    });
    assert(dismissBtn && dismissBtn.text === '✕ Dismiss' && dismissBtn.w > 30,
      'the failure row carries a legible "✕ Dismiss" (got: ' + JSON.stringify(dismissBtn) + ')');
    await waitTourTitle(page, 'Now a static mistake', 60000);
    assert(await clickTourButton(page, 'Next'), 'lesson 19 static Next');
    await waitTourTitle(page, 'Focus on type errors', 30000);
    await page.evaluate(() => toggleKind('type-errors'));
    await waitTourTitle(page, 'And what already exists', 60000);
    await page.evaluate(() => toggleKind('lint'));
    await waitTourTitle(page, 'You get to disagree', 60000);
    assert(await clickTourButton(page, 'Next'), 'lesson 19 lint Next');
    await waitTourTitle(page, 'What each surface answers', 60000);
    await page.evaluate(() => toggleKind('all'));
    await finishAndDelete(page);
    console.log('  lesson 19: walked — a persisted failure under the ✕ lens + Runs tab, the ⚠ and ⚐ lenses');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-history-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-history-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
