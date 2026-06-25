// Branch lifecycle UI e2e — top-bar branch chip + popover CRUD flow.
//
// Coverage:
//   • clicking the branch chip opens the popover
//   • Create form: input + button creates branch, switches to it
//   • URL gets `?branch=NAME` + reload picks up the new branch
//   • after reload, popover shows the new branch as current
//   • Delete row removes a non-current branch (confirm dialog auto-accepted)
//   • dismiss on Escape
//
// Run from this directory:  node edit-branch-lifecycle.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const TEST_BRANCH = 'lifecycle-feat' + RUN_ID;


async function cleanup(page) {
  try {
    await api(page, 'DELETE',
              '/api/branches/' + encodeURIComponent(TEST_BRANCH));
  } catch (_) {}
}


async function openBranchPopover(page) {
  await page.click('#branch-chip-btn');
  try {
    await page.waitForFunction(
      () => {
        const p = document.getElementById('branch-popover');
        return p && !p.classList.contains('hidden')
               && p.querySelector('.branch-popover-list');
      },
      null,
      {timeout: 5000});
    return true;
  } catch (_) { return false; }
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 200));
    d.accept();
  });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-branch-lifecycle — chip + popover create/switch/delete');

  try {
    await cleanup(page);
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});

    // ===================================================================
    // Phase A: chip + popover wiring on the default branch.
    // ===================================================================
    const initialChip = await page.textContent('#branch-chip-name');
    assert(initialChip?.trim() === 'main',
           'chip starts on :main: ' + JSON.stringify(initialChip));

    let opened = await openBranchPopover(page);
    assert(opened, 'branch popover opens on chip click');

    const popoverState = await page.evaluate(() => {
      const p = document.getElementById('branch-popover');
      const rows = p.querySelectorAll('.branch-row');
      const mainRow = Array.from(rows).find(
        (r) => r.dataset.branchName === 'main');
      const createInput = !!p.querySelector('#branch-create-input');
      const createBtn = !!p.querySelector('#branch-create-btn');
      return {
        rowCount: rows.length,
        mainPresent: !!mainRow,
        mainTagged: mainRow?.classList.contains('branch-row-current'),
        createInput,
        createBtn,
      };
    });
    assert(popoverState.rowCount >= 1,
           'popover lists at least one branch: ' + popoverState.rowCount);
    assert(popoverState.mainPresent,
           ':main row present in list');
    assert(popoverState.mainTagged,
           ':main row carries .branch-row-current marker');
    assert(popoverState.createInput && popoverState.createBtn,
           'Create form has input + button');

    // ===================================================================
    // Phase B: create a new feature branch.
    // The Create handler navigates via window.location reassignment;
    // wait for the reload to complete by polling location.search.
    // ===================================================================
    await page.fill('#branch-create-input', TEST_BRANCH);
    await page.click('#branch-create-btn');

    // Wait for reload to finish — chip should show the new branch.
    await page.waitForFunction(
      (target) => {
        const span = document.getElementById('branch-chip-name');
        return span && span.textContent.trim() === target;
      },
      TEST_BRANCH,
      {timeout: 15000});

    const urlSearch = await page.evaluate(() => location.search);
    assert(urlSearch.includes('branch=' + encodeURIComponent(TEST_BRANCH)),
           'URL carries ?branch= after create: ' + urlSearch);

    // Re-open popover — the new branch shows as current.
    opened = await openBranchPopover(page);
    assert(opened, 'popover re-opens after create');
    const afterCreate = await page.evaluate((target) => {
      const p = document.getElementById('branch-popover');
      const rows = p.querySelectorAll('.branch-row');
      const newRow = Array.from(rows).find(
        (r) => r.dataset.branchName === target);
      return {
        newRowPresent: !!newRow,
        newRowCurrent: newRow?.classList.contains('branch-row-current'),
        mainRowHasMergeBtn: !!Array.from(rows)
          .find((r) => r.dataset.branchName === 'main')
          ?.querySelector('.branch-row-merge'),
      };
    }, TEST_BRANCH);
    assert(afterCreate.newRowPresent,
           'new branch row appears in popover');
    assert(afterCreate.newRowCurrent,
           'new branch row marked as current');
    assert(afterCreate.mainRowHasMergeBtn,
           ':main row exposes ⇢ merge button (target = feat)');

    // ===================================================================
    // Phase C: switch back to :main via switchToBranch (skips
    // confirmable Δ / ⇢ paths — those have their own tests).
    // ===================================================================
    await page.evaluate(() => window.switchToBranch?.('main'));
    await page.waitForFunction(
      () => {
        const span = document.getElementById('branch-chip-name');
        return span && span.textContent.trim() === 'main';
      },
      null,
      {timeout: 15000});

    // Re-open popover; verify the feat row now exposes Δ + ⇢ + ×.
    opened = await openBranchPopover(page);
    assert(opened, 'popover re-opens after switch back to main');
    const fromMain = await page.evaluate((target) => {
      const p = document.getElementById('branch-popover');
      const featRow = Array.from(p.querySelectorAll('.branch-row'))
        .find((r) => r.dataset.branchName === target);
      return {
        present: !!featRow,
        hasDiff: !!featRow?.querySelector('.branch-row-diff'),
        hasMerge: !!featRow?.querySelector('.branch-row-merge'),
        hasDelete: !!featRow?.querySelector('.branch-row-delete'),
      };
    }, TEST_BRANCH);
    assert(fromMain.present, 'feat row visible from main');
    assert(fromMain.hasDiff, 'feat row exposes Δ from main');
    assert(fromMain.hasMerge, 'feat row exposes ⇢ from main');
    assert(fromMain.hasDelete,
           'feat row exposes × Delete (non-current, no children)');

    // ===================================================================
    // Phase D: delete the feat branch via the row's × button.
    // confirm() auto-accepted by the shared dialog handler above.
    // ===================================================================
    await page.evaluate((target) => {
      const p = document.getElementById('branch-popover');
      const row = Array.from(p.querySelectorAll('.branch-row'))
        .find((r) => r.dataset.branchName === target);
      row.querySelector('.branch-row-delete').click();
    }, TEST_BRANCH);

    // Wait for branch to disappear from the API. Generous timeout —
    // under bulk-sweep load the `:branches` query can lag behind the
    // delete by 5-15s (it's a versioned read, not a direct table
    // lookup; the resolver walks the chain).
    await page.waitForFunction(
      async (target) => {
        try {
          const r = await window.authFetch('/api/branches');
          if (!r.ok) return false;
          const body = await r.json();
          return !body.branches.some((b) => b.name === target);
        } catch (_) { return false; }
      },
      TEST_BRANCH,
      {timeout: 30000});

    const branchList = await api(page, 'GET', '/api/branches');
    assert(!(branchList.branches || []).some((b) => b.name === TEST_BRANCH),
           'feat branch gone from API after × delete');

    // ===================================================================
    // Phase E: Escape dismisses the popover.
    // (Delete in Phase D didn't auto-close — the toggle on the chip
    // would just close the still-open popover. Ensure it's closed
    // first, then open fresh.)
    // ===================================================================
    await page.keyboard.press('Escape');
    // (escape dispatched; the following assertion gates the next step)
    opened = await openBranchPopover(page);
    assert(opened, 'popover re-opens for dismiss test');
    await page.keyboard.press('Escape');
    // (escape dispatched; the following assertion gates the next step)
    const dismissed = await page.evaluate(() => {
      const p = document.getElementById('branch-popover');
      return p.classList.contains('hidden');
    });
    assert(dismissed, 'popover dismisses on Escape');

    console.log('✓ branch lifecycle verified — create / switch / delete / dismiss');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
