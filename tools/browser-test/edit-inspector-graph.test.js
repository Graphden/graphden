// Graph chrome and native bodies have independent lifetimes. Deferred real
// partials exercise repeated selection; keyboard focus must stay on tabs.
const {chromium} = require('playwright');
const {assert, newContext, waitFor, BASE} = require('./edit-test-helpers');
let phase = 'boot';
(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  const releases = new Set();
  page.on('pageerror', error => {
    errors.push(error.name);
    console.log('[Inspector frame]', JSON.stringify(error.stack.split('\n').slice(1, 5).map(line => {
      const match = line.match(/at ([\w.$<> ]+).*:(\d+):(\d+)/);
      return match ? {frame: match[1].trim(), line: Number(match[2]), column: Number(match[3])} : {frame: 'unknown'};
    })));
  });
  page.on('console', message => { if (message.type() === 'error') errors.push('console-error'); });
  try {
    await page.goto(BASE + '/#web-server');
    await page.waitForFunction(() => typeof lookups !== 'undefined' && lookups?.fnMap?.size > 1 && typeof graphReady === 'function' && graphReady() && !graph.animating);
    phase = 'selection';
    const ids = await page.evaluate(() => [...lookups.fnMap.values()].filter(fn => fn.name).slice(0, 2).map(fn => fn.id));
    assert(ids.length === 2, 'two real graph identities are available');
    await page.evaluate(id => gdInspectorRender(id), ids[0]);
    await page.locator('#gd-insp-overview .gd-insp-runs-loading').waitFor({state: 'detached'});
    assert(await page.evaluate(() => !GraphdenRenderer.ownsTarget(document.getElementById('gd-insp-tabbody'))
      && GraphdenRenderer.ownsTarget(document.querySelector('.gd-insp-head'))
      && !GraphdenRenderer.ownsTarget(document.querySelector('[data-gd-inspector-diff-host]'))),
    'graph chrome excludes both native sibling hosts');
    const overview = page.locator('[data-insp-tab="overview"]');
    await overview.focus(); await overview.press('ArrowRight');
    await page.locator('[data-insp-tab="bindings"][aria-selected="true"]').waitFor();
    assert(await page.locator('[data-insp-tab="bindings"]').evaluate(el => el === document.activeElement),
      'roving keyboard selection focuses the painted tab');
    await page.locator('[data-insp-tab="bindings"]').press('ArrowRight');
    await page.locator('#gd-insp-run-host .gd-insp-run.visible').waitFor();
    assert(await page.locator('[data-insp-tab="stats"]').evaluate(el => el === document.activeElement),
      'Runs mount does not steal keyboard tab focus');
    await page.evaluate(() => { window.inspectorRunBeforeSettings = document.querySelector('#gd-insp-run-host .gd-insp-run'); });
    await page.locator('.auth-avatar:visible, #auth-lock-btn:visible').first().click();
    await page.locator('.auth-menu-item[data-item="Settings"]').click();
    await page.locator('#gd-settings').waitFor({state: 'visible'});
    assert(await page.evaluate(() => document.getElementById('gd-inspector').closest('[inert]') !== null
      && window.inspectorRunBeforeSettings.isConnected), 'Settings covers and retains the native Run pane');
    await page.keyboard.press('Escape');
    await page.locator('#gd-settings').waitFor({state: 'hidden'});
    assert(await page.evaluate(() => window.inspectorRunBeforeSettings === document.querySelector('#gd-insp-run-host .gd-insp-run')),
      'closing Settings preserves the exact native Run controls');
    await page.locator('[data-insp-tab="stats"]').focus();
    await page.locator('[data-insp-tab="stats"]').press('End');
    await page.locator('[data-insp-tab="history"][aria-selected="true"]').waitFor();
    await page.locator('[data-insp-tab="history"]').press('Home');
    await page.locator('[data-insp-tab="overview"][aria-selected="true"]').waitFor();
    assert(await page.locator('#gd-insp-tabbody').getAttribute('aria-labelledby') === 'gd-insp-tab-overview',
      'native panel ARIA follows graph tab state');
    phase = 'deferred-partials';
    const held = [];
    const routePath = '**/partials/inspector-overview?*';
    await page.route(routePath, async route => {
      const response = await route.fetch();
      let release; const ready = new Promise(resolve => { release = resolve; });
      held.push({release, ready}); releases.add(release); await ready; releases.delete(release);
      await route.fulfill({response}).catch(() => {});
    });
    await page.evaluate(id => gdInspectorRender(id), ids[0]);
    await page.waitForFunction(() => document.querySelector('#gd-insp-overview .gd-insp-runs-loading'));
    // The real request has reached the held response boundary before the next selection.
    assert(await waitFor(() => held.length >= 1, 30000), 'first real A response reached the controlled boundary');
    await page.evaluate(id => gdInspectorRender(id), ids[1]);
    assert(await waitFor(() => held.length >= 2, 30000), 'real B response reached the controlled boundary');
    await page.evaluate(id => gdInspectorRender(id), ids[0]);
    assert(await waitFor(() => held.length >= 3, 30000), 'newest real A response reached the controlled boundary');
    held[2].release();
    await page.locator('#gd-insp-overview .gd-insp-runs-loading').waitFor({state: 'detached'});
    const current = await page.locator('#gd-insp-overview').innerHTML();
    held[0].release(); held[1].release();
    assert(await page.locator('#gd-insp-overview').innerHTML() === current, 'late A/B responses preserve the newest A host');
    phase = 'interrupted-overview-resume';
    await page.setViewportSize({width: 1000, height: 850});
    await page.evaluate(id => gdInspectorRender(id), ids[0]);
    assert(await waitFor(() => held.length >= 4, 30000), 'interruptible Overview reached the response boundary');
    await page.evaluate(() => { window.inspectorInterruptedHost = document.getElementById('gd-insp-overview'); gdInspectorGraph.close(); });
    await page.setViewportSize({width: 1400, height: 900});
    assert(await waitFor(() => held.length >= 5, 30000), 'wide viewport starts a fresh read without selecting a tab');
    held[4].release();
    await page.locator('#gd-insp-overview .gd-insp-runs-loading').waitFor({state: 'detached'});
    assert(await page.evaluate(() => window.inspectorInterruptedHost === document.getElementById('gd-insp-overview')),
      'interrupted Overview resumes in the exact existing native host');
    held[3].release();
    await page.unroute(routePath);
    phase = 'responsive';
    await page.setViewportSize({width: 1000, height: 850});
    await page.locator('.gd-insp-close').waitFor({state: 'visible'});
    await page.locator('[data-insp-tab="overview"]').focus();
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.body.classList.contains('gd-insp-open'));
    await page.waitForFunction(() => document.getElementById('gd-inspector').getBoundingClientRect().top >= innerHeight - 1);
    assert(await page.locator('#gd-inspector').evaluate(el => el.getBoundingClientRect().top >= innerHeight - 1),
      'Escape moves the narrow Inspector sheet outside the viewport');
    await page.setViewportSize({width: 1400, height: 900});
    await page.locator('.gd-insp-close').waitFor({state: 'hidden'});
    assert(!await page.locator('.gd-insp-close').isVisible(), 'wide Inspector hides the sheet close action');
    await page.locator('[data-insp-tab="overview"]').focus();
    await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowRight');
    await page.locator('#gd-insp-run-host .gd-insp-run.visible').waitFor();
    assert(await page.locator('[data-insp-tab="stats"]').evaluate(el => el === document.activeElement),
      'wide Runs works after closing the narrow sheet without selecting again');
    phase = 'hidden-initial-run';
    let shellRelease;
    const shellPath = '**/partials/execute-popover?*';
    await page.route(shellPath, async route => {
      const response = await route.fetch();
      await new Promise(resolve => { shellRelease = resolve; releases.add(resolve); });
      releases.delete(shellRelease);
      await route.fulfill({response}).catch(() => {});
    });
    await page.evaluate(id => gdInspectorShowRuns(id), ids[0]);
    assert(await waitFor(() => typeof shellRelease === 'function', 30000), 'initial Run shell reached its controlled boundary');
    await page.setViewportSize({width: 1000, height: 850});
    await page.evaluate(() => {
      window.inspectorHiddenRun = document.querySelector('#gd-insp-run-host .gd-insp-run');
      gdInspectorGraph.close();
    });
    shellRelease();
    await page.waitForFunction(() => window.inspectorHiddenRun.classList.contains('visible'));
    assert(await page.evaluate(() => window.inspectorHiddenRun.isConnected && !window.inspectorHiddenRun.contains(document.activeElement)),
      'initial Run form completes hidden without stealing focus');
    await page.unroute(shellPath);
    await page.setViewportSize({width: 1400, height: 900});
    await page.waitForFunction(() => document.querySelector('#gd-insp-runs .execute-history-host-wrap'));
    assert(await page.evaluate(() => window.inspectorHiddenRun === document.querySelector('#gd-insp-run-host .gd-insp-run')),
      'wide resume retains the hidden initial Run form');
    phase = 'retained-run-history';
    await page.evaluate(() => {
      const pane = document.querySelector('#gd-insp-run-host .gd-insp-run');
      const textarea = document.createElement('textarea'); textarea.dataset.lang = 'clojure';
      textarea.value = 'retained Inspector draft'; pane.appendChild(textarea);
      window.inspectorRetainedControls = {pane, textarea, view: gdCode.enhance(textarea)};
    });
    assert(await page.evaluate(() => !!window.inspectorRetainedControls.view), 'real CodeMirror control mounted in native Run owner');
    const historyReads = [];
    const historyPath = '**/partials/execute-history?*';
    await page.route(historyPath, async route => {
      const response = await route.fetch();
      let release; const ready = new Promise(resolve => { release = resolve; });
      const index = historyReads.length;
      historyReads.push({release}); releases.add(release); await ready; releases.delete(release);
      const html = await response.text();
      await route.fulfill({response, body: html + '<div data-gd-read-proof="' + index + '"></div>'}).catch(() => {});
    });
    await page.evaluate(() => document.getElementById('gd-insp-runs').dispatchEvent(new Event('gd-refresh-runs')));
    assert(await waitFor(() => historyReads.length === 1, 30000), 'loaded Runs begins its held history refresh');
    await page.setViewportSize({width: 1000, height: 850});
    await page.evaluate(() => gdInspectorGraph.close());
    await page.setViewportSize({width: 1400, height: 900});
    assert(await waitFor(() => historyReads.length === 2, 30000), 'retained Run owner resumes interrupted history');
    historyReads[1].release();
    await page.locator('#gd-insp-runs [data-gd-read-proof="1"]').waitFor({state: 'attached'});
    historyReads[0].release();
    await page.evaluate(() => document.getElementById('gd-insp-runs').dispatchEvent(new Event('gd-refresh-runs')));
    assert(await waitFor(() => historyReads.length === 3, 30000), 'older visible history read reached its controlled boundary');
    await page.evaluate(() => document.getElementById('gd-insp-runs').dispatchEvent(new Event('gd-refresh-runs')));
    assert(await waitFor(() => historyReads.length === 4, 30000), 'newer visible history read overlaps the held older response');
    historyReads[3].release();
    await page.locator('#gd-insp-runs [data-gd-read-proof="3"]').waitFor({state: 'attached'});
    historyReads[2].release();
    await page.unroute(historyPath);
    assert(await page.locator('#gd-insp-runs [data-gd-read-proof="3"]').count() === 1,
      'late history responses cannot replace the newest same-owner read');
    assert(await page.evaluate(() => {
      const saved = window.inspectorRetainedControls;
      return saved.pane === document.querySelector('#gd-insp-run-host .gd-insp-run') && saved.textarea.isConnected
        && gdCode.viewOf(saved.textarea) === saved.view && gdCode.get(saved.textarea) === 'retained Inspector draft';
    }), 'close/resume/history preserves exact native controls, CodeMirror view and draft');
    await page.setViewportSize({width: 1000, height: 850});
    await page.evaluate(id => gdInspectorRender(id), ids[0]);
    await page.locator('.gd-insp-close').waitFor({state: 'visible'});
    await page.waitForFunction(() => Math.abs(new DOMMatrixReadOnly(getComputedStyle(document.getElementById('gd-inspector')).transform).m42) < 1);
    await page.screenshot({path: '/tmp/gd-inspector-graph-narrow.png'});
    await page.evaluate(() => window.dispatchEvent(new Event('gd-auth-changed')));
    await page.waitForFunction(() => document.getElementById('gd-inspector').dataset.fnId === '');
    assert(await page.locator('#gd-insp-tabbody').evaluate(el => el.children.length === 0), 'account change clears old native selection immediately');
    assert(errors.length === 0, 'no unclassified browser errors');
    console.log('PASS graph Inspector ownership, real partial generations, keyboard focus, responsive close and account reset');
  } catch (error) {
    const state = await page.evaluate(() => ({
      selectedTab: document.querySelector('[data-insp-tab][aria-selected="true"]')?.dataset.inspTab,
      runConnected: window.inspectorRetainedControls?.pane?.isConnected ?? null,
      historyMarkers: [...document.querySelectorAll('[data-gd-read-proof]')].map(el => el.dataset.gdReadProof),
      historyLoading: !!document.querySelector('#gd-insp-runs .execute-submit-spinner'),
      historyChildren: document.getElementById('gd-insp-runs')?.children.length,
    })).catch(() => ({unavailable: true}));
    console.log('Inspector safe state:', JSON.stringify(state));
    throw error;
  } finally {
    for (const release of releases) release();
    await page.unrouteAll({behavior: 'wait'}); await browser.close();
  }
})().catch(error => {console.error('FAIL Inspector graph proof:', phase, require('./safe-diagnostics').errorKind(error)); process.exitCode = 1;});
