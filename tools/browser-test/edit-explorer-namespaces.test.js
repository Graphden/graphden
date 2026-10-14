// Graph-presented namespace bodies inside the native lazy Explorer tree.
const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  let stage = 'boot';
  const location = error => /[a-z-]+\.js:\d+:\d+/.exec(error.stack || '')?.[0] || error.name;
  const knownError = error => ['Invalid Explorer namespace body', 'Explorer namespace style must stay inside its body',
    'Unsupported graph element', 'Graph style must be a map', 'Invalid Explorer row', 'Invalid Explorer toolbar']
    .find(message => error.message === message) || error.name;
  page.on('pageerror', error => { errors.push(error.name); console.error('Explorer pageerror at ' + location(error) + ': ' + knownError(error)); });
  page.on('console', message => { if (message.type() === 'error') errors.push('console-error'); });
  try {
    await page.goto(BASE + '/');
    await page.waitForSelector('[data-ns-path="core"] .gd-explorer-namespace');
    const initial = await page.evaluate(() => {
      const list = document.getElementById('entity-list');
      const header = list.querySelector('[data-ns-path="core"]');
      const pseudo = list.querySelector('.ns-header-pseudo');
      const body = header.querySelector('.gd-explorer-namespace-host');
      const sheet = list.dataset.gdUiStyle;
      return {role: header.getAttribute('role'), label: header.getAttribute('aria-label'),
        level: header.getAttribute('aria-level'), expanded: header.getAttribute('aria-expanded'),
        path: header.dataset.nsPath, bodyOwned: GraphdenRenderer.ownsTarget(body),
        listOwned: GraphdenRenderer.ownsTarget(list),
        pseudo: pseudo ? {role: pseudo.getAttribute('role'), label: pseudo.getAttribute('aria-label'),
          count: pseudo.querySelector('.ns-count')?.textContent} : null,
        sheets: document.querySelectorAll('style[data-gd-ui-styles="' + sheet + '"]').length};
    });
    assert(initial.role === 'treeitem' && initial.label === 'core' && initial.level === '1'
      && initial.expanded === 'false' && initial.path === 'core' && initial.bodyOwned
      && !initial.listOwned && initial.sheets === 1,
    'native tree identity and accessible name wrap one graph namespace body and shared sheet');
    assert(initial.pseudo?.role === 'treeitem' && initial.pseudo.label === '(primitives)'
      && Number.isSafeInteger(Number(initial.pseudo.count)),
    'pseudo namespace uses the same graph surface and an authorized native accessible name');
    await page.locator('[data-ns-path="core"] .ns-label').click();
    await page.waitForSelector('[data-ns-path="core.arithmetic"]');
    assert(await page.locator('[data-ns-path="core"]').getAttribute('aria-expanded') === 'true'
      && await page.locator('[data-ns-path="core"] .ns-arrow').textContent() === '▼',
    'native lazy expansion updates ARIA and the graph arrow together');
    await page.locator('[data-ns-path="core"] .ns-label').click();
    assert(await page.locator('[data-ns-path="core"]').getAttribute('aria-expanded') === 'false'
      && await page.locator('[data-ns-path="core"] .ns-arrow').textContent() === '▶',
    'collapse disposes children and refreshes only this header body');
    await page.screenshot({path: '/tmp/gd-explorer-namespaces-light.png'});
    await page.evaluate(() => document.body.classList.add('theme-dark'));
    await page.screenshot({path: '/tmp/gd-explorer-namespaces-dark.png'});
    await page.evaluate(() => document.body.classList.remove('theme-dark'));

    stage = 'scale';
    const scale = await page.evaluate(() => {
      const list = document.getElementById('entity-list');
      const group = document.createElement('div');
      list.appendChild(group);
      for (let index = 0; index < 540; index++) {
        const host = document.createElement('span');
        group.appendChild(host);
        gdExplorerGraph.mountNamespace(host, {name: 'namespace ' + index, expanded: false,
          pseudo: false, count: 0, 'type-errors': 0, failed: 0, lint: 0});
      }
      const first = group.firstElementChild;
      const result = {bodies: group.querySelectorAll('.gd-explorer-namespace').length,
        labels: group.querySelectorAll('.ns-label').length,
        sheets: document.querySelectorAll('style[data-gd-ui-styles="' + list.dataset.gdUiStyle + '"]').length};
      gdExplorerGraph.disposeRows(group);
      result.disposed = !GraphdenRenderer.ownsTarget(first);
      group.remove();
      return result;
    });
    assert(scale.bodies === 540 && scale.labels === 540 && scale.sheets === 1 && scale.disposed,
    '540 loaded namespace bodies have no picker cap, share one style and dispose cleanly');

    stage = 'personal layout and isolation';
    const custom = await page.evaluate(async () => {
      const previousLoader = window.gdLoadUIComponentRuntime;
      const previousFallback = window.gdUIComponentSectionFallback;
      const plan = GraphdenBuiltinPlans.plans.explorer;
      const builtin = GraphdenBrowser.createRuntime(plan);
      const key = GraphdenBrowser.keyword;
      let fallbackCount = 0;
      let invalid = '';
      window.gdUIComponentSectionFallback = (...args) => {
        fallbackCount++;
        previousFallback?.(...args);
      };
      window.gdLoadUIComponentRuntime = async () => ({run(entry, supplied) {
        const accepted = new Set(plan.inputs[plan.entries[entry]].accepted);
        const args = Object.fromEntries(Object.entries(supplied).filter(([name]) => accepted.has(name)));
        const view = builtin.run(entry, args);
        if (entry !== 'view' || args.inputs.get(key('surface')) !== 'namespace') return view;
        const name = args.inputs.get(key('namespace')).get(key('name'));
        const changed = new Map(view);
        changed.set(key('tree'), invalid === 'button'
          ? ['button', new Map([[key('type'), 'button']]), name]
          : invalid === 'class'
            ? ['span', new Map([[key('class'), 'ns-header']]), name]
            : ['span', new Map([[key('class'), 'gd-explorer-namespace personal']]),
              ['strong', new Map(), name + ' custom']]);
        if (invalid === 'style') changed.set(key('styles'), [new Map([
          [key('selector'), '& .ns-row-actions'],
          [key('declarations'), new Map([['display', 'none']])],
        ])]);
        return changed;
      }});
      try {
        await gdExplorerGraph.reload();
        const header = document.querySelector('[data-ns-path="core"]');
        const personal = header.querySelector('.gd-explorer-namespace.personal strong')?.textContent;
        const label = header.getAttribute('aria-label');
        const actions = header.querySelector('.ns-row-actions');
        invalid = 'button';
        await gdExplorerGraph.reload();
        const buttonRestored = !!header.querySelector('.gd-explorer-namespace .ns-label');
        invalid = 'class';
        await gdExplorerGraph.reload();
        const classRestored = !!header.querySelector('.gd-explorer-namespace .ns-label');
        invalid = 'style';
        await gdExplorerGraph.reload();
        return {personal, label, actions: !!actions, fallbackCount,
          restored: buttonRestored && classRestored
            && !!header.querySelector('.gd-explorer-namespace .ns-label'),
          toolbar: document.querySelectorAll('#kind-filters button').length,
          status: gdUIComponentsStatus()};
      } finally {
        window.gdUIComponentSectionFallback = previousFallback;
        window.gdLoadUIComponentRuntime = previousLoader;
        await gdExplorerGraph.reload();
      }
    });
    assert(custom.personal === 'core custom' && custom.label === 'core'
      && custom.actions && custom.restored && custom.fallbackCount === 3
      && custom.toolbar === 11 && custom.status.includes('namespaces'),
    'safe personal layout is accepted; forged controls, host classes and sibling styles fall back only in namespace section');

    stage = 'server-selected source';
    const server = await page.evaluate(async () => {
      const response = await authFetch(API.api_ui_configuration_plan, {method: 'POST', cache: 'no-store',
        headers: {'Content-Type': 'application/json'}, body: JSON.stringify({component: 'explorer'})});
      const result = await response.json();
      if (!response.ok || !result.ok || !result.plan) return {available: false};
      const key = GraphdenBrowser.keyword;
      const record = object => new Map(Object.entries(object).map(([name, value]) => [key(name), value]));
      const runtime = GraphdenBrowser.createRuntime(result.plan, {operationLimit: 600});
      const namespace = record({name: 'server namespace', expanded: true, pseudo: true,
        count: 540, 'type-errors': 1, failed: 2, lint: 3});
      const view = runtime.run('view', {inputs: record({surface: 'namespace', namespace,
        selected: new Map(), counts: new Map(), failed: 0, authenticated: false,
        'apps-available': false, fx: false})});
      const tree = GraphdenRenderer.vnode(view.get(key('tree')), 'gd-server-namespace', 48);
      return {available: true, label: tree.props.children[1]?.props.children,
        count: tree.props.children[2]?.props.children,
        chips: tree.props.children.slice(3).map(item => item?.props?.className),
        styles: GraphdenStyles.normalize(view.get(key('styles'))).length};
    });
    assert(server.available && server.label === 'server namespace' && server.count === 540
      && JSON.stringify(server.chips) === JSON.stringify([
        'ns-type-error-chip', 'ns-problem-chip ns-failed-chip', 'ns-problem-chip ns-lint-chip'])
      && server.styles > 0,
    'the installed, server-selected Explorer plan exports namespace body and scoped styles');
    stage = 'native rename';
    await page.locator('[data-ns-path="core"] .ns-edit-btn').click();
    assert(await page.evaluate(() => {
      const header = document.querySelector('[data-ns-path="core"]');
      return header?.querySelector('.gd-explorer-namespace-host')?.style.display === 'none'
        && !!header.querySelector('input');
    }), 'native rename hides the entire graph body while its inline input owns the row');
    await page.locator('[data-ns-path="core"] input').press('Escape');
    await page.waitForSelector('[data-ns-path="core"] .gd-explorer-namespace');
    assert(errors.length === 0, 'no browser errors');
    console.log('PASS Explorer namespace graph, native tree, 540-header scale and personal isolation');
  } catch (error) {
    console.error('FAIL Explorer namespaces during ' + stage + ': ' + error.name);
    process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(() => { console.error('FAIL Explorer namespaces; payloads withheld'); process.exitCode = 1; });
