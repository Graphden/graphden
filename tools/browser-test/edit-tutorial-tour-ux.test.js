// Lessons 12, 18, 17, 09, 15 — running fns, workspaces, the
// Explorer/Inspector view layer, in-graph state, and tracing a run.
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// Run from this directory:  node edit-tutorial-tour-ux.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect,
  runViaRowActions, tourTitle, extendViaRowActions, bindFirstPlaceholder,
  bindFnRefPlaceholder, finishAndDelete, runWithEffectAck, waitTourClosed,
  openRowActionsFor, bindNamedPlaceholder, bindPlaceholderOn, extendInPlace,
} = require('./tutorial-tour-helpers');


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-ux — lessons 12 / 17 / 18 / 09 / 15');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

    // ---------- Lesson 12 — executing a fn ----------
    await page.goto(BASE + '/?tutorial=12');
    await waitTourTitle(page, 'Running is part of editing', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 12 Next');
    await waitTourTitle(page, 'Find str-len');
    await filterAndSelect(page, 'str-len', 'str-len');
    await waitTourTitle(page, 'Free args become the form', 150000);
    await runViaRowActions(page, 'hello');
    await waitTourTitle(page, 'Five', 150000);
    assert(await page.waitForFunction(() => /(^|\D)5(\D|$)/.test(
      (document.querySelector('.execute-result-host')?.textContent || '').replace(/Submitting…/, '')), null,
      {timeout: 60000, polling: 200}).then(() => true, () => false),
      'the pane shows 5 for hello');
    assert(await clickTourButton(page, 'Next'), 'lesson 12 look-step Next');
    await waitTourTitle(page, 'Keep the interesting one', 150000);
    // The step now completes on a REAL persisted run (the
    // body[data-gd-persisted-run] marker) — do what the lesson says:
    // reopen Run, tick "Save to history", enter graphden, Run.
    await page.waitForSelector('button.more-actions-trigger', {timeout: 30000});
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '▶')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await page.waitForSelector('.execute-popover.visible .execute-run-btn',
      {timeout: 10000});
    await page.waitForFunction(() => {
      const p = document.querySelector('.execute-popover.visible');
      return p && p.querySelector('[data-form-field]');
    }, null, {timeout: 10000, polling: 100});
    await page.evaluate(() => {
      const p = document.querySelector('.execute-popover.visible');
      const f = p.querySelector('[data-form-field]');
      f.value = 'graphden';
      f.dispatchEvent(new Event('input', {bubbles: true}));
      const persist = p.querySelector('.execute-persist-checkbox');
      if (persist && !persist.checked) persist.click();
    });
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, 'History is a graph read', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 12 history Next');
    await waitTourTitle(page, "That's the run loop", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 12 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 12: walked (nothing created)');

    // ---------- Lesson 17 — Explorer and Inspector ----------
    await page.goto(BASE + '/?tutorial=17');
    await waitTourTitle(page, 'Two panes, one graph', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 17 Next');
    await waitTourTitle(page, 'Narrow by kind');
    // The lens chips are the step's target; exercise one for real.
    await page.waitForSelector('.kind-toggle', {timeout: 30000});
    const lensCount = await page.evaluate(
      () => document.querySelectorAll('.kind-toggle').length);
    assert(lensCount >= 3,
      'the Explorer offers kind lenses (got ' + lensCount + ')');
    await page.evaluate(() => {
      const t = Array.from(document.querySelectorAll('.kind-toggle'))
        .find((b) => /types/.test(b.textContent));
      if (t) t.click();
    });
    assert(await clickTourButton(page, 'Next'), 'lesson 17 lens Next');
    await waitTourTitle(page, 'Back to everything');
    await page.evaluate(() => {
      const all = document.querySelector('.kind-toggle.kind-all');
      if (all) all.click();
    });
    assert(await clickTourButton(page, 'Next'), 'lesson 17 all-lens Next');
    await waitTourTitle(page, 'Select something');
    await filterAndSelect(page, 'str-len', 'str-len');
    await waitTourTitle(page, "The Inspector's tabs", 150000);
    const tabs = await page.evaluate(() => Array.from(
      document.querySelectorAll('.gd-insp-tab')).map((t) => t.textContent.trim()));
    assert(tabs.includes('Bindings') && tabs.includes('Runs'),
      'the Inspector shows its tabs (got ' + JSON.stringify(tabs) + ')');
    // The step tells the reader to click Bindings, and (since the audit of
    // 2026-09-09) waits for that tab to be selected — it used to pass on the
    // tabs merely existing, so the reader could skip the click unnoticed.
    await page.click('.gd-insp-tab[data-insp-tab="bindings"]');
    await waitTourTitle(page, 'Who uses a fn?', 150000);
    await filterAndSelect(page, 'const', 'const');
    await waitTourTitle(page, 'Used by', 150000);
    // Click the Overview tab for real — the Used-by section renders
    // below the overview partial, and the step's dom-check waits on it.
    await page.evaluate(() => {
      const t = Array.from(document.querySelectorAll('.gd-insp-tab'))
        .find((b) => /Overview/.test(b.textContent));
      if (t) t.click();
    });
    await page.waitForSelector('.gd-insp-usages .gd-insp-usage-row', {timeout: 30000});
    const usage = await page.evaluate(() => ({
      rows: document.querySelectorAll('.gd-insp-usages .gd-insp-usage-row').length,
      glabel: document.querySelector('.gd-insp-usage-glabel')?.textContent || '',
    }));
    assert(usage.rows > 0, 'Used-by lists rows for :const');
    assert(/Extended by \d+/.test(usage.glabel),
      'children group labeled (got "' + usage.glabel + '")');
    // A row click navigates — the selection leaves :const.
    const beforeHash = await page.evaluate(() => location.hash);
    await page.evaluate(() => {
      document.querySelector('.gd-insp-usages .gd-insp-usage-row').click();
    });
    await page.waitForFunction((h) => location.hash !== h, beforeHash,
      {timeout: 30000, polling: 200});
    console.log('  lesson 17: Used-by row navigated to '
      + await page.evaluate(() => location.hash));
    await waitTourTitle(page, "That's the view layer", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 17 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 17: walked (nothing created)');

    // ---------- lesson 18 — working without the mouse ----------
    await page.goto(BASE + '/?tutorial=18');
    await waitTourTitle(page, 'Hands off the mouse', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 18 Next');
    await waitTourTitle(page, 'The cheatsheet');
    // Focus must be OUTSIDE inputs for bare keys — blur whatever holds it.
    await page.evaluate(() => document.activeElement?.blur?.());
    await page.keyboard.press('?');
    await page.waitForSelector('#gd-cheatsheet.visible', {timeout: 15000});
    await waitTourTitle(page, 'Close it', 150000);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('#gd-cheatsheet.visible'),
      null, {timeout: 15000, polling: 100});
    // Esc was consumed by the sheet — the tour must survive it.
    assert(await page.evaluate(() => !!document.querySelector('#gd-tour-pop')),
      'closing the cheatsheet with Escape does not end the tour');
    await waitTourTitle(page, 'The leader', 150000);
    await page.evaluate(() => document.activeElement?.blur?.());
    await page.keyboard.press(' ');
    // The which-key element is a hidden singleton — wait on MEASURED
    // visibility, same predicate the tour's dom checks use.
    const whichKeyVisible = () => {
      const el = document.querySelector('.gd-which-key');
      if (!el) return false;
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    };
    await page.waitForFunction(whichKeyVisible, null, {timeout: 15000, polling: 100});
    await waitTourTitle(page, 'Read it, then dismiss', 150000);
    await page.keyboard.press('Escape');
    await page.waitForFunction(
      () => {
        const el = document.querySelector('.gd-which-key');
        if (!el) return true;
        const r = el.getBoundingClientRect();
        return !(r.width > 0 && r.height > 0);
      }, null, {timeout: 15000, polling: 100});
    await waitTourTitle(page, 'Jump to the filter', 150000);
    // The step's check is FOCUS in the filter; the '/' BINDING itself is
    // asserted separately below on a fresh page — inside the tour the
    // keypress races the step re-render and flakes, and pinning the
    // binding twice buys nothing.
    await page.focus('#search-input');
    await waitTourTitle(page, 'The canvas walks by edges', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 18 canvas Next');
    await waitTourTitle(page, "That's the keyboard", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 18 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 18: walked (keyboard-only, nothing created)');

    // The '/' binding proper — fresh page, no tour in the key path.
    await page.goto(BASE + '/');
    await page.waitForSelector('#search-input', {timeout: 30000});
    await page.evaluate(() => document.activeElement?.blur?.());
    await page.keyboard.press('/');
    await page.waitForFunction(() => document.activeElement?.id === 'search-input',
      null, {timeout: 15000, polling: 100});
    console.log("  '/' shortcut focuses the Explorer filter");

    // ---------- lesson 19 — workspaces ----------
    await page.goto(BASE + '/?tutorial=19');
    await waitTourTitle(page, 'Your slice of a shared graph', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 19 Next');
    await waitTourTitle(page, 'Open the workspace chip');
    await page.waitForSelector('#gd-ws-chip', {timeout: 30000});
    await page.evaluate(() => document.getElementById('gd-ws-chip').click());
    await waitTourTitle(page, 'Pick a root', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 19 root Next');
    await waitTourTitle(page, 'And back');
    assert(await clickTourButton(page, 'Next'), 'lesson 19 back Next');
    await waitTourTitle(page, 'Virtual groups', 150000);
    await page.waitForSelector('#gd-views-btn', {timeout: 15000});
    await page.evaluate(() => document.getElementById('gd-views-btn').click());
    await page.waitForSelector('.gd-views-pop', {timeout: 15000});
    await waitTourTitle(page, 'Save a rule', 150000);
    await page.evaluate(() => {
      const pop = document.querySelector('.gd-views-pop');
      const [nameIn, ruleIn] = pop.querySelectorAll('.gd-views-input');
      nameIn.value = 'on-const';
      ruleIn.value = 'uses:core.logic.const';
      pop.querySelector('.gd-views-save').click();
    });
    await page.waitForSelector('#gd-views-btn.gd-views-active', {timeout: 30000});
    // The tree is now the computed membership — non-empty for :const.
    await page.waitForFunction(() =>
      document.querySelectorAll('#entity-list .entity-item').length > 0,
      null, {timeout: 30000, polling: 200});
    const viewRows = await page.evaluate(() =>
      document.querySelectorAll('#entity-list .entity-item').length);
    assert(viewRows > 0, 'smart view renders members (' + viewRows + ' rows)');
    await waitTourTitle(page, 'Back to the whole tree', 150000);
    await page.evaluate(() => document.getElementById('gd-views-btn').click());
    await page.waitForSelector('.gd-views-pop .gd-views-row-clear', {timeout: 15000});
    await page.evaluate(() =>
      document.querySelector('.gd-views-pop .gd-views-row-clear').click());
    await page.waitForFunction(() => !document.querySelector('.gd-views-active'),
      null, {timeout: 15000, polling: 100});
    await waitTourTitle(page, "That's workspaces");
    assert(await clickTourButton(page, 'Finish'), 'lesson 19 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 19: walked (nothing created)');

    // ---------- Lesson 09 — in-graph state ----------
    await page.goto(BASE + '/?tutorial=09');
    await waitTourTitle(page, 'A graph can remember', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 09 Next');
    // Built from the outside in (2026-09-16): the writer first, then the
    // cell it needs is bound as the base fn and EXTENDED IN PLACE from its
    // card — no trip through the Explorer, no retyping its name.
    await waitTourTitle(page, 'Find swap-conj');
    await filterAndSelect(page, 'swap-conj', 'swap-conj');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-bump', 'swap-conj');
    await waitTourTitle(page, 'tutorial-bump is open', 150000);
    await waitTourTitle(page, 'Point it at a cell', 150000);
    await bindPlaceholderOn(page, 'tutorial-bump', 'a', 'fn-ref', 'cell');
    await waitTourTitle(page, 'Make it yours, in place', 150000);
    await extendInPlace(page, 'cell', 'tutorial-cell');
    await waitTourTitle(page, 'Seed it with an empty list', 150000);
    await bindPlaceholderOn(page, 'tutorial-cell', 'initial-value', 'literal', '[]');
    // The slot points at the CHILD now, not at the base fn.
    const bumpBinds = await page.evaluate(async () => {
      const r = await authFetch(API.api_graph_entities + '?scope=search&q=tutorial-');
      const d = await r.json();
      const bump = (d.fns || []).find((f) => f.name === 'tutorial-bump');
      const cell = (d.fns || []).find((f) => f.name === 'tutorial-cell');
      const sub = await (await authFetch(API.api_graph_entities + '?scope=subtree&root-id=' + bump.id)).json();
      return (sub.bindings || []).filter((b) => b['fn-id'] === bump.id).map((b) => b['ref-fn-id'] === cell.id);
    });
    assert(bumpBinds.length === 1 && bumpBinds[0] === true,
      'tutorial-bump :a now references tutorial-cell (' + JSON.stringify(bumpBinds) + ')');
    await waitTourTitle(page, 'Run it', 150000);
    // Writing to a cell is the :state effect, so Run is gated behind the
    // acknowledgement checkbox — the same gate lesson 13 teaches.
    await runWithEffectAck(page, 'tick', 'tutorial-bump');
    // The lesson's whole claim: the SECOND run sees the first one's value —
    // and since 2026-09-13 the tour makes the reader do that second run
    // (the old single "Run it twice" step passed on the first).
    await waitTourTitle(page, 'Run it again', 150000);
    await page.waitForSelector('.execute-popover.visible .execute-run-btn:not([disabled])', {timeout: 30000});
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, "That's in-graph state", 150000);
    const rows = await page.evaluate(() => document.querySelectorAll('.execute-result-list > li').length);
    assert(rows >= 2, 'the pane lists two rows after the second run (got ' + rows + ')');
    // The lesson's whole claim, checked where it is unambiguous: run the
    // fn twice through the API and watch the cell's list GROW. (The result
    // pane renders effects and typed representations, so asserting on its
    // text would be asserting on the rendering, not on the state.)
    const runViaApi = async () => {
      const r = await page.evaluate(async () => {
        const resp = await authFetch(API.api_execute, {
          method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({'fn-name': 'tutorial-bump', args: {value: 'tick'}})});
        return resp.json();
      });
      assert(r.status === 'succeeded',
        'tutorial-bump ran (got ' + JSON.stringify(r).slice(0, 120) + ')');
      return r.result || [];
    };
    const before = await runViaApi();
    const after = await runViaApi();
    assert(after.length > before.length,
      'the cell kept its value between runs (' + JSON.stringify(before)
      + ' → ' + JSON.stringify(after) + ')');
    await finishAndDelete(page);
    console.log('  lesson 09: walked + cleaned (state survived the second run)');

    // ---------- Lesson 15 — tracing a run ----------
    await page.goto(BASE + '/?tutorial=15');
    await waitTourTitle(page, 'What actually ran?', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 15 Next');
    // Built from the OUTSIDE IN (2026-09-16): the outer fn is extended from
    // the Explorer once; every inner fn is the base fn bound into a slot and
    // then EXTENDED IN PLACE from its card — the reader never leaves
    // tutorial-sentence's canvas and never retypes a name. One ring per
    // action: each bind / extend is its own step, so the walk waits for
    // every title a reader would see.
    await waitTourTitle(page, 'Find str-join');
    await filterAndSelect(page, 'str-join', 'str-join');
    await waitTourTitle(page, 'The outer fn', 150000);
    await extendViaRowActions(page, 'tutorial-sentence', 'str-join');
    await waitTourTitle(page, 'tutorial-sentence is open', 150000);
    await waitTourTitle(page, 'Spaces between the words', 150000);
    await bindPlaceholderOn(page, 'tutorial-sentence', 'separator', 'literal', ' ');
    await waitTourTitle(page, 'Feed it a list', 150000);
    // :coll is a LIST slot — its empty `+` offers the whole-list fn-ref.
    await bindPlaceholderOn(page, 'tutorial-sentence', 'coll', 'whole-list', 'map');
    await waitTourTitle(page, 'Extend it in place', 150000);
    await extendInPlace(page, 'map', 'tutorial-shout');
    await waitTourTitle(page, 'Pick the function', 150000);
    // The child's own `+`s are on THIS canvas — a one-hop ref child draws
    // its unbound slots.
    await bindPlaceholderOn(page, 'tutorial-shout', 'func', 'fn-ref', 'str-upper');
    await waitTourTitle(page, 'And the words', 150000);
    await bindPlaceholderOn(page, 'tutorial-shout', 'coll', 'whole-list', 'str-split');
    // Binding a ref on a CHILD card unfolds it, so the bound fn is drawn —
    // the reader must be able to reach its ⋯ for the next step.
    await page.waitForSelector('.node-overlay[data-fn-name="str-split"]', {timeout: 60000});
    await waitTourTitle(page, 'Extend that one too', 150000);
    await extendInPlace(page, 'str-split', 'tutorial-words');
    await waitTourTitle(page, 'The whole pipeline, unfolded', 150000);
    // …and the fit after the in-place extend brings the new card on screen.
    await page.waitForFunction(() => {
      const r = document.querySelector('.node-overlay[data-fn-name="tutorial-words"]').getBoundingClientRect();
      const surface = document.getElementById('graph-surface').getBoundingClientRect();
      return r.width > 0 && r.right <= surface.right + 8 && r.left >= surface.left - 8;
    }, null, {timeout: 15000, polling: 100});
    assert(await clickTourButton(page, 'Next'), 'pipeline look-step Next');
    await waitTourTitle(page, 'Give it text', 150000);
    await bindPlaceholderOn(page, 'tutorial-words', 'string', 'literal', 'hello,big,world');
    await waitTourTitle(page, 'And where to cut', 150000);
    await bindPlaceholderOn(page, 'tutorial-words', 'separator', 'literal', ',');
    await waitTourTitle(page, 'Peek inside without leaving', 150000);
    await openRowActionsFor(page, 'tutorial-shout');
    await page.waitForSelector('.row-actions-popover [data-action="peek-fn"]',
      {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('.row-actions-popover [data-action="peek-fn"]')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await page.waitForSelector('.fn-peek-panel', {timeout: 15000});
    const peek = await page.evaluate(() => ({
      title: document.querySelector('.fn-peek-title')?.textContent,
      hasBody: !!document.querySelector('.fn-peek-body'),
    }));
    assert(peek.title === 'tutorial-shout',
      'peek panel names the peeked fn (got ' + peek.title + ')');
    await waitTourTitle(page, 'Run it with a trace', 150000);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.fn-peek-panel'),
      null, {timeout: 15000, polling: 100});
    // Escape must have been CONSUMED by the panel — the tour survives.
    assert(await page.evaluate(() => !!document.querySelector('#gd-tour-pop')),
      'closing the peek with Escape does not end the tour');
    // The pipeline the reader built is still unfolded on this canvas.
    for (const name of ['tutorial-sentence', 'tutorial-shout', 'tutorial-words', 'str-upper']) {
      assert(await page.evaluate((n) => !!document.querySelector('.node-overlay[data-fn-name="' + n + '"]'), name),
        name + ' is on the canvas before the run');
    }
    // Run the ROOT with history + trace + capture values — the values are
    // what the path view prints on the cards and the tree lists. The
    // capture confirm is a native dialog; the page-level handler accepts it.
    await openRowActionsFor(page, 'tutorial-sentence');
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '▶')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await page.waitForSelector('.execute-popover.visible .execute-run-btn', {timeout: 15000});
    await page.evaluate(() => {
      for (const sel of ['.execute-persist-checkbox', '.execute-trace-checkbox']) {
        const cb = document.querySelector('.execute-popover.visible ' + sel);
        if (cb && !cb.checked) cb.click();
      }
    });
    await page.click('.execute-popover.visible .execute-capture-values-checkbox');
    assert(await page.evaluate(() =>
      document.querySelector('.execute-popover.visible .execute-capture-values-checkbox').checked),
      'capture values stays ticked after the confirm');
    await page.evaluate(() => document.querySelector('.execute-run-btn').click());
    await waitTourTitle(page, 'Draw the path', 150000);
    await page.waitForSelector('.execute-show-path-btn', {timeout: 60000});
    await page.evaluate(() => document.querySelector('.execute-show-path-btn').click());
    await waitTourTitle(page, 'Read the path', 150000);
    // What the reader is told to see: every fn that ran lit — the three
    // made here plus str-upper with its 3× call count (a callable run by
    // :map is a traced frame per call) — and the value transforming hop
    // by hop on the chips.
    const path = await page.evaluate(() => ({
      highlighted: Array.from(document.querySelectorAll('.node-overlay.path-highlighted'))
        .map((el) => el.dataset.fnName),
      badges: Array.from(document.querySelectorAll('.node-overlay.path-highlighted'))
        .map((el) => [el.dataset.fnName, el.querySelector('.path-trace-badge')?.textContent]),
      chips: Array.from(document.querySelectorAll('.path-value-badge'))
        .map((el) => el.textContent),
    }));
    for (const name of ['tutorial-sentence', 'tutorial-shout', 'tutorial-words', 'str-upper']) {
      assert(path.highlighted.includes(name),
        name + ' is on the drawn path: ' + JSON.stringify(path.highlighted));
    }
    assert(path.badges.some(([n, b]) => n === 'str-upper' && /^3× /.test(b || '')),
      'str-upper ran once per word — its badge counts 3×: ' + JSON.stringify(path.badges));
    assert(path.chips.includes('= "HELLO BIG WORLD"'),
      'the sentence prints on its card: ' + JSON.stringify(path.chips));
    assert(path.chips.some((c) => /^= value$/.test(c) || /HELLO/.test(c)),
      'the list hops carry value chips: ' + JSON.stringify(path.chips));
    assert(await clickTourButton(page, 'Next'), 'Read the path Next');
    await waitTourTitle(page, 'Open the call tree', 150000);
    await page.waitForSelector('#gd-insp-runs .execute-history-tree-btn', {timeout: 30000});
    await page.click('#gd-insp-runs .execute-history-tree-btn');
    await waitTourTitle(page, 'Read the tree', 150000);
    const tree = await page.evaluate(() => ({
      rows: Array.from(document.querySelectorAll('.trace-view-panel .trace-row'))
        .map((r) => r.textContent.replace(/\s+/g, ' ').trim().slice(0, 60)),
      values: Array.from(document.querySelectorAll('.trace-view-panel .trace-value'))
        .map((v) => v.textContent.replace(/\s+/g, ' ').trim().slice(0, 40)),
    }));
    assert(tree.rows.length >= 6,
      'the tree lists the root, two hops and three str-upper calls: ' + JSON.stringify(tree.rows));
    assert(tree.rows[0].includes('tutorial-sentence'),
      'the run\'s own fn is the tree\'s root: ' + JSON.stringify(tree.rows[0]));
    assert(tree.values.some((v) => /HELLO BIG WORLD/.test(v)),
      'the tree carries the captured values: ' + JSON.stringify(tree.values));
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.trace-view-panel'),
      null, {timeout: 15000, polling: 100});
    assert(await clickTourButton(page, 'Next'), 'Read the tree Next');
    await waitTourTitle(page, 'Your trail', 150000);
    // The filter is still holding the last search — clear it for real,
    // as the step instructs; the Recent list only shows outside search.
    await page.evaluate(() => {
      if (typeof clearSearch === 'function') clearSearch();
    });
    await page.waitForSelector('#gd-recent-fns:not([hidden]) .gd-recent-row',
      {timeout: 15000});
    const recents = await page.evaluate(() => ({
      hidden: document.getElementById('gd-recent-fns')?.hidden,
      rows: Array.from(document.querySelectorAll('.gd-recent-row'))
        .map((r) => r.textContent),
    }));
    assert(recents.hidden === false && recents.rows.length > 0,
      'Recent list is visible with rows (' + JSON.stringify(recents.rows) + ')');
    await waitTourTitle(page, "That's debugging in place", 150000);
    // A recent row navigates — the trail is the way back.
    const trailHash = await page.evaluate(() => location.hash);
    await page.evaluate(() => document.querySelector('.gd-recent-row').click());
    await page.waitForFunction((h) => location.hash !== h, trailHash,
      {timeout: 30000, polling: 200});
    console.log('  lesson 15: Recent row navigated to '
      + await page.evaluate(() => location.hash));
    await finishAndDelete(page);
    console.log('  lesson 15: walked + cleaned (pipeline + path + tree + peek + trail)');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour title at failure:', await tourTitle(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-ux-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-ux-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
