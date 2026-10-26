// Appearance pane is rendered by the selected editor root; typing remains native.
const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  let initialTheme;
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(BASE + '/');
    initialTheme = await page.evaluate(() => gdPrefRead('theme') || null);
    await page.waitForFunction(() => !!gdEditorRoot.componentNow('appearance'), null, {timeout: 30000});
    await page.evaluate(() => { gdShellSurface('settings'); gdActivateSettingsSection('appearance'); });
    await page.waitForSelector('#gd-theme-root #gd-theme-edit');
    await page.click('#gd-theme-edit');
    await page.waitForSelector('#gd-theme-editor .gd-theme-swatch');
    const initial = await page.evaluate(() => ({
      swatches: document.querySelectorAll('#gd-theme-editor .gd-theme-swatch').length,
      expected: gdThemeTokens.length,
      fonts: document.querySelectorAll('#gd-theme-editor .gd-theme-font').length,
      selected: !!gdEditorRoot.componentNow('appearance'),
    }));
    assert(initial.selected && initial.swatches === initial.expected && initial.fonts === 3,
      'selected root renders the entire theme editor');
    const font = page.locator('#gd-theme-editor .gd-theme-font[data-font="ui"]');
    await font.focus();
    await font.fill('Arial, sans-serif');
    await page.waitForTimeout(350);
    assert(await font.evaluate(element => element === document.activeElement && element.value === 'Arial, sans-serif'),
      'debounced preference write preserves the focused field and draft');
    const scale = page.locator('#gd-theme-scale');
    assert(await scale.getAttribute('min') === '70' && await scale.getAttribute('max') === '160',
      'graph uses the sanitizer scale range');
    assert(errors.length === 0, 'no browser page errors: ' + JSON.stringify(errors));
    await page.screenshot({path: '/tmp/graphden-d2-appearance.png'});
    console.log('PASS appearance graph: selected root, complete editor, focused draft and scale');
  } finally {
    try {
      if (initialTheme !== undefined) {
        const restored = await page.evaluate(async value => {
          const saved = await gdPrefWrite('theme', value);
          if (saved) gdApplyThemePayload(value?.payload || null);
          return saved;
        }, initialTheme);
        assert(restored, 'appearance test restores the original theme preference');
      }
    } finally { await browser.close(); }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
