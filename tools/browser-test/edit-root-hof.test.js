// Root HOF bootstrap in the existing editor. The server boundary is covered
// separately by browser-snapshot-integration-test; this fixture also verifies
// that an unsupported adapter cannot replace the active legacy editor.
// GRAPHDEN_URL=http://localhost:<port> node tools/browser-test/edit-root-hof.test.js
// Exit 0 = PASS, 1 = FAIL. No graph writes or persistent browser artifacts.
'use strict';
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const {newContext} = require('./edit-test-helpers');
(async () => {
  const {browser, page} = await newContext(chromium);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  const endpoint = '**/api/ui/root/plan*';
  try {
    const result = await page.evaluate(async () => {
      const response = await window.authFetch(window.API.api_ui_root_plan, {cache: 'no-store'});
      return {status: response.status, body: await response.json()};
    });
    assert.equal(result.status, 200);
    assert.equal(result.body.ok, true);
    assert(result.body.plan.primitives.some(primitive => primitive.op === 'call'));
    await page.evaluate(() => window.gdEditorRoot.reload());
    assert.equal(await page.locator('#app').isVisible(), true);

    assert.equal(await page.locator('#app').getAttribute('data-gd-root-adapter'), 'legacy');
    const forged = await page.evaluate(async () => {
      const response = await window.authFetch(window.API.api_ui_root_plan + '?target=00000000-0000-0000-0000-000000000000&principal=forged');
      return await response.json();
    });
    assert.equal(forged.plan.entries.root, result.body.plan.entries.root);

    const replacement = structuredClone(result.body);
    const byId = new Map(replacement.plan.functions.map(fn => [fn.id, fn]));
    const argument = (fn, name) => fn.args.find(arg => arg.name === name)?.expr;
    const literalKey = fn => argument(fn, 'key')?.value?.[2];
    const menuField = replacement.plan.functions.find(fn => literalKey(fn) === 'account-menu');
    assert(menuField, 'root record contains the account-menu field');
    let component = byId.get(argument(menuField, 'value').fn);
    while (component && literalKey(component) !== 'view') component = byId.get(argument(component, 'map')?.fn);
    assert.equal(argument(component, 'value').kind, 'closure');
    const viewBinding = argument(component, 'value');
    const encodedView = await page.evaluate(() => {
      const g = window.GraphdenBrowser;
      const menu = window.gdEditorRoot.componentNow('account-menu');
      const state = menu.run('initial', {});
      const theme = new Map([[g.keyword('accent'), '#336699'], [g.keyword('canvas-background'), '#ffffff']]);
      const server = new Map([[g.keyword('phase'), 'idle'], [g.keyword('label'), ''], [g.keyword('branch-name'), '']]);
      return g.encode(menu.run('view', {state, theme, server}));
    });
    const tree = encodedView[1].find(([key]) => key[0] === 'keyword' && key[2] === 'common-tree')[1];
    const rewriteChildren = value => value[0] === 'string' && value[1] === 'Settings'
      ? ['string', 'Root binding settings']
      : value[0] === 'vector' ? ['vector', value[1].map(rewriteChildren)] : value;
    const rewritten = rewriteChildren(tree);
    const assocPrimitive = replacement.plan.primitives.find(primitive => primitive.op === 'assoc');
    replacement.plan.functions.push({id: 'browser-proof-view', primitive: assocPrimitive.id, aliases: [], env: [],
      args: [
        {name: 'map', slot: 'proof-map', expr: {kind: 'call', fn: viewBinding.fn, renames: []}},
        {name: 'key', slot: 'proof-key', expr: {kind: 'literal', value: ['keyword', null, 'common-tree']}},
        {name: 'value', slot: 'proof-value', expr: {kind: 'literal', value: rewritten}},
      ]});
    viewBinding.fn = 'browser-proof-view';
    await page.route(endpoint, route => route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(replacement)}));
    await page.evaluate(() => window.gdEditorRoot.reload());
    await page.locator('#auth-lock-btn').click();
    await page.getByRole('menuitem', {name: 'Root binding settings', exact: true}).waitFor();
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => typeof openFnPicker === 'function');
    await page.evaluate(() => openFnPicker({anchorEl: document.body, onPick() {}}));
    await page.locator('.fn-picker-popover input').waitFor();
    await page.keyboard.press('Escape');
    await page.locator('.fn-picker-popover').waitFor({state: 'detached'});
    assert.equal(await page.locator('#app').isVisible(), true);
    await page.unroute(endpoint);

    const alternate = structuredClone(result.body);
    const operations = new Map(alternate.plan.primitives.map(primitive => [primitive.id, primitive.op]));
    const body = alternate.plan.functions.find(fn => operations.get(fn.primitive) === 'const'
      && fn.args.some(arg => arg.name === 'value' && arg.expr.kind === 'literal'
        && arg.expr.value[0] === 'map' && arg.expr.value[1].some(([key]) => key[0] === 'keyword' && key[2] === 'legacy')));
    assert(body, 'static root callback is part of the checked graph');
    body.args.find(arg => arg.name === 'value').expr.value = ['map', [[['keyword', null, 'legacy'], ['bool', false]]]];
    await page.route(endpoint, route => route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(alternate)}));
    await page.evaluate(() => window.gdEditorRoot.reload());
    await page.getByRole('status').filter({hasText: 'Editor configuration could not be loaded.'}).waitFor();
    assert.equal(await page.locator('#app').isVisible(), true);
    assert.equal(await page.locator('#app').getAttribute('data-gd-root-adapter'), null);
    await page.setViewportSize({width: 390, height: 844});
    await page.screenshot({path: '/tmp/graphden-root-hof.png'});

    await page.unroute(endpoint);
    await page.evaluate(() => window.gdEditorRoot.reload());
    assert.equal(await page.locator('#app').getAttribute('data-gd-root-adapter'), 'legacy');
    assert.equal(await page.locator('#app').isVisible(), true);
    assert.deepEqual(errors, []);
  } finally {
    await page.unroute(endpoint);
    await page.evaluate(() => window.gdEditorRoot?.dispose()).catch(() => {});
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
