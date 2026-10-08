// Lesson 41: ordinary publish/install/update/rollback controls and their exact
// receipts. No fixture pre-created installer namespaces or name-based purge.
const {chromium} = require('playwright');
const {assert, newContext, api, BASE} = require('./edit-test-helpers');
const {
  waitTourTitle, clickTourButton, createBranchViaChip, switchBranchViaChip,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  bindNamedPlaceholder, editBoundValue, filterAndSelect, finishAndDelete,
  tourWhere,
} = require('./tutorial-tour-helpers');

async function publish(page, version) {
  await page.locator('.ns-header[data-ns-path="tutorial-greetings"] .ns-publish-btn')
    .dispatchEvent('click');
  await page.fill('#gd-nspub-name', 'tutorial-greet');
  await page.fill('#gd-nspub-version', version);
  await page.locator('#gd-nspub-go').dispatchEvent('click');
  await waitTourTitle(page, 'Close the publish form', 120000);
  await page.getByRole('button', {name: 'Close publish form', exact: true}).click();
}

async function openPackages(page) {
  await page.locator('#gd-pkg-chip').dispatchEvent('click');
  await page.waitForSelector('[data-packages-panel]', {timeout: 30000});
}

async function closePackages(page) {
  await waitTourTitle(page, 'Close the packages panel', 120000);
  await page.getByRole('button', {name: 'Close packages panel', exact: true}).click();
}

async function movePin(page, version) {
  await openPackages(page);
  const form = page.locator('.packages-update-form')
    .filter({has: page.locator('input[name="name"][value="tutorial-greet"]')});
  await form.locator('.packages-version-input').fill(version);
  await form.locator('.packages-update-btn').dispatchEvent('click');
  await closePackages(page);
}

async function runWelcome(page) {
  await filterAndSelect(page, 'tutorial-shop.welcome', 'welcome');
  await page.locator('.node-overlay[data-fn-name="welcome"] '
    + '.ancestor-line[data-level="0"] .fn-run-trigger').dispatchEvent('click');
  await page.waitForSelector('.execute-popover.visible .execute-run-btn', {timeout: 30000});
  await page.locator('.execute-popover.visible .execute-run-btn').dispatchEvent('click');
}

async function ledger(page) {
  return page.evaluate(() => JSON.parse(localStorage.getItem('graphden.tour') || 'null')?.created || []);
}

(async () => {
  const {browser, page} = await newContext(chromium);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => { void dialog.accept(); });
  let owned = [];
  let finished = false;
  try {
    const branchRows = await api(page, 'GET', '/api/branches');
    const branches = Array.isArray(branchRows) ? branchRows : branchRows.branches;
    assert(!branches.some(row => ['tutorial-vendor', 'tutorial-site'].includes(row.name)),
      'lesson sibling names are available; do not adopt or delete existing branches');
    const initial = await api(page, 'GET', '/api/graph/entities?scope=tree');
    assert(!initial.namespaces.some(row => ['tutorial-greetings', 'tutorial-shop'].includes(row.name)),
      'lesson namespace names are available; no pre-created materialization fixture');
    const registry = await api(page, 'GET', '/api/packages');
    assert(!(Array.isArray(registry) ? registry : registry.packages || [])
      .some(row => row.name === 'tutorial-greet'), 'do not claim an existing publication');

    await page.goto(BASE + '/?branch=main');
    await page.evaluate(() => window.openTutorialMenu());
    await page.locator('[data-lesson-id="41"] .gd-tour-btn-primary').dispatchEvent('click');
    await waitTourTitle(page, 'Author and consumer', 150000);
    assert(await clickTourButton(page, 'Next'), 'start the author/consumer loop');
    await waitTourTitle(page, 'Create the author branch');
    await createBranchViaChip(page, 'tutorial-vendor');
    await waitTourTitle(page, 'Create tutorial-greetings');
    await createRootNamespace(page, 'tutorial-greetings');
    await waitTourTitle(page, 'Create greet');
    await createFnInNamespace(page, 'tutorial-greetings', 'greet');
    await waitTourTitle(page, 'Give greet its parent');
    await setParentViaStrip(page, 'const');
    await waitTourTitle(page, 'Set the source to 1');
    await bindNamedPlaceholder(page, 'value', 'literal', '1');
    await waitTourTitle(page, 'Publish 1.0.0');
    await publish(page, '1.0.0');
    await waitTourTitle(page, 'Return to main');
    await switchBranchViaChip(page, 'main');
    await waitTourTitle(page, 'Create the consumer branch');
    await createBranchViaChip(page, 'tutorial-site');
    await waitTourTitle(page, 'Install 1.0.0');
    await openPackages(page);
    await page.locator('[data-packages-panel] details').evaluate(element => { element.open = true; });
    await page.locator('[hx-post="/api/packages/panel-install?name=tutorial-greet&version=1.0.0"]')
      .dispatchEvent('click');
    await closePackages(page);
    await waitTourTitle(page, 'Create tutorial-shop');
    await createRootNamespace(page, 'tutorial-shop');
    await waitTourTitle(page, 'Create welcome');
    await createFnInNamespace(page, 'tutorial-shop', 'welcome');
    await waitTourTitle(page, 'Give welcome its parent');
    await setParentViaStrip(page, 'to-str');
    await waitTourTitle(page, 'Reference the installed graph');
    await bindNamedPlaceholder(page, 'value', 'fn-ref', 'tutorial-greetings@1-0-0.greet');
    await waitTourTitle(page, 'Run the first release');
    await runWelcome(page);
    await waitTourTitle(page, 'Back to the author');
    owned = await ledger(page);
    assert(owned.some(row => row.type === 'package-install' && row.id && row.receipt === 'created'),
      'ordinary Install saved the actual pin UUID');
    assert(owned.some(row => row.materialized && row.id && row.receipt === 'created'),
      'materialization response saved its newly created namespace UUID');

    await switchBranchViaChip(page, 'tutorial-vendor');
    await waitTourTitle(page, 'Open your source graph');
    await filterAndSelect(page, 'tutorial-greetings.greet', 'greet');
    await waitTourTitle(page, 'Set the source to 2');
    await editBoundValue(page, '2');
    await waitTourTitle(page, 'Show the source namespace');
    await page.locator('#search-clear').dispatchEvent('click');
    await waitTourTitle(page, 'Publish 1.0.1');
    await publish(page, '1.0.1');
    await waitTourTitle(page, 'Back to the consumer');
    await switchBranchViaChip(page, 'tutorial-site');
    await waitTourTitle(page, 'The old pin still returns 1');
    await runWelcome(page);
    await waitTourTitle(page, 'Update the consumer');
    await movePin(page, '1.0.1');
    await waitTourTitle(page, 'The update returns 2');
    await runWelcome(page);
    await waitTourTitle(page, 'Roll back the consumer');
    await movePin(page, '1.0.0');
    await waitTourTitle(page, 'The rollback returns 1');
    await runWelcome(page);
    await waitTourTitle(page, 'Move forward again');
    await movePin(page, '1.0.1');
    await waitTourTitle(page, 'The second update returns 2');
    await runWelcome(page);
    await waitTourTitle(page, 'Return for cleanup');
    owned = await ledger(page);
    assert(owned.filter(row => row.type === 'package-version' && row.id && row['content-hash']).length === 2,
      'both ordinary Publish replies saved distinct exact version receipts');
    assert(owned.filter(row => row.type === 'package-install').length === 1,
      'pin update and rollback preserved one actual pin identity');
    await switchBranchViaChip(page, 'main');
    await waitTourTitle(page, 'That is the version loop');
    await page.screenshot({path: '/tmp/tutorial-package-lifecycle.png'});
    await finishAndDelete(page);
    finished = true;
    const after = await api(page, 'GET', '/api/branches');
    const afterRows = Array.isArray(after) ? after : after.branches;
    assert(!afterRows.some(row => owned.some(entry => entry.type === 'branch' && entry.id === row.id)),
      'ordinary cleanup removed both exact sibling branch UUIDs');
    const afterRegistry = await api(page, 'GET', '/api/packages');
    assert(!(Array.isArray(afterRegistry) ? afterRegistry : afterRegistry.packages || [])
      .some(row => owned.some(entry => entry.type === 'package-version' && entry.id === row.id)),
      'ordinary cleanup withdrew exactly the two recorded version UUIDs');
    const afterTree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    assert(!afterTree.namespaces.some(row => owned.some(entry => entry.type === 'ns' && entry.id === row.id)),
      'ordinary cleanup removed the exact author, consumer and materialized namespaces');
    assert(errors.length === 0, 'no uncaught browser errors: ' + errors.join('; '));
    console.log('PASS: lesson 41 ordinary UI version loop and exact cleanup');
  } catch (error) {
    process.exitCode = 1;
    console.error('FAIL:', error.message);
    console.error('Tour:', await tourWhere(page).catch(() => 'unavailable'));
    await page.screenshot({path: '/tmp/tutorial-package-lifecycle-fail.png'}).catch(() => {});
  } finally {
    if (!finished) {
      // Reuse production cleanup on its saved principal/branch/UUID ledger.
      // Unknown replies remain visible in Lessons; never synthesize ownership.
      const saved = await ledger(page).catch(() => owned);
      console.error('Retained exact receipt ledger:', JSON.stringify(saved));
      await page.evaluate(async () => { if (_tourState) await _tourEnd(); }).catch(() => {});
      if (await page.getByRole('button', {name: 'Delete them', exact: true}).isVisible().catch(() => false)) {
        await page.getByRole('button', {name: 'Delete them', exact: true}).click().catch(() => {});
      }
    }
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
