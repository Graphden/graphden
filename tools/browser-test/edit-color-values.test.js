// Typed color edits through the actual graph, including manual text, native
// picker, alpha, invalid-value diagnostics and the compact canvas preview.
// Run with GRAPHDEN_URL/AUTH_TOKEN; all imported fixture graphs are cleaned up.
const {chromium} = require('playwright');
const {execFileSync} = require('node:child_process');
const {assert, newContext, api, waitForServerHealthy, AUTH, BASE} = require('./edit-test-helpers');
const {removeUseSiteBinding, bindPlaceholderOn} = require('./tutorial-tour-helpers');
const {captureFixtureNamespaces, cleanupGraphFixture} = require('./ui-graph-fixture-cleanup');
const branch = 'color-values-' + process.pid + '-' + Date.now().toString(36);

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  if (process.env.GRAPHDEN_SESSION_COOKIE) {
    await page.context().addCookies([{name: 'gd_session', value: process.env.GRAPHDEN_SESSION_COOKIE,
      url: BASE, httpOnly: true, secure: BASE.startsWith('https:')}]);
  }
  const errors = [];
  let prepared;
  let baseline;
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  try {
    baseline = await captureFixtureNamespaces(page);
    prepared = JSON.parse(execFileSync('bb', ['-cp', 'src', 'tools/ui_preview/prepare.clj',
      '--account-menu', '--picker', BASE, branch, 'user.color-check'], {
      encoding: 'utf8', timeout: 120000, env: {...process.env, AUTH_TOKEN: AUTH}
    }));
    await page.goto(prepared.url);
    await page.waitForFunction(() => window.gdShellMenuGraph?.ready);
    await removeUseSiteBinding(page, 'theme-base-canvas-background');
    const typeLabel = await page.locator('.edge-label-overlay[data-arg-name="value"] .arg-type-chip').innerText();
    assert(typeLabel.startsWith('color') && !typeLabel.includes('matches'),
      'the type chip shows the nominal type without a regex covering the bind button');
    await bindPlaceholderOn(page, 'theme-canvas-background', 'value', 'literal', '#1234');
    const preview = page.locator('.arg-literal-preview svg rect');
    await preview.waitFor();
    assert(await preview.getAttribute('fill') === '#1234', 'canvas preview uses the actual value');
    const formResponse = page.waitForResponse(r => r.url().includes('/api/value-form'));
    await page.locator('.arg-value-editable').first().click();
    const form = await formResponse;
    assert(form.ok(), 'the value form loads in the edited branch');
    const widget = page.locator('[data-form-widget="color"]');
    const text = widget.getByRole('textbox', {name: 'HEX color'});
    const alpha = widget.getByRole('spinbutton', {name: 'Alpha (0–255)'});
    await text.waitFor();
    assert(await text.inputValue() === '#1234', 'manual notation and alpha are preserved on open');
    assert(await alpha.inputValue() === '68', 'short HEX alpha expands correctly');
    await text.fill('wrong');
    assert(await text.evaluate(el => el.validity.customError), 'invalid manual text has a form error');
    const popover = page.locator('.arg-value-edit-popover');
    const save = popover.getByRole('button', {name: 'Save', exact: true});
    if (await save.isEnabled()) await save.click();
    assert(await widget.count() === 1, 'invalid value is not saved through the form');
    await text.fill('#11223380');
    assert(await alpha.inputValue() === '128', 'manual alpha reaches its control');
    await widget.locator('input[type=color]').evaluate(el => {
      el.value = '#aabbcc';
      el.dispatchEvent(new Event('input', {bubbles: true}));
    });
    assert(await text.inputValue() === '#aabbcc80', 'RGB picker preserves alpha');
    await alpha.fill('64');
    assert(await text.inputValue() === '#aabbcc40', 'alpha input updates the shared field');
    await save.click();
    await widget.waitFor({state: 'detached'});
    await page.reload();
    await page.waitForFunction(() => window.gdShellMenuGraph?.ready);
    assert(await page.evaluate(() => getComputedStyle(document.body).getPropertyValue('--bg').trim()) === '#aabbcc40',
      'saved color feeds the actual UI graph');
    await preview.waitFor();
    assert(await preview.getAttribute('fill') === '#aabbcc40', 'saved preview preserves alpha');
    const bindingId = await page.evaluate(() => {
      const row = [...lookups.bindingMap.values()].find(b => b.value === '#aabbcc40');
      return row?.id;
    });
    assert(!!bindingId, 'edited binding can be addressed by identity');
    const warned = await api(page, 'PUT', '/api/entities/binding/' + bindingId,
      'value=' + encodeURIComponent(JSON.stringify('wrong')), {'X-Graphden-Branch': branch});
    assert(warned['type-warnings']?.length > 0, 'API retains the existing error-tolerance diagnostics');
    const planQuery = new URLSearchParams({branch, initial: prepared.entries.initial,
      update: prepared.entries.update, view: prepared.entries.view});
    const rejectedPlan = await page.request.get(BASE + '/ui-preview/plan?' + planQuery,
      {headers: {Authorization: 'Bearer ' + AUTH}});
    assert(rejectedPlan.status() === 422, 'an invalid typed graph is rejected by browser-plan export');
    await api(page, 'PUT', '/api/entities/binding/' + bindingId,
      'value=' + encodeURIComponent(JSON.stringify('#aabbcc40')), {'X-Graphden-Branch': branch});
    assert(errors.length === 0, 'color editing has no browser exceptions');
  } finally {
    try { if (prepared) await cleanupGraphFixture(page, prepared, baseline); }
    finally { await browser.close(); }
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
