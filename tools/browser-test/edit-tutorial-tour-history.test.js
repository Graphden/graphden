// Lessons 22 and 16 — version history, and the diagnostics bar's three panels.
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
// file at five minutes, and lesson 23's history has to be BUILT (two edits
// before the popover shows anything) while 29 has to produce both a failed
// run and a type error.
//
// Run from this directory:  node edit-tutorial-tour-history.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, tourTitle, filterAndSelect,
  extendViaRowActions, finishAndDelete, openOperateSection, waitUntil,
} = require('./tutorial-tour-helpers');


// Set a fn's description through the ⋯ → i inline editor — the edit lesson
// 28 asks for twice, and the cheapest write that cuts a new `:fn-version`
// (a binding write would NOT: it versions the binding, not the fn).
async function setDescription(page, text) {
  // ⋯ → i TOGGLES a pinned description tooltip, and only a pinned one grows
  // the ✎ Edit button — so this is a sequence with state, not three
  // independent clicks. Drive it as one attempt and retry the whole thing:
  // a half-open tooltip left by the previous attempt is exactly what makes
  // the save fire against a null entity id ("Save failed — check that
  // you're signed in", on a session that is signed in).
  for (let attempt = 0; attempt < 3; attempt++) {
    // Reset: close whatever is open, unpin, drop the row-actions popover.
    await page.keyboard.press('Escape').catch(() => {});
    await page.evaluate(() => {
      document.querySelectorAll('.description-tooltip').forEach((el) => {
        el.style.display = 'none';
      });
      if (typeof window.descriptionTooltipSticky !== 'undefined') {
        window.descriptionTooltipSticky = false;
      }
    });
    // KEEP THE SLEEP. Escape is asynchronous in its EFFECT — the editor's
    // keydown handler unpins and closes on a later tick — and there is no
    // single observable that says "Escape has been processed": waiting for
    // the row-actions popover to be gone is not it (tried, two gate runs,
    // `setDescription` failed 5/5 both times). Until the editor exposes
    // that state, this is a settle by measurement, not by hope.
    await page.waitForTimeout(400);

    await page.waitForSelector('button.more-actions-trigger', {timeout: 30000});
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover [data-action="description"]',
                               {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('.row-actions-popover [data-action="description"]')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });

    const opened = await page.waitForFunction(
      () => Array.from(document.querySelectorAll('.description-tooltip-btn'))
        .some((b) => /Edit/.test(b.textContent)),
      null, {timeout: 10000, polling: 200}).then(() => true).catch(() => false);
    if (!opened) continue;

    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.description-tooltip-btn'))
        .find((b) => /Edit/.test(b.textContent)).click();
    });
    const editing = await page.waitForSelector('.description-tooltip-textarea',
                                               {timeout: 10000})
      .then(() => true).catch(() => false);
    if (!editing) continue;

    await page.evaluate((v) => {
      const ta = document.querySelector('.description-tooltip-textarea');
      ta.value = v;
      ta.dispatchEvent(new Event('input', {bubbles: true}));
      Array.from(document.querySelectorAll('.description-tooltip-btn'))
        .find((b) => b.textContent.trim() === 'Save').click();
    }, text);

    // Confirm against the SERVER, not `window.graphData`: the editor's graph
    // state is a script-scope binding the modules share, and what a page
    // evaluate sees under `window.graphData` is not reliably the same object
    // the description patch wrote into.
    const landed = await page.waitForFunction(async (v) => {
      const r = await fetch('/api/graph/entities?scope=search&q=tutorial-versioned');
      const j = await r.json();
      return (j.fns || []).some((f) => f.name === 'tutorial-versioned'
                                    && f.description === v);
    }, text, {timeout: 20000, polling: 500}).then(() => true).catch(() => false);
    if (landed) return;
    // Why it did not land. The editor puts the reason in the tooltip's
    // error line — a refusal the server explained, a missing id, a real
    // auth problem. Before this, three silent retries and a bare "did not
    // land" was all a failing gate reported, and diagnosing it meant
    // reproducing a load-dependent flake by hand.
    const why = await page.evaluate(() => {
      const err = document.querySelector('.description-tooltip-error');
      return {
        error: err && err.style.display !== 'none' ? err.textContent.trim() : null,
        editing: !!document.querySelector('.description-tooltip-textarea'),
        tooltipOpen: !!document.querySelector('.description-tooltip'),
      };
    }).catch(() => null);
    console.log('  attempt ' + (attempt + 1) + ' did not land: ' + JSON.stringify(why));
  }
  throw new Error('setDescription("' + text + '") did not land after 3 attempts');
}


async function openVersionHistory(page) {
  await page.waitForSelector('button.more-actions-trigger', {timeout: 30000});
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
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
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-history — lessons 22 / 16');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

    // ---------- lesson 23 — version history ----------
    await page.goto(BASE + '/?tutorial=23');
    await waitTourTitle(page, 'Every edit writes a row', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 23 opening Next');

    await waitTourTitle(page, 'Something to edit', 30000);
    await filterAndSelect(page, 'const', 'const');
    await extendViaRowActions(page, 'tutorial-versioned');
    await waitTourTitle(page, 'Give it a description', 150000);

    await setDescription(page, 'first draft');
    assert(await clickTourButton(page, 'Next'), 'lesson 23 first-edit Next');
    await waitTourTitle(page, 'And another', 30000);
    await setDescription(page, 'second draft');
    assert(await clickTourButton(page, 'Next'), 'lesson 23 second-edit Next');

    await waitTourTitle(page, 'Open the history', 30000);
    await openVersionHistory(page);
    await waitTourTitle(page, 'Restore the first draft', 60000);

    const before = await page.evaluate(() =>
      document.querySelectorAll('.fn-versions-row').length);
    assert(before >= 3,
      'three version rows: the create and two edits (got ' + before + ')');
    const rowText = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.fn-versions-row'))
        .map((r) => r.textContent.trim()).join(' | '));
    assert(/first draft/.test(rowText) && /second draft/.test(rowText),
      'both descriptions are in the timeline (got: ' + rowText.slice(0, 160) + ')');

    // Restore the "first draft" row — the dialog handler accepts the confirm.
    await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('.fn-versions-row'))
        .find((r) => /first draft/.test(r.textContent) && !/second draft/.test(r.textContent));
      row.querySelector('.fn-versions-restore').click();
    });
    await page.waitForFunction(async () => {
      const r = await fetch('/api/graph/entities?scope=search&q=tutorial-versioned');
      const j = await r.json();
      return (j.fns || []).some((f) => f.name === 'tutorial-versioned'
                                    && f.description === 'first draft');
    }, null, {timeout: 60000, polling: 500});
    assert(await clickTourButton(page, 'Next'), 'lesson 23 restored Next');

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

    assert(await clickTourButton(page, 'Next'), 'lesson 23 append-only Next');
    await waitTourTitle(page, 'What restore does not touch', 30000);
    assert(await clickTourButton(page, 'Next'), 'lesson 23 bindings Next');
    await waitTourTitle(page, "That's the timeline", 30000);
    await finishAndDelete(page);
    console.log('  lesson 23: walked — two edits, restore, and the extra row it wrote');

    // ---------- Lesson 16 — the three diagnostics panels ----------
    await page.goto(BASE + '/?tutorial=16');
    await waitTourTitle(page, 'Three kinds of wrong', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 16 opening Next');

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
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, 'Find it in the tree', 150000);
    // The failed lens — the chip toggles the focus; the tour's check is the
    // pressed state.
    await page.evaluate(() => toggleKind('failed'));
    await waitTourTitle(page, 'Read the failure', 60000);
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
    await waitTourTitle(page, 'Now a static mistake', 60000);
    assert(await clickTourButton(page, 'Next'), 'lesson 16 static Next');
    await waitTourTitle(page, 'Focus on type errors', 30000);
    await page.evaluate(() => toggleKind('type-errors'));
    await waitTourTitle(page, 'And what already exists', 60000);
    await page.evaluate(() => toggleKind('lint'));
    await waitTourTitle(page, 'You get to disagree', 60000);
    assert(await clickTourButton(page, 'Next'), 'lesson 16 lint Next');
    await waitTourTitle(page, 'What each surface answers', 60000);
    await page.evaluate(() => toggleKind('all'));
    await finishAndDelete(page);
    console.log('  lesson 16: walked — a persisted failure under the ✕ lens + Runs tab, the ⚠ and ⚐ lenses');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour title at failure:', await tourTitle(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-history-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-history-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
