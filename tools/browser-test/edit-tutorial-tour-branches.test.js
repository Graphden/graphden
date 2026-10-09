// Lesson 23: existing fn edits, additions, sibling merge/remerge, exact cleanup.
// Own file keeps the real reload/merge sequence within the runner's file cap.
const {chromium} = require('playwright');
const {assert, newContext, api, openBranchPopover} = require('./edit-test-helpers');
const {
  waitTourTitle, clickTourButton, filterAndSelect, extendViaRowActions,
  bindFirstPlaceholder, editBoundValue, createBranchViaChip, switchBranchViaChip,
  compareBranchViaChip, setBranchLocalViaStrip,
  setFnDescription, tourWhere, cleanupRecordedTutorialBranches,
} = require('./tutorial-tour-helpers');

async function ownedBranchRow(page, branch) {
  await openBranchPopover(page);
  const row = page.locator('.branch-row[data-branch-id="' + branch.id + '"]');
  await row.waitFor({state: 'visible', timeout: 30000});
  assert(await row.getAttribute('data-branch-name') === branch.name, 'branch control retains its exact owned UUID/name');
  return row;
}

async function mergeOwnedSource(page, source, target) {
  assert(new URL(page.url()).searchParams.get('branch') === target.name, 'merge stays on the owned sibling target');
  const row = await ownedBranchRow(page, source);
  const [, receipt] = await Promise.all([
    page.waitForNavigation({waitUntil: 'load', timeout: 60000}),
    page.waitForResponse(r => r.request().method() === 'POST'
      && new URL(r.url()).pathname === '/api/branches/' + target.id + '/merge', {timeout: 60000})
      // The editor consumes JSON and navigates immediately; CDP can lose the
      // response body even inside this callback. Verify stored target content
      // below after navigation instead of reading that body a second time.
      .then(response => ({ok: response.ok(), status: response.status()})),
    row.locator('.branch-row-merge').click(),
  ]);
  assert(receipt.ok && receipt.status === 200, 'exact sibling target merge returned 200');
}

async function switchToOwnedBranch(page, branch) {
  const row = await ownedBranchRow(page, branch);
  await Promise.all([
    page.waitForURL(url => url.searchParams.get('branch') === branch.name,
      {waitUntil: 'load', timeout: 60000}),
    row.locator('.branch-row-name').click(),
  ]);
}

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (dialog) => { dialog.accept().catch(() => {}); });
  let failed = false;
  const owned = [];
  try {
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // Seed the description's dark-theme regression before the tutorial
    // sheet exists, so Settings remains reachable by an ordinary click.
    await page.goto(BASE + '/');
    await page.waitForSelector('#search-input', {state: 'attached'});
    if (!await page.evaluate(() => document.body.classList.contains('theme-dark'))) {
      await page.evaluate(() => gdShellSurface('settings'));
      await page.locator('#gd-set-theme').click();
      await page.waitForFunction(() => document.body.classList.contains('theme-dark'));
      await page.evaluate(() => gdShellSurface('build'));
    }
    await page.goto(BASE + '/?tutorial=23');
    await waitTourTitle(page, 'Branches are views, not copies', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 23 Next');
    await waitTourTitle(page, 'Create the common sandbox');
    await createBranchViaChip(page, 'tutorial-sandbox');
    await waitTourTitle(page, 'Find str-upper', 150000);
    await filterAndSelect(page, 'str-upper', 'str-upper');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'branch-demo', 'str-upper');
    await waitTourTitle(page, 'Give it a value on the sandbox', 150000);
    await bindFirstPlaceholder(page, 'base version');
    await waitTourTitle(page, 'Pin a fn to its branch', 150000);
    await setBranchLocalViaStrip(page, 'branch-demo', true);
    await waitTourTitle(page, 'Make this demo mergeable', 150000);
    await setBranchLocalViaStrip(page, 'branch-demo', false);
    await waitTourTitle(page, 'Fork a branch', 150000);
    await createBranchViaChip(page, 'tutorial-branch');
    await waitTourTitle(page, 'Change the value here', 150000);
    await editBoundValue(page, 'branch version');
    await waitTourTitle(page, 'Describe the changed function', 150000);
    const fnId = await setFnDescription(page, 'branch-demo', 'source draft');
    await waitTourTitle(page, 'Add a child on the source', 150000);
    await extendViaRowActions(page, 'branch-added', 'branch-demo');
    await waitTourTitle(page, 'Go back to the sandbox', 150000);
    const addedId = await page.evaluate(() => _tourState.created.find(
      row => row.type === 'fn' && row.name === 'branch-added')?.id);
    assert(addedId, 'source child has an exact created UUID receipt');
    await switchBranchViaChip(page, 'tutorial-sandbox');
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, 'Fork the sibling target', 150000);
    await createBranchViaChip(page, 'tutorial-merge-target');
    await waitTourTitle(page, 'Compare the branches', 150000);
    const branchReceipts = await page.evaluate(() => _tourState.created.filter(row => row.type === 'branch'));
    const sourceBranch = branchReceipts.find(row => row.name === 'tutorial-branch');
    const targetBranch = branchReceipts.find(row => row.name === 'tutorial-merge-target');
    assert(sourceBranch?.id && targetBranch?.id && sourceBranch['base-branch-id']
      && sourceBranch['base-branch-id'] === targetBranch['base-branch-id'], 'owned source and target share the exact base UUID');
    const targetHeaders = {'X-Graphden-Branch': targetBranch.id};
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await compareBranchViaChip(page, 'tutorial-branch');
    await waitTourTitle(page, 'Read it, then exit', 150000);
    const changes = await page.locator('#gd-diff-insp').innerText();
    assert(changes.includes('source draft') && changes.includes('branch version')
      && changes.includes('base version'), 'same visible UUID shows its field and binding replacement');
    // Source-only ghosts deliberately do not participate in text filtering.
    // Read the exact child's comparison row without switching to its branch.
    await page.fill('#search-input', '');
    const rootGroup = page.locator('.ns-header-pseudo');
    if (await rootGroup.getAttribute('aria-expanded') !== 'true') await rootGroup.click();
    const ghost = page.locator('.gd-diff-ghost[data-ghost-fn-id="' + addedId + '"]');
    await ghost.waitFor({state: 'visible', timeout: 30000});
    assert(await page.evaluate(id => _gdDiffMode?.byFnId.get(id)?.__kind === 'missing', addedId),
      'exact source child is absent on the target, separately from the common UUID edit');
    await page.locator('.gd-diff-chip-off').click();
    await page.waitForSelector('#gd-diff-chip', {state: 'detached', timeout: 15000});
    await waitTourTitle(page, 'Merge into the sibling target', 150000);
    await mergeOwnedSource(page, sourceBranch, targetBranch);
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, 'Return to the source', 150000);
    const merged = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + fnId, undefined, targetHeaders);
    assert(merged.fns.some(fn => fn.id === fnId && fn.description === 'source draft'),
      'merge transfers fields on the common-base identity');
    const added = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + addedId, undefined, targetHeaders);
    assert(added.fns.some(fn => fn.id === addedId && fn['parent-ids']?.includes(fnId)),
      'merge transfers the exact source child and its common-base parent');
    await switchToOwnedBranch(page, sourceBranch);
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, 'Make a later edit', 150000);
    // Exact argument overlays are siblings of their function card.
    const valueHandle = await page.waitForFunction(({id, branch}) => {
      if (selectedFnId !== id || getCurrentBranchName() !== branch.name) return null;
      return [...document.querySelectorAll('.arg-value-editable')].find(element => {
        const overlay = element.closest('.node-overlay');
        const node = graph.nodes.get(overlay?.dataset.nodeId);
        return argRowFromNode(node?.data)?.['fn-id'] === id;
      });
    }, {id: fnId, branch: sourceBranch}, {timeout: 45000});
    await valueHandle.asElement().click();
    const editor = page.locator('.arg-value-edit-popover').last();
    await editor.locator('[data-form-field], .arg-value-edit-input').first().fill('second branch version');
    await editor.getByRole('button', {name: 'Save', exact: true}).click();
    await editor.waitFor({state: 'detached', timeout: 30000});
    await waitTourTitle(page, 'Back to the merge target', 150000);
    await switchToOwnedBranch(page, targetBranch);
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, 'Merge the later edit', 150000);
    await mergeOwnedSource(page, sourceBranch, targetBranch);
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, "That's branching", 150000);
    const remerged = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + fnId, undefined, targetHeaders);
    assert(remerged.bindings.some(binding => binding['fn-id'] === fnId && binding.value === 'second branch version'),
      'remerge transfers the later literal on the exact sibling target');
    // Reload before finishing: ownership and cleanup destination must survive.
    owned.push(...await page.evaluate(() => _tourState.created.filter(item => item.type === 'branch')));
    assert(owned.length === 3 && owned.every(item => item.id && item['base-branch-id']),
      'all branch ownership comes from actual API UUID responses');
    await page.reload();
    await waitTourTitle(page, "That's branching", 150000);
    await page.getByRole('button', {name: 'Finish', exact: true}).click();
    const remove = page.locator('#gd-tour-pop .gd-tour-btn').filter({hasText: /^(Delete them|Delete branch & return)$/});
    await remove.waitFor({state: 'visible', timeout: 120000});
    await remove.click();
    await page.waitForURL(url => !url.searchParams.has('branch'), {waitUntil: 'load', timeout: 60000});
    await page.waitForSelector('#gd-tour-pop', {state: 'detached', timeout: 60000});
    assert(!new URLSearchParams(new URL(page.url()).search).get('branch'), 'cleanup returns to main');
    const result = await api(page, 'GET', '/api/branches');
    const rows = Array.isArray(result) ? result : result.branches;
    assert(owned.every(item => !rows.some(row => row.id === item.id)), 'target, source and common base were deleted');
    console.log('PASS lesson 23: replacement + addition, merge/remerge, reload and exact owned cleanup');
  } catch (error) {
    failed = true;
    console.error('FAIL:', error.message);
    try { console.error('tour at failure:', await tourWhere(page)); } catch (_) {}
  } finally {
    try { await cleanupRecordedTutorialBranches(page, owned); }
    catch (error) { failed = true; console.error('Owned cleanup failed:', error.message); }
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
