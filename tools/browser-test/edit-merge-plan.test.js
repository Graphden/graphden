// A stacked branch (S.base = R, R edited) merged into main is refused
// with the ORDERED remedy: the modal lists "R" as step 1 with its own
// button, and that button runs the ordinary merge of R. Server side
// (the plan in the 409 body) is pinned by branches_graph_test; this is
// the click path.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName,
       openBranchPopover} = require('./edit-test-helpers');

const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN_NAME = 'merge-plan-probe' + RUN_ID;
// The merge-all stack edits THREE fns — one per branch — so each branch's
// change is inherited (not superseded) below it and the plan has two steps.
const FN2_NAME = 'merge-all-probe2' + RUN_ID;
const FN3_NAME = 'merge-all-probe3' + RUN_ID;
const R_BRANCH = 'merge-plan-r' + RUN_ID;
const S_BRANCH = 'merge-plan-s' + RUN_ID;
// Second scenario — a 3-deep stack merged with "Merge all in order".
const A_BRANCH = 'merge-all-a' + RUN_ID;
const B_BRANCH = 'merge-all-b' + RUN_ID;
const C_BRANCH = 'merge-all-c' + RUN_ID;
const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

async function cleanup(page) {
  for (const n of [FN_NAME, FN2_NAME, FN3_NAME]) {
    try { await deleteFnByName(page, n); } catch (_) {}
  }
  for (const b of [S_BRANCH, R_BRANCH, C_BRANCH, B_BRANCH, A_BRANCH]) {
    try { await api(page, 'DELETE', '/api/branches/' + encodeURIComponent(b)); } catch (_) {}
  }
}

async function putDescriptionOn(page, fnId, branch, desc) {
  return page.evaluate(async ({id, br, d}) => {
    const body = new URLSearchParams();
    body.set('description', d);
    const r = await window.authFetch('/api/entities/fn/' + id, {
      method: 'PUT',
      headers: {'Content-Type': 'application/x-www-form-urlencoded', 'X-Graphden-Branch': br},
      body: body.toString(),
    });
    return {status: r.status, body: await r.text()};
  }, {id: fnId, br: branch, d: desc});
}

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => d.accept());
  try {
    console.log('edit-merge-plan — the inherited-content refusal offers "merge R first"');
    await cleanup(page);
    await page.goto(BASE + '/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});

    const identity = (await getEntities(page, 'identity')).fns.find((f) => f.name === 'identity');
    assert(identity, ':identity parent resolved');
    await api(page, 'POST', '/api/entities/fn', 'name=' + FN_NAME + '&parent-ids=' + identity.id + '&description=seed');
    const fnId = (await getEntities(page, FN_NAME)).fns.find((f) => f.name === FN_NAME)?.id;
    assert(fnId, 'seed fn created on main');

    // R off main with an edit; S stacked on R with nothing of its own.
    assert((await api(page, 'POST', '/api/branches', {name: R_BRANCH}))?.ok, 'R created');
    const rPut = await putDescriptionOn(page, fnId, R_BRANCH, 'R-edit');
    assert(rPut.status === 200, 'edit on R: ' + rPut.body.slice(0, 120));
    assert((await api(page, 'POST', '/api/branches', {name: S_BRANCH, 'base-branch-id': R_BRANCH}))?.ok, 'S stacked on R');

    // From main, merge S → the plan modal names R.
    await page.reload();
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});
    assert(await openBranchPopover(page), 'branch popover opens');
    await page.click('.branch-row[data-branch-name="' + S_BRANCH + '"] .branch-row-merge');
    await page.waitForSelector('.merge-conflicts-modal:not(.hidden) .merge-plan-step', {timeout: 15000});
    const plan = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.merge-plan-step')).map((r) => r.getAttribute('data-plan-name')));
    assert(plan.length === 1 && plan[0] === R_BRANCH, 'the plan is exactly [R] (got ' + JSON.stringify(plan) + ')');

    // Step 1's button merges R into main (the confirm is auto-accepted); the
    // success path reloads the page.
    await Promise.all([
      page.waitForNavigation({waitUntil: 'load', timeout: 30000}).catch(() => null),
      page.click('.merge-plan-step-btn'),
    ]);
    await page.waitForSelector('#branch-chip-btn', {timeout: 15000});
    const onMain = (await getEntities(page, FN_NAME)).fns.find((f) => f.name === FN_NAME);
    assert(onMain?.description === 'R-edit', 'R landed on main via the plan step (got ' + onMain?.description + ')');

    // --- "Merge all in order": A ← B ← C, each editing its OWN fn (an
    // edit of the same fn on C would supersede A's and B's and nothing
    // would be inherited); from main, merge C → the plan is [A, B]; one
    // click lands all three.
    await api(page, 'POST', '/api/entities/fn', 'name=' + FN2_NAME + '&parent-ids=' + identity.id + '&description=seed');
    await api(page, 'POST', '/api/entities/fn', 'name=' + FN3_NAME + '&parent-ids=' + identity.id + '&description=seed');
    const fn2Id = (await getEntities(page, FN2_NAME)).fns.find((f) => f.name === FN2_NAME)?.id;
    const fn3Id = (await getEntities(page, FN3_NAME)).fns.find((f) => f.name === FN3_NAME)?.id;
    assert(fn2Id && fn3Id, 'two more seed fns on main');
    assert((await api(page, 'POST', '/api/branches', {name: A_BRANCH}))?.ok, 'A created');
    assert((await putDescriptionOn(page, fnId, A_BRANCH, 'A-edit')).status === 200, 'edit on A');
    assert((await api(page, 'POST', '/api/branches', {name: B_BRANCH, 'base-branch-id': A_BRANCH}))?.ok, 'B stacked on A');
    assert((await putDescriptionOn(page, fn2Id, B_BRANCH, 'B-edit')).status === 200, 'edit on B');
    assert((await api(page, 'POST', '/api/branches', {name: C_BRANCH, 'base-branch-id': B_BRANCH}))?.ok, 'C stacked on B');
    assert((await putDescriptionOn(page, fn3Id, C_BRANCH, 'C-edit')).status === 200, 'edit on C');
    await page.reload();
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});
    assert(await openBranchPopover(page), 'branch popover opens again');
    await page.click('.branch-row[data-branch-name="' + C_BRANCH + '"] .branch-row-merge');
    await page.waitForSelector('.merge-conflicts-modal:not(.hidden) #merge-plan-all', {timeout: 15000});
    const plan2 = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.merge-plan-step')).map((r) => r.getAttribute('data-plan-name')));
    assert(plan2.length === 2 && plan2[0] === A_BRANCH && plan2[1] === B_BRANCH,
           'the plan is [A, B] in order (got ' + JSON.stringify(plan2) + ')');
    await Promise.all([
      page.waitForNavigation({waitUntil: 'load', timeout: 60000}).catch(() => null),
      page.click('#merge-plan-all'),
    ]);
    await page.waitForSelector('#branch-chip-btn', {timeout: 15000});
    const descs = {};
    for (const [n, id] of [[FN_NAME, fnId], [FN2_NAME, fn2Id], [FN3_NAME, fn3Id]]) {
      descs[n] = (await getEntities(page, n)).fns.find((f) => f.id === id)?.description;
    }
    assert(descs[FN_NAME] === 'A-edit' && descs[FN2_NAME] === 'B-edit' && descs[FN3_NAME] === 'C-edit',
           'A, B and C all landed on main in order (got ' + JSON.stringify(descs) + ')');
    console.log('  PASS');
  } catch (e) {
    console.log('FAIL: ' + (e && e.stack || e));
    process.exitCode = 1;
  } finally {
    try { await cleanup(page); } catch (_) {}
    await browser.close();
  }
})();
