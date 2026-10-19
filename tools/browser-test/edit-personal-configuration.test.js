'use strict';
const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {undoConfiguration} = require('./configuration-undo-test-helpers');
const {trackHomeSetup} = require('./configuration-home-test-helpers');
let phase = 'boot';
const fs = require('node:fs');
function saveReceipts(receipts) {
  fs.writeFileSync('/tmp/gd-personal-config-browser-receipts-' + process.pid + '.json',
    JSON.stringify(receipts), {mode: 0o600});
}

async function appearance(page) {
  if (!await page.locator('#gd-ui-components-root').isVisible()) {
    await page.locator('.auth-avatar:visible, #auth-lock-btn:visible').first().click();
    await page.locator('.auth-menu-item[data-item="Settings"]').click();
  }
  await page.locator('#gd-ui-components-root').waitFor({state: 'visible'});
}
async function personalize(page, component, receipts) {
  await appearance(page);
  const preview = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/ui/configuration/preview'));
  const applied = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/ui/configuration/apply'));
  await page.locator('[data-ui-component="' + component + '"]').click();
  const proposal = await (await preview).json();
  assert(proposal.ok && proposal.receipt.created, 'visible Personalize previews a new ordinary configuration');
  receipts.push(proposal);
  saveReceipts(receipts);
  const result = await (await applied).json();
  assert(result.ok && result.committed && result.id === proposal.receipt.id,
    'real APPLY commits the previewed ordinary root');
  await page.waitForFunction(id => gdUIConfigurationState()?.selection?.['fn-id'] === id, result.id);
  return result;
}
(async () => {
  const {browser, page} = await newContext(chromium);
  const receipts = [], pageErrors = [];
  let homeSetup;
  let originalId;
  page.on('pageerror', () => pageErrors.push(true));
  try {
    homeSetup = await trackHomeSetup(page);
    assert(await page.evaluate(() => ['plan', 'prepare', 'preview', 'apply', 'undo'].every(name =>
      typeof API['api_ui_configuration_' + name] === 'string')),
    'real generated route URLs expose the configuration host contract');
    await page.waitForFunction(() => gdPrefsReady && gdUIConfigurationState()?.revision);
    const before = await page.evaluate(() => ({configuration: gdUIConfigurationState(),
      theme: gdPrefRead('theme'), roots: gdUIComponentsRoots()}));
    originalId = before.configuration.selection['fn-id'];
    if (Object.values(before.configuration.custom || {}).some(Boolean)) {
      const reset = await api(page, 'POST', '/api/ui/configuration/preview', {operation: 'activate', command: {
        'selection-id': before.configuration.defaults, 'expected-preference': before.configuration.preference,
        'expected-configuration-revision': before.configuration.revision}});
      assert(reset.ok && reset.receipt.created, 'controlled fixture selects shared defaults without deleting existing edits');
      receipts.push(reset); saveReceipts(receipts);
      assert((await api(page, 'POST', '/api/ui/configuration/apply', reset.request)).committed, 'controlled fixture commits');
      await page.evaluate(() => gdCaptureUIConfiguration());
    }
    phase = 'picker';
    const first = await personalize(page, 'fn-picker', receipts);
    assert(first.functions.length < 20 && first.namespaces.length === 0,
      'simple customization inherits a small graph without copying its dependency closure');
    const picker = await page.evaluate(() => gdUIComponentsRoots()['picker-id']);
    assert(picker !== before.roots['picker-id'], 'picker has its own inherited view identity');
    phase = 'recents';
    const second = await personalize(page, 'recents', receipts);
    assert(second.previous.id === first.id, 'next activation preserves the previous editor graph by reference');
    assert(await page.evaluate(id => gdUIComponentsRoots()['picker-id'] === id, picker),
      'customizing another component preserves the existing picker identity');
    assert(await page.evaluate(theme => JSON.stringify(gdPrefRead('theme')) === JSON.stringify(theme), before.theme),
      'component personalization does not activate another theme');
    const state = await page.evaluate(() => gdUIConfigurationState());
    assert(state.custom['fn-picker'] && state.custom.recents, 'both writable personal components remain openable');
    phase = 'cas';
    const command = component => ({operation: 'personalize', command: {component,
      'expected-preference': state.preference, 'expected-configuration-revision': state.revision}});
    const stale = await api(page, 'POST', '/api/ui/configuration/preview', command('account-menu'));
    const newer = await api(page, 'POST', '/api/ui/configuration/preview', command('explorer'));
    assert(stale.ok && newer.ok, 'two real clients preview the same authoritative configuration');
    receipts.push(newer);
    saveReceipts(receipts);
    const changed = await api(page, 'POST', '/api/ui/configuration/apply', newer.request);
    assert(changed.ok && changed.committed, 'the newer client commits its ordinary root');
    const rejected = await api(page, 'POST', '/api/ui/configuration/apply', stale.request);
    assert(rejected.status === 409, 'the stale client cannot overwrite the committed configuration');
    const index = await api(page, 'GET', '/api/graph/entities?scope=index');
    const live = new Set(index.fns.map(row => row.id));
    assert(stale.receipt.functions.every(id => !live.has(id)), 'stale APPLY creates none of its planned identities');

  } finally {
    try {
    phase = 'undo';
    for (const proposal of [...receipts].reverse()) {
      const result = await undoConfiguration(page, proposal);
      const expected = new Set(proposal.receipt['function-receipts'].map(row => row.id));
      assert(result.ok && result.deleted.length === expected.size
        && result.deleted.every(id => expected.has(id)),
      'original receipt atomically deletes only its new graphs and restores its prior');
    }
    phase = 'defaults';
    if (receipts.length) {
      await page.evaluate(() => gdCaptureUIConfiguration());
      await page.waitForFunction(id => gdUIConfigurationState()?.selection?.['fn-id'] === id, originalId);
      assert(await page.evaluate(id => gdUIConfigurationState()?.selection['fn-id'] === id, originalId),
        'cleanup restores the original shared or authored configuration without a mandatory baseline editor');
    }
    phase = 'home cleanup';
    if (homeSetup) await homeSetup.cleanup();
    phase = 'pageerrors';
    assert(pageErrors.length === 0, 'native personalization and cleanup have no page errors');
    } finally { await browser.close(); }
  }
})().catch(error => {
  const kind = error?.name === 'TimeoutError' ? 'timeout' : 'assertion-or-request';
  process.stderr.write('personal configuration browser proof failed: ' + phase + ': ' + kind + '\n');
  process.exitCode = 1;
});
