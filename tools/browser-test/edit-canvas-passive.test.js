// No server is needed: exercise the shipped canvas plan and actual DOM renderer
// in Chromium, including selected-plan replacement and native sibling ownership.
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');
const source = process.env.GRAPHDEN_CANVAS_SOURCE_ROOT || path.join(__dirname, '../..');

(async () => {
  const browser = await chromium.launch({headless: true});
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  try {
    await page.setContent('<main id="canvas"></main>');
    for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js',
      'app/ui-preview/graph-styles.js', 'app/ui-preview/graph-renderer.js',
      'app/ui-preview/builtin-plans.js']) {
      await page.addScriptTag({path: path.join(source, 'resources/packages', file)});
    }
    await page.evaluate(() => {
      window.fixture = {failures: [], clicks: 0};
      window.gdPrefOwner = 'canvas-fixture-owner';
      window.graphdenCurrentOrg = 'canvas-fixture-org';
      window.getCurrentBranchName = () => 'main';
      window.gdUIComponentFailed = component => fixture.failures.push(component);
      // Transport is controlled; all evaluated plans and rendered DOM are real.
      window.gdLoadUIComponentRuntime = async () => null;
    });
    await page.addScriptTag({path: path.join(source, 'resources/packages/app/editor/editor-canvas-graph.js')});

    const builtin = await page.evaluate(() => {
      const owner = document.createElement('section');
      owner.id = 'native-overlay';
      const button = document.createElement('button');
      button.textContent = 'Run';
      button.addEventListener('click', () => fixture.clicks++);
      owner.appendChild(button);
      document.getElementById('canvas').appendChild(owner);
      fixture.owner = owner;
      fixture.button = button;
      fixture.hosts = [];
      fixture.mount = (surface, facts = {}, options = {}) => {
        const host = document.createElement('span');
        owner.appendChild(host);
        const body = gdCanvasGraph.mount(owner, host, surface, facts, options);
        fixture.hosts.push(host);
        return {host, body};
      };
      fixture.mount('fn-name', {name: 'private', secret: true});
      fixture.mount('root-diagnostics', {root: true, 'type-errors': 2, 'failed-runs': 3, lint: 1});
      fixture.mount('branch-local', {'branch-state': 'inherited', 'branch-seed': 'http-server'},
        {titleElement: button, nativeTitle: 'Native branch title', titleSuffix: ' · readonly'});
      fixture.mount('deep-free', {'deep-free': ['first', 'last']});
      fixture.mount('fn-name');
      const sheets = [...new Set(fixture.hosts.map(host => host.dataset.gdUiStyle))];
      return {name: fixture.hosts[0].textContent, secret: !!owner.querySelector('.fn-row-secret-mark'),
        badges: [...fixture.hosts[1].children[0].children].map(el => [el.className, el.title]),
        branch: fixture.hosts[2].textContent, title: button.title,
        free: fixture.hosts[3].innerText.trim(), empty: fixture.hosts[4].textContent,
        sheets, sheetCount: document.querySelectorAll('style[data-gd-ui-styles]').length,
        owned: fixture.hosts.every(host => GraphdenRenderer.ownsTarget(host)),
        nativeOwned: GraphdenRenderer.ownsTarget(button), failures: fixture.failures};
    });
    assert.equal(builtin.name, '🔒 private');
    assert.equal(builtin.secret, true);
    assert.deepEqual(builtin.badges, [
      ['type-error-badge', '2 type errors on this fn'],
      ['failed-run-badge', '3 unresolved failed runs — see the Runs tab'],
      ['lint-badge', '1 lint finding — see the Inspector'],
    ]);
    assert.match(builtin.branch, /branch-local/);
    assert.match(builtin.title, /http-server.*readonly/);
    assert.equal(builtin.free, '⇣first ⇣last');
    assert.equal(builtin.empty, '');
    assert.equal(builtin.sheets.length, 1);
    assert.ok(builtin.sheets[0]);
    assert.equal(builtin.sheetCount, 1);
    assert.equal(builtin.owned, true);
    assert.equal(builtin.nativeOwned, false);
    assert.deepEqual(builtin.failures, []);

    const projection = await page.evaluate(() => {
      const rejected = [];
      for (const [surface, facts] of [
        ['root-diagnostics', {}], ['other', {}], ['fn-name', {id: 'forbidden-identity'}],
        ['fn-name', {secret: 'vault-content'}], ['fn-name', {'type-errors': -1}],
        ['fn-name', {'failed-runs': Number.MAX_SAFE_INTEGER + 1}],
        ['branch-local', {'branch-state': 'forged'}], ['deep-free', {'deep-free': [null]}],
      ]) {
        const host = document.createElement('span');
        try { gdCanvasGraph.mount(fixture.owner, host, surface, facts); rejected.push(false); }
        catch (_) { rejected.push(!GraphdenRenderer.ownsTarget(host) && !host.childNodes.length); }
      }
      return rejected;
    });
    assert.ok(projection.every(Boolean), 'invalid facts fail before renderer ownership or DOM changes');

    const selected = await page.evaluate(async () => {
      // Change ordinary plan literals, retaining its real bounded executor and
      // all four surfaces. This is selected presentation, without a server write.
      const literals = new Map([['gd-canvas-name', 'gd-canvas-personal'],
        ['& .gd-canvas-branch', '& .gd-canvas-personal'], ['contents', 'inline-flex']]);
      const replace = value => Array.isArray(value) ? value.map(replace)
        : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value)
          .map(([key, item]) => [key, replace(item)]))
        : literals.has(value) ? literals.get(value) : value;
      fixture.personalPlan = replace(GraphdenBuiltinPlans.plans.canvas);
      fixture.personal = GraphdenBrowser.createRuntime(fixture.personalPlan, {operationLimit: 150000});
      window.gdLoadUIComponentRuntime = async () => fixture.personal;
      fixture.button.focus();
      await gdCanvasGraph.reload();
      const scope = fixture.hosts[0].dataset.gdUiStyle;
      return {custom: !!fixture.hosts[0].querySelector('.gd-canvas-personal'),
        display: getComputedStyle(fixture.hosts[0].firstElementChild).display,
        shared: fixture.hosts.every(host => host.dataset.gdUiStyle === scope),
        scope, sheets: document.querySelectorAll('style[data-gd-ui-styles]').length,
        sameButton: fixture.owner.firstChild === fixture.button,
        focus: document.activeElement === fixture.button, failures: fixture.failures};
    });
    assert.equal(selected.custom, true);
    assert.equal(selected.display, 'inline-flex', 'real renderer retains shared scope after render');
    assert.equal(selected.shared, true);
    assert.equal(selected.sheets, 1);
    assert.notEqual(selected.scope, builtin.sheets[0]);
    assert.equal(selected.sameButton, true);
    assert.equal(selected.focus, true);
    assert.deepEqual(selected.failures, []);
    await page.locator('#native-overlay > button').click();
    assert.equal(await page.evaluate(() => fixture.clicks), 1);

    const passive = await page.evaluate(async () => {
      const k = GraphdenBrowser.keyword;
      const record = object => new Map(Object.entries(object).map(([key, value]) => [k(key), value]));
      const invalid = [
        [k('button'), 'Injected action'],
        [k('span'), record({id: 'gd-canvas-stolen'}), 'Identity'],
        [k('span'), record({class: 'fn-row-run-trigger'}), 'Native class'],
        [k('span'), record({tabindex: 0}), 'Focus'],
        [k('span'), record({'data-fn-id': 'forged'}), 'Authority'],
      ];
      const results = [];
      for (const tree of invalid) {
        // An adversarial selected runtime probes the adapter's extra passive
        // contract; it still goes through the actual renderer validator.
        window.gdLoadUIComponentRuntime = async () => ({run: () => record({tree, title: '', styles: []})});
        await gdCanvasGraph.reload();
        await Promise.resolve();
        results.push(fixture.owner.querySelectorAll('button').length === 1
          && fixture.hosts[0].textContent === '🔒 private'
          && fixture.owner.firstChild === fixture.button
          && !fixture.owner.querySelector('[data-fn-id], [tabindex]'));
      }
      return {results, failures: fixture.failures.length,
        scopes: [...new Set(fixture.hosts.map(host => host.dataset.gdUiStyle))]};
    });
    assert.ok(passive.results.every(Boolean), 'selected markup cannot add native actions, identity or focus');
    assert.equal(passive.failures, 5);
    assert.equal(passive.scopes.length, 1);
    assert.ok(passive.scopes[0]);

    const lifetime = await page.evaluate(() => {
      const owner = document.createElement('section');
      const host = document.createElement('span');
      owner.appendChild(host);
      document.getElementById('canvas').appendChild(owner);
      const names = Array.from({length: 240}, (_, n) => 'arg' + n);
      gdCanvasGraph.mount(owner, host, 'deep-free', {'deep-free': names});
      const allNames = names.every(name => host.textContent.includes('⇣' + name + ' '));
      gdCanvasGraph.dispose(owner);
      gdCanvasGraph.dispose(owner);
      const disposed = !GraphdenRenderer.ownsTarget(host) && !host.childNodes.length && !host.dataset.gdUiStyle;
      owner.remove();
      return {allNames, disposed,
        retained: fixture.hosts.every(body => GraphdenRenderer.ownsTarget(body) && body.dataset.gdUiStyle),
        sheets: document.querySelectorAll('style[data-gd-ui-styles]').length};
    });
    assert.deepEqual(lifetime, {allNames: true, disposed: true, retained: true, sheets: 1});

    const disposed = await page.evaluate(async () => {
      window.gdLoadUIComponentRuntime = (_name, _plan, _options, signal) => {
        fixture.pendingSignal = signal;
        return new Promise(resolve => { fixture.resolveLate = resolve; });
      };
      const pending = gdCanvasGraph.reload();
      window.dispatchEvent(new Event('gd-auth-changed'));
      fixture.resolveLate(fixture.personal);
      await pending;
      return {aborted: fixture.pendingSignal.aborted,
        empty: fixture.hosts.every(host => !host.childNodes.length && !host.dataset.gdUiStyle),
        released: fixture.hosts.every(host => !GraphdenRenderer.ownsTarget(host)),
        sheets: document.querySelectorAll('style[data-gd-ui-styles]').length,
        sameButton: fixture.owner.firstChild === fixture.button, clicks: fixture.clicks};
    });
    assert.deepEqual(disposed, {aborted: true, empty: true, released: true, sheets: 0, sameButton: true, clicks: 1});

    const switched = await page.evaluate(async () => {
      window.gdPrefsReady = true;
      window.gdPrefOwner = 'settled-owner-a';
      const {host} = fixture.mount('fn-name', {name: 'Owner A label', secret: true});
      const replies = [];
      window.gdLoadUIComponentRuntime = (_name, _plan, _options, signal) => new Promise(resolve => {
        replies.push({resolve, signal});
      });
      const previous = gdCanvasGraph.reload();
      window.gdPrefOwner = 'settled-owner-b'; // Cookie/prefs refresh, no auth event.
      const current = gdCanvasGraph.reload();
      const retiredBeforeReply = !host.childNodes.length && !host.dataset.gdUiStyle
        && !GraphdenRenderer.ownsTarget(host) && replies[0].signal.aborted;
      replies[0].resolve(fixture.personal);
      await previous;
      const ignoredPrevious = !host.childNodes.length;
      replies[1].resolve(fixture.personal);
      await current;
      const ignoredCurrent = !host.childNodes.length;
      const fresh = fixture.mount('fn-name', {name: 'Owner B label'}).host;
      const newOwner = fresh.textContent === 'Owner B label'
        && !!fresh.querySelector('.gd-canvas-personal');
      gdCanvasGraph.dispose(fixture.owner);
      return {retiredBeforeReply, ignoredPrevious, ignoredCurrent, newOwner,
        sameButton: fixture.owner.firstChild === fixture.button,
        sheets: document.querySelectorAll('style[data-gd-ui-styles]').length};
    });
    assert.deepEqual(switched, {retiredBeforeReply: true, ignoredPrevious: true,
      ignoredCurrent: true, newOwner: true, sameButton: true, sheets: 0});

    const mountedScope = await page.evaluate(async () => {
      window.gdLoadUIComponentRuntime = async () => fixture.personal;
      await gdCanvasGraph.reload();
      const previous = fixture.mount('fn-name', {name: 'Previous owner'}).host;
      const wasPersonal = !!previous.querySelector('.gd-canvas-personal');
      window.gdPrefOwner = 'mount-owner-c';
      const current = fixture.mount('fn-name', {name: 'Current owner'}).host;
      const builtinBeforeReload = !!current.querySelector('.gd-canvas-name')
        && !current.querySelector('.gd-canvas-personal') && current.textContent === 'Current owner';
      let release;
      let signal;
      window.gdLoadUIComponentRuntime = (_name, _plan, _options, abortSignal) => {
        signal = abortSignal;
        return new Promise(resolve => { release = resolve; });
      };
      const pending = gdCanvasGraph.reload();
      window.gdPrefOwner = 'mount-owner-d';
      const fresh = fixture.mount('fn-name', {name: 'Fresh owner'}).host;
      release(fixture.personal);
      await pending;
      const result = {wasPersonal, builtinBeforeReload, aborted: signal.aborted,
        oldHostsEmpty: !previous.childNodes.length && !current.childNodes.length,
        lateIgnored: fresh.textContent === 'Fresh owner' && !!fresh.querySelector('.gd-canvas-name')
          && !fresh.querySelector('.gd-canvas-personal')};
      gdCanvasGraph.dispose(fixture.owner);
      return result;
    });
    assert.deepEqual(mountedScope, {wasPersonal: true, builtinBeforeReload: true,
      aborted: true, oldHostsEmpty: true, lateIgnored: true});

    const hydration = await page.evaluate(async () => {
      window.gdAccount = {id: 'same-auth-account'};
      window.isAuthenticated = () => true;
      window.gdPrefOwner = null;
      window.gdPrefsReady = false;
      const {host} = fixture.mount('fn-name', {name: 'Hydrated label'});
      const nativeButton = fixture.button;
      const bodyNode = host.firstElementChild;
      nativeButton.focus();
      window.gdPrefOwner = 'first-authorized-owner';
      window.gdPrefsReady = true;
      window.gdLoadUIComponentRuntime = async () => fixture.personal;
      await gdCanvasGraph.reload();
      window.dispatchEvent(new Event('gd-graph-changed'));
      const result = {connected: host.isConnected && bodyNode.isConnected,
        sameBody: host.firstElementChild === bodyNode, text: host.textContent,
        owned: GraphdenRenderer.ownsTarget(host), scoped: !!host.dataset.gdUiStyle,
        sameButton: fixture.owner.firstChild === nativeButton,
        focus: document.activeElement === nativeButton};
      gdCanvasGraph.dispose(fixture.owner);
      return result;
    });
    assert.deepEqual(hydration, {connected: true, sameBody: true, text: 'Hydrated label',
      owned: true, scoped: true, sameButton: true, focus: true});
    assert.deepEqual(errors, [], 'no browser errors');
    console.log('PASS passive canvas: shipped/selected plans, ownership, scopes, auth replies and owner hydration');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
