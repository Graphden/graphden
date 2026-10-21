// Lesson 17: graph-backed test verdicts in the interactive tutorial.
// Each suite owns its browser context and cleans tutorial fixtures.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect,
  extendViaRowActions, bindFirstPlaceholder, editBoundValue,
  appendSeqItemViaEdge, bindFnRefPlaceholder, createRootNamespace,
  createFnInNamespace, setParentViaStrip, finishAndDelete, tourWhere,
  waitUntil,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-tests — Lesson 17: graph-backed test verdicts in the interactive tutorial.');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- Lesson 17 — tests (2 + 2 → assert-eq → green → red → green) --
    await page.goto(BASE + '/?tutorial=17');
    await waitTourTitle(page, 'A test is just a fn', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 17 Next');
    // The fn under test first — a real 2 + 2, so the assertion compares
    // something computed rather than a literal with itself.
    await waitTourTitle(page, 'Something to test');
    await filterAndSelect(page, 'add', 'add');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-sum', 'add');
    await waitTourTitle(page, 'tutorial-sum is open', 150000);
    await waitTourTitle(page, 'Two…', 150000);
    await bindFirstPlaceholder(page, '2');
    await waitTourTitle(page, '…plus two', 150000);
    await appendSeqItemViaEdge(page, '2');
    // The filter still says `add` from the step before — and a filtered tree
    // hides the namespace row the next step clicks into. Clearing it is a
    // step of its own now, ringed on the ×; the check reads the input.
    await waitTourTitle(page, 'Clear the filter', 150000);
    await page.evaluate(() => document.querySelector('#search-clear')?.click());
    await page.waitForFunction(() => !(document.querySelector('input[placeholder="Filter..."]')?.value),
      null, {timeout: 15000, polling: 100});
    await waitTourTitle(page, 'Create the tests namespace', 150000);
    await createRootNamespace(page, 'tests');
    await waitTourTitle(page, 'Add the test fn', 150000);
    await createFnInNamespace(page, 'tests', 'two-plus-two');
    await waitTourTitle(page, 'Make it an assertion', 150000);
    await setParentViaStrip(page, 'assert-eq');
    await waitTourTitle(page, 'What it computes', 150000);
    // assert-eq exposes exactly two slots — wait for BOTH placeholders to
    // paint before touching either. (The step's title lands on selection,
    // which is earlier than the card.) The canvas decides which placeholder
    // is first, and the lesson's checks are order-independent: a fn-ref in
    // :expected and a 4 in :actual is the same passing test.
    await page.waitForFunction(
      () => document.querySelectorAll('.placeholder-binder').length === 2,
      null, {timeout: 60000, polling: 150});
    await bindFnRefPlaceholder(page, 'tutorial-sum');
    await waitTourTitle(page, 'What it should be', 150000);
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
    // The step completes on the Explorer's green dot under the ✓ tests
    // lens, so do what the lesson tells the reader to: click the chip.
    // (`dom` checks mean VISIBLE — the row must be on screen, which the
    // lens guarantees for the namespace the reader just built in.)
    await page.waitForSelector('#kind-filters .kind-toggle[data-kind="tests"]',
                               {timeout: 30000});
    const testsLensOn = () => page.evaluate(() =>
      document.querySelector('#kind-filters .kind-toggle[data-kind="tests"]')
        ?.getAttribute('aria-pressed') === 'true');
    for (let attempt = 0; attempt < 5 && !(await testsLensOn()); attempt++) {
      await page.evaluate(() => {
        document.querySelector('#kind-filters .kind-toggle[data-kind="tests"]')?.click();
      });
      await waitUntil(page, () =>
        document.querySelector('#kind-filters .kind-toggle[data-kind="tests"]')
          ?.getAttribute('aria-pressed') === 'true', null, 1000);
    }
    assert(await testsLensOn(), '✓ tests lens switched on');
    // Auto-run is asynchronous (the write returns first) — poll rather than
    // read once. `pending` is a status too — poll until the run REACHES a
    // terminal one.
    const testStatus = async (want) => {
      let row = null;
      for (let i = 0; i < 45; i++) {
        const statuses = await api(page, 'GET', '/api/tests/status');
        row = (Array.isArray(statuses) ? statuses : []).find(
          (t) => t['fn-name'] === 'two-plus-two');
        if (row && row.status === want) break;
        await new Promise((r) => setTimeout(r, 2000));
      }
      return row;
    };
    let testRow = await testStatus('succeeded');
    assert(testRow && testRow.status === 'succeeded',
      'the test auto-ran and passed (got: ' + JSON.stringify(testRow) + ')');
    // Break it: the bound 4 → 5. The dot goes grey (new version, not run),
    // then red — the tour's step completes on the red dot.
    await waitTourTitle(page, 'Break it', 150000);
    await editBoundValue(page, '5');
    testRow = await testStatus('failed');
    assert(testRow && testRow.status === 'failed',
      'the auto-run caught the lie (got: ' + JSON.stringify(testRow) + ')');
    await waitTourTitle(page, 'Fix it', 150000);
    await editBoundValue(page, '4');
    testRow = await testStatus('succeeded');
    assert(testRow && testRow.status === 'succeeded',
      'and the fix turned it green again (got: ' + JSON.stringify(testRow) + ')');
    await waitTourTitle(page, 'Tests are graph, too', 150000);
    await finishAndDelete(page);
    console.log('  lesson 17: walked + cleaned');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-tests-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-tests-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
