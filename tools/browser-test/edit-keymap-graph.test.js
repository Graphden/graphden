// Keyboard settings use the editor root while the host keeps IME and capture ownership.
const {chromium} = require('playwright');
const {isDeepStrictEqual} = require('node:util');
const {assert, newContext, nodeApi, nodeApiJson, waitForServerHealthy, BASE} = require('./edit-test-helpers');

async function waitForKeymap(expected, description) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const current = (await nodeApiJson('GET', '/api/prefs')).keymap ?? null;
    if (isDeepStrictEqual(current, expected)) return;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error('server keymap did not reach ' + description);
}

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  let originalKeymap;
  let originalCaptured = false;
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(BASE + '/');
    await page.waitForFunction(() => gdPrefsReady && !!gdEditorRoot.componentNow('keymap'),
      null, {timeout: 30000});
    originalKeymap = (await nodeApiJson('GET', '/api/prefs')).keymap ?? null;
    originalCaptured = true;
    await page.evaluate(() => {
      gdShellSurface('settings');
      gdActivateSettingsSection('keyboard');
    });
    await page.waitForSelector('#gd-keymap-root .gd-km-table');
    const start = await page.evaluate(() => ({
      rows: document.querySelectorAll('#gd-keymap-root .gd-km-row').length,
      keycaps: document.querySelectorAll('#gd-keymap-root .gd-key-cap').length,
      view: !!gdEditorRoot.componentNow('keymap'),
    }));
    assert(start.view && start.rows >= 15 && start.keycaps >= start.rows,
      'selected root renders every shortcut with keycaps');
    await page.evaluate(() => {
      registerShortcut({id: 'graph-test-conflict-a', keys: 'x y', leader: true,
        group: 'Probe', description: 'Conflict A', run() {}});
      registerShortcut({id: 'graph-test-conflict-b', keys: 'x y', leader: true,
        group: 'Probe', description: 'Conflict B', run() {}});
      gdRenderKeymapPane();
    });
    const conflicts = await page.evaluate(() => ({
      rows: document.querySelectorAll('#gd-keymap-root .gd-km-row.is-conflict').length,
      notes: document.querySelectorAll('#gd-keymap-root .gd-km-conflict').length,
    }));
    assert(conflicts.rows >= 2 && conflicts.notes === 1,
      'graph marks both conflicting bindings and announces the conflict');
    const leader = page.locator('#gd-keymap-root .gd-km-row[data-shortcut="graph-fit"] .gd-km-leader');
    const originalLeader = await leader.isChecked();
    await leader.focus();
    await page.keyboard.press('Space');
    assert(await leader.evaluate(element => element === document.activeElement),
      'a preference update keeps focus on the keyboard control');
    assert(await leader.isChecked() !== originalLeader, 'Space changes the graph-fit leader setting');
    const changed = await page.evaluate(() => gdPrefRead('keymap'));
    await waitForKeymap(changed, 'the changed graph-fit binding');
    await page.click('#gd-keymap-root .gd-km-row[data-shortcut="graph-fit"] .gd-km-change');
    const before = await page.locator('#gd-keymap-root .gd-km-recording').textContent();
    const allowed = await page.evaluate(() => {
      const event = new KeyboardEvent('keydown', {key: 'Process', bubbles: true,
        cancelable: true, isComposing: true});
      window.dispatchEvent(event);
      return !event.defaultPrevented;
    });
    const after = await page.locator('#gd-keymap-root .gd-km-recording').textContent();
    assert(allowed && before === after, 'IME composition is neither captured nor swallowed');
    await page.keyboard.press('Escape');
    assert(await page.locator('#gd-keymap-root .gd-km-recording').count() === 0,
      'Escape cancels recording without a preference write');
    assert(errors.length === 0, 'no browser page errors: ' + JSON.stringify(errors));
    console.log('PASS keymap graph: root, conflicts, IME and capture');
  } finally {
    try {
      if (originalCaptured) {
        const response = await nodeApi('PUT', '/api/prefs/keymap', {value: originalKeymap});
        assert(response.ok, 'restore the original server keymap');
        await waitForKeymap(originalKeymap, 'the original binding');
      }
    } finally { await browser.close(); }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
