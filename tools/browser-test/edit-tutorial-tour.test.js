// Lessons 01, 02, 03 — the basic loop, reading a card, inheritance
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
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions, runFromOpenPane,
  appendSeqItemViaEdge,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, tourWhere, waitTourClosed,
  bindPlaceholderOn, setSealsViaBadge, settleTourRing,
  moveSeqItem, insertSeqLiteralBefore, addMiParentViaParentRow,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  // Lesson 08's "remove this binding" step fires a native confirm(); with no
  // handler Playwright auto-dismisses it and the step would never complete.
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour — lessons 01 / 02 / 03 walked end-to-end');
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
    // The last step hands over to the text: a link to the written lesson
    // and one line on what it adds beyond the steps just walked.
    const readOn = await page.evaluate(() => {
      const a = document.querySelector('#gd-tour-pop .gd-tour-read .gd-tour-read-link');
      return {
        href: a ? a.getAttribute('href') : null,
        target: a ? a.getAttribute('target') : null,
        adds: document.querySelector('#gd-tour-pop .gd-tour-read-adds')?.textContent || '',
      };
    });
    assert(/\/tutorial\/01-fn-defs$/.test(readOn.href || ''),
      'the last step links to the written lesson 01 (got: ' + readOn.href + ')');
    assert(readOn.target === '_blank', 'the text opens in its own tab — the tour stays');
    assert(/^In the text: /.test(readOn.adds) && readOn.adds.length > 30,
      'and says what the text adds (got: ' + readOn.adds + ')');

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

    // ---------- Lesson 02 — reading a card (look-only, platform fn) ----------
    // The lesson unfolds / folds ancestor rows on app.routes' health and its
    // handler card, and reads three descriptions. Nothing is created, so it
    // ends on the small "finished" card. The walk asserts what the reader
    // is told to see — the method edge appearing and folding away — not
    // merely that each step advanced.
    await page.goto(BASE + '/?tutorial=02');
    await waitTourTitle(page, 'Reading a card', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 02 Next');
    await waitTourTitle(page, 'Open a real route');
    await filterAndSelect(page, 'health', 'health');
    await waitTourTitle(page, 'What the fn is for', 150000);
    const inspDesc = await page.evaluate(() =>
      document.querySelector('.gd-insp-head .gd-insp-desc')?.textContent.trim() || '');
    assert(/^GET \/health/.test(inspDesc),
      'the Inspector head shows health\'s description (got: ' + inspDesc + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 02 inspector Next');
    await waitTourTitle(page, 'What the canvas drew');
    // The step is text only — its layout request may still be in flight
    // when the popover lands (~1 s under a loaded host); a reader looks
    // at the card once it is drawn, so wait for it (bounded, honest
    // worst case) before reading what it shows.
    await page.waitForFunction(() => document.querySelectorAll(
      '.node-overlay[data-fn-name="health"] .ancestor-line[data-level]').length > 0,
    null, {timeout: 30000}).catch(() => {});
    const drawn = await page.evaluate(() => ({
      rows: Array.from(document.querySelectorAll(
        '.node-overlay[data-fn-name="health"] .ancestor-line[data-level]'))
        .map((l) => l.textContent.replace(/⋯/g, '').trim()),
      handlerCard: !!document.querySelector('.node-overlay[data-fn-name="_health-handler"]'),
      edges: Array.from(document.querySelectorAll('.edge-label-overlay'))
        .map((e) => e.dataset.argName),
    }));
    assert(drawn.rows.join(',') === 'health,get-route,route,list',
      'the health card shows its four ancestry rows (got: ' + drawn.rows.join(',') + ')');
    assert(drawn.handlerCard, 'the handler ref is drawn as a (closed) card');
    assert(drawn.edges.includes('path') && drawn.edges.includes('handler')
      && !drawn.edges.includes('method'),
      'only what health binds itself is drawn — no method edge yet (got: '
      + drawn.edges.join(',') + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 02 canvas Next');
    await waitTourTitle(page, 'Unfold one row');
    await page.click('.node-overlay[data-fn-name="health"] .ancestor-line[data-level="1"]');
    await page.mouse.move(5, 5);
    await waitTourTitle(page, 'What a slot means', 150000);
    await page.waitForSelector('.edge-label-overlay[data-arg-name="method"] .description-badge',
      {timeout: 30000});
    const methodValue = await page.evaluate(() => Array.from(
      document.querySelectorAll('.node-overlay .arg-value-text'))
      .map((v) => v.textContent.trim()));
    assert(methodValue.some((v) => v === '"get"'),
      'unfolding get-route drew the inherited method value (got: '
      + methodValue.join(' | ') + ')');
    await page.hover('.edge-label-overlay[data-arg-name="method"] .description-badge');
    await waitTourTitle(page, 'Fold it back', 150000);
    const slotDesc = await page.evaluate(() =>
      document.querySelector('.description-tooltip')?.textContent || '');
    assert(/HTTP method literal/.test(slotDesc),
      'the slot\'s description is what the tooltip shows (got: ' + slotDesc.slice(0, 60) + ')');
    await page.click('.node-overlay[data-fn-name="health"] .ancestor-line[data-level="0"]');
    await page.mouse.move(5, 5);
    await waitTourTitle(page, 'Open a closed card', 150000);
    await page.waitForFunction(() => !document.querySelector(
      '.edge-label-overlay[data-arg-name="method"]'), null, {timeout: 30000, polling: 100});
    await page.click('.node-overlay[data-fn-name="_health-handler"] .ancestor-line[data-level="1"]');
    await page.mouse.move(5, 5);
    await waitTourTitle(page, 'A namespace has one too', 150000);
    await page.waitForSelector('.node-overlay[data-fn-name="_health-json-body"]', {timeout: 30000});
    await page.hover('.ns-header[data-ns-path="app.routes"]');
    await page.hover('.ns-header[data-ns-path="app.routes"] .description-badge');
    const nsDesc = await page.evaluate(() =>
      document.querySelector('.description-tooltip')?.textContent || '');
    assert(/Application routes/.test(nsDesc),
      'the namespace\'s description shows on its i (got: ' + nsDesc.slice(0, 60) + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 02 namespace Next');
    await waitTourTitle(page, 'You can read any card now', 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 02 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 02: walked (nothing created)');

    // ---------- Lesson 03 — parents & inheritance (extend flow) ----------
    await page.goto(BASE + '/?tutorial=03');
    await waitTourTitle(page, 'Inheritance, hands on', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 03 Next');
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
    assert(await clickTourButton(page, 'Next'), 'lesson 03 look-step Next');
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
    await waitTourTitle(page, 'Two parents at once', 150000);
    // The wrapper exists, parented to :to-str, with add-10 bound in.
    const wrapped = await page.evaluate(() => {
      const w = (graphData?.fns || []).find((f) => f.name === 'add-10-text');
      return w ? {parents: w['parent-ids']?.length, selected: location.hash} : null;
    });
    assert(wrapped && wrapped.parents === 1,
      'wrapper created and loaded (' + JSON.stringify(wrapped) + ')');
    // MI in the editor (2026-09-20): extend the status axis, then add the
    // content-type axis as a SECOND parent from the parent row's ⋯ — the
    // picker searches the whole graph, json-content-type is not loaded on
    // this canvas (the old client-side gate disabled the + for exactly that).
    await filterAndSelect(page, 'ok-response', 'ok-response');
    await waitTourTitle(page, 'Extend the status axis', 150000);
    await extendViaRowActions(page, 'tutorial-json-ok', 'ok-response');
    await waitTourTitle(page, 'Add the second parent', 150000);
    await addMiParentViaParentRow(page, 'tutorial-json-ok', 'json-content-type');
    await waitTourTitle(page, 'Read the diamond', 150000);
    const found = await api(page, 'GET', '/api/graph/entities?scope=search&q=tutorial-json-ok');
    const jsonOk = (found.fns || []).find((f) => f.name === 'tutorial-json-ok');
    const axes = await api(page, 'GET', '/api/graph/entities?scope=search&q=-response');
    const axisNames = (jsonOk?.['parent-ids'] || []).map((pid) =>
      ((axes.fns || []).concat(found.fns || [])).find((f) => f.id === pid)?.name
      || 'unknown').sort();
    assert(jsonOk && (jsonOk['parent-ids'] || []).length === 2,
      'tutorial-json-ok carries two parents (got: ' + JSON.stringify(jsonOk?.['parent-ids']) + ')');
    assert(axisNames.includes('ok-response'),
      'the status axis is still a parent (got: ' + JSON.stringify(axisNames) + ')');
    await settleTourRing(page, 10000);
    assert(await clickTourButton(page, 'Next'), 'lesson 03 diamond Next');
    await waitTourTitle(page, "That's inheritance — both ways", 150000);
    await finishAndDelete(page);
    console.log('  lesson 03: walked + cleaned (extend + wrap + MI)');

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
