// Transport lifecycle in a real browser, without booting a Graphden stack.
// The graph evaluator is a stub: plan execution is covered by native/runtime suites.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const path = require('node:path');
(async () => {
  const browser = await chromium.launch({headless: true});
  const page = await browser.newPage();
  const errors = [], requests = [];
  page.on('pageerror', error => errors.push(error.message));
  const id = '11111111-1111-1111-1111-111111111111';
  const plan = {entries: {view: 'view'}, inputs: {view: {accepted: []}}};
  const item = {ok: true, 'selection-id': id, 'configuration-branch-id': id,
    'configuration-revision': 'a'.repeat(64), 'configuration-defaults-id': id,
    'configuration-preference': {present: false, value: null}, roots: {}, plan};
  try {
    await page.route('http://batch.local/**', async route => {
      if (route.request().method() !== 'POST') return route.fulfill({contentType: 'text/html', body: '<!doctype html><title>Component batch transport</title>'});
      requests.push(route);
    });
    await page.goto('http://batch.local/');
    await page.evaluate(() => {
      window.gdPrefsReady = true; window.gdPrefOwner = 'alice';
      window.API = {api_ui_configuration_plan: '/plan', api_ui_configuration_plans: '/plans'};
      window.gdPrefOnChange = () => {};
      window.authFetch = (...args) => fetch(...args);
      window.GraphdenBrowser = {createRuntime: () => ({run() {}})};
    });
    for (const file of ['editor-ui-policy.js', 'editor-ui-components.js']) {
      await page.addScriptTag({path: path.join(__dirname, '../../resources/packages/app/editor', file)});
    }
    await page.evaluate(plan => {
      const names = ['inspector', 'explorer', 'canvas', 'canvas-values', 'canvas-inheritance', 'canvas-metadata', 'canvas-type-structure', 'canvas-bindings'];
      window.firstAbort = new AbortController();
      window.pending = names.map((name, i) => gdLoadUIComponentRuntime(name, plan, {}, i === 0 ? firstAbort.signal : undefined)
        .then(() => 'ready', error => error.name));
    }, plan);
    await page.waitForFunction(() => performance.getEntriesByType('resource').some(entry => entry.name.endsWith('/editor-ui-components.js')) || !!window.pending);
    for (let attempts = 0; requests.length === 0 && attempts < 100; attempts++) await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(requests.length, 1);
    const components = requests[0].request().postDataJSON().components;
    assert.equal(components.length, 8);
    await page.evaluate(() => firstAbort.abort());
    await requests[0].fulfill({contentType: 'application/json', body: JSON.stringify({ok: true, results: Object.fromEntries(components.map(component => [component, item]))})});
    assert.deepEqual(await page.evaluate(() => Promise.all(pending)), ['AbortError', ...Array(7).fill('ready')]);
    assert.equal(await page.evaluate(() => gdUIComponentRuntimeIdentity('explorer')), id);
    await page.evaluate(plan => {
      window.old = gdLoadUIComponentRuntime('inspector', plan).then(() => 'installed', error => error.name);
      window.dispatchEvent(new Event('gd-auth-changed'));
    }, plan);
    assert.equal(await page.evaluate(() => old), 'AbortError');
    assert.equal(await page.evaluate(() => gdUIConfigurationState()), null);
    assert.deepEqual(errors, []);
    console.log('PASS browser component batch: eight consumers, one POST, isolated abort, auth invalidation');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
