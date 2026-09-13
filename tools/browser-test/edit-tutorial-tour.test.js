// Lessons 01, 02, 04 — the basic loop, inheritance, free args
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// Run from this directory:  node edit-tutorial-tour.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  NS_NAME, FN_NAME, hardCleanup, waitTourTitle, clickTourButton,
  filterAndSelect, extendViaRowActions, bindFirstPlaceholder,
  renameArgViaEdgeLabel,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions,
  appendSeqItemViaEdge,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, tourTitle,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  // Lesson 05's "remove this binding" step fires a native confirm(); with no
  // handler Playwright auto-dismisses it and the step would never complete.
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour — lesson 01 walked end-to-end');
  let failed = false;
  try {
    await hardCleanup(page); // a previous failed run must not pre-pass checks

    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    await page.goto(BASE + '/?tutorial=01');

    // Step 1 — welcome (manual).
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    console.log('  step 1: welcome shown');
    assert(await clickTourButton(page, 'Next'), 'welcome Next button');

    // Step 2 — create the namespace through the real sidebar flow.
    await waitTourTitle(page, 'Create a namespace');
    await page.waitForSelector('.create-root-ns-btn', {timeout: 10000});
    await page.click('.create-root-ns-btn');
    await page.waitForSelector('.inline-input', {timeout: 5000});
    await page.fill('.inline-input', NS_NAME);
    await page.press('.inline-input', 'Enter');
    await waitTourTitle(page, 'Add a function');
    console.log('  step 2: namespace created, tour advanced');

    // Step 3 — create the fn via the ns "+" menu. The inline row only
    // renders inside an EXPANDED namespace (the lesson text says so too),
    // so expand first.
    await page.evaluate((name) => {
      const headers = Array.from(document.querySelectorAll('.ns-header'));
      const target = headers.find(
        (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      if (!target) throw new Error('namespace row not found: ' + name);
      const arrow = target.querySelector('.ns-arrow');
      if (arrow && /▶/.test(arrow.textContent || '')) target.click();
    }, NS_NAME);
    await page.waitForFunction((name) => {
      const headers = Array.from(document.querySelectorAll('.ns-header'));
      const target = headers.find(
        (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      const arrow = target?.querySelector('.ns-arrow');
      return arrow && /▼/.test(arrow.textContent || '');
    }, NS_NAME, {timeout: 5000, polling: 100});
    await page.evaluate((name) => {
      const headers = Array.from(document.querySelectorAll('.ns-header'));
      const target = headers.find(
        (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      const plus = target.querySelector('.ns-plus-btn');
      if (!plus) throw new Error('ns-plus-btn not found');
      plus.click();
    }, NS_NAME);
    await page.waitForSelector('.create-menu', {timeout: 5000});
    await page.click('.create-menu-item[data-type="fn"]');
    await page.waitForSelector('.inline-input', {timeout: 5000});
    await page.fill('.inline-input', FN_NAME);
    await page.press('.inline-input', 'Enter');
    await waitTourTitle(page, 'Set the parent', 150000);
    console.log('  step 3: fn created, tour advanced');

    // Step 4 — assign :add through the reparent strip + fn picker.
    await setParentViaStrip(page, 'add');
    await waitTourTitle(page, 'The first number', 150000);
    console.log('  step 4: parent set, tour advanced');

    // Steps 5 + 6 — the two numbers: the placeholder `+` appends the first
    // item, the `+` at the chain's tail on the edge appends the second.
    await bindFirstPlaceholder(page, '1');
    await waitTourTitle(page, 'And the second', 150000);
    await appendSeqItemViaEdge(page, '1');
    await waitTourTitle(page, 'Run it', 150000);
    console.log('  steps 5-6: 1, 1 appended, tour advanced');

    // Step 6 — run the fn via ⋯ → ▶ → Run.
    await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    const ranOpen = await page.evaluate(() => {
      const runBtn = Array.from(
        document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '▶');
      if (!runBtn) return false;
      runBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
      return true;
    });
    assert(ranOpen, '▶ surfaced in row-actions popover');
    await page.waitForSelector('.execute-popover.visible .execute-run-btn',
      {timeout: 10000});
    await page.click('.execute-popover.visible .execute-run-btn');
    // The look-at-it beat: the result stays on screen until the reader
    // says Next — and it had better say 2.
    await waitTourTitle(page, 'Two', 150000);
    const two = await page.waitForFunction(() => {
      const t = document.querySelector('.execute-result-host')?.textContent || '';
      return /(^|\D)2(\D|$)/.test(t.replace(/Submitting…/, '')) ? t.trim().slice(0, 80) : false;
    }, null, {timeout: 60000, polling: 200}).then((h) => h.jsonValue());
    assert(two, 'the result pane shows 2 (got: ' + two + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 01 look-step Next');
    await waitTourTitle(page, "That's the whole loop", 150000);
    console.log('  steps 7-8: executed, looked at the 2, tour advanced');

    // Step 7 — finish → cleanup dialog → delete what the tour created.
    assert(await clickTourButton(page, 'Finish'), 'Finish button');
    await waitTourTitle(page, 'Clean up tutorial items?');
    assert(await clickTourButton(page, 'Delete them'), 'Delete them button');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 20000, polling: 200});
    console.log('  step 7: cleanup ran, tour closed');

    // The tour's own cleanup must have removed both rows.
    const tree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    assert(!(tree.namespaces || []).some((n) => n.name === NS_NAME),
      'tutorial namespace deleted by the tour cleanup');

    // ---------- Lesson 02 — parents & inheritance (extend flow) ----------
    await page.goto(BASE + '/?tutorial=02');
    await waitTourTitle(page, 'Inheritance, hands on', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 02 Next');
    await waitTourTitle(page, 'Find :add');
    await filterAndSelect(page, 'add', 'add');
    await waitTourTitle(page, 'Extend it');
    await extendViaRowActions(page, 'add-10', 'add');
    // The selection-gate step ("The editor opened add-10") auto-advances
    // once the editor re-selects the child — waiting for the NEXT title
    // therefore guarantees add-10 is selected, so the "+" click below
    // cannot land on :add's own placeholder (the 2026-08-20 poisoning).
    await waitTourTitle(page, 'Seed the inherited slot', 150000);
    await bindFirstPlaceholder(page, '10');
    await waitTourTitle(page, 'Run the child', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Ten', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 02 look-step Next');
    await waitTourTitle(page, 'Now wrap it', 150000);
    // Wrap: ⋯ on the add-10 card → ⬆ Wrap → pick :to-str as the parent.
    await page.waitForFunction(() => {
      return Array.from(document.querySelectorAll('.node-overlay')).some((ov) =>
        ov.textContent.trim().startsWith('add-10')
        && ov.querySelector('button.more-actions-trigger'));
    }, null, {timeout: 60000, polling: 200});
    await page.evaluate(() => {
      const ov = Array.from(document.querySelectorAll('.node-overlay')).find((o) =>
        o.textContent.trim().startsWith('add-10')
        && o.querySelector('button.more-actions-trigger'));
      ov.querySelector('button.more-actions-trigger')
        .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    });
    await page.waitForSelector('.row-actions-popover [data-action="wrap-fn"]',
      {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('.row-actions-popover [data-action="wrap-fn"]')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await page.waitForSelector('.fn-picker-popover .fn-picker-search', {timeout: 15000});
    await page.fill('.fn-picker-popover .fn-picker-search', 'to-str');
    await page.waitForSelector('.fn-picker-popover .fn-picker-row[data-fn-name$="to-str"]',
      {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('.fn-picker-popover .fn-picker-row[data-fn-name$="to-str"]')
        .click();
    });
    await waitTourTitle(page, 'Name the wrapper', 150000);
    await page.waitForSelector('.arg-value-edit-popover .extend-ns-select', {timeout: 15000});
    // Wait for the slot select to resolve its candidates (":value" of to-str).
    await page.waitForFunction(() => {
      const sels = document.querySelectorAll('.arg-value-edit-popover .extend-ns-select');
      const slotSel = sels[sels.length - 1];
      return slotSel && Array.from(slotSel.options).some((o) => /:value/.test(o.textContent));
    }, null, {timeout: 20000, polling: 200});
    await page.evaluate((name) => {
      const pop = document.querySelector('.arg-value-edit-popover');
      const input = pop.querySelector('.arg-value-edit-input');
      input.value = name;
      input.dispatchEvent(new Event('input', {bubbles: true}));
      Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
        .find((b) => b.textContent.trim() === 'Save').click();
    }, 'add-10-text');
    await waitTourTitle(page, "That's inheritance — both ways", 150000);
    // The wrapper exists, parented to :to-str, with add-10 bound in.
    const wrapped = await page.evaluate(() => {
      const w = (graphData?.fns || []).find((f) => f.name === 'add-10-text');
      return w ? {parents: w['parent-ids']?.length, selected: location.hash} : null;
    });
    assert(wrapped && wrapped.parents === 1,
      'wrapper created and loaded (' + JSON.stringify(wrapped) + ')');
    await finishAndDelete(page);
    console.log('  lesson 02: walked + cleaned (extend + wrap)');

    // ---------- Lesson 04 — free arguments ----------
    await page.goto(BASE + '/?tutorial=04');
    await waitTourTitle(page, 'Free args: the template mechanism', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 04 Next');
    await waitTourTitle(page, 'Find to-json-string');
    await filterAndSelect(page, 'to-json', 'to-json-string');
    await waitTourTitle(page, 'A free arg becomes a Run field');
    await runViaRowActions(page, '{"a": 1}');
    await waitTourTitle(page, 'A string came back', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 04 look-step Next');
    await waitTourTitle(page, 'Pin it in a child', 150000);
    await extendViaRowActions(page, 'tutorial-json', 'to-json-string');
    // Selection gate again — "Bind :data in the child" only appears once
    // tutorial-json is the selected fn, so the "+" is the child's.
    await waitTourTitle(page, 'Bind :data in the child', 150000);
    await bindFirstPlaceholder(page, '{"greeting": "hello"}');
    await waitTourTitle(page, 'Bound beats free', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 04 step-5 Next');
    // --- the rename arc ---
    await waitTourTitle(page, 'A free arg can also be RENAMED', 150000);
    await filterAndSelect(page, 'to-json', 'to-json-string');
    await waitTourTitle(page, 'A second child', 150000);
    await extendViaRowActions(page, 'tutorial-renamed', 'to-json-string');
    await waitTourTitle(page, 'tutorial-renamed is open', 150000);
    await waitTourTitle(page, 'Rename it', 150000);
    await renameArgViaEdgeLabel(page, 'data', 'payload');
    await waitTourTitle(page, 'The new name is the interface', 150000);
    // The result pane still holds the run from earlier in this lesson, so
    // this step is `manual` — a dom check on it would pass before the user
    // ran anything.
    await runViaRowActions(page, '{"a": 1}');
    assert(await clickTourButton(page, 'Next'), 'lesson 04 rename-run Next');
    await waitTourTitle(page, 'Templates, specialized', 150000);
    // The rename must be a VIEW over the same slot, so the value has to
    // arrive under the NEW name — a binding written on the view slot
    // instead of the declared one would look right and run empty.
    const renamedFound = await api(page, 'GET',
      '/api/graph/entities?scope=search&q=tutorial-renamed');
    const renamedFn = (renamedFound.fns || [])
      .find((f) => f.name === 'tutorial-renamed');
    assert(renamedFn, 'tutorial-renamed exists');
    const renamedRan = await api(page, 'POST', '/api/execute',
      {'fn-id': renamedFn.id, args: {payload: {a: 1}}});
    assert(renamedRan.result === '{"a":1}',
      'the value arrives under the new name (got: '
      + JSON.stringify(renamedRan.result) + ')');
    await finishAndDelete(page);
    console.log('  lesson 04: walked + cleaned');

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
