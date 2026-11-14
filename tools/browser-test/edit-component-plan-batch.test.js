// Actual component plans respect the current server transport hint, retain
// independent consumer cancellation, and leave the editor usable.
// GRAPHDEN_URL=http://localhost:<port> node tools/browser-test/edit-component-plan-batch.test.js
// Read-only test: no graph fixtures or stored preferences to clean up.
'use strict';
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const {newContext, waitForEditorReady, BASE} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [], transports = [];
  let batchMax = '1';
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/ui/configuration/plan') transports.push(request.postDataJSON());
  });
  // Exercise both server recommendations on the same real backend. Source
  // authorization and plan data remain the ordinary production endpoints.
  await page.route('**/api/prefs', async route => {
    const response = await route.fetch();
    await route.fulfill({response, headers: {...response.headers(), 'x-graphden-ui-plan-batch-max': batchMax}});
  });
  try {
    await page.goto(BASE, {waitUntil: 'domcontentloaded'});
    await waitForEditorReady(page);
    await page.waitForLoadState('networkidle');
    for (const size of [1, 3]) {
      batchMax = String(size);
      await page.evaluate(() => gdPrefsRefresh());
      await page.waitForLoadState('networkidle');
      assert.equal(await page.evaluate(() => gdUIPlanBatchMax), size);
      transports.length = 0;
      const result = await page.evaluate(async () => {
        const plans = {'canvas': 'canvas', 'canvas-values': 'canvasValues',
          'canvas-inheritance': 'canvasInheritance', 'canvas-metadata': 'canvasMetadata',
          'explorer': 'explorer', 'inspector': 'inspector'};
        const start = performance.now(), ready = [];
        await Promise.all(Object.entries(plans).map(async ([name, key]) => {
          await gdLoadUIComponentRuntime(name, GraphdenBuiltinPlans.plans[key]);
          ready.push(performance.now() - start);
        }));
        return {firstReadyMs: Math.round(Math.min(...ready)), allReadyMs: Math.round(performance.now() - start),
          selected: Object.keys(plans).map(name => gdUIComponentRuntimeIdentity(name)), status: gdUIComponentsStatus()};
      });
      await page.waitForLoadState('networkidle');
      assert(result.selected.every(id => typeof id === 'string' && id === result.selected[0]));
      assert.equal(result.status, '');
      assert.equal(transports.length, size === 1 ? 6 : 2);
      assert(transports.every(command => size === 1 ? typeof command.component === 'string'
        : command.components.length === 3 && new Set(command.components).size === 3));
      console.log(JSON.stringify({transportSize: size, requests: transports.length,
        firstReadyMs: result.firstReadyMs, allReadyMs: result.allReadyMs}));
    }
    const cancellation = await page.evaluate(async () => {
      const original = window.fetch;
      let release, started;
      const gate = new Promise(resolve => { release = resolve; });
      const sent = new Promise(resolve => { started = resolve; });
      let sharedSignal;
      window.fetch = function(input, options) {
        const url = new URL(typeof input === 'string' ? input : input.url, location.href);
        if (url.pathname !== '/api/ui/configuration/plan') return original.apply(this, arguments);
        sharedSignal = options.signal;
        const args = arguments, receiver = this;
        started();
        return gate.then(() => original.apply(receiver, args));
      };
      try {
        const owner = new AbortController();
        const first = gdLoadUIComponentRuntime('canvas', GraphdenBuiltinPlans.plans.canvas, {}, owner.signal)
          .then(() => 'resolved', error => error.name);
        const second = gdLoadUIComponentRuntime('canvas-values', GraphdenBuiltinPlans.plans.canvasValues);
        await sent;
        owner.abort();
        const firstOutcome = await first;
        const siblingTransportAlive = !sharedSignal.aborted;
        release();
        await second;
        return {firstOutcome, siblingTransportAlive, selected: !!gdUIComponentRuntimeIdentity('canvas-values')};
      } finally { release(); window.fetch = original; }
    });
    assert.deepEqual(cancellation, {firstOutcome: 'AbortError', siblingTransportAlive: true, selected: true});
    assert.equal(errors.length, 0, 'no page or console errors');
    assert(await page.locator('#entity-list').isVisible(), 'Explorer remains mounted');
    console.log('PASS server-directed component plan batches and independent cancellation');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
