// Merge-conflict resolution modal e2e — the per-row source/target
// picker that appears when a feat → target merge surfaces a conflict.
//
// Flow:
//   1. Seed a fn on main with description="seed".
//   2. Create a throwaway TARGET branch and a feat branch, both off main
//      (siblings — both inherit the fn).
//   3. On target: PUT description="TARGET-edit". On feat: PUT description=
//      "FEAT-edit". Same fn, different version rows → conflict.
//   4. Switch to target, open branch popover, click ⇢ on feat row.
//   5. Conflict modal appears with one row + radio choice (source / target).
//   6. Pick "target" (keep "TARGET-edit") and Apply merge.
//   7. After reload, the fn's description on target is "TARGET-edit".
//
// The target is a throwaway sibling, not main, so cleanup can undo the
// merge: a merged feat is undeletable while its target lives (merge is
// by-reference), and main always lives — merging into main leaked feat
// into every later file's branch list. Deleting the target first frees it.
//
// Run from this directory:  node edit-merge-conflict.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName,
       deleteBranches, openBranchPopover} = require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN_NAME = 'merge-conflict-probe' + RUN_ID;
const FEAT_BRANCH = 'merge-conflict-feat' + RUN_ID;
const TARGET_BRANCH = 'merge-conflict-tgt' + RUN_ID;
const BASE_URL = process.env.GRAPHDEN_URL || 'http://localhost:9002';


async function cleanup(page) {
  // The merge target goes first — then feat is no longer a live merge
  // source — and the seed fn on main last.
  await deleteBranches([TARGET_BRANCH, FEAT_BRANCH]);
  try {
    await deleteFnByName(page, FN_NAME);
  } catch (e) {
    process.stderr.write('  ! cleanup: ' + e.message + '\n');
  }
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
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 200));
    d.accept();
  });
  console.log('edit-merge-conflict — modal renders + per-row picker + Apply merge');

  try {
    await cleanup(page);
    await page.goto(BASE_URL + '/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});

    // ===================================================================
    // Phase A: seed a fn on main with description="seed".
    // ===================================================================
    const mainEnts = await getEntities(page, 'const');
    const constFn = mainEnts.fns.find((f) => f.name === 'const');
    assert(constFn, ':const parent resolved');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + FN_NAME + '&parent-ids=' + constFn.id
              + '&description=seed');
    const created = (await getEntities(page, FN_NAME)).fns.find(
      (f) => f.name === FN_NAME);
    assert(created, 'seed fn created on main: ' + created?.id);
    const fnId = created.id;

    // ===================================================================
    // Phase B: create the target + feat branches (both inherit the fn),
    // then put a diverging description on each.
    // ===================================================================
    for (const name of [TARGET_BRANCH, FEAT_BRANCH]) {
      const branchResp = await api(page, 'POST', '/api/branches', {name});
      assert(branchResp?.ok,
             name + ' branch created: '
             + JSON.stringify(branchResp).slice(0, 200));
    }

    const tgtPut = await putDescriptionOn(page, fnId, TARGET_BRANCH, 'TARGET-edit');
    assert(tgtPut.status === 200,
           'PUT description on target: ' + JSON.stringify(tgtPut).slice(0, 200));
    const featPut = await putDescriptionOn(page, fnId, FEAT_BRANCH, 'FEAT-edit');
    assert(featPut.status === 200,
           'PUT description on feat: ' + JSON.stringify(featPut).slice(0, 200));

    // ===================================================================
    // Phase C: from the target, open branch popover + click ⇢ on feat
    // row. The conflict modal should appear.
    // ===================================================================
    await page.goto(BASE_URL + '/?branch=' + encodeURIComponent(TARGET_BRANCH));
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});
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
    assert(/TARGET-edit/.test(conflictState.firstRowTargetLabel),
           'target label shows the target\'s description: '
           + JSON.stringify(conflictState.firstRowTargetLabel).slice(0, 200));
    assert(conflictState.sourceChecked,
           'source pre-selected by default');
    assert(conflictState.applyBtn, 'Apply merge button present');
    assert(conflictState.cancelBtn, 'Cancel button present');

    // ===================================================================
    // Phase D: pick "target" for each row → Apply merge → page reloads.
    // After reload, the fn's description should be "TARGET-edit"
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
    // Wait for the reload's initGraph to finish before the direct fetch.
    // The sidebar loads lazily now (?scope=tree), so `graphData.fns` is
    // NOT the whole graph after init — it's empty until a fn is selected.
    // Gate on the namespace tree being populated instead (same post-merge
    // rebuild: cache invalidated → the first `/api/graph/entities` call
    // rebuilds from raw storage, so keep the 30s budget).
    await page.waitForFunction(() => {
      return typeof graphData !== 'undefined'
        && Array.isArray(graphData?.namespaces)
        && graphData.namespaces.length > 0;
    }, null, {timeout: 30000, polling: 100});

    const finalDescription = await page.evaluate(async ({id, branch}) => {
      const r = await window.authFetch('/api/graph/entities',
                                       {headers: {'X-Graphden-Branch': branch}});
      const ents = await r.json();
      return (ents.fns || []).find((f) => f.id === id)?.description;
    }, {id: fnId, branch: TARGET_BRANCH});
    assert(finalDescription === 'TARGET-edit',
           'after merge with target picked, fn description = "TARGET-edit": '
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
