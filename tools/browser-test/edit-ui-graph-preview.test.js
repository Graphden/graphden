// Local graph menus: actual DOM interactions, lazy animation states and no RPC
// after loading. Run against the isolated self-hosted prototype instance.
const {chromium} = require('playwright');
const {execFileSync} = require('node:child_process');
const path = require('node:path');
const {isDeepStrictEqual} = require('node:util');
const {assert, newContext, api, waitForServerHealthy, AUTH, BASE} = require('./edit-test-helpers');
const {captureFixtureNamespaces, cleanupGraphFixture} = require('./ui-graph-fixture-cleanup');
const branch = 'ui-preview-' + process.pid + '-' + Date.now().toString(36);

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  let prepared;
  let namespaceBaseline;
  page.on('pageerror', (error) => errors.push(error.message));
  try {
    namespaceBaseline = await captureFixtureNamespaces(page);
    prepared = JSON.parse(execFileSync('bb', ['-cp', 'src', 'tools/ui_preview/prepare.clj',
      BASE, branch, 'user.ui-preview'], {encoding: 'utf8', timeout: 120000,
      cwd: path.resolve(__dirname, '../..'), env: {...process.env, AUTH_TOKEN: AUTH}}));
    assert(prepared.branch === branch, 'ordinary editable preview branch created');
    await page.goto(prepared.url);
    await page.waitForFunction(() => !!window.uiGraphPreview, null, {timeout: 120000});
    const entries = await page.evaluate(() => window.uiGraphPreview.plan.entries);
    const differential = await page.evaluate(() => {
      const graph = window.GraphdenBrowser;
      const runtime = window.uiGraphPreview.runtime;
      const plain = (value) => {
        if (value instanceof graph.Keyword) return value.name;
        if (value instanceof Map) return Object.fromEntries([...value].map(([k, v]) => [plain(k), plain(v)]));
        if (Array.isArray(value)) return value.map(plain);
        return value;
      };
      let state = runtime.run('initial');
      const initial = plain(state);
      const cases = [];
      for (const raw of [{kind: 'open'}, {kind: 'animation-finished'},
        {kind: 'keydown', key: 'ArrowDown'}, {kind: 'keydown', key: 'Enter'},
        {kind: 'animation-finished'}]) {
        const event = {key: '', index: -1, ...raw};
        const input = plain(state);
        state = runtime.run('update', {state,
          event: new Map(Object.entries(event).map(([k, v]) => [graph.keyword(k), v]))});
        cases.push({input, event, state: plain(state), view: plain(runtime.run('view', {state}))});
      }
      return {initial, cases};
    });
    const execute = (entry, args) => api(page, 'POST', '/api/execute?branch=' + encodeURIComponent(branch),
      {'fn-id': entries[entry], args, 'timeout-ms': 15000});
    const initial = await execute('initial', {});
    assert(isDeepStrictEqual(initial.result, differential.initial), 'stored initial graph agrees on JVM and browser');
    for (const item of differential.cases) {
      const update = await execute('update', {state: item.input, event: item.event});
      const view = await execute('view', {state: item.state});
      assert(isDeepStrictEqual(update.result, item.state), 'stored update agrees for ' + item.event.kind);
      assert(isDeepStrictEqual(view.result, item.view), 'stored view agrees for ' + item.state.phase);
    }
    const first = page.locator('#ui-preview-first');
    const second = page.locator('#ui-preview-second');
    const trigger = first.locator('[data-event="open"]');
    await page.evaluate(() => { window.originalPreviewTrigger = document.querySelector('#ui-preview-first [data-event="open"]'); });
    await trigger.click();
    await page.waitForFunction(() => document.querySelector('#ui-preview-first [data-phase]').dataset.phase === 'open');
    assert(await second.locator('[data-phase]').getAttribute('data-phase') === 'closed', 'mounts keep independent state');
    await first.locator('[data-index="0"]').press('ArrowDown');
    assert(await first.locator('[data-index="1"]').evaluate((element) => element === document.activeElement), 'arrows move keyboard focus');
    await first.locator('[data-index="1"]').press('Enter');
    await page.waitForFunction(() => document.querySelector('#ui-preview-first [data-phase]').dataset.phase === 'closed');
    assert(await trigger.textContent() === 'Violet', 'graph update changes selection');
    assert(await trigger.evaluate((element) => element === document.activeElement), 'closing restores trigger focus');
    assert(await page.evaluate(() => window.originalPreviewTrigger === document.querySelector('#ui-preview-first [data-event="open"]')), 'DOM patch preserves trigger identity');
    assert(await first.locator('[data-phase]').evaluate((element) => element.style.getPropertyValue('--ui-accent')) === '#a78bfa', 'selected theme feeds the graph style');
    await page.screenshot({path: '/tmp/ui-graph-preview-selected.png'});
    // Observe actual intermediate frames, not just a screenshot after a click.
    const frames = await page.evaluate(async () => {
      const preview = window.uiGraphPreview.mounts[0];
      const root = document.querySelector('#ui-preview-first [data-phase]');
      const list = root.querySelector('[role="menu"]');
      const values = [];
      preview.dispatch({kind: 'open'});
      const start = performance.now();
      while (performance.now() - start < 250) {
        await new Promise(requestAnimationFrame);
        values.push({phase: root.dataset.phase, opacity: Number(getComputedStyle(list).opacity)});
      }
      return values;
    });
    assert(frames.some((frame) => frame.phase === 'opening' && frame.opacity > 0 && frame.opacity < 1), 'opening has visible intermediate animation frames');
    assert(frames.at(-1).phase === 'open', 'animation completion advances graph phase');
    const interrupted = await page.evaluate(async () => {
      const preview = window.uiGraphPreview.mounts[0];
      const root = document.querySelector('#ui-preview-first [data-phase]');
      const list = root.querySelector('[role="menu"]');
      preview.dispatch({kind: 'outside'});
      while (root.dataset.phase !== 'closed') await new Promise(requestAnimationFrame);
      preview.dispatch({kind: 'open'});
      await new Promise(requestAnimationFrame);
      await new Promise(requestAnimationFrame);
      const before = Number(getComputedStyle(list).opacity);
      preview.dispatch({kind: 'outside'});
      const after = Number(getComputedStyle(list).opacity);
      while (root.dataset.phase !== 'closed') await new Promise(requestAnimationFrame);
      return {before, after, phase: root.dataset.phase};
    });
    assert(Math.abs(interrupted.after - interrupted.before) < 0.05,
      'interrupted opening closes from its current opacity without jumping');
    assert(interrupted.phase === 'closed', 'cancelled opening cannot reopen the menu');
    await trigger.click();
    await page.waitForFunction(() => document.querySelector('#ui-preview-first [data-phase]').dataset.phase === 'open');
    const network = [];
    await page.route('**/*', (route) => { network.push(route.request().url()); return route.abort(); });
    await first.locator('[data-index="1"]').press('Escape');
    await page.waitForFunction(() => document.querySelector('#ui-preview-first [data-phase]').dataset.phase === 'closed');
    await second.locator('[data-event="open"]').click();
    await page.waitForFunction(() => document.querySelector('#ui-preview-second [data-phase]').dataset.phase === 'open');
    await second.locator('[data-index="2"]').click();
    await page.waitForFunction(() => document.querySelector('#ui-preview-second [data-phase]').dataset.phase === 'closed');
    assert(await second.locator('[data-event="open"]').textContent() === 'Amber', 'second mount runs while server requests are blocked');
    assert(await trigger.textContent() === 'Violet', 'second selection does not mutate the first');
    assert(network.length === 0, 'menu interactions send no server requests');
    assert(errors.length === 0, 'browser reports no uncaught errors');
  } finally {
    try {
      if (prepared) {
        await cleanupGraphFixture(page, prepared, namespaceBaseline);
      }
    } finally { await browser.close(); }
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
