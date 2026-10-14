// Explorer active filters and the Add filter chooser use the same graph source.
// Native buttons retain exact identities, focus and the saved-view mutation API.
// Run: GRAPHDEN_URL=http://localhost:<port> node tools/browser-test/edit-explorer-filters-graph.test.js
const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  let stage = 'boot';
  page.on('pageerror', error => { errors.push(error.name); });
  page.on('console', message => { if (message.type() === 'error') errors.push('console-error'); });
  try {
    await page.goto(BASE + '/');
    await page.waitForSelector('[data-ns-path="core"] .gd-explorer-namespace');
    await page.evaluate(() => gdClearFilters());

    stage = 'stable chooser and actions';
    await page.evaluate(() => {
      window.__originalFilterViews = window.gdFetchSharedViews;
      window.gdInvalidateSharedViews();
      window.gdFetchSharedViews = () => new Promise(resolve => {
        window.__releaseFilterViews = () => resolve([]);
      });
    });
    await page.locator('#gd-filter-add').click();
    await page.waitForSelector('.gd-filter-add-pop [data-effect="io"] .gd-filter-option-body');
    const menu = await page.evaluate(() => {
      const el = document.querySelector('.gd-filter-add-pop');
      const root = el.querySelector('[data-ws="core"]');
      const effect = el.querySelector('[data-effect="io"]');
      return {role: el.getAttribute('role'), label: el.getAttribute('aria-label'),
        root: root?.textContent, rootRole: root?.getAttribute('role'),
        rootChecked: root?.getAttribute('aria-checked'), effect: effect?.textContent,
        effectPressed: effect?.getAttribute('aria-pressed'),
        graphOwned: GraphdenRenderer.ownsTarget(effect?.querySelector('.gd-filter-graph-host')),
        sheet: el.dataset.gdUiStyle,
        sheetCount: document.querySelectorAll('style[data-gd-ui-styles="' + el.dataset.gdUiStyle + '"]').length};
    });
    assert(menu.role === 'dialog' && menu.label === 'Add a filter'
      && menu.root === '☐ core' && menu.rootRole === 'checkbox' && menu.rootChecked === 'false'
      && menu.effect === '☐ io' && menu.effectPressed === 'false'
      && menu.graphOwned && menu.sheet && menu.sheetCount === 1,
    'native accessible chooser shells wrap graph-owned option bodies and one shared sheet');
    await page.screenshot({path: '/tmp/gd-explorer-filter-chooser-light.png'});

    const name = page.locator('.gd-filter-add-pop input[aria-label="Only names containing"]');
    await name.fill('retained draft');
    await name.focus();
    await page.evaluate(() => {
      window.__filterDraftNode = document.querySelector('.gd-filter-add-pop input[aria-label="Only names containing"]');
      window.__releaseFilterViews();
    });
    assert(await page.evaluate(() => {
      const input = document.querySelector('.gd-filter-add-pop input[aria-label="Only names containing"]');
      return input === window.__filterDraftNode && input.value === 'retained draft'
        && document.activeElement === input;
    }), 'late shared-view refresh preserves the native input node, draft and focus');
    await page.evaluate(() => { window.gdFetchSharedViews = window.__originalFilterViews; });

    await page.evaluate(() => {
      window.__oldFilterChoice = document.querySelector('.gd-filter-add-pop [data-effect="io"]');
    });
    await page.locator('.gd-filter-add-pop [data-effect="io"]').click();
    assert(await page.evaluate(() => {
      window.__oldFilterChoice.click();
      return gdFilters().effects.includes('io');
    }), 'a detached option cannot replay a stale native filter choice');
    await page.locator('.gd-filter-add-pop [data-ws="core"]').click();
    assert(await page.evaluate(() => {
      const input = document.querySelector('.gd-filter-add-pop input[aria-label="Only names containing"]');
      return input === window.__filterDraftNode && input.value === 'retained draft'
        && gdFilters().effects.includes('io') && gdFilters().namespaces.includes('core')
        && document.querySelector('.gd-filter-add-pop [data-effect="io"]').getAttribute('aria-pressed') === 'true'
        && [...document.querySelectorAll('#gd-filter-chips .kind-label')].some(node => node.textContent === 'fx io')
        && [...document.querySelectorAll('#gd-filter-chips .kind-label')].some(node => node.textContent === 'in core');
    }), 'typed graph selection reaches exact native filter verbs without remounting the draft');
    await name.focus();
    await name.press('Enter');
    assert(await page.evaluate(() => gdFilters().name === 'retained draft'
      && !!document.querySelector('#gd-filter-chips .kind-label')
      && document.activeElement === window.__filterDraftNode),
    'name input keeps focus while a new graph-presented chip appears');
    await page.locator('.gd-filter-add-pop [data-action="unused"]').click();
    assert(await page.evaluate(() => gdFilters().unused
      && document.querySelector('.gd-filter-add-pop [data-action="unused"]').getAttribute('aria-checked') === 'true'),
    'unused choice updates the native model and graph selection mark');

    stage = 'restore and picker';
    await page.evaluate(() => { gdCloseFilterAdd(); gdToggleExclude('core.tests'); });
    await page.locator('#gd-filter-add').click();
    assert(await page.locator('.gd-filter-add-pop [data-restore="core.tests"]').textContent() === '↺ core.tests',
      'restore exclusion is graph-presented without a forged target');
    await page.locator('.gd-filter-add-pop [data-restore="core.tests"]').click();
    assert(await page.evaluate(() => !gdFilters().exclude.includes('core.tests')), 'restore invokes the exact native axis');
    await page.locator('.gd-filter-add-pop [data-action="add-uses"]').click();
    await page.waitForSelector('.fn-picker-popover');
    assert(await page.locator('.gd-filter-add-pop').count() === 0, 'native picker replaces the chooser dialog');
    await page.keyboard.press('Escape');

    stage = 'large option set and server source';
    const scale = await page.evaluate(() => {
      const before = graphData.namespaces;
      graphData.namespaces = [...before, ...Array.from({length: 540}, (_, i) => ({
        id: 'synthetic-' + i, name: 'synthetic-' + i, 'parent-id': null,
      }))];
      gdOpenFilterAdd(document.getElementById('gd-filter-add'));
      const el = document.querySelector('.gd-filter-add-pop');
      const result = {count: el.querySelectorAll('[data-ws^="synthetic-"]').length,
        owned: GraphdenRenderer.ownsTarget(el.querySelector('[data-ws="synthetic-0"] .gd-filter-graph-host')),
        sheets: document.querySelectorAll('style[data-gd-ui-styles="' + el.dataset.gdUiStyle + '"]').length};
      gdCloseFilterAdd();
      graphData.namespaces = before;
      return result;
    });
    assert(scale.count === 540 && scale.owned && scale.sheets === 1,
      '540 choices render per item without a whole-Explorer cap or per-option stylesheet');
    const server = await page.evaluate(async () => {
      const response = await authFetch(API.api_ui_configuration_plan, {method: 'POST', cache: 'no-store',
        headers: {'Content-Type': 'application/json'}, body: JSON.stringify({component: 'explorer'})});
      const result = await response.json();
      if (!response.ok || !result.ok || !result.plan) return {available: false, status: response.status};
      const key = GraphdenBrowser.keyword;
      const record = object => new Map(Object.entries(object).map(([name, value]) => [key(name), value]));
      const runtime = GraphdenBrowser.createRuntime(result.plan, {operationLimit: 1000});
      const input = (surface, filter) => record({surface, filter: record(filter), selected: new Map(),
        counts: new Map(), failed: 0, authenticated: false, 'apps-available': false, fx: false});
      const textOf = node => {
        if (node == null || node === false) return '';
        if (Array.isArray(node)) return node.map(textOf).join('');
        if (typeof node === 'object') return textOf(node.props?.children);
        return String(node);
      };
      const chips = [['namespace', 'core', 'in core'], ['exclude', 'core', 'not core'],
        ['uses', 'core', 'uses core'], ['effect', 'io', 'fx io'], ['unused', 'Unused', 'Unused'],
        ['name', 'needle', 'name needle'], ['view', 'saved', 'view saved']]
        .map(([axis, name, expected]) => {
          const view = runtime.run('view', {inputs: input('filter-chip',
            {axis, name, selected: true, missing: false})});
          return [axis, textOf(GraphdenRenderer.vnode(view.get(key('tree')), axis, 48)), expected];
        });
      const missing = runtime.run('view', {inputs: input('filter-chip',
        {axis: 'view', name: 'saved', selected: true, missing: true})});
      const options = [['namespace', 'core', false, '☐ core'], ['restore', 'core.old', false, '↺ core.old'],
        ['uses-picker', 'pick a fn', false, '+ pick a fn'], ['effect', 'io', true, '☑ io'],
        ['view', 'saved', true, '☑ saved'], ['unused', 'Unused', false, '☐ Unused']]
        .map(([axis, name, selected, expected]) => {
          const view = runtime.run('view', {inputs: input('filter-option',
            {axis, name, selected, missing: false})});
          return [axis, textOf(GraphdenRenderer.vnode(view.get(key('tree')), axis, 48)), expected];
        });
      const viewItems = [['head', '', false, false, 'Views'],
        ['hint', '', false, false, 'Save in the graph to share on this branch. Device only stays in this browser.'],
        ['all', '', false, false, '◍ All functions — no filters'],
        ['device-section', '', false, false, 'Device only'], ['graph-section', '', false, false, 'In the graph'],
        ['device-apply', 'personal', true, false, '● personal'],
        ['graph-apply', 'shared', false, true, '⚠ shared'],
        ['device-delete', '', false, false, '×'], ['graph-edit', '', false, false, 'Edit filters'],
        ['graph-open', '', false, false, 'Edit graph'],
        ['save-section', '', false, false, 'Save the current filters'],
        ['edit-section', 'shared', false, false, 'Edit shared'],
        ['save-device', '', false, false, 'Device only'],
        ['save-graph', '', false, false, 'Save in graph'],
        ['save-graph-edit', '', false, false, 'Save changes']]
        .map(([axis, name, selected, missing, expected]) => {
          const view = runtime.run('view', {inputs: input('view-item', {axis, name, selected, missing})});
          return [axis, textOf(GraphdenRenderer.vnode(view.get(key('tree')), axis, 48)), expected,
            GraphdenStyles.normalize(view.get(key('styles'))).length];
        });
      const kinds = ['remove-namespace', 'remove-exclude', 'remove-uses', 'remove-effect',
        'remove-unused', 'remove-name', 'remove-view', 'select-namespace', 'restore-exclude',
        'select-effect', 'select-view', 'select-unused', 'pick-uses', 'clear-view',
        'apply-device-view', 'apply-graph-view', 'delete-device-view', 'edit-graph-view',
        'open-graph-view', 'save-device-view', 'save-graph-view'];
      const requests = kinds.map(kind => {
        const transition = runtime.run('update', {state: record({handled: false}),
          event: record({kind, value: 'exact-value'})});
        return [kind, [...transition.get(key('requests'))].map(request =>
          [request.get(key('kind')), request.get(key('value'))])];
      });
      const unknown = runtime.run('update', {state: record({handled: false}),
        event: record({kind: 'forged-filter-request', value: 'exact-value'})});
      const formPolicies = [
        [{}, {visible: true, mode: 'save', 'device-available': true,
          'graph-visible': true, 'graph-available': true, 'enter-target': 'graph', 'name-valid': true}],
        [{editing: true, active: false}, {visible: true, mode: 'edit'}],
        [{authenticated: false}, {'graph-available': false}],
        [{share: false}, {'graph-visible': false, 'enter-target': 'device'}],
        [{pending: true, name: ' \t '}, {'device-available': false,
          'graph-available': false, 'enter-target': 'none', 'name-valid': false}],
      ].map(([changes, expected]) => {
        const facts = {active: true, editing: false, authenticated: true,
          share: true, pending: false, name: ' café ', ...changes};
        const supplied = input('view-form', {axis: '', name: facts.name, selected: facts.active, missing: false});
        supplied.set(key('selected'), new Map([['view-editing', facts.editing],
          ['share-available', facts.share], ['pending', facts.pending]]));
        supplied.set(key('authenticated'), facts.authenticated);
        const actual = runtime.run('initial', {inputs: supplied});
        return Object.entries(expected).every(([name, value]) => actual.get(key(name)) === value);
      });
      return {available: true, functionCount: result.plan.functions.length,
        chips, formPolicies, missing: textOf(GraphdenRenderer.vnode(
        missing.get(key('tree')), 'missing', 48)), options, viewItems, requests,
        unknown: [...unknown.get(key('requests'))].length,
        styles: GraphdenStyles.normalize(missing.get(key('styles'))).length};
    });
    assert(server.available && server.functionCount > 0 && server.functionCount <= 512
      && server.chips.every(([, actual, expected]) => actual === expected + '×')
      && server.missing === '⚠ view saved×'
      && server.options.every(([, actual, expected]) => actual === expected)
      && server.viewItems.every(([, actual, expected, styles]) => actual === expected && styles > 0)
      && server.requests.length === 21
      && server.requests.every(([kind, requests]) =>
        JSON.stringify(requests) === JSON.stringify([[kind, 'exact-value']]))
      && server.formPolicies.every(Boolean) && server.unknown === 0 && server.styles > 0,
    'actual server-selected source exports every filter axis, option, typed request, and styles: '
      + JSON.stringify(server));

    stage = 'saved-view popup draft, actions and visual';
    const savedName = 'popup-device-' + Date.now().toString(36);
    await page.evaluate(() => {
      window.__originalViewFetch = window.gdFetchSharedViews;
      gdInvalidateSharedViews();
      window.gdFetchSharedViews = () => new Promise(resolve => {
        window.__releaseViewFetch = () => resolve([]);
      });
    });
    await page.locator('#gd-ws-chip').click();
    const popupInput = page.locator('#gd-ws-pop .gd-views-input');
    await popupInput.fill(savedName);
    await popupInput.focus();
    await page.evaluate(() => {
      window.__savedViewInput = document.querySelector('#gd-ws-pop .gd-views-input');
      window.__savedViewRow = document.querySelector('#gd-ws-pop [data-ws-all]');
      window.__releaseViewFetch();
    });
    assert(await page.evaluate(() => {
      const pop = document.getElementById('gd-ws-pop');
      const input = pop?.querySelector('.gd-views-input');
      return input === window.__savedViewInput && input.value && document.activeElement === input
        && pop.querySelector('[data-ws-all]') === window.__savedViewRow
        && GraphdenRenderer.ownsTarget(pop.querySelector('.gd-view-graph-host'))
        && document.querySelectorAll('style[data-gd-ui-styles="' + pop.dataset.gdUiStyle + '"]').length === 1;
    }), 'late graph-list refresh retains the exact focused draft/all action and one shared sheet');
    await page.evaluate(() => { window.gdFetchSharedViews = window.__originalViewFetch; });
    await page.getByRole('button', {name: 'Device only', exact: true}).click();
    assert(await page.evaluate(name => gdReadViews().some(view => view.name === name)
      && document.querySelector('#gd-ws-pop .gd-views-input') === window.__savedViewInput
      && document.querySelector('#gd-ws-pop [data-view-row="device:' + name + '"] .gd-views-apply')?.textContent === '● ' + name,
    savedName), 'typed graph request saves the exact device view without replacing the draft');
    await page.screenshot({path: '/tmp/gd-explorer-views-light.png'});
    await page.evaluate(() => document.body.classList.add('theme-dark'));
    await page.screenshot({path: '/tmp/gd-explorer-views-dark.png'});
    await page.evaluate(() => document.body.classList.remove('theme-dark'));
    await page.keyboard.press('Escape');
    await page.evaluate(name => gdDeleteView(name), savedName);

    const manyViews = await page.evaluate(() => {
      const before = localStorage.getItem('graphden.explorer.views');
      try {
        gdWriteViews(Array.from({length: 540}, (_, index) => ({name: 'device-' + index, filters: gdEmptyFilters()})));
        gdOpenViewPop(document.getElementById('gd-ws-chip'));
        const pop = document.getElementById('gd-ws-pop');
        return {count: pop.querySelectorAll('[data-view-row^="device:"]').length,
          owned: GraphdenRenderer.ownsTarget(pop.querySelector('[data-view-row="device:device-539"] .gd-view-graph-host')),
          sheetCount: document.querySelectorAll('style[data-gd-ui-styles="' + pop.dataset.gdUiStyle + '"]').length};
      } finally {
        gdCloseViewPop();
        if (before === null) localStorage.removeItem('graphden.explorer.views');
        else localStorage.setItem('graphden.explorer.views', before);
      }
    });
    assert(manyViews.count === 540 && manyViews.owned && manyViews.sheetCount === 1,
      '540 device views render without a popup collection cap or per-row stylesheet');

    stage = 'visual and auth lifetime';
    await page.screenshot({path: '/tmp/gd-explorer-filters-light.png'});
    await page.evaluate(() => document.body.classList.add('theme-dark'));
    await page.screenshot({path: '/tmp/gd-explorer-filters-dark.png'});
    await page.evaluate(() => document.body.classList.remove('theme-dark'));
    await page.evaluate(() => {
      window.gdFetchSharedViews = () => new Promise(resolve => {
        window.__releaseOldFilterViews = () => resolve([]);
      });
    });
    await page.locator('#gd-filter-add').click();
    await page.waitForSelector('.gd-filter-add-pop');
    await page.evaluate(() => {
      window.__oldFilterScope = JSON.stringify(gdFilters());
      window.__oldFilterChoice = document.querySelector('.gd-filter-add-pop [data-effect="io"]');
      window.gdPrefOwner = 'changed-owner-for-filter-lifetime-proof';
      window.dispatchEvent(new Event('gd-auth-changed'));
      window.__oldFilterChoice.click();
    });
    await page.evaluate(() => window.__releaseOldFilterViews());
    await page.waitForFunction(() => !document.querySelector('.gd-filter-add-pop'));
    assert(await page.evaluate(() => document.querySelectorAll('#gd-filter-chips .gd-filter-chip').length === 0
      && !document.getElementById('gd-ws-chip').hasAttribute('data-view')
      && JSON.stringify(gdFilters()) === window.__oldFilterScope),
    'auth replacement clears old chip bodies and rejects late/replayed old-owner choices');
    assert(errors.length === 0, 'no browser errors across graph choices and auth disposal');
    console.log('PASS Explorer filter graph choices, native input/focus, large set, server source and auth lifetime');
  } catch (error) {
    console.error('FAIL Explorer filters during ' + stage + ': ' + error.name);
    process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(() => { console.error('FAIL Explorer filters; payloads withheld'); process.exitCode = 1; });
