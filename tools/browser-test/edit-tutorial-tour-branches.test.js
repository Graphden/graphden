// Lesson 23: existing fn edits, additions, sibling merge/remerge, exact cleanup.
// Own file keeps the real reload/merge sequence within the runner's file cap.
const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect, extendViaRowActions,
  bindFirstPlaceholder, editBoundValue, createBranchViaChip, switchBranchViaChip,
  compareBranchViaChip, exitBranchCompare, mergeBranchViaChip, setBranchLocalViaStrip,
  setFnDescription, finishAndDelete, tourWhere, cleanupRecordedTutorialBranches,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (dialog) => { dialog.accept().catch(() => {}); });
  let failed = false;
  const owned = [];
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
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
    await switchBranchViaChip(page, 'tutorial-sandbox');
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, 'Fork the sibling target', 150000);
    await createBranchViaChip(page, 'tutorial-merge-target');
    await waitTourTitle(page, 'Compare the branches', 150000);
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await compareBranchViaChip(page, 'tutorial-branch');
    await waitTourTitle(page, 'Read it, then exit', 150000);
    const changes = await page.locator('#gd-diff-insp').innerText();
    assert(changes.includes('source draft') && changes.includes('branch version')
      && changes.includes('base version'), 'same visible UUID shows its field and binding replacement');
    await filterAndSelect(page, 'branch-added', 'branch-added');
    assert(await page.locator('#gd-diff-insp .gd-diff-insp-head .branch-diff-marker.bd-added').count() === 1,
      'source child is an addition, separately from the existing fn edit');
    await exitBranchCompare(page);
    await waitTourTitle(page, 'Merge into the sibling target', 150000);
    await mergeBranchViaChip(page, 'tutorial-branch');
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, 'Return to the source', 150000);
    const merged = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + fnId);
    assert(merged.fns.some(fn => fn.id === fnId && fn.description === 'source draft'),
      'merge transfers fields on the common-base identity');
    await switchBranchViaChip(page, 'tutorial-branch');
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, 'Make a later edit', 150000);
    await editBoundValue(page, 'second branch version');
    await waitTourTitle(page, 'Back to the merge target', 150000);
    await switchBranchViaChip(page, 'tutorial-merge-target');
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, 'Merge the later edit', 150000);
    await mergeBranchViaChip(page, 'tutorial-branch');
    await filterAndSelect(page, 'branch-demo', 'branch-demo');
    await waitTourTitle(page, "That's branching", 150000);
    // Reload before finishing: ownership and cleanup destination must survive.
    owned.push(...await page.evaluate(() => _tourState.created.filter(item => item.type === 'branch')));
    assert(owned.length === 3 && owned.every(item => item.id && item['base-branch-id']),
      'all branch ownership comes from actual API UUID responses');
    await page.reload();
    await waitTourTitle(page, "That's branching", 150000);
    await finishAndDelete(page);
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
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
