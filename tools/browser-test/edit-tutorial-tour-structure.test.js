// Lessons 04, 08, 09, 10, 11 — slots/bindings, types, HOFs, components, escape hatch
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// Run from this directory:  node edit-tutorial-tour-structure.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  NS_NAME, FN_NAME, hardCleanup, waitTourTitle, settleTourRing, clickTourButton,
  filterAndSelect, extendViaRowActions, bindFirstPlaceholder,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions,
  bindNamedPlaceholder,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, tourTitle,
  bindOptionalArgChip, appendFnRefViaChip, createRecordType,
  clickTourAdvance, waitTourClosed, appendSeqItemViaEdge, bindSeqAnchorPlaceholder,
  deleteBoundValue, setLambdaParamsViaChip,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  // Lesson 08's "remove this binding" step fires a native confirm().
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-structure — lessons 04 / 08 / 09 / 10 / 11');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- Lesson 08 — types (mismatch explainer + diagnostic) ----------
    await page.goto(BASE + '/?tutorial=08');
    await waitTourTitle(page, 'Types are fn-rows too', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 08 Next');
    await waitTourTitle(page, 'Find str-len');
    await filterAndSelect(page, 'str-len', 'str-len');
    await waitTourTitle(page, 'Read the chips', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 08 chips Next');
    await waitTourTitle(page, 'Extend it');
    await extendViaRowActions(page, 'tutorial-typed', 'str-len');
    // Selection gate — the "+" must be the CHILD's (see lesson 03's note).
    await waitTourTitle(page, 'Ask for a fn the slot cannot take', 150000);
    await pickIncompatFnRef(page, 'str-len');
    await waitTourTitle(page, 'The server explains the mismatch', 150000);
    await pickAnyway(page);
    await waitTourTitle(page, 'A diagnostic, not a wall', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 08 diagnostic Next');
    await waitTourTitle(page, 'Clear it');
    await removeUseSiteBinding(page, 'str-len');
    // --- the "author your own type" arc ---
    // The filter still holds `str-len` from earlier in this lesson, and a
    // filtered tree hides every other row — including the namespace the
    // next steps need. Clearing it is a step of its own, ringed on the ×.
    await waitTourTitle(page, 'Clear the filter', 150000);
    await page.evaluate(() => document.querySelector('#search-clear')?.click());
    await waitTourTitle(page, 'Types are things you MAKE, too', 150000);
    // The filter is debounced; the next steps need the unfiltered tree back,
    // so wait for rows beyond the single filtered match to be visible again
    // rather than for 600ms.
    await page.waitForFunction(() => Array.from(
      document.querySelectorAll('#entity-list .entity-item'))
      .filter((e) => !e.hasAttribute('hidden')).length > 1,
    null, {timeout: 30000, polling: 100}).catch(() => {});
    await createRootNamespace(page, NS_NAME).catch(() => {});
    await waitTourTitle(page, 'New type…', 150000);
    await createRecordType(page, NS_NAME, 'tutorial-point',
                           [['x', 'int'], ['y', 'int']]);
    await waitTourTitle(page, 'A type is a fn row', 150000);
    await waitTourTitle(page, "That's the type system", 150000);
    // A type-row is a fn row: no impl, no parents, classified `record`.
    const typeFound = await api(page, 'GET',
      '/api/graph/entities?scope=search&q=tutorial-point');
    const typeFn = (typeFound.fns || []).find((f) => f.name === 'tutorial-point');
    assert(typeFn, 'tutorial-point exists');
    assert((typeFn['parent-ids'] || []).length === 0,
      'a type-row has no parents');
    assert(typeFn.role === 'record',
      'the server classifies it as a record (got: ' + typeFn.role + ')');
    await finishAndDelete(page);
    console.log('  lesson 08: walked + cleaned');

    // ---------- Lesson 04 — slots and bindings (two children, one slot) ----
    await page.goto(BASE + '/?tutorial=04');
    await waitTourTitle(page, 'One slot, many bindings', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 04 Next');
    await waitTourTitle(page, 'Find str-upper');
    await filterAndSelect(page, 'str-upper', 'str-upper');
    await waitTourTitle(page, 'Make the first child', 150000);
    await extendViaRowActions(page, 'tutorial-a', 'str-upper');
    await waitTourTitle(page, 'Bind the inherited slot', 150000);
    await bindFirstPlaceholder(page, 'alpha');
    await waitTourTitle(page, 'Run tutorial-a', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'ALPHA', 150000);
    assert(await page.waitForFunction(() => /ALPHA/.test(
      document.querySelector('.execute-result-host')?.textContent || ''), null,
      {timeout: 60000, polling: 200}).then(() => true, () => false),
      'tutorial-a ran and the pane shows ALPHA');
    assert(await clickTourButton(page, 'Next'), 'lesson 04 ALPHA Next');
    await waitTourTitle(page, 'Back to the parent', 150000);
    await filterAndSelect(page, 'str-upper', 'str-upper');
    await waitTourTitle(page, 'Make a second child', 150000);
    await extendViaRowActions(page, 'tutorial-b', 'str-upper');
    await waitTourTitle(page, 'Give it a different value', 150000);
    await bindFirstPlaceholder(page, 'beta');
    await waitTourTitle(page, 'Run tutorial-b', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'BETA', 150000);
    assert(await page.waitForFunction(() => /BETA/.test(
      document.querySelector('.execute-result-host')?.textContent || ''), null,
      {timeout: 60000, polling: 200}).then(() => true, () => false),
      'tutorial-b ran and the pane shows BETA');
    assert(await clickTourButton(page, 'Next'), 'lesson 04 BETA Next');
    // Change, then take away (2026-09-20): the literal's popover edits the
    // binding in place; its Delete drops the row and the + comes back.
    await waitTourTitle(page, 'Change it', 150000);
    await editBoundValue(page, 'gamma');
    await waitTourTitle(page, 'Take it away', 150000);
    await deleteBoundValue(page);
    await waitTourTitle(page, 'One slot, two values', 150000);
    assert(await page.waitForFunction(() => !!document.querySelector('.placeholder-binder'), null,
      {timeout: 60000, polling: 200}).then(() => true, () => false),
      'the + is back on tutorial-b\'s :string after Delete');
    // The point of the lesson, asserted over the API — NOT over `lookups`,
    // which only holds the subtree of the currently selected fn (tutorial-b
    // at this point, so tutorial-a's bindings simply aren't loaded).
    const bindingsOf = async (name) => {
      const found = await api(page, 'GET',
        '/api/graph/entities?scope=search&q=' + name);
      const fn = (found.fns || []).find((f) => f.name === name);
      if (!fn) return [];
      const sub = await api(page, 'GET',
        '/api/graph/entities?scope=subtree&root-id=' + fn.id);
      return (sub.bindings || [])
        .filter((b) => b['fn-id'] === fn.id)
        .map((b) => ({slot: b['slot-id'], value: b.value}));
    };
    const twoChildren = {a: await bindingsOf('tutorial-a'),
                         b: await bindingsOf('tutorial-b')};
    assert(twoChildren.a.length === 1 && twoChildren.b.length === 0,
      'tutorial-a keeps its one binding; tutorial-b\'s was deleted (got: '
      + JSON.stringify(twoChildren) + ')');
    assert(twoChildren.a[0].value === 'alpha',
      'tutorial-a still holds alpha — the delete on tutorial-b touched no one else');
    await finishAndDelete(page);
    console.log('  lesson 04: walked + cleaned');

    // ---------- Lesson 09 — higher-order functions ----------
    await page.goto(BASE + '/?tutorial=09');
    await waitTourTitle(page, 'A slot that wants a function', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 09 Next');
    await waitTourTitle(page, 'Find map');
    await filterAndSelect(page, 'map', 'map');
    await waitTourTitle(page, 'Read the two slots', 150000);
    // A reader reads the two chips before moving on — and the step's ring
    // sits on them, so the audit must see them (map's canvas is a big one;
    // under gate load the chips land after the title does).
    await page.waitForSelector('.arg-type-chip', {timeout: 60000});
    // …and let the ring settle on them: the audit records a ring only once
    // it has held a tick, and a Next pressed the instant the chips appear
    // reads as NEVER-RINGED.
    await settleTourRing(page, 10000);
    assert(await clickTourButton(page, 'Next'), 'lesson 09 slots Next');
    await waitTourTitle(page, 'Extend it');
    await extendViaRowActions(page, 'tutorial-map', 'map');
    // Narrowing (2026-09-14): the data goes in FIRST, so the reader can watch
    // :func's chip tighten from (item:'a) to (item:text) before the picker
    // ever opens — and the picker then says so too.
    await waitTourTitle(page, 'The data first', 150000);
    await bindSeqAnchorPlaceholder(page, 'graph');
    await waitTourTitle(page, 'And a second item', 150000);
    await appendSeqItemViaEdge(page, 'den');
    await waitTourTitle(page, 'Watch the other slot', 150000);
    assert(await page.waitForFunction(() => Array.from(document.querySelectorAll('.arg-type-chip'))
      .some((c) => /item:text/.test((c.textContent || '').replace(/\s+/g, ''))), null,
      {timeout: 60000, polling: 200}).then(() => true, () => false),
      ':func\'s chip reads (item:text) once :coll holds text');
    assert(await clickTourButton(page, 'Next'), 'lesson 09 chip-step Next');
    await waitTourTitle(page, 'A callable slot offers no literal', 150000);
    // Clicking the callable slot's "+" goes straight to the fn picker —
    // no value form in between. That IS the lesson's claim. :coll is bound,
    // so the one placeholder left is :func's.
    await page.waitForSelector('.placeholder-binder', {timeout: 30000});
    await page.evaluate(() => document.querySelector('.placeholder-binder').click());
    await waitTourTitle(page, 'Narrower already', 150000);
    const pickerState = await page.evaluate(() => {
      const pk = document.querySelector('.fn-picker-popover');
      return {
        expected: pk?.querySelector('.fn-picker-expected')?.textContent.trim(),
        valueForms: document.querySelectorAll('.arg-value-edit-popover').length
      };
    });
    assert(/item:text/.test((pickerState.expected || '').replace(/\s+/g, '')),
      'the picker already expects (item:text) — narrowed by the bound list (got: ' + pickerState.expected + ')');
    assert(pickerState.valueForms === 0,
      'a callable slot offered no literal value form');
    await page.fill('.fn-picker-search', 'str-upper');
    await page.waitForFunction(() => Array.from(document.querySelectorAll('.fn-picker-row-compat'))
      .some((r) => /(^|[./])str-upper$/.test(r.dataset.fnName || '')), null,
      {timeout: 30000, polling: 150});
    await page.evaluate(() => Array.from(document.querySelectorAll('.fn-picker-row-compat'))
      .find((r) => /(^|[./])str-upper$/.test(r.dataset.fnName || '')).click());
    await waitTourTitle(page, 'Run it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Once per item', 150000);
    assert(await page.waitForFunction(() => /GRAPH\W+DEN/.test(
      (document.querySelector('.execute-result-host')?.textContent || '').replace(/\s+/g, ' ')), null,
      {timeout: 60000, polling: 200}).then(() => true, () => false),
      'the pane shows ["GRAPH" "DEN"] — str-upper ran once per item');
    assert(await clickTourButton(page, 'Next'), 'lesson 09 look-step Next');
    // A callable of the reader's own + its λ declaration (2026-09-20).
    await waitTourTitle(page, 'A callable of your own', 150000);
    await filterAndSelect(page, 'str-upper', 'str-upper');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-upper', 'str-upper');
    await waitTourTitle(page, 'Say what a caller fills', 150000);
    await setLambdaParamsViaChip(page, 'tutorial-upper', ['string']);
    await waitTourTitle(page, "That's a HOF", 150000);
    const upperIdx = await api(page, 'GET', '/api/graph/entities?scope=search&q=tutorial-upper');
    const shout = (upperIdx.fns || []).find((f) => f.name === 'tutorial-upper');
    const shoutRow = shout
      ? ((await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + shout.id)).fns || [])
        .find((f) => f.id === shout.id)
      : null;
    assert(shoutRow && JSON.stringify(shoutRow['lambda-params']) === JSON.stringify(['string']),
      'the fn row carries lambda-params ["string"] (got: ' + JSON.stringify(shoutRow?.['lambda-params']) + ')');
    await finishAndDelete(page);
    console.log('  lesson 09: walked + cleaned (+ λ)');

    // ---------- Lesson 10 — components (free-arg chips + list append) ------
    await page.goto(BASE + '/?tutorial=10');
    await waitTourTitle(page, 'A page is a function', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 10 Next');
    await waitTourTitle(page, 'Find button');
    await filterAndSelect(page, 'button', 'button');
    await waitTourTitle(page, 'Make it yours', 150000);
    await extendViaRowActions(page, 'tutorial-button', 'button');
    // Selection gate — the chip must be the CHILD's.
    // Selection-gate steps carry a `selected` check — they advance on their
    // own once the child is open; there is no Next to click.
    await waitTourTitle(page, 'tutorial-button is open', 150000);
    await waitTourTitle(page, 'Give it a label', 150000);
    // Unified-arg-edges: a component's propagated inputs render as the
    // SAME placeholder edges any argument gets — `label` must be there,
    // as a binder on a placeholder edge. That IS the lesson's claim now.
    const labelEdge = await page.evaluate(() => {
      const e = window.graphView.edgeList().find(
        (x) => x.data?.argName === 'label' && x.data?.isUnset);
      return e ? {target: e.data.target,
                  binder: !!document.querySelector(
                    '.placeholder-binder[data-node-id="' + e.data.target + '"]')}
               : null;
    });
    assert(labelEdge && labelEdge.binder,
      'the propagated label input is a bindable placeholder edge');
    await bindOptionalArgChip(page, 'label', 'Run');
    await waitTourTitle(page, 'Run it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'A real button', 150000);
    // The preview is a real document with the components stylesheet: the
    // button inside the frame must carry the sheet's padding, not the
    // browser default — that is what the reader is told to look at.
    await page.waitForSelector('iframe.execute-result-component-preview', {timeout: 60000});
    const previewed = await page.evaluate(() => {
      const f = document.querySelector('iframe.execute-result-component-preview');
      return {link: /rel="stylesheet"/.test(f.getAttribute('srcdoc') || ''),
              button: /<button[^>]*>Run<\/button>/.test(f.getAttribute('srcdoc') || '')};
    });
    assert(previewed.link && previewed.button,
      'the preview frame is a styled document holding the button (got: ' + JSON.stringify(previewed) + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 10 button Next');
    await waitTourTitle(page, 'Style it', 150000);
    await bindOptionalArgChip(page, 'attrs', '{"class": "primary"}');
    await waitTourTitle(page, 'Run it again', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Now something to put it in', 150000);
    await filterAndSelect(page, 'card', 'card');
    await waitTourTitle(page, 'Extend the card too', 150000);
    await extendViaRowActions(page, 'tutorial-card', 'card');
    await waitTourTitle(page, 'tutorial-card is open', 150000);
    await waitTourTitle(page, 'Put your button inside', 150000);
    await appendFnRefViaChip(page, 'children', 'tutorial-button');
    await waitTourTitle(page, 'Run the card', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Nested — and still your button', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 10 card Next');
    await waitTourTitle(page, "That's a page, in pieces", 150000);
    // The composition itself, asserted over the API — the card's hiccup
    // must nest the button's.
    const cardFound = await api(page, 'GET',
      '/api/graph/entities?scope=search&q=tutorial-card');
    const cardFn = (cardFound.fns || []).find((f) => f.name === 'tutorial-card');
    assert(cardFn, 'tutorial-card exists');
    const ran = await api(page, 'POST', '/api/execute',
      {'fn-id': cardFn.id, args: {}});
    assert(JSON.stringify(ran.result) === '["div",{"class":"card"},["button",{"class":"primary"},"Run"]]',
      'card renders with the button nested inside (got: '
      + JSON.stringify(ran.result) + ')');
    await finishAndDelete(page);
    console.log('  lesson 10: walked + cleaned');

    // ---------- Lesson 11 — the escape hatch (code editor + rename) --------
    await page.goto(BASE + '/?tutorial=11');
    await waitTourTitle(page, 'When no component fits', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 11 Next');
    await waitTourTitle(page, 'Find wrap-custom-script');
    await filterAndSelect(page, 'custom-script', 'wrap-custom-script');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-script', 'wrap-custom-script');
    await waitTourTitle(page, 'tutorial-script is open', 150000);
    await waitTourTitle(page, 'Write some JavaScript', 150000);
    await bindOptionalArgChip(page, 'body', "document.title = 'Graphden';",
                              {code: true});
    await waitTourTitle(page, 'Run it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Source, not a preview', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 11 look-step Next');
    await waitTourTitle(page, 'Know what you gave up', 150000);
    // `?body` is a RENAME of the inherited `:content` slot, and a binding
    // must land on the declared slot — written on the rename view it shows
    // on the card and is invisible at run time. Assert the value actually
    // arrives.
    const scriptFound = await api(page, 'GET',
      '/api/graph/entities?scope=search&q=tutorial-script');
    const scriptFn = (scriptFound.fns || [])
      .find((f) => f.name === 'tutorial-script');
    assert(scriptFn, 'tutorial-script exists');
    const scriptRan = await api(page, 'POST', '/api/execute',
      {'fn-id': scriptFn.id, args: {}});
    assert(JSON.stringify(scriptRan.result)
             === '["script",{},"document.title = \'Graphden\';"]',
      'the JS reached the rendered tag (got: '
      + JSON.stringify(scriptRan.result) + ')');
    await finishAndDelete(page);
    console.log('  lesson 11: walked + cleaned');

    // ---------- Lesson 13 — recursion (a READING tour) ----------
    // The only lesson that asks the reader to READ a fn rather than build
    // one: `:fix` needs ~7 fn-defs, which is a written lesson, not twenty
    // steps of clicking. What the tour must prove is that the fn it points
    // at is really there and really recursive — a renamed step or a
    // re-parented `:branch-chain` would leave the lesson describing a graph
    // that no longer exists.
    await page.goto(BASE + '/?tutorial=13');
    await waitTourTitle(page, 'Loops, where cycles are forbidden', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 13 opening Next');
    await waitTourTitle(page, 'Find a real one', 30000);
    await filterAndSelect(page, 'branch-chain', 'branch-chain');
    await waitTourTitle(page, 'Its parent is :fix', 150000);

    const shape = await api(page, 'GET',
      '/api/graph/entities?scope=search&q=branch-chain');
    const chain = (shape.fns || []).find((f) => f.name === 'branch-chain');
    assert(chain, 'the lesson\'s example fn exists');
    const sub = await api(page, 'GET',
      '/api/graph/entities?scope=subtree&root-id=' + chain.id);
    const byId = new Map((sub.fns || []).map((f) => [f.id, f]));
    const parents = (chain['parent-ids'] || []).map((id) => byId.get(id)?.name);
    assert(parents.includes('fix'),
      'branch-chain still inherits :fix (parents: ' + parents.join(', ') + ')');
    const names = (sub.fns || []).map((f) => f.name);
    assert(names.includes('_branch-chain-step'),
      'its step fn is still in the closure');
    assert(names.includes('_branch-chain-recurse'),
      'and so is the arm that invokes :self — the recursion the lesson reads');

    for (let i = 0; i < 5; i++) {
      assert(await clickTourAdvance(page, 'Next'), 'lesson 13 Next #' + (i + 1));
    }
    await waitTourTitle(page, "That's recursion", 30000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 13 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 13: walked (reading tour — :fix, its step, its :self arm)');

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
