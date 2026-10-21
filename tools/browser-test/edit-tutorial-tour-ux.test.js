// Lessons 15, 20, 21, 22 — running fns, the Explorer/Inspector,
// working without the mouse, filters and views, in-graph state, and
// tracing a run.
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
const fs = require('node:fs');
const {assert, newContext, api, nodeApi} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect,
  runViaRowActions, tourWhere, extendViaRowActions, bindFirstPlaceholder,
  bindFnRefPlaceholder, finishAndDelete, runWithEffectAck, waitTourClosed,
  openRowActionsFor, bindNamedPlaceholder, bindPlaceholderOn, extendInPlace,
  waitUntil,
} = require('./tutorial-tour-helpers');


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-ux — lessons 15 / 20 / 21 / 22');
  let failed = false;
  let createdViewReceipt = null;
  const viewReceiptPath = '/tmp/graphden-native22-receipt-' + process.pid + '-' + Date.now() + '.json';
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

    // ---------- Lesson 15 — executing a fn ----------
    await page.goto(BASE + '/?tutorial=15');
    await waitTourTitle(page, 'Running is part of editing', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 15 Next');
    await waitTourTitle(page, 'Find str-len');
    await filterAndSelect(page, 'str-len', 'str-len');
    await waitTourTitle(page, 'Free args become the form', 150000);
    await runViaRowActions(page, 'hello');
    await waitTourTitle(page, 'Five', 150000);
    assert(await page.waitForFunction(() => /(^|\D)5(\D|$)/.test(
      (document.querySelector('.execute-result-host')?.textContent || '').replace(/Submitting…/, '')), null,
      {timeout: 60000, polling: 200}).then(() => true, () => false),
      'the pane shows 5 for hello');
    assert(await clickTourButton(page, 'Next'), 'lesson 15 look-step Next');
    await waitTourTitle(page, 'Keep the interesting one', 150000);
    // The step now completes on a REAL persisted run (the
    // body[data-gd-persisted-run] marker) — do what the lesson says:
    // reopen Run, tick "Save to history", enter graphden, Run.
    await openRowActionsFor(page, 'str-len', 30000, {root: true});
    await page.locator('.row-actions-popover [data-action="run-fn"]').click();
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
    assert(await clickTourButton(page, 'Next'), 'lesson 15 history Next');
    await waitTourTitle(page, "That's the run loop", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 15 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 15: walked (nothing created)');

    // ---------- Lesson 20 — Explorer and Inspector ----------
    await page.goto(BASE + '/?tutorial=20');
    await waitTourTitle(page, 'Two panes, one graph', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 20 Next');
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
    assert(await clickTourButton(page, 'Next'), 'lesson 20 lens Next');
    await waitTourTitle(page, 'Back to everything');
    await page.evaluate(() => {
      const all = document.querySelector('.kind-toggle.kind-all');
      if (all) all.click();
    });
    assert(await clickTourButton(page, 'Next'), 'lesson 20 all-lens Next');
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
    console.log('  lesson 20: Used-by row navigated to '
      + await page.evaluate(() => location.hash));
    await waitTourTitle(page, "That's the view layer", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 20 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 20: walked (nothing created)');

    // ---------- lesson 21 — working without the mouse ----------
    await page.goto(BASE + '/?tutorial=21');
    await waitTourTitle(page, 'Hands off the mouse', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 21 Next');
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
    await waitTourTitle(page, 'Onto the tree', 150000);
    // The field's exit: Escape puts the keyboard on a tree row (the reader
    // used to be stuck — Escape did nothing, `?` and Space typed).
    await page.focus('#search-input');
    await page.keyboard.press('Escape');
    const afterEsc = await page.evaluate(() => {
      const a = document.activeElement;
      return { row: !!a?.closest('#entity-list [role="treeitem"]'), tag: a?.tagName };
    });
    assert(afterEsc.row, 'Escape in the filter lands on a tree row (got ' + JSON.stringify(afterEsc) + ')');
    await waitTourTitle(page, 'Into the graph', 150000);
    // Space g g from the tree row — the keyboard reaches the canvas.
    await page.keyboard.press(' ');
    await page.waitForSelector('.gd-which-key', {timeout: 10000});
    await page.keyboard.press('g');
    await page.keyboard.press('g');
    await waitUntil(page, () => !!document.activeElement?.closest('#graph-container')
      || document.activeElement === document.getElementById('graph-container'), null, 5000);
    const onCanvas = await page.evaluate(() => !!document.activeElement?.closest('#graph-container'));
    assert(onCanvas, 'Space g g moves the keyboard into the graph');
    await waitTourTitle(page, "That's the keyboard", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 21 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 21: walked (keyboard-only, nothing created)');

    // The '/' binding proper — fresh page, no tour in the key path.
    await page.goto(BASE + '/');
    await page.waitForSelector('#search-input', {timeout: 30000});
    await page.evaluate(() => document.activeElement?.blur?.());
    await page.keyboard.press('/');
    await page.waitForFunction(() => document.activeElement?.id === 'search-input',
      null, {timeout: 15000, polling: 100});
    console.log("  '/' shortcut focuses the Explorer filter");

    // ---------- lesson 22 — filters and views ----------
    // A reader arrives with the filters a previous lesson left on; the
    // lesson starts from a clean slate the way the tour's own cleanup does.
    await page.evaluate(() => { if (typeof gdClearFilters === 'function') gdClearFilters(); });
    await page.goto(BASE + '/?tutorial=22');
    await waitTourTitle(page, 'One way to narrow the tree', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 22 Next');
    await waitTourTitle(page, 'Add a namespace filter');
    await page.waitForSelector('#gd-filter-add', {timeout: 30000});
    await page.click('#gd-filter-add');
    await page.waitForSelector('.gd-filter-add-pop .gd-ws-opt[data-ws="core"]', {timeout: 15000});
    await page.click('.gd-filter-add-pop .gd-ws-opt[data-ws="core"]');
    await waitTourTitle(page, 'The view chip', 150000);
    const scoped = await page.evaluate(() => ({
      chip: document.querySelector('#gd-ws-chip b')?.textContent,
      chips: Array.from(document.querySelectorAll('#gd-filter-chips .kind-label')).map((e) => e.textContent),
      roots: Array.from(document.querySelectorAll('#entity-list .ns-header[aria-level="1"]')).filter((e) => !e.hidden).map((e) => e.dataset.nsPath),
    }));
    assert(scoped.chip === '1 filter', 'the view chip counts the filter (got: ' + scoped.chip + ')');
    assert(scoped.chips.join() === 'in core', 'an "in core" chip appeared (got: ' + scoped.chips.join() + ')');
    assert(scoped.roots.join() === 'core', 'the tree collapsed to core (got: ' + scoped.roots.join() + ')');
    await page.keyboard.press('Escape');
    assert(await clickTourButton(page, 'Next'), 'view-chip Next');
    await waitTourTitle(page, 'Clear it', 150000);
    await page.evaluate(() => toggleKind('all'));
    await waitTourTitle(page, 'A filter the graph answers', 150000);
    const cleared = await page.evaluate(() => ({
      chip: document.querySelector('#gd-ws-chip b')?.textContent,
      chips: document.querySelectorAll('#gd-filter-chips .gd-filter-chip').length,
    }));
    assert(cleared.chip === 'All functions' && cleared.chips === 0, '◍ all clears every filter (got: ' + JSON.stringify(cleared) + ')');
    // uses: through the + filter menu and the fn picker.
    await page.click('#gd-filter-add');
    await page.waitForSelector('.gd-filter-add-pop [data-action="add-uses"]', {timeout: 15000});
    await page.click('.gd-filter-add-pop [data-action="add-uses"]');
    await page.waitForSelector('.fn-picker-popover .fn-picker-search', {timeout: 15000});
    await page.type('.fn-picker-popover .fn-picker-search', 'const');
    await page.waitForSelector('.fn-picker-popover .fn-picker-row[data-fn-name="core.logic.const"]', {timeout: 20000});
    await page.click('.fn-picker-popover .fn-picker-row[data-fn-name="core.logic.const"]');
    await waitTourTitle(page, 'Create a graph view', 150000);
    // The tree is now the computed membership — non-empty for :const.
    await page.waitForFunction(() =>
      document.querySelectorAll('#entity-list .entity-item').length > 0,
      null, {timeout: 30000, polling: 200});
    const viewRows = await page.evaluate(() => ({
      rows: document.querySelectorAll('#entity-list .entity-item').length,
      chips: Array.from(document.querySelectorAll('#gd-filter-chips .kind-label')).map((e) => e.textContent),
    }));
    assert(viewRows.rows > 0, 'the uses filter renders members (' + viewRows.rows + ' rows)');
    assert(viewRows.chips.join() === 'uses core.logic.const', 'a "uses" chip names the fn (got: ' + viewRows.chips.join() + ')');
    await page.evaluate(() => gdCloseFilterAdd());
    await page.click('#kind-filters .kind-toggle[data-kind="all"]');
    await page.click('#kind-filters .kind-toggle[data-kind="fn"]');
    await page.click('#gd-filter-add');
    await page.fill('.gd-filter-add-pop input[aria-label="Only names containing"]', 'tutorial-');
    await page.press('.gd-filter-add-pop input[aria-label="Only names containing"]', 'Enter');
    await page.keyboard.press('Escape');
    await page.click('#gd-ws-chip');
    await page.fill('#gd-ws-pop .gd-views-input', 'tutorial-function-view');
    const viewContext = await page.evaluate(async () => {
      const response = await authFetch(API.api_branches);
      if (!response.ok) throw new Error('View branch receipt unavailable');
      const body = await response.json();
      const rows = Array.isArray(body) ? body : body.branches;
      const branch = rows.find(row => row.name === _tourSessionBranch());
      if (!branch?.id) throw new Error('View branch identity unavailable');
      return {branchId: branch.id, branchName: branch.name};
    });
    const saveResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/views/save', {timeout: 30000});
    await page.getByRole('button', {name: 'Save in graph', exact: true}).click();
    const savedResponse = await saveResponse;
    const command = savedResponse.request().postDataJSON();
    const savedBody = await savedResponse.json();
    assert(savedResponse.status() === 200 && savedBody.ok === true && savedBody.committed === true
      && savedBody.view?.id === command['create-id'], 'Save returns the exact preassigned fresh UUID');
    createdViewReceipt = {...viewContext, id: savedBody.view.id, name: savedBody.view.name};
    fs.writeFileSync(viewReceiptPath, JSON.stringify(createdViewReceipt), {mode: 0o600});
    console.log('  exact view receipt: ' + viewReceiptPath);
    await waitTourTitle(page, 'Edit the same view', 150000);
    const viewId = await page.evaluate(() => gdActiveViewId());
    assert(viewId, 'graph Save retains the preassigned identity');
    await page.keyboard.press('Escape');
    await page.click('#gd-ws-chip');
    await page.click('#gd-ws-pop [data-edit-view="' + viewId + '"]');
    await page.fill('.gd-filter-add-pop input[aria-label="Only names containing"]', 'tutorial-function-view');
    await page.press('.gd-filter-add-pop input[aria-label="Only names containing"]', 'Enter');
    await page.keyboard.press('Escape');
    await page.click('#gd-ws-chip');
    await page.getByRole('button', {name: 'Save changes', exact: true}).click();
    await waitTourTitle(page, 'Run the view', 150000);
    const persisted = (await api(page, 'GET', '/api/views')).find(view => view.id === viewId);
    assert(persisted?.filters.kinds.join() === 'fn' && persisted.filters.name === 'tutorial-function-view',
      'Edit preserves the kind and changes name on the same persisted view UUID');
    await page.keyboard.press('Escape');
    await page.click('#gd-ws-chip');
    await page.getByRole('button', {name: 'Open view fn tutorial-function-view', exact: true}).click();
    await page.waitForSelector('.node-overlay[data-fn-name="tutorial-function-view"]');
    console.log('  exact view effect flags: ' + JSON.stringify(await page.evaluate(async id => {
      const fn = lookups.fnMap.get(id);
      const cached = richTypeEntryOf(fn);
      const response = await authFetch('/api/types');
      const types = response.ok ? await response.json() : {};
      const fresh = types[fn?.name];
      const base = [...lookups.fnMap.values()].find(row => row.name === 'app.views.explorer-view');
      const hasDb = effects => Array.isArray(effects) && effects.some(effect => effect.replace(/^:/, '') === 'db');
      return {selectedMatches: selectedFnId === id, fnPresent: !!fn,
        parentMatches: !!base && fn?.['parent-ids']?.includes(base.id),
        cachedPresent: !!cached, cachedHasDb: hasDb(cached?.effects),
        freshStatus: response.status, freshPresent: !!fresh,
        freshIdentityMatches: fresh?.['fn-id'] === id, freshHasDb: hasDb(fresh?.effects)};
    }, viewId)));
    if (await page.locator('.node-overlay[data-fn-name="tutorial-function-view"] .fn-run-trigger').count()) {
      await page.click('.node-overlay[data-fn-name="tutorial-function-view"] .fn-run-trigger');
    } else {
      await page.click('.node-overlay[data-fn-name="tutorial-function-view"] .ancestor-line[data-level="0"] .more-actions-trigger');
      await page.click('.row-actions-popover [data-action="run"]');
    }
    try {
      await page.waitForSelector('.execute-popover.visible .execute-confirm-checkbox');
    } catch (error) {
      console.error('  exact view run flags: ' + JSON.stringify(await page.evaluate(id => ({
        selectedMatches: selectedFnId === id, runReady: !!document.querySelector('#gd-insp-run-host .execute-popover.visible'),
        warning: !!document.querySelector('#gd-insp-run-host .execute-effects-warning'),
        checkbox: !!document.querySelector('#gd-insp-run-host .execute-confirm-checkbox'),
        disabled: document.querySelector('#gd-insp-run-host .execute-run-btn')?.disabled,
      }), viewId)));
      throw error;
    }
    assert(await page.locator('.execute-popover.visible .execute-run-btn').isDisabled(), 'database effect requires consent');
    await page.check('.execute-popover.visible .execute-confirm-checkbox');
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, 'Back to the whole tree', 150000);
    const viewResult = await page.evaluate(() => JSON.parse(document.querySelector(
      '#gd-insp-run-host .execute-result-raw pre').textContent));
    assert(Array.isArray(viewResult.fns) && viewResult.fns.some(fn => fn.id === viewId)
      && viewResult.fns.every(fn => fn.name.includes('tutorial-function-view'))
      && Number.isInteger(viewResult.total) && viewResult.total >= viewResult.fns.length,
    'saved view Run returns its own UUID and only matching filtered functions');
    await page.click('#kind-filters .kind-toggle[data-kind="all"]');
    await waitTourTitle(page, "That's filters and views", 60000);
    await finishAndDelete(page);
    const viewsAfterCleanup = await api(page, 'GET', '/api/views');
    assert(!viewsAfterCleanup.some(view => view.id === viewId), 'lesson cleanup removed the exact created view');
    console.log('  lesson 22: created, edited both conditions, ran and cleaned exact UUID');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-ux-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-ux-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    if (createdViewReceipt) {
      try {
        const receipt = createdViewReceipt;
        const branches = await api(page, 'GET', '/api/branches');
        const rows = Array.isArray(branches) ? branches : branches.branches;
        assert(rows?.some(row => row.id === receipt.branchId && row.name === receipt.branchName),
          'exact saved view branch identity retained');
        const headers = {'X-Graphden-Branch': receipt.branchId};
        const views = await api(page, 'GET', '/api/views', undefined, headers);
        const view = views.find(row => row.id === receipt.id);
        if (view) {
          const deleted = await nodeApi('DELETE', '/api/entities/fn/' + receipt.id, undefined, headers);
          const body = await deleted.json().catch(() => null);
          assert(deleted.ok && body?.ok !== false && !body?.error, 'exact saved view UUID removed');
        }
        const remaining = await api(page, 'GET', '/api/views', undefined, headers);
        assert(!remaining.some(row => row.id === receipt.id), 'exact saved view UUID absent');
        fs.unlinkSync(viewReceiptPath);
      } catch (_) {
        failed = true;
        console.error('Exact saved view cleanup incomplete; retained receipt: ' + viewReceiptPath);
      }
    }
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
