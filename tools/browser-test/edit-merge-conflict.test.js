// Merge-conflict resolution modal e2e — the per-row source/target
// picker that appears when feat → main merge surfaces a conflict.
//
// Flow:
//   1. Seed a fn on main with description="seed".
//   2. Create feat branch (inherits the fn).
//   3. On main: PUT description="MAIN-edit". On feat: PUT description=
//      "FEAT-edit". Same identity, different version rows → conflict.
//   4. Switch to main, open branch popover, click ⇢ on feat row.
//   5. Conflict modal appears with one row + radio choice (source / target).
//   6. Pick "target" (keep main's "MAIN-edit") and Apply merge.
//   7. After reload, the fn's description is "MAIN-edit".
//
// Run from this directory:  node edit-merge-conflict.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN_NAME = 'merge-conflict-probe' + RUN_ID;
const FEAT_BRANCH = 'merge-conflict-feat' + RUN_ID;


async function cleanup(page) {
  // Delete fn on each branch; branch row last.
  try {
    await deleteFnByName(page, FN_NAME);
  } catch (_) {}
  try {
    await api(page, 'DELETE',
              '/api/branches/' + encodeURIComponent(FEAT_BRANCH));
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


async function putDescriptionOn(page, fnId, branch, desc) {
  return page.evaluate(async ({id, br, d}) => {
    const body = new URLSearchParams();
    body.set('description', d);
    const r = await window.authFetch('/api/entities/fn/' + id, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'X-Graphden-Branch': br,
      },
      body: body.toString(),
    });
    return {status: r.status, body: await r.text()};
  }, {id: fnId, br: branch, d: desc});
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
  console.log('edit-merge-conflict — modal renders + per-row picker + Apply merge');

  try {
    await cleanup(page);
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});

    // ===================================================================
    // Phase A: seed a fn on main with description="seed".
    // ===================================================================
    const mainEnts = await getEntities(page);
    const identity = mainEnts.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity parent resolved');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + FN_NAME + '&parent-ids=' + identity.id
              + '&description=seed');
    const created = (await getEntities(page)).fns.find(
      (f) => f.name === FN_NAME);
    assert(created, 'seed fn created on main: ' + created?.id);
    const fnId = created.id;

    // ===================================================================
    // Phase B: create feat branch (inherits the fn), then put a
    // diverging description on main and on feat.
    // ===================================================================
    const branchResp = await api(page, 'POST', '/api/branches',
                                 {name: FEAT_BRANCH});
    assert(branchResp?.ok,
           'feat branch created: '
           + JSON.stringify(branchResp).slice(0, 200));

    const mainPut = await putDescriptionOn(page, fnId, 'main', 'MAIN-edit');
    assert(mainPut.status === 200,
           'PUT description on main: ' + JSON.stringify(mainPut).slice(0, 200));
    const featPut = await putDescriptionOn(page, fnId, FEAT_BRANCH, 'FEAT-edit');
    assert(featPut.status === 200,
           'PUT description on feat: ' + JSON.stringify(featPut).slice(0, 200));

    // ===================================================================
    // Phase C: from main, open branch popover + click ⇢ on feat row.
    // The conflict modal should appear.
    // ===================================================================
    const opened = await openBranchPopover(page);
    assert(opened, 'branch popover opens');

    await page.click(
      '.branch-row[data-branch-name="' + FEAT_BRANCH + '"] .branch-row-merge');
    await page.waitForSelector('.merge-conflicts-modal:not(.hidden)',
                               {timeout: 10000});

    const conflictState = await page.evaluate(() => {
      const m = document.querySelector('.merge-conflicts-modal');
      const rows = Array.from(m.querySelectorAll('.merge-conflict-row'));
      return {
        modalVisible: !m.classList.contains('hidden'),
        rowCount: rows.length,
        firstRowEntityName: rows[0]
          ?.querySelector('.merge-conflict-entity')?.textContent,
        firstRowSourceLabel: rows[0]
          ?.querySelector('input[value="source"]')
          ?.parentElement?.textContent,
        firstRowTargetLabel: rows[0]
          ?.querySelector('input[value="target"]')
          ?.parentElement?.textContent,
        sourceChecked: !!rows[0]
          ?.querySelector('input[value="source"]')?.checked,
        applyBtn: !!m.querySelector('#merge-conflicts-submit'),
        cancelBtn: !!m.querySelector('#merge-conflicts-cancel'),
      };
    });
    assert(conflictState.modalVisible, 'conflict modal visible');
    assert(conflictState.rowCount >= 1,
           'at least one conflict row: ' + conflictState.rowCount);
    assert(conflictState.firstRowEntityName === 'fn',
           'first conflict row is :fn entity: '
           + JSON.stringify(conflictState.firstRowEntityName));
    assert(/FEAT-edit/.test(conflictState.firstRowSourceLabel),
           'source label shows feat description: '
           + JSON.stringify(conflictState.firstRowSourceLabel).slice(0, 200));
    assert(/MAIN-edit/.test(conflictState.firstRowTargetLabel),
           'target label shows main description: '
           + JSON.stringify(conflictState.firstRowTargetLabel).slice(0, 200));
    assert(conflictState.sourceChecked,
           'source pre-selected by default');
    assert(conflictState.applyBtn, 'Apply merge button present');
    assert(conflictState.cancelBtn, 'Cancel button present');

    // ===================================================================
    // Phase D: pick "target" for each row → Apply merge → page reloads.
    // After reload, the fn's description should be "MAIN-edit"
    // (target won).
    // ===================================================================
    await page.evaluate(() => {
      document.querySelectorAll(
        '.merge-conflict-row input[value="target"]')
        .forEach((r) => { r.checked = true;
                          r.dispatchEvent(new Event('change', {bubbles: true})); });
    });

    // Apply triggers location.reload() on success — wait for the
    // navigation explicitly.
    await Promise.all([
      page.waitForNavigation({timeout: 15000}).catch(() => {}),
      page.evaluate(() => {
        document.querySelector('#merge-conflicts-submit')?.click();
      }),
    ]);
    // The reload re-mounts the editor; wait for the branch chip to
    // come back as a robust "page ready" marker. After a successful
    // merge the per-ctx graph-cache is invalidated, so the FIRST
    // post-reload `/api/graph/entities` fetch rebuilds from raw
    // storage. Under e2e suite load that rebuild + JS bundle parse
    // can exceed the 15s budget; bump to 30s.
    await page.waitForSelector('#branch-chip-btn', {timeout: 30000});
    // Wait for graphData to repopulate after the reload's initGraph,
    // so the subsequent direct fetch reads through a warm cache.
    await page.waitForFunction((id) => {
      const fns = (typeof graphData !== 'undefined' && graphData?.fns) || [];
      return fns.some(f => f.id === id);
    }, fnId, {timeout: 5000, polling: 100});

    const finalDescription = await page.evaluate(async (id) => {
      const r = await window.authFetch('/api/graph/entities');
      const ents = await r.json();
      return (ents.fns || []).find((f) => f.id === id)?.description;
    }, fnId);
    assert(finalDescription === 'MAIN-edit',
           'after merge with target picked, fn description = "MAIN-edit": '
           + JSON.stringify(finalDescription));

    console.log('✓ merge-conflict modal + per-row picker + Apply verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
