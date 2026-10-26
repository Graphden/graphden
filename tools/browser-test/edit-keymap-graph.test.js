// Keyboard settings use the editor root while the host keeps IME and capture ownership.
const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(BASE + '/');
    await page.waitForFunction(() => !!gdEditorRoot.componentNow('keymap'), null, {timeout: 30000});
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
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
