// Graph-presented function-row bodies inside the native lazy Explorer tree.
const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  const location = error => /(?:editor|graph)-(?:[a-z-]+)\.js:\d+:\d+/.exec(error.stack || '')?.[0] || error.name;
  let stage = 'boot';
  page.on('pageerror', error => { errors.push(error.name); console.error('Explorer pageerror at ' + location(error)); });
  page.on('console', message => { if (message.type() === 'error') errors.push('console-error'); });
  try {
    await page.goto(BASE + '/');
    await page.waitForSelector('[data-ns-path="core"]');
    await page.locator('[data-ns-path="core"] .ns-label').click();
    await page.waitForSelector('[data-ns-path="core.arithmetic"]');
    await page.locator('[data-ns-path="core.arithmetic"] .ns-label').click();
    await page.waitForSelector('#entity-list .entity-item .gd-explorer-row .name');
    const initial = await page.evaluate(() => {
      const list = document.getElementById('entity-list');
      const row = list.querySelector('.entity-item');
      const host = row.querySelector('.gd-explorer-row-host');
      const name = row.querySelector('.gd-explorer-row .name');
      const sheet = list.dataset.gdUiStyle;
      return {role: row.getAttribute('role'), level: row.getAttribute('aria-level'),
        id: row.dataset.fnId, name: name.textContent,
        hostOwned: GraphdenRenderer.ownsTarget(host), listOwned: GraphdenRenderer.ownsTarget(list),
        sheets: document.querySelectorAll('style[data-gd-ui-styles="' + sheet + '"]').length};
    });
    assert(initial.role === 'treeitem' && Number(initial.level) > 0 && initial.id && initial.name,
      'native tree identity and ARIA wrap a graph-presented name');
    assert(initial.hostOwned && !initial.listOwned && initial.sheets === 1,
      'each row body has its own renderer while the list has one scoped stylesheet');
    await page.locator('#entity-list .entity-item .gd-explorer-row .name').first().click();
    assert(await page.evaluate(() => {
      const row = document.querySelector('#entity-list .entity-item.selected');
      return row?.dataset.fnId === selectedFnId && row.getAttribute('aria-selected') === 'true';
    }), 'click selection remains owned by the native treeitem');
    await page.screenshot({path: '/tmp/gd-explorer-rows-light.png'});
    await page.evaluate(() => document.body.classList.add('theme-dark'));
    await page.screenshot({path: '/tmp/gd-explorer-rows-dark.png'});
    await page.evaluate(() => document.body.classList.remove('theme-dark'));

    stage = 'scale';
    const scale = await page.evaluate(() => {
      const list = document.getElementById('entity-list');
      const container = document.createElement('div');
      list.appendChild(container);
      const row = {name: 'plain', secret: false, 'secret-path': '', service: false,
        'service-state': '', app: false, 'app-hosts': '', type: false, test: false,
        'test-status': '', 'test-error': '', failed: 0, 'type-errors': 0, lint: 0, effects: ''};
      for (let n = 0; n < 240; n++) {
        const host = document.createElement('span');
        container.appendChild(host);
        gdExplorerGraph.mountRow(host, {...row, name: 'plain ' + n});
      }
      const keyword = GraphdenBrowser.keyword;
      const record = object => new Map(Object.entries(object).map(([key, value]) => [keyword(key), value]));
      const runtime = GraphdenBrowser.createRuntime(GraphdenBuiltinPlans.plans.explorer, {operationLimit: 600});
      const full = {...row, secret: true, 'secret-path': '/demo', service: true,
        'service-state': 'running', app: true, 'app-hosts': 'example.test', type: true,
        test: true, 'test-status': 'failed', 'test-error': 'oops', failed: 2,
        'type-errors': 1, lint: 3, effects: 'io, time'};
      const output = runtime.run('view', {inputs: record({surface: 'row', row: record(full),
        selected: new Map(), counts: new Map(), failed: 0, authenticated: false,
        'apps-available': false, fx: false})});
      const sheets = document.querySelectorAll('style[data-gd-ui-styles="' + list.dataset.gdUiStyle + '"]').length;
      const result = {rows: container.querySelectorAll('.gd-explorer-row').length,
        spans: container.querySelectorAll('span').length,
        names: container.querySelectorAll('.gd-explorer-row .name').length,
        sheets, fullMarkers: output.get(keyword('tree')).filter(Array.isArray).length};
      const fullHost = document.createElement('span');
      container.appendChild(fullHost);
      gdExplorerGraph.mountRow(fullHost, full);
      const failure = fullHost.querySelector('.kind-marker-failed');
      result.fullDom = fullHost.querySelectorAll('.gd-explorer-row > span').length;
      result.fullTitle = failure?.title;
      result.fullStyle = failure && getComputedStyle(failure).color !== getComputedStyle(list).color;
      const testKind = window.isTestFn;
      const testStatus = window.getTestStatusForFnId;
      try {
        window.isTestFn = () => true;
        window.getTestStatusForFnId = () => ({status: 'failed', error: 'x'.repeat(5000)});
        const projected = explorerRowProjection({id: 'fixture', displayName: 'fixture'}, false);
        const longHost = document.createElement('span');
        container.appendChild(longHost);
        gdExplorerGraph.mountRow(longHost, projected);
        result.longTitle = longHost.querySelector('.kind-marker-test')?.title.length;
      } finally {
        window.isTestFn = testKind;
        window.getTestStatusForFnId = testStatus;
      }
      const first = container.firstElementChild;
      gdExplorerGraph.disposeRows(container);
      result.disposed = !GraphdenRenderer.ownsTarget(first);
      container.remove();
      return result;
    });
    assert(scale.rows === 240 && scale.names === 240 && scale.spans === 720,
      '240 loaded row presentations have three spans each, with no picker cap or empty marker nodes');
    assert(scale.sheets === 1 && scale.disposed && scale.fullMarkers === 11
      && scale.fullDom === 11 && scale.fullTitle === '2 unresolved failed runs' && scale.fullStyle
      && scale.longTitle === 4015,
      'large row set shares one sheet, disposes cleanly and bounded styled badges fit a 600-operation budget');

    stage = 'compare scale';
    const compareScale = await page.evaluate(() => {
      const group = document.createElement('div');
      document.getElementById('entity-list').appendChild(group);
      for (let index = 0; index < 240; index++) {
        const host = document.createElement('span');
        group.appendChild(host);
        gdExplorerGraph.mountDiffItem(host, {kind: 'modified', title: 'Changed',
          summary: 'row ' + index, branch: 'review', counts: {}});
      }
      const first = group.firstElementChild;
      const sheet = first.dataset.gdUiStyle;
      const result = {bodies: group.querySelectorAll('.gd-diff-body').length,
        labels: group.querySelectorAll('.gd-diff-label').length,
        shared: Array.from(group.children).every(host => host.dataset.gdUiStyle === sheet),
        sheets: document.querySelectorAll('style[data-gd-ui-styles="' + sheet + '"]').length};
      gdExplorerGraph.disposeDiff(group);
      result.disposed = !GraphdenRenderer.ownsTarget(first);
      group.remove();
      return result;
    });
    assert(compareScale.bodies === 240 && compareScale.labels === 240
      && compareScale.shared && compareScale.sheets === 1 && compareScale.disposed,
    '240 compare bodies share one scoped sheet and dispose without a tree cap: '
      + JSON.stringify(compareScale));

    stage = 'server graph parity';
    const serverParity = await page.evaluate(async () => {
      const response = await authFetch(API.api_ui_configuration_plan, {method: 'POST', cache: 'no-store',
        headers: {'Content-Type': 'application/json'}, body: JSON.stringify({component: 'explorer'})});
      const result = await response.json();
      if (!response.ok || !result.ok || !result.plan) return {available: false};
      const keyword = GraphdenBrowser.keyword;
      const record = object => new Map(Object.entries(object).map(([key, value]) => [keyword(key), value]));
      const runtime = GraphdenBrowser.createRuntime(result.plan, {operationLimit: 600});
      const row = {name: 'server row', secret: false, 'secret-path': '', service: true,
        'service-state': '', app: false, 'app-hosts': '', type: false, test: true,
        'test-status': '', 'test-error': '', failed: 0, 'type-errors': 0, lint: 0, effects: ''};
      const markers = changes => {
        const view = runtime.run('view', {inputs: record({surface: 'row', row: record({...row, ...changes}),
          selected: new Map(), counts: new Map(), failed: 0, authenticated: false,
          'apps-available': false, fx: false})});
        const nodes = [GraphdenRenderer.vnode(view.get(keyword('tree')), 'gd-server-row', 48)];
        const found = {};
        while (nodes.length) {
          const node = nodes.pop();
          if (node?.props?.className?.includes('kind-marker-service')) found.service = node.props.className;
          if (node?.props?.className?.includes('kind-marker-test')) found.test = node.props.className;
          nodes.push(...[node?.props?.children].flat().filter(Boolean));
        }
        return found;
      };
      return {available: true,
        tests: ['succeeded', 'failed', ''].map(value => markers({'test-status': value}).test),
        services: ['running', 'failed', 'backoff', 'exited', 'elsewhere', 'disabled', ''].map(value => markers({'service-state': value}).service)};
    });
    assert(serverParity.available
      && JSON.stringify(serverParity.tests) === JSON.stringify([
        'fn-kind-marker kind-marker-test test-passed',
        'fn-kind-marker kind-marker-test test-failed',
        'fn-kind-marker kind-marker-test test-stale'])
      && JSON.stringify(serverParity.services) === JSON.stringify([
        'fn-kind-marker kind-marker-service svc-running',
        'fn-kind-marker kind-marker-service svc-failed',
        'fn-kind-marker kind-marker-service svc-backoff',
        'fn-kind-marker kind-marker-service svc-exited',
        'fn-kind-marker kind-marker-service svc-elsewhere',
        'fn-kind-marker kind-marker-service svc-disabled',
        'fn-kind-marker kind-marker-service']),
    'server-resolved Explorer graph preserves every row status class across package import');

    stage = 'fallback';
    const fallback = await page.evaluate(async () => {
      const original = window.gdLoadUIComponentRuntime;
      const sectionFallback = window.gdUIComponentSectionFallback;
      let fallbackCalls = 0;
      const surfaces = [];
      window.gdUIComponentSectionFallback = (...args) => { fallbackCalls++; sectionFallback?.(...args); };
      const plan = GraphdenBuiltinPlans.plans.explorer;
      const builtin = GraphdenBrowser.createRuntime(plan);
      const keyword = GraphdenBrowser.keyword;
      window.gdLoadUIComponentRuntime = async () => ({run(entry, supplied) {
        const accepted = new Set(plan.inputs[plan.entries[entry]].accepted);
        const args = Object.fromEntries(Object.entries(supplied).filter(([name]) => accepted.has(name)));
        if (entry === 'view') {
          surfaces.push(args.inputs?.get(keyword('surface')) || 'missing');
          const inputs = new Map(args.inputs);
          inputs.set(keyword('surface'), 'toolbar');
          args.inputs = inputs;
        }
        return builtin.run(entry, args);
      }});
      const diffGroup = document.createElement('div');
      const diffItem = document.createElement('span');
      const diffLens = document.createElement('div');
      diffGroup.append(diffItem, diffLens);
      document.getElementById('entity-list').appendChild(diffGroup);
      try {
        await gdExplorerGraph.reload();
        gdExplorerGraph.mountDiffItem(diffItem, {kind: 'modified', title: 'Changed',
          summary: 'value changed', branch: 'review', counts: {}});
        gdExplorerGraph.mountDiffLens(diffLens, {changedOnly: false, added: true,
          modified: true, missing: true, inside: true, substantiveOnly: false,
          notes: true, effectsOnly: false});
        return {toolbar: document.querySelectorAll('#kind-filters button').length,
          names: document.querySelectorAll('#entity-list .entity-item .gd-explorer-row .name').length,
          diffBadge: diffItem.querySelector('.gd-diff-badge')?.textContent,
          diffButtons: diffLens.querySelectorAll('button[data-kind]').length,
          status: gdUIComponentsStatus(), fallbackCalls, surfaces};
      } finally {
        gdExplorerGraph.disposeDiff(diffGroup);
        diffGroup.remove();
        window.gdUIComponentSectionFallback = sectionFallback;
        window.gdLoadUIComponentRuntime = original;
        await gdExplorerGraph.reload();
      }
    });
    assert(fallback.toolbar === 11 && fallback.names > 0 && fallback.status.includes('compare')
      && fallback.diffBadge === '±' && fallback.diffButtons === 8
      && fallback.fallbackCalls === 3
      && fallback.surfaces.includes('row') && fallback.surfaces.includes('namespace')
      && fallback.surfaces.includes('diff-item'),
    'legacy personal toolbar keeps row, namespace and compare sections on the built-in graph');
    stage = 'auth lifetime';
    const authChange = await page.evaluate(async () => {
      const list = document.getElementById('entity-list');
      const priorGraph = graphData;
      const oldHost = list.querySelector('.gd-explorer-row-host');
      const oldNamespaceHost = list.querySelector('.gd-explorer-namespace-host');
      const style = list.dataset.gdUiStyle;
      const secret = '/previous-owner/private';
      const fixture = document.createElement('span');
      list.appendChild(fixture);
      gdExplorerGraph.mountRow(fixture, {name: 'old secret', secret: true, 'secret-path': secret,
        service: false, 'service-state': '', app: false, 'app-hosts': '', type: false,
        test: false, 'test-status': '', 'test-error': '', failed: 0,
        'type-errors': 0, lint: 0, effects: ''});
      _secretsList = [{id: 'old-secret', path: secret}];
      const oldFetch = window.fetch;
      let release;
      window.fetch = (url, options) => String(url).includes('?scope=tree')
        ? new Promise(resolve => { release = () => oldFetch(url, options).then(resolve); })
        : oldFetch(url, options);
      try {
        window.dispatchEvent(new Event('gd-auth-changed'));
        const loading = initGraph();
        updateEntityList(priorGraph);
        const blocked = graphData === null && _knownFns.size === 0 && _secretsList.length === 0
          && !list.textContent.includes(secret) && !list.querySelector('.entity-item')
          && !GraphdenRenderer.ownsTarget(oldHost)
          && !GraphdenRenderer.ownsTarget(oldNamespaceHost)
          && !list.dataset.gdUiStyle
          && !document.querySelector('style[data-gd-ui-styles="' + style + '"]');
        release();
        await loading;
        return {blocked, restored: !!graphData && !!list.querySelector('[data-ns-path="core"]')};
      } finally { window.fetch = oldFetch; }
    });
    assert(authChange.blocked && authChange.restored,
      'auth change removes old row data, secret metadata and scoped style until a fresh authorized tree arrives');
    assert(errors.length === 0, 'no browser errors');
    console.log('PASS Explorer graph rows, native selection, shared styles, 240-row scale and section fallback');
  } catch (error) {
    console.error('FAIL Explorer graph rows during ' + stage + ' at ' + location(error));
    process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(() => { console.error('FAIL Explorer graph rows; payloads withheld'); process.exitCode = 1; });
