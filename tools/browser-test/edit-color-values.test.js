// Typed color edits through the actual graph, including manual text, native
// picker, alpha, invalid-value diagnostics and the compact canvas preview.
// Run with GRAPHDEN_URL/AUTH_TOKEN; all imported fixture graphs are cleaned up.
const {chromium} = require('playwright');
const {execFileSync} = require('node:child_process');
const path = require('node:path');
const {assert, newContext, api, waitForServerHealthy, AUTH, BASE} = require('./edit-test-helpers');
const {removeUseSiteBinding, bindPlaceholderOn, pickIncompatFnRef} = require('./tutorial-tour-helpers');
const {captureFixtureNamespaces, cleanupGraphFixture} = require('./ui-graph-fixture-cleanup');
const {components} = require('./selective-copy-errors');
const branch = 'color-values-' + process.pid + '-' + Date.now().toString(36);

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  let prepared;
  let baseline;
  let phase = 'initial-boot';
  const checkpoint = next => { phase = next; console.log('color phase', JSON.stringify({phase, time: Date.now()})); };
  const receipts = [], pendingReceipts = [];
  const reasons = new Set(['policy-refresh-required', 'type-source-mismatch', 'type-check-failed',
    'type-unresolved', 'structural-operation-source-mismatch', 'source-unavailable']);
  const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value) ? value : null;
  page.on('response', response => {
    const url = new URL(response.url());
    if (!['/ui-preview/plan', '/api/ui/configuration/plan'].includes(url.pathname)) return;
    const started = phase;
    let component = null;
    try { const name = response.request().postDataJSON()?.component; if (components.has(name)) component = name; } catch (_) {}
    pendingReceipts.push((async () => {
      let body; try { body = await response.json(); } catch (_) {}
      const row = {phase: started, kind: component ? 'component' : 'review', component, status: response.status(),
        parsed: !!body, ok: body?.ok === true || body?.format === 1,
        code: body?.code == null ? null : ['policy-refresh-required', 'component-missing'].includes(body.code) ? body.code : 'other',
        retryable: body?.retryable === true,
        reason: body?.reason == null ? null : reasons.has(body.reason) ? body.reason : 'other',
        target: uuid(body?.['fn-id']), selection: uuid(body?.['selection-id'])};
      if (receipts.length < 128) receipts.push(row);
    })());
  });
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  try {
    baseline = await captureFixtureNamespaces(page);
    prepared = JSON.parse(execFileSync('bb', ['-cp', 'src', 'tools/ui_preview/prepare.clj',
      '--account-menu', '--picker', BASE, branch, 'user.color-check'], {
      encoding: 'utf8', timeout: 120000, cwd: path.resolve(__dirname, '../..'), env: {...process.env, AUTH_TOKEN: AUTH}
    }));
    console.log('color fixture', JSON.stringify({branch, namespace: prepared.namespace}));
    checkpoint('initial-boot');
    await page.goto(prepared.url);
    await page.waitForFunction(() => window.gdShellMenuGraph?.ready);
    await removeUseSiteBinding(page, '_theme-base-canvas-background');
    const typeLabel = await page.locator('.edge-label-overlay[data-arg-name="value"] .arg-type-chip > .gd-canvas-body-host').innerText();
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
    assert(await page.locator('.value-form-raw-toggle').count() === 0,
      'a manual text widget has no redundant raw mode that bypasses validation');
    assert(await text.inputValue() === '#1234', 'manual notation and alpha are preserved on open');
    assert(await alpha.inputValue() === '68', 'short HEX alpha expands correctly');
    checkpoint('ui-invalid-not-sent');
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
    checkpoint('valid-save');
    const saved = page.waitForResponse(response => response.request().method() === 'PUT'
      && new URL(response.url()).pathname.startsWith('/api/entities/binding/'));
    await save.click();
    const savedResponse = await saved;
    assert(savedResponse.ok(), 'valid color binding save succeeds');
    checkpoint('valid-save-ack');
    const savedBinding = new URL(savedResponse.url()).pathname.split('/').pop();
    const root = await page.evaluate(() => selectedFnId);
    const persisted = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + root,
      undefined, {'X-Graphden-Branch': branch});
    const savedRow = persisted.bindings?.find(row => row.id === savedBinding);
    console.log('valid color persistence', JSON.stringify({present: !!savedRow, valueValid: savedRow?.value === '#aabbcc40',
      binding: uuid(savedBinding), owner: uuid(savedRow?.['fn-id'])}));
    assert(savedRow?.value === '#aabbcc40', 'valid color is persisted before reload');
    await widget.waitFor({state: 'detached'});
    checkpoint('valid-reload');
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
    checkpoint('intentional-api-invalid');
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
    checkpoint('view-is-not-a-color-value');
    await removeUseSiteBinding(page, '_theme-base-canvas-background');
    await pickIncompatFnRef(page, 'as-ui-view');
    const mismatch = await page.locator('.mismatch-explainer.visible').innerText();
    assert(/ui-view|UiView/i.test(mismatch), 'a view is rejected by the precise Color sink before execution');
    const afterRefusal = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + root,
      undefined, {'X-Graphden-Branch': branch});
    const viewIds = new Set((afterRefusal.fns || []).filter(fn => fn.name === 'as-ui-view').map(fn => fn.id));
    assert(!(afterRefusal.bindings || []).some(binding => binding['fn-id'] === root && viewIds.has(binding['ref-fn-id'])),
      'the refused view connection does not become a saved Color binding');
    assert(errors.length === 0, 'color editing has no browser exceptions');
  } catch (error) {
    await Promise.all(pendingReceipts);
    const context = await page.evaluate(() => {
      const state = window.gdUIConfigurationState?.();
      return {selection: state?.selection?.['fn-id'] || null, revision: state?.revision || null,
        shellReady: window.gdShellMenuGraph?.ready === true};
    }).catch(() => null);
    console.log('finite plan diagnostics', JSON.stringify({phase, context, receipts, count: pendingReceipts.length}));
    throw error;
  } finally {
    try { if (prepared) await cleanupGraphFixture(page, prepared, baseline); }
    finally { await browser.close(); }
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
