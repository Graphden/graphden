'use strict';
const {assert, api, BASE} = require('./edit-test-helpers');
const {waitTourTitle, clickTourButton, finishAndDelete} = require('./tutorial-tour-helpers');

async function settings(page) {
  if (!await page.locator('.auth-menu-item[data-item="Settings"]').isVisible()) {
    await page.locator('.auth-avatar:visible, #auth-lock-btn:visible').first().click();
  }
  await page.locator('.auth-menu-item[data-item="Settings"]').click();
  await page.locator('#gd-ui-components-root').waitFor({state: 'visible'});
}

async function openGroup(page, root) {
  await settings(page);
  await page.locator('[data-ui-graph="' + root + '"]').click();
}

async function selectCreatedLeaf(page, manifest, group, name) {
  const namespace = manifest.namespaces.find(row => row.name === group);
  const fn = manifest.functions.find(row => row.name === name && row['namespace-id'] === namespace.id);
  assert(fn, 'the named value belongs to the exact created group');
  await page.locator('input[placeholder="Filter..."]').fill(name);
  // Search pins exact matches above the namespace tree, so the same UUID
  // legitimately has two rows. Click its first visible presentation.
  await page.locator('#entity-list .entity-item[data-fn-id="' + fn.id + '"]:visible .name').first().click();
  await page.waitForFunction(id => selectedFnId === id, fn.id);
  const card = page.locator('.node-overlay[data-fn-name="' + name + '"]');
  await card.locator('.ancestor-line').first().click();
  return fn.id;
}

async function editOwnValue(page, fnId, slot, value, position) {
  // Read the actual graph→DOM association; the mutation itself uses the
  // visible typed form, never an API write or synthetic click.
  await page.waitForFunction(({id, slot, position}) => [...document.querySelectorAll('.arg-value-editable')].some(el => {
    const node = gv.node(el.closest('.node-overlay').dataset.nodeId);
    const arg = node && argRowFromNode(node.data());
    return arg?.['fn-id'] === id && arg.name === slot && (position === undefined || arg.position === position);
  }), {id: fnId, slot, position});
  const selector = await page.evaluate(({id, slot, position}) => {
    const el = [...document.querySelectorAll('.arg-value-editable')].find(el => {
      const arg = argRowFromNode(gv.node(el.closest('.node-overlay').dataset.nodeId).data());
      return arg?.['fn-id'] === id && arg.name === slot && (position === undefined || arg.position === position);
    });
    return '.node-overlay[data-node-id="' + CSS.escape(el.closest('.node-overlay').dataset.nodeId) + '"] .arg-value-editable';
  }, {id: fnId, slot, position});
  await page.locator(selector).click();
  const popover = page.locator('.arg-value-edit-popover');
  const input = popover.locator('[data-form-field], .arg-value-edit-input').first();
  if (value.startsWith('#')) assert(await popover.locator('input[type="color"]').count() === 1,
    'the copied canonical color type retains its native color widget');
  await input.fill(value);
  await popover.getByRole('button', {name: 'Save', exact: true}).click();
  await popover.waitFor({state: 'detached'});
}

async function walkUIComponentsLesson(page) {
  await page.goto(BASE + '/?tutorial=25');
  await waitTourTitle(page, 'Personal graphs, real editor components', 150000);
  assert(await clickTourButton(page, 'Next'), 'lesson 25 starts');
  await waitTourTitle(page, 'Open UI graphs');
  await settings(page);
  await waitTourTitle(page, 'Create personal graphs');
  const previous = await page.evaluate(() => ({components: gdPrefRead('components'), theme: gdPrefRead('theme'), branch: getCurrentBranchName()}));
  const preferencePath = await page.evaluate(() => new URL(API.api_prefs_key('components'), location.href).pathname);
  const matchesSelectionWrite = url => url.pathname === preferencePath;
  let selectionWaitedForPersistence = false;
  const holdSelectionWrite = async route => {
    if (route.request().method() !== 'PUT') return route.continue();
    try {
      // Delay the real write, without synthesizing its response. The first
      // plan must not race the server's authoritative preference lookup.
      await page.waitForTimeout(300);
      selectionWaitedForPersistence = await page.evaluate(value =>
        JSON.stringify(gdPrefRead('components')) === JSON.stringify(value), previous.components);
    } finally { await route.continue(); }
  };
  await page.route(matchesSelectionWrite, holdSelectionWrite);
  await page.locator('#gd-ui-components-create').click();
  const picker = page.locator('.fn-picker-popover[aria-label="Pick a namespace"]');
  // A cloud runner supplies the full authorized path. Resolve its actual
  // UUID from the editor's namespace index and refuse ambiguous labels.
  const destinationPath = process.env.GRAPHDEN_COMPONENT_NAMESPACE || '(root)';
  const destinationIds = await page.evaluate(path => path === '(root)' ? [null]
    : [...lookups.nsPathMap].filter(([, value]) => value === path).map(([id]) => id), destinationPath);
  assert(destinationIds.length === 1, 'the exact destination path identifies one loaded namespace');
  const destinationId = destinationIds[0];
  await picker.getByRole('combobox', {name: 'Filter namespaces', exact: true}).fill(destinationPath);
  const option = picker.getByRole('option', {name: destinationPath, exact: true});
  assert(await option.count() === 1, 'the picker offers exactly the intended full namespace path');
  const previewPath = await page.evaluate(() => new URL(API.api_ui_components_create_preview, location.href).pathname);
  const previewRequest = page.waitForRequest(request => request.method() === 'POST'
    && new URL(request.url()).pathname === previewPath);
  await option.click();
  assert((await previewRequest).postDataJSON()['namespace-id'] === destinationId,
    'the visible picker sends the intended namespace UUID to the real preview');
  try { await waitTourTitle(page, 'Open the theme group', 150000); }
  finally { await page.unroute(matchesSelectionWrite, holdSelectionWrite); }
  assert(selectionWaitedForPersistence, 'the personal selection waits for the real preference write before loading plans');
  const receipt = await page.evaluate(() => _tourState.uiComponentManifests.at(-1));
  const manifest = receipt.manifest;
  assert(receipt.request['namespace-id'] === destinationId,
    'the authoritative creation request retains the selected namespace UUID');
  assert(manifest.namespaces.find(row => row.id === receipt.request['root-id'])?.['parent-id'] === destinationId,
    'the created manifest root belongs to the intended namespace UUID');
  await page.locator('[data-ui-graph="theme-id"]').click();
  await waitTourTitle(page, 'Change a shared color');
  const canvas = await selectCreatedLeaf(page, manifest, 'theme', 'theme-canvas-color');
  await editOwnValue(page, canvas, 'value', '#fff7ed');
  await waitTourTitle(page, 'Open the menu view', 60000);
  await openGroup(page, 'menu-id');
  await waitTourTitle(page, 'A local menu value');
  const hover = await selectCreatedLeaf(page, manifest, 'menu', 'account-menu-hover');
  await editOwnValue(page, hover, 'value', '#fed7aa');
  await page.locator('.auth-avatar:visible, #auth-lock-btn:visible').first().click();
  await waitTourTitle(page, 'Open the menu behavior', 60000);
  await openGroup(page, 'menu-update-id');
  await waitTourTitle(page, 'Change a keyboard decision');
  const keymap = await selectCreatedLeaf(page, manifest, 'menu', 'account-menu-key-map');
  await editOwnValue(page, keymap, 'vals', 'last', 2);
  await page.locator('.auth-avatar:visible, #auth-lock-btn:visible').first().click();
  await page.locator('.auth-menu [role="menuitem"]').first().focus();
  await page.keyboard.press('Home');
  await waitTourTitle(page, 'Open the picker view', 60000);
  await openGroup(page, 'picker-id');
  await waitTourTitle(page, 'The boundary and cleanup');
  await page.screenshot({path: '/tmp/graphden-personal-ui-components.png'});
  await finishAndDelete(page);
  const after = await page.evaluate(() => ({components: gdPrefRead('components'), theme: gdPrefRead('theme'), branch: getCurrentBranchName()}));
  assert(JSON.stringify(after) === JSON.stringify(previous), 'cleanup restores previous Appearance selections and keeps the current branch');
  const rows = await api(page, 'GET', '/api/graph/entities?scope=tree');
  for (const namespace of manifest.namespaces) {
    assert(!rows.namespaces.some(row => row.id === namespace.id), 'the exact created namespace is gone');
  }
  return manifest;
}
module.exports = {walkUIComponentsLesson, selectCreatedLeaf};
