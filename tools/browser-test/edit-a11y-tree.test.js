// Explorer tree — keyboard navigation (WAI-ARIA tree pattern).
//
// The tree is the editor's primary navigation and was mouse-only: rows were
// `div`s with an `onclick`, so a keyboard user could not reach a single
// function. `<nav id="entity-list">` contained no focusable element at all.
//
// The hard part is not the arrow keys, it is that the tree is REBUILT
// constantly — `updateEntityList` does `innerHTML = ''`, and selecting a fn
// calls it. So the interesting assertions here are the ones about surviving
// a rebuild: open a function with Enter (which destroys every row) and check
// the keyboard is still in the tree, on the row you were on.
//
// What this pins:
//   1. The tree is reachable by Tab, and exactly ONE row is a tab stop.
//   2. Arrows move; Right expands, Left collapses, Home/End jump.
//   3. Enter opens the function — and focus survives the rebuild that causes.
//   4. Row buttons (rename, description) are revealed by keyboard focus, not
//      only by hover.
//
// Read-only apart from expanding namespaces.
//
// Run:  node edit-a11y-tree.test.js

const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

const PROBE_FN = 'web-server';

const rowInfo = () => {
  const a = document.activeElement;
  if (!a) return null;
  return {
    isTreeItem: a.getAttribute('role') === 'treeitem',
    fnId: a.dataset?.fnId || null,
    nsPath: a.dataset?.nsPath || null,
    level: a.getAttribute('aria-level'),
    expanded: a.getAttribute('aria-expanded'),
    text: (a.textContent || '').trim().slice(0, 30),
  };
};

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium);
  console.log('edit-a11y-tree — the Explorer tree from the keyboard');

  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  try {
    await page.goto('about:blank');
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.waitForFunction(
      () => graphReady() && document.querySelectorAll('#entity-list [role="treeitem"]').length > 0,
      null, {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A — structure.
    // ===================================================================
    const structure = await page.evaluate(() => {
      const tree = document.getElementById('entity-list');
      const items = [...tree.querySelectorAll('[role="treeitem"]')];
      const tabbable = items.filter((el) => el.getAttribute('tabindex') === '0');
      return {
        treeRole: tree.getAttribute('role'),
        itemCount: items.length,
        tabStops: tabbable.length,
        allLevelled: items.every((el) => !!el.getAttribute('aria-level')),
        nsHaveExpanded: items.filter((el) => el.dataset.nsPath)
                             .every((el) => el.hasAttribute('aria-expanded')),
        fnsHaveSelected: items.filter((el) => el.dataset.fnId)
                              .every((el) => el.hasAttribute('aria-selected')),
      };
    });
    assert(structure.treeRole === 'tree', 'the container is a tree, got ' + structure.treeRole);
    assert(structure.itemCount > 3, 'it has rows: ' + structure.itemCount);
    assert(structure.tabStops === 1,
           'exactly one row is a tab stop (roving tabindex), got ' + structure.tabStops);
    assert(structure.allLevelled,
           'every row states its depth — the tree renders flat, so depth cannot be inferred');
    assert(structure.nsHaveExpanded, 'namespace rows expose aria-expanded');
    assert(structure.fnsHaveSelected, 'function rows expose aria-selected');

    // ===================================================================
    // Phase B — arrows.
    // ===================================================================
    await page.evaluate(() => {
      const first = document.querySelector('#entity-list [role="treeitem"]');
      first.focus();
    });
    const start = await page.evaluate(rowInfo);
    assert(start.isTreeItem, 'focus starts on a row: ' + start.text);

    await page.keyboard.press('ArrowDown');
    const down = await page.evaluate(rowInfo);
    assert(down.text !== start.text, 'ArrowDown moves to another row (' + down.text + ')');

    await page.keyboard.press('ArrowUp');
    const back = await page.evaluate(rowInfo);
    assert(back.text === start.text, 'ArrowUp comes back');

    // Right expands a collapsed namespace, in place.
    const beforeExpand = await page.evaluate(() => {
      // Park on a collapsed namespace row.
      const ns = [...document.querySelectorAll('#entity-list [role="treeitem"][data-ns-path]')]
        .find((el) => el.getAttribute('aria-expanded') === 'false');
      if (!ns) return null;
      ns.focus();
      return {path: ns.dataset.nsPath,
              rows: document.querySelectorAll('#entity-list [role="treeitem"]').length};
    });
    if (!beforeExpand) {
      console.log('  – expand probe skipped: every namespace already open');
    } else {
      await page.keyboard.press('ArrowRight');
      await page.waitForTimeout(600);
      const afterExpand = await page.evaluate((path) => {
        const ns = document.querySelector('#entity-list [data-ns-path="' + path + '"]');
        return {
          expanded: ns?.getAttribute('aria-expanded'),
          rows: document.querySelectorAll('#entity-list [role="treeitem"]').length,
        };
      }, beforeExpand.path);
      assert(afterExpand.expanded === 'true',
             'ArrowRight expands the namespace (' + beforeExpand.path + ')');
      assert(afterExpand.rows > beforeExpand.rows,
             'and its children appear (' + beforeExpand.rows + ' → ' + afterExpand.rows + ')');

      // Left collapses it again.
      await page.evaluate((path) => {
        document.querySelector('#entity-list [data-ns-path="' + path + '"]').focus();
      }, beforeExpand.path);
      await page.keyboard.press('ArrowLeft');
      await page.waitForTimeout(400);
      const collapsed = await page.evaluate((path) => document
        .querySelector('#entity-list [data-ns-path="' + path + '"]')
        ?.getAttribute('aria-expanded'), beforeExpand.path);
      assert(collapsed === 'false', 'ArrowLeft collapses it');
    }

    // Home / End.
    await page.keyboard.press('End');
    const atEnd = await page.evaluate(() => {
      const items = [...document.querySelectorAll('#entity-list [role="treeitem"]')]
        .filter((el) => !el.hidden && el.offsetParent !== null);
      return document.activeElement === items[items.length - 1];
    });
    assert(atEnd, 'End jumps to the last row');
    await page.keyboard.press('Home');
    const atHome = await page.evaluate(() => {
      const items = [...document.querySelectorAll('#entity-list [role="treeitem"]')]
        .filter((el) => !el.hidden && el.offsetParent !== null);
      return document.activeElement === items[0];
    });
    assert(atHome, 'Home jumps to the first row');

    // ===================================================================
    // Phase C — Enter opens a fn, and focus survives the full rebuild.
    // ===================================================================
    const target = await page.evaluate(() => {
      const fnRow = [...document.querySelectorAll('#entity-list [role="treeitem"][data-fn-id]')]
        .find((el) => !el.hidden && el.offsetParent !== null);
      if (!fnRow) return null;
      fnRow.focus();
      return {fnId: fnRow.dataset.fnId, text: fnRow.textContent.trim().slice(0, 24)};
    });
    if (!target) {
      console.log('  – open probe skipped: no visible fn row');
    } else {
      await page.keyboard.press('Enter');
      // selectFn → updateEntityList → innerHTML = '' → every row above is gone.
      await page.waitForTimeout(1500);
      const after = await page.evaluate((fnId) => {
        const tree = document.getElementById('entity-list');
        const active = document.activeElement;
        return {
          // `selectedFnId` is a top-level `let` in editor-state.js, not a
          // window property — reachable by bare name inside evaluate().
          selectedFn: selectedFnId === fnId,
          focusStillInTree: tree.contains(active),
          onSameRow: active?.dataset?.fnId === fnId,
          tabStops: [...tree.querySelectorAll('[role="treeitem"][tabindex="0"]')].length,
          rowSelected: tree.querySelector('[data-fn-id="' + fnId + '"]')
                           ?.getAttribute('aria-selected'),
        };
      }, target.fnId);
      assert(after.selectedFn, 'Enter opens the function (' + target.text + ')');
      assert(after.focusStillInTree,
             'focus survives the rebuild Enter causes — the whole tree is re-created');
      assert(after.onSameRow, 'and lands back on the same row, not the top of the list');
      assert(after.tabStops === 1, 'still exactly one tab stop after the rebuild');
      assert(after.rowSelected === 'true', 'the row reports itself as selected');
    }

    // ===================================================================
    // Phase D — row actions are reachable, not hover-only.
    // ===================================================================
    const focused = await page.evaluate(() => {
      const row = [...document.querySelectorAll('#entity-list [role="treeitem"][data-ns-path]')]
        .find((el) => !el.hidden && el.querySelector('.ns-row-actions .create-btn-inline'));
      if (!row) return false;
      row.focus();   // :focus-within is on the ROW, so the row itself counts
      return true;
    });
    // The buttons fade in over 0.1s. getComputedStyle mid-transition reports
    // the CURRENT animated value, so reading it in the same tick as focus()
    // returns 0 no matter what the rule says.
    await page.waitForTimeout(400);
    const actions = !focused ? null : await page.evaluate(() => {
      const btn = document.activeElement.querySelector('.ns-row-actions .create-btn-inline');
      return {opacity: getComputedStyle(btn).opacity};
    });
    if (!actions) {
      console.log('  – row-actions probe skipped: no row with inline buttons');
    } else {
      assert(parseFloat(actions.opacity) > 0.5,
             'row buttons become visible when the row has focus (opacity '
             + actions.opacity + ') — reachable but invisible is worse than absent');
    }

    // ===================================================================
    // Phase E — the inspector tab strip (ARIA tab pattern).
    // ===================================================================
    const tabs = await page.evaluate(() => {
      const list = document.querySelector('.gd-insp-tabs');
      if (!list) return null;
      const items = [...list.querySelectorAll('[role="tab"]')];
      const body = document.getElementById('gd-insp-tabbody');
      return {
        count: items.length,
        tabStops: items.filter((t) => t.getAttribute('tabindex') === '0').length,
        allControl: items.every((t) => t.getAttribute('aria-controls') === 'gd-insp-tabbody'),
        panelRole: body?.getAttribute('role'),
        panelLabelled: body?.getAttribute('aria-labelledby'),
        selected: items.find((t) => t.getAttribute('aria-selected') === 'true')?.dataset.inspTab,
      };
    });
    if (!tabs) {
      console.log('  – inspector probe skipped: no tab strip (nothing selected?)');
    } else {
      assert(tabs.count >= 2, 'the inspector has tabs: ' + tabs.count);
      assert(tabs.tabStops === 1,
             'one tab is a tab stop, arrows move between them — got ' + tabs.tabStops);
      assert(tabs.allControl, 'each tab points at the panel it controls');
      assert(tabs.panelRole === 'tabpanel', 'the body is a tabpanel, got ' + tabs.panelRole);
      assert(tabs.panelLabelled === 'gd-insp-tab-' + tabs.selected,
             'the panel is named by the selected tab (' + tabs.panelLabelled + ')');

      // ArrowRight moves selection AND focus.
      await page.evaluate(() => {
        document.querySelector('.gd-insp-tab[aria-selected="true"]').focus();
      });
      await page.keyboard.press('ArrowRight');
      await page.waitForTimeout(500);
      const moved = await page.evaluate((was) => {
        const active = document.activeElement;
        return {
          onATab: active?.getAttribute('role') === 'tab',
          changed: active?.dataset.inspTab !== was,
          selectedFollows: active?.getAttribute('aria-selected') === 'true',
          panelFollows: document.getElementById('gd-insp-tabbody')
            ?.getAttribute('aria-labelledby') === 'gd-insp-tab-' + active?.dataset.inspTab,
        };
      }, tabs.selected);
      assert(moved.onATab && moved.changed, 'ArrowRight moves to the next tab');
      assert(moved.selectedFollows, 'and selects it');
      assert(moved.panelFollows, 'and the panel re-points at it');
    }

    assert(pageErrors.length === 0, 'no page errors: ' + JSON.stringify(pageErrors));
    console.log('a11y-tree — PASS');
  } finally {
    await browser.close();
  }
})();
