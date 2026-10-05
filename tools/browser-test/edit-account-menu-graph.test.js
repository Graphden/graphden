// Product slice: the existing account menu and canvas consume editable graphs.
const {chromium} = require('playwright');
const {execFileSync} = require('node:child_process');
const {isDeepStrictEqual} = require('node:util');
const {assert, newContext, api, waitForServerHealthy, AUTH, BASE} = require('./edit-test-helpers');
const {removeUseSiteBinding, bindPlaceholderOn, editBoundValue} = require('./tutorial-tour-helpers');
const {captureFixtureNamespaces, cleanupGraphFixture} = require('./ui-graph-fixture-cleanup');
const branch = 'account-menu-' + process.pid + '-' + Date.now().toString(36);

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  let prepared;
  let namespaceBaseline;
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('dialog', (dialog) => dialog.accept());
  try {
    namespaceBaseline = await captureFixtureNamespaces(page);
    prepared = JSON.parse(execFileSync('bb', ['-cp', 'src', 'tools/ui_preview/prepare.clj',
      '--account-menu', '--picker', BASE, branch, 'user.ui-preview'], {encoding: 'utf8', timeout: 120000,
      env: {...process.env, AUTH_TOKEN: AUTH}}));
    await page.goto(BASE + '/?branch=' + encodeURIComponent(branch));
    const baseTheme = await page.evaluate(() => ({
      accent: getComputedStyle(document.body).getPropertyValue('--gd-flow').trim(),
      canvas: getComputedStyle(document.body).getPropertyValue('--bg').trim(),
    }));
    await page.goto(prepared.url);
    await page.waitForFunction(() => window.gdShellMenuGraph?.ready, null, {timeout: 120000});
    assert(await page.evaluate(() => getComputedStyle(document.body).getPropertyValue('--gd-flow').trim()) === baseTheme.accent,
      'default graph preserves the current personal theme');
    const themed = await page.evaluate(async () => {
      const original = window.gdActiveThemePayload();
      const wasDark = document.body.classList.contains('theme-dark');
      window.gdApplyThemePayload({mode: 'dark', tokens: {'--gd-flow': '#aabbcc', '--bg': '#112233'}});
      await Promise.resolve();
      const custom = {accent: getComputedStyle(document.body).getPropertyValue('--gd-flow').trim(),
        canvas: getComputedStyle(document.body).getPropertyValue('--bg').trim()};
      window.gdApplyThemePayload(original);
      if (!original) applyTheme(wasDark);
      await Promise.resolve();
      return custom;
    });
    assert(themed.accent === '#aabbcc' && themed.canvas === '#112233',
      'graph defaults follow an existing custom theme without overwriting it');
    await page.waitForFunction(() => window.gdFnPickerGraph?.ready, null, {timeout: 120000});
    await page.evaluate(async () => {
      await searchFns('const');
      window.openFnPicker({anchorEl: document.getElementById('auth-lock-btn'), onPick() {}});
    });
    const picker = page.locator('.fn-picker-popover');
    await picker.waitFor();
    assert(await picker.locator('[data-gd-ui-style]').count() === 1,
      'the real picker renders its list through the graph component');
    await picker.locator('.fn-picker-search').fill('const');
    await page.waitForFunction(() => document.querySelector('.fn-picker-row'));
    const stableRow = await page.locator('.fn-picker-row').first().evaluateHandle((node) => node);
    await picker.locator('.fn-picker-search').press('ArrowDown');
    assert(await stableRow.evaluate((node) => node.isConnected),
      'keyboard selection retains the keyed candidate DOM');
    await picker.locator('.fn-picker-search').press('Escape');
    assert(await page.locator('.fn-picker-popover').count() === 0,
      'Escape disposes the managed picker');
    assert(await page.locator('style[data-gd-ui-styles]').count() === 0,
      'closing the picker releases its graph stylesheet');
    await page.waitForSelector('.node-overlay button.more-actions-trigger');
    const anchorBaseline = await page.evaluate(() => _viewportListeners.length);
    await page.evaluate(() => openFnPicker({
      anchorEl: document.querySelector('.node-overlay button.more-actions-trigger'), onPick() {},
    }));
    const panBefore = await page.evaluate(() => gv.pan());
    await page.evaluate((pan) => setViewportPan(pan.x + 60, pan.y), panBefore);
    await page.waitForFunction(() => {
      const popup = document.querySelector('.fn-picker-popover');
      const anchor = document.querySelector('.node-overlay button.more-actions-trigger');
      if (!popup || !anchor) return false;
      const expected = Math.max(8, Math.min(anchor.getBoundingClientRect().left,
        innerWidth - popup.offsetWidth - 8));
      return Math.abs(popup.getBoundingClientRect().left - expected) < 1;
    });
    assert(true, 'picker follows its graph anchor during canvas pan');
    const viewportBefore = page.viewportSize();
    await page.setViewportSize({width: 800, height: 700});
    await page.waitForFunction(() => {
      const rect = document.querySelector('.fn-picker-popover').getBoundingClientRect();
      return rect.left >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight;
    });
    assert(true, 'open picker stays inside the resized viewport');
    await picker.locator('.fn-picker-search').press('Escape');
    assert(await page.evaluate(() => _viewportListeners.length) === anchorBaseline,
      'closing picker releases its canvas-position subscription');
    await page.setViewportSize(viewportBefore);
    await page.evaluate((pan) => setViewportPan(pan.x, pan.y), panBefore);
    await page.evaluate(() => {
      const arrange = window.pickerArrange;
      window.pickerArrange = () => ({exact: [], groups: Array.from({length: 1000}, (_, i) => ({
        ns: 'large.ns' + i, rows: [], open: false, compat: 0, other: 0, truncated: false,
      })), shown: 0, total: 1000, hiddenOther: 0});
      try { window.openFnPicker({anchorEl: document.getElementById('auth-lock-btn'), onPick() {}}); }
      finally { window.pickerArrange = arrange; }
    });
    assert(await picker.locator('.fn-picker-ns-toggle').count() === 1000,
      'oversized category lists retain every namespace through the native renderer');
    assert(await picker.locator('[data-gd-ui-style]').count() === 0,
      'fallback releases the graph DOM owner before native rendering');
    await picker.locator('.fn-picker-search').press('Escape');
    const chip = page.locator('#auth-lock-btn');
    await chip.click();
    const menu = page.locator('#auth-popover [role="menu"]');
    await menu.waitFor();
    const labels = await menu.locator('[role="menuitem"]').allTextContents();
    assert(labels.includes('Settings') && labels.includes('Organization') && labels.includes('Sign out'),
      'real menu keeps management and authenticated session actions');
    assert(await menu.evaluate((element) => getComputedStyle(element).display === 'flex'
      && getComputedStyle(element.parentElement).backgroundColor !== 'rgba(0, 0, 0, 0)'),
      'graph frame uses existing product styles');
    await menu.getByRole('menuitem', {name: 'Settings', exact: true}).press('ArrowDown');
    assert(await menu.getByRole('menuitem', {name: 'Organization', exact: true}).evaluate((element) => element === document.activeElement),
      'graph keyboard navigation moves through real menu items');
    await page.screenshot({path: '/tmp/account-menu-graph-open.png'});
    const frames = await page.evaluate(async () => {
      const chip = document.getElementById('auth-lock-btn');
      chip.click();
      await new Promise((resolve) => setTimeout(resolve, 180));
      chip.click();
      const node = document.querySelector('#auth-popover [role="menu"]');
      const values = [];
      for (let i = 0; i < 12; i++) {
        await new Promise(requestAnimationFrame);
        values.push(Number(getComputedStyle(node).opacity));
      }
      return values;
    });
    assert(frames.some((value) => value > 0 && value < 1), 'real opening animation has intermediate frames');
    await menu.getByRole('menuitem', {name: 'Settings', exact: true}).press('Escape');
    await page.waitForFunction(() => document.getElementById('auth-popover').classList.contains('hidden'));
    assert(await chip.evaluate((element) => element === document.activeElement), 'closing restores account chip focus');
    const parity = await page.evaluate(() => {
      const api = window.GraphdenBrowser;
      const runtime = window.gdShellMenuGraph.runtime;
      const plain = (value) => value instanceof api.Keyword ? value.name
        : value instanceof Map ? Object.fromEntries([...value].map(([k, v]) => [plain(k), plain(v)]))
        : value && typeof value !== 'string' && value[Symbol.iterator] ? [...value].map(plain) : value;
      const context = {items: ['Settings', 'Organization', 'Sign out']};
      const event = {kind: 'open', key: '', index: -1};
      const theme = {accent: '#112233', 'canvas-background': '#334455'};
      const state = runtime.run('initial');
      const map = (value) => new Map(Object.entries(value).map(([k, v]) => [api.keyword(k), v]));
      const next = runtime.run('update', {state, event: map(event), context: map(context)});
      return {state: plain(state), event, context, next: plain(next), theme,
        view: plain(runtime.run('view', {state: next, theme: map(theme)}))};
    });
    const execute = (entry, args) => api(page, 'POST', '/api/execute?branch=' + encodeURIComponent(branch),
      {'fn-id': prepared.entries[entry], args, 'timeout-ms': 15000}, {'X-Graphden-Branch': branch});
    const updated = await execute('update', {state: parity.state, event: parity.event, context: parity.context});
    assert(isDeepStrictEqual(updated.result, parity.next), 'real menu state agrees on JVM and browser: ' + JSON.stringify({updated, expected: parity.next}));
    const viewed = await execute('view', {state: parity.next, theme: parity.theme});
    assert(isDeepStrictEqual(viewed.result, parity.view), 'real product menu markup and theme agree on JVM and browser');
    const graphUrl = new URL(prepared.url);
    graphUrl.hash = prepared.namespace + '.theme-canvas-background';
    await page.goto(graphUrl.toString());
    await page.waitForSelector('.arg-value-editable', {timeout: 120000});
    await removeUseSiteBinding(page, 'theme-base-canvas-background');
    const [savedTheme] = await Promise.all([
      page.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/entities/binding', {timeout: 120000}),
      bindPlaceholderOn(page, 'theme-canvas-background', 'value', 'literal', '#ddeedd'),
    ]);
    assert(savedTheme.ok(), 'theme binding save completes before reloading');
    await page.goto(prepared.url);
    await page.reload();
    await page.waitForFunction(() => window.gdShellMenuGraph?.ready, null, {timeout: 120000});
    const editedTheme = await page.evaluate(() => ({canvas: getComputedStyle(document.body).getPropertyValue('--bg').trim(),
      ready: window.gdShellMenuGraph?.ready,
      tokens: [...window.gdShellMenuGraph.runtime.run('view', {state: window.gdShellMenuGraph.state,
        theme: new Map([[GraphdenBrowser.keyword('accent'), '#112233'], [GraphdenBrowser.keyword('canvas-background'), '#334455']])})
        .get(GraphdenBrowser.keyword('theme-tokens'))]}));
    assert(editedTheme.canvas === '#ddeedd',
      'editing a normal graph changes the real canvas: ' + JSON.stringify({editedTheme, errors}));
    await chip.click();
    assert(await menu.evaluate((element) => element.style.getPropertyValue('--gd-account-menu-hover')) === '#ddeedd',
      'the shared graph value also changes the real menu consumer');
    await menu.getByRole('menuitem', {name: 'Settings', exact: true}).press('Escape');
    await page.waitForFunction(() => document.getElementById('auth-popover').classList.contains('hidden'));
    graphUrl.hash = prepared.namespace + '.account-menu-hover';
    await page.goto(graphUrl.toString());
    await page.waitForSelector('.node-overlay[data-fn-name="account-menu-hover"]', {timeout: 120000});
    await page.waitForSelector('.node-overlay[data-fn-name="theme-canvas-background"]', {timeout: 120000});
    await removeUseSiteBinding(page, 'theme-canvas-background');
    await bindPlaceholderOn(page, 'account-menu-hover', 'value', 'literal', '#ffedcc');
    await page.goto(prepared.url);
    await page.reload();
    await page.waitForFunction(() => window.gdShellMenuGraph?.ready, null, {timeout: 120000});
    await chip.click();
    assert(await menu.evaluate((element) => element.style.getPropertyValue('--gd-account-menu-hover')) === '#ffedcc'
      && await page.evaluate(() => getComputedStyle(document.body).getPropertyValue('--bg').trim()) === '#ddeedd',
    'disconnecting the menu color changes only that consumer');
    await menu.getByRole('menuitem', {name: 'Settings', exact: true}).click();
    await page.waitForFunction(() => document.body.dataset.surface === 'settings');
    assert(await page.locator('#auth-popover').evaluate((element) => element.classList.contains('hidden')),
      'native Settings action switches the real surface and closes immediately');
    graphUrl.hash = prepared.namespace + '.account-menu-key-map';
    await page.goto(graphUrl.toString());
    await page.waitForSelector('.node-overlay[data-fn-name="account-menu-key-map"]', {timeout: 120000});
    await page.waitForSelector('.arg-value-editable', {timeout: 120000});
    assert((await page.locator('.arg-value-editable').first().textContent()).includes('ArrowUp'), 'the real menu exposes its key mapping in the graph');
    await editBoundValue(page, 'k');
    await page.goto(prepared.url);
    await page.reload();
    await page.waitForFunction(() => window.gdShellMenuGraph?.ready, null, {timeout: 120000});
    await chip.click();
    await menu.getByRole('menuitem', {name: 'Settings', exact: true}).press('ArrowUp');
    assert(await menu.getByRole('menuitem', {name: 'Settings', exact: true}).evaluate((element) => element === document.activeElement),
      'replaced ArrowUp no longer navigates');
    await menu.getByRole('menuitem', {name: 'Settings', exact: true}).press('k');
    assert(await menu.locator('[role="menuitem"]').last().evaluate((element) => element === document.activeElement),
      'editing the ordinary key map changes navigation through the real menu');
    await menu.locator('[role="menuitem"]').last().press('Escape');
    await page.waitForFunction(() => document.getElementById('auth-popover').classList.contains('hidden'));
    await page.context().setOffline(true);
    await chip.click();
    await menu.getByRole('menuitem', {name: 'Settings', exact: true}).press('End');
    await menu.locator('[role="menuitem"]').last().press('Escape');
    await page.waitForFunction(() => document.getElementById('auth-popover').classList.contains('hidden'));
    await page.context().setOffline(false);
    assert(errors.length === 0, 'product integration produces no browser errors');
    await page.screenshot({path: '/tmp/account-menu-graph-editor.png'});
  } finally {
    try {
      if (prepared) {
        await page.context().setOffline(false);
        await page.goto(BASE + '/');
        await cleanupGraphFixture(page, prepared, namespaceBaseline);
      }
    } finally { await browser.close(); }
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
