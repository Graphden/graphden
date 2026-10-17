'use strict';
const {assert, api, BASE} = require('./edit-test-helpers');
const {waitTourTitle, clickTourButton} = require('./tutorial-tour-helpers');
const {captureLoadedUIComponents, waitForLoadedUIComponents} = require('./ui-component-runtime-test-helpers');

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
  // The popover appears before its asynchronous typed form arrives.
  // Wait for the value control before checking which widget was rendered.
  await input.waitFor({state: 'visible', timeout: 60000});
  if (value.startsWith('#')) assert(await popover.locator('input[type="color"]').count() === 1,
    'the copied canonical color type retains its native color widget');
  await input.fill(value);
  await popover.getByRole('button', {name: 'Save', exact: true}).click();
  await popover.waitFor({state: 'detached'});
}

async function waitForPersonalMenuValue(page, fnId, configuration, value, token = '--gd-account-menu-hover') {
  // Saving detaches the form before graph data and its authorized personal
  // plan finish reloading. Opening earlier is closed by that reload.
  await page.waitForFunction(({fnId, configuration, value, token}) => {
    const shell = window.gdShellMenuGraph;
    if (!shell?.ready || !shell.runtime || window.gdUIComponentsRoots?.()['menu-id'] !== configuration
      || window.gdUIComponentRuntimeIdentity?.('account-menu') !== window.gdUIConfigurationState?.()?.selection?.['fn-id']
      || !(graphData.bindings || []).some(row => row['fn-id'] === fnId && row.value === value
        && lookups.slotMap.get(row['slot-id'])?.name === 'value')) return false;
    const key = window.GraphdenBrowser.keyword;
    const theme = new Map(Object.entries(window.gdGraphThemeBase()).map(([name, token]) => [key(name), token]));
    const view = shell.runtime.run('view', {state: shell.state, theme});
    return view.get(key(token === '--bg' ? 'theme-tokens' : 'menu-tokens'))?.get(token) === value;
  }, {fnId, configuration, value, token}, {timeout: 60000});
}

async function waitForPersonalMenuHome(page, fnId, configuration) {
  await page.waitForFunction(({fnId, configuration}) => {
    const shell = window.gdShellMenuGraph;
    if (!shell?.ready || !shell.runtime || window.gdUIComponentsRoots?.()['menu-id'] !== configuration
      || window.gdUIComponentRuntimeIdentity?.('account-menu') !== window.gdUIConfigurationState?.()?.selection?.['fn-id']) return false;
    const binding = (graphData.bindings || []).find(row => row['fn-id'] === fnId
      && lookups.slotMap.get(row['slot-id'])?.name === 'vals');
    if (!(graphData['list-items'] || []).some(row => row['binding-id'] === binding?.id
      && row.position === 2 && row.value === 'last')) return false;
    // Inspect the pure update result without changing mounted state. The
    // real Home key below still proves focus and active-index behavior.
    const key = window.GraphdenBrowser.keyword;
    const context = new Map([[key('items'), ['first', 'middle', 'last']]]);
    const update = (state, kind, pressed = '') => shell.runtime.run('update', {state, context,
      event: new Map([[key('kind'), kind], [key('key'), pressed], [key('index'), -1]])});
    const opened = update(shell.runtime.run('initial'), 'open');
    return update(opened, 'keydown', 'Home').get(key('active')) === 2;
  }, {fnId, configuration}, {timeout: 60000});
}

function componentCleanupTimeout(manifest) {
  // Each exact receipt row needs an ownership GET and DELETE; dependency
  // refusals may require another pass. The condition still returns early.
  return Math.max(120000, (manifest.functions.length + manifest.namespaces.length) * 10000);
}

async function reviewEditorConfigurationCleanup(page, timeout) {
  let remaining = await page.evaluate(() => (_tourState?.editorConfigurations || []).filter(row => row.receipt !== 'removed').length);
  while (remaining) {
    await waitTourTitle(page, 'Delete these tutorial UI graphs?', timeout);
    const text = await page.locator('#gd-tour-pop').textContent();
    assert(text.includes('including your edits') && text.includes('Restore'),
      'cleanup presents the current deletion and restore decision before consent');
    assert(await clickTourButton(page, 'Delete these graphs & restore'), 'confirm the reviewed exact configuration cleanup');
    await page.waitForFunction(previous => (_tourState?.editorConfigurations || [])
      .filter(row => row.receipt !== 'removed').length < previous, remaining, {timeout});
    remaining = await page.evaluate(() => (_tourState?.editorConfigurations || []).filter(row => row.receipt !== 'removed').length);
  }
}

async function bindPersonalMenuTheme(page, entry, accent, background) {
  await page.waitForFunction(entry => [...document.querySelectorAll('.placeholder-binder[data-arg-name="theme"]')]
    .some(el => el.dataset.fnName === lookups.fnMap.get(entry)?.name), entry);
  const selector = await page.evaluate(entry => {
    const el = [...document.querySelectorAll('.placeholder-binder[data-arg-name="theme"]')]
      .find(el => el.dataset.fnName === lookups.fnMap.get(entry)?.name);
    return '.placeholder-binder[data-node-id="' + CSS.escape(el.dataset.nodeId) + '"]';
  }, entry);
  const binder = page.locator(selector);
  await binder.waitFor({state: 'visible'});
  assert(await binder.evaluate((el, id) => el.dataset.fnName === lookups.fnMap.get(id)?.name, entry),
    'the free input binder belongs to the personal view root');
  await binder.click();
  await page.getByRole('button', {name: 'Bind literal', exact: true}).click();
  const form = page.locator('.arg-value-edit-popover');
  await form.locator('[data-form-field][data-field-path="accent"]').waitFor({state: 'visible', timeout: 60000});
  assert(await form.locator('input[type="color"]').count() === 2,
    'the inherited theme record retains both canonical color widgets');
  await form.locator('[data-form-field][data-field-path="accent"]').fill(accent);
  await form.locator('[data-form-field][data-field-path="canvas-background"]').fill(background);
  await form.getByRole('button', {name: 'Save', exact: true}).click();
  await form.waitFor({state: 'detached'});
}

async function walkUIComponentsLesson(page, {onReceipt = async () => {}, onStagedReceipt = async () => {}, cleanupOnly = false} = {}) {
  await page.goto(BASE + '/?tutorial=25');
  await waitTourTitle(page, 'Personal graphs, shared definitions', 150000);
  assert(await clickTourButton(page, 'Next'), 'lesson 25 starts');
  await waitTourTitle(page, 'Open UI graphs');
  await settings(page);
  await waitTourTitle(page, 'Personalize the menu');
  const previous = await page.evaluate(() => ({components: gdPrefRead('components'),
    configuration: gdUIConfigurationState().selection['fn-id'], theme: gdPrefRead('theme'),
    picker: gdUIComponentsRoots()['picker-id'], branch: getCurrentBranchName()}));
  const loadedComponents = await captureLoadedUIComponents(page);
  assert(loadedComponents.names.includes('account-menu') && loadedComponents.names.includes('tutorial-catalog'),
    'the menu and tutorial catalog use the ordinary configuration before personalization');
  const applyPath = await page.evaluate(() => new URL(API.api_ui_configuration_apply, location.href).pathname);
  const matchesApply = url => url.pathname === applyPath;
  let selectionWaitedForPersistence = false;
  const holdApply = async route => {
    if (route.request().method() !== 'POST') return route.continue();
    try {
      const staged = await page.evaluate(() => _tourState.editorConfigurations.at(-1));
      await onStagedReceipt({...staged, configurationProposal: route.request().postDataJSON()});
      selectionWaitedForPersistence = await page.evaluate(id =>
        gdUIConfigurationState()?.selection?.['fn-id'] === id, previous.configuration);
    } finally { await route.continue(); }
  };
  await page.route(matchesApply, holdApply);
  try {
    await page.locator('[data-ui-component="account-menu"]').click();
    await waitTourTitle(page, 'Open your menu view', 150000);
  } finally { await page.unroute(matchesApply, holdApply); }
  const receipt = await page.evaluate(() => _tourState.editorConfigurations.at(-1));
  const roots = await page.evaluate(() => gdUIComponentsRoots());
  const manifest = {namespaces: [], functions: receipt.original['function-receipts'], roots};
  await onReceipt({...receipt, manifest});
  assert(manifest.functions.length < 20, 'the lesson creates a small inherited component instead of a full closure copy');
  assert(roots['menu-id'] === receipt.original['entry-id'], 'the personal view is the exact previewed entry');
  assert(roots['picker-id'] === previous.picker, 'personalizing the menu retains the previous picker identity');
  assert(selectionWaitedForPersistence, 'selection publication waits for the actual APPLY commit');
  assert(await page.evaluate(theme => JSON.stringify(gdPrefRead('theme')) === JSON.stringify(theme), previous.theme),
    'personalization keeps the theme preference');
  if (cleanupOnly) {
    await page.keyboard.press('Escape');
  } else {
    await page.locator('[data-ui-graph="menu-id"]').click();
    await waitTourTitle(page, 'Capture a personal theme input');
    await bindPersonalMenuTheme(page, roots['menu-id'], '#7c3aed', '#f5f3ff');
    await waitTourTitle(page, 'Shared helpers stay shared', 60000);
    await page.locator('.auth-avatar:visible, #auth-lock-btn:visible').first().click();
    assert(await page.evaluate(() => gdThemeTokenValue('--gd-flow') === '#7c3aed'
      && gdThemeTokenValue('--bg') === '#f5f3ff'), 'the local capture changes actual editor tokens');
    assert(await clickTourButton(page, 'Next'), 'continue after the live color change');
    await waitTourTitle(page, 'Other components keep their identities');
    await openGroup(page, 'picker-id');
    await waitTourTitle(page, 'The boundary and cleanup');
    assert(await page.evaluate(id => selectedFnId === id, previous.picker), 'the picker identity remains unchanged');
    await page.screenshot({path: '/tmp/graphden-personal-ui-components.png'});
    assert(await clickTourButton(page, 'Finish'), 'Finish button');
  }
  await reviewEditorConfigurationCleanup(page, 120000);
  await page.waitForFunction(() => !document.querySelector('#gd-tour-pop')
    || document.querySelector('#gd-tour-title')?.textContent === 'Lesson 25 finished',
  null, {timeout: 120000});
  if (await page.locator('#gd-tour-title').count()
    && await page.locator('#gd-tour-title').textContent() === 'Lesson 25 finished') {
    assert(await clickTourButton(page, 'Close'), 'close the finished lesson after cleanup');
  }
  await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'), null, {timeout: 120000});
  await page.waitForFunction(previous => gdUIConfigurationState()?.selection?.['fn-id'] === previous.configuration
    && gdUIComponentsRoots()?.['picker-id'] === previous.picker
    && JSON.stringify(gdPrefRead('components')) === JSON.stringify(previous.components)
    && JSON.stringify(gdPrefRead('theme')) === JSON.stringify(previous.theme)
    && getCurrentBranchName() === previous.branch,
  previous, {timeout: 60000});
  // Configuration publication can precede another component's bounded policy
  // retry. Wait for the actual previously loaded runtimes, not just the picker.
  await waitForLoadedUIComponents(page, loadedComponents);
  assert(await page.evaluate(() => gdShellMenuGraph.ready && !gdUIComponentsStatus()),
    'cleanup restores the loaded component runtimes without a terminal fallback');
  const after = await page.evaluate(() => ({components: gdPrefRead('components'),
    configuration: gdUIConfigurationState()?.selection?.['fn-id'], theme: gdPrefRead('theme'),
    picker: gdUIComponentsRoots()['picker-id'], branch: getCurrentBranchName()}));
  assert(JSON.stringify(after) === JSON.stringify(previous), 'cleanup restores the previous ordinary configuration and retains other selections');
  const rows = await api(page, 'GET', '/api/graph/entities?scope=index');
  const remaining = new Set(rows.fns.map(row => row.id));
  assert(manifest.functions.every(row => !remaining.has(row.id)), 'all exact lesson function identities are gone');
  assert(rows.namespaces.some(row => row.id === manifest.functions[0]['namespace-id']),
    'cleanup retains the personal profile namespace');
  return manifest;
}

module.exports = {componentCleanupTimeout, walkUIComponentsLesson, bindPersonalMenuTheme, selectCreatedLeaf, editOwnValue, waitForPersonalMenuValue, waitForPersonalMenuHome};
