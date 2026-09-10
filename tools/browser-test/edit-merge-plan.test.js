// A stacked branch (S.base = R, R edited) merged into main from the branch
// popover lands R's change too: the server merges R in first and the
// post-merge alert names it (`merged-first`). Server side is pinned by
// branches_graph_test + merge core_test; this is the click path, plus a
// 3-deep stack (A ← B ← C, one fn edited per branch) merged in one click.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName,
       openBranchPopover} = require('./edit-test-helpers');

const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN_NAME = 'merge-plan-probe' + RUN_ID;
// The 3-deep stack edits THREE fns — one per branch — so each branch's
// change is inherited (not superseded) below it.
const FN2_NAME = 'merge-all-probe2' + RUN_ID;
const FN3_NAME = 'merge-all-probe3' + RUN_ID;
const R_BRANCH = 'merge-plan-r' + RUN_ID;
const S_BRANCH = 'merge-plan-s' + RUN_ID;
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

// Click the popover's merge button for `branch` and wait for the reload the
// success path triggers. Dialogs (confirm + the merged-first alert) are
// accepted by the page-level handler; the alerts' text is collected.
async function mergeFromPopover(page, branch) {
  assert(await openBranchPopover(page), 'branch popover opens');
  await Promise.all([
    page.waitForNavigation({waitUntil: 'load', timeout: 60000}).catch(() => null),
    page.click('.branch-row[data-branch-name="' + branch + '"] .branch-row-merge'),
  ]);
  await page.waitForSelector('#branch-chip-btn', {timeout: 15000});
}

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const alerts = [];
  page.on('dialog', (d) => { if (d.type() === 'alert') alerts.push(d.message()); d.accept(); });
  try {
    console.log('edit-merge-plan — a stacked branch merges transitively');
    await page.goto(BASE + '/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 30000});
    const ents = await getEntities(page, 'identity');
    const identity = ents.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity parent resolved');
    await api(page, 'POST', '/api/entities/fn', 'name=' + FN_NAME + '&parent-ids=' + identity.id + '&description=seed');
    const fnId = (await getEntities(page, FN_NAME)).fns.find((f) => f.name === FN_NAME)?.id;
    assert(fnId, 'seed fn created on main');
    assert((await api(page, 'POST', '/api/branches', {name: R_BRANCH}))?.ok, 'R created');
    const rPut = await putDescriptionOn(page, fnId, R_BRANCH, 'R-edit');
    assert(rPut.status === 200, 'edit on R: ' + rPut.body.slice(0, 120));
    assert((await api(page, 'POST', '/api/branches', {name: S_BRANCH, 'base-branch-id': R_BRANCH}))?.ok, 'S stacked on R');

    // From main, merge S → R's change lands too, and the alert says R went first.
    await page.reload();
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});
    await mergeFromPopover(page, S_BRANCH);
    const onMain = (await getEntities(page, FN_NAME)).fns.find((f) => f.id === fnId);
    assert(onMain?.description === 'R-edit', 'R-edit reached main through S (got ' + onMain?.description + ')');
    assert(alerts.some((a) => a.includes('Merged ' + R_BRANCH)),
           'the post-merge alert names R as merged first: ' + JSON.stringify(alerts));

    // A ← B ← C, each editing its OWN fn; from main, merge C → all three land.
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
    alerts.length = 0;
    await mergeFromPopover(page, C_BRANCH);
    const descs = {};
    for (const [n, id] of [[FN_NAME, fnId], [FN2_NAME, fn2Id], [FN3_NAME, fn3Id]]) {
      descs[n] = (await getEntities(page, n)).fns.find((f) => f.id === id)?.description;
    }
    assert(descs[FN_NAME] === 'A-edit' && descs[FN2_NAME] === 'B-edit' && descs[FN3_NAME] === 'C-edit',
           'A, B and C all landed on main from one merge (got ' + JSON.stringify(descs) + ')');
    assert(alerts.some((a) => a.includes(A_BRANCH) && a.includes(B_BRANCH)),
           'the alert names A and B as merged first, in order: ' + JSON.stringify(alerts));
    console.log('  PASS');
  } catch (e) {
    console.log('FAIL: ' + (e && e.stack || e));
    process.exitCode = 1;
  } finally {
    try { await cleanup(page); } catch (_) {}
    await browser.close();
  }
})();
