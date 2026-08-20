// Lessons 03, 05, 06 — slots/bindings, types, higher-order fns
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
  NS_NAME, FN_NAME, hardCleanup, waitTourTitle, clickTourButton,
  filterAndSelect, extendViaRowActions, bindFirstPlaceholder,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, tourTitle,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  // Lesson 05's "remove this binding" step fires a native confirm().
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-structure — lessons 03 / 05 / 06');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- Lesson 05 — types (mismatch explainer + diagnostic) ----------
    await page.goto(BASE + '/?tutorial=05');
    await waitTourTitle(page, 'Types are fn-rows too', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 05 Next');
    await waitTourTitle(page, 'Find str-len');
    await filterAndSelect(page, 'str-len', 'str-len');
    await waitTourTitle(page, 'Read the chips', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 05 chips Next');
    await waitTourTitle(page, 'Extend it');
    await extendViaRowActions(page, 'tutorial-typed', 'str-len');
    // Selection gate — the "+" must be the CHILD's (see lesson 02's note).
    await waitTourTitle(page, 'Ask for a fn the slot cannot take', 150000);
    await pickIncompatFnRef(page, 'str-len');
    await waitTourTitle(page, 'The server explains the mismatch', 150000);
    await pickAnyway(page);
    await waitTourTitle(page, 'A diagnostic, not a wall', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 05 diagnostic Next');
    await waitTourTitle(page, 'Clear it');
    await removeUseSiteBinding(page, 'str-len');
    await waitTourTitle(page, "That's the type system", 150000);
    await finishAndDelete(page);
    console.log('  lesson 05: walked + cleaned');

    // ---------- Lesson 03 — slots and bindings (two children, one slot) ----
    await page.goto(BASE + '/?tutorial=03');
    await waitTourTitle(page, 'One slot, many bindings', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 03 Next');
    await waitTourTitle(page, 'Find str-upper');
    await filterAndSelect(page, 'str-upper', 'str-upper');
    await waitTourTitle(page, 'Make the first child', 150000);
    await extendViaRowActions(page, 'tutorial-a', 'str-upper');
    await waitTourTitle(page, 'Bind the inherited slot', 150000);
    await bindFirstPlaceholder(page, 'alpha');
    await waitTourTitle(page, 'Back to the parent', 150000);
    await filterAndSelect(page, 'str-upper', 'str-upper');
    await waitTourTitle(page, 'Make a second child', 150000);
    await extendViaRowActions(page, 'tutorial-b', 'str-upper');
    await waitTourTitle(page, 'Give it a different value', 150000);
    await bindFirstPlaceholder(page, 'beta');
    await waitTourTitle(page, 'One slot, two values', 150000);
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
    assert(twoChildren.a.length === 1 && twoChildren.b.length === 1,
      'each child carries exactly one binding');
    assert(twoChildren.a[0].slot === twoChildren.b[0].slot,
      'both bindings point at the SAME inherited slot id');
    assert(twoChildren.a[0].value === 'alpha' && twoChildren.b[0].value === 'beta',
      'the two children hold independent values');
    await finishAndDelete(page);
    console.log('  lesson 03: walked + cleaned');

    // ---------- Lesson 06 — higher-order functions ----------
    await page.goto(BASE + '/?tutorial=06');
    await waitTourTitle(page, 'A slot that wants a function', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 06 Next');
    await waitTourTitle(page, 'Find map');
    await filterAndSelect(page, 'map', 'map');
    await waitTourTitle(page, 'Read the two slots', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 06 slots Next');
    await waitTourTitle(page, 'Extend it');
    await extendViaRowActions(page, 'tutorial-map', 'map');
    await waitTourTitle(page, 'A callable slot offers no literal', 150000);
    // Clicking the callable slot's "+" goes straight to the fn picker —
    // no value form in between. That IS the lesson's claim.
    await page.waitForSelector('.placeholder-binder', {timeout: 30000});
    await page.evaluate(() => {
      const binders = Array.from(document.querySelectorAll('.placeholder-binder'));
      binders[0].click();
    });
    await waitTourTitle(page, 'Compatible means callable-shaped', 150000);
    const pickerState = await page.evaluate(() => {
      const pk = document.querySelector('.fn-picker-popover');
      return {
        expected: pk?.querySelector('.fn-picker-expected')?.textContent.trim(),
        valueForms: document.querySelectorAll('.arg-value-edit-popover').length
      };
    });
    assert(/item/.test(pickerState.expected || ''),
      'picker states the callable shape (got: ' + pickerState.expected + ')');
    assert(pickerState.valueForms === 0,
      'a callable slot offered no literal value form');
    await page.keyboard.press('Escape');
    await waitTourTitle(page, 'See one wired up', 150000);
    await filterAndSelect(page, 'stringify-map-keys', 'stringify-map-keys');
    await waitTourTitle(page, 'Run it', 150000);
    await runViaRowActions(page, '{"a": 1}');
    await waitTourTitle(page, "That's a HOF", 150000);
    await finishAndDelete(page);
    console.log('  lesson 06: walked + cleaned');

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
