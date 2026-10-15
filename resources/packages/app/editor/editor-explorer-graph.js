// The finite toolbar, tree bodies and filter choices own empty hosts. One
// runtime and shared scoped styles serve all loaded bodies; tree navigation,
// filter mutation, focus and pending POST controls remain native.
(() => {
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const kinds = ['all', 'fn', 'types', 'secrets', 'services', 'apps', 'tests', 'failed', 'type-errors', 'lint'];
  const diffLenses = ['changedOnly', 'added', 'modified', 'missing', 'inside',
    'substantiveOnly', 'notes', 'effectsOnly'];
  const field = (record, name) => record instanceof Map ? record.get(key(name)) : undefined;
  const record = object => new Map(Object.entries(object).map(([name, value]) => [key(name), value]));
  const exact = (value, names) => {
    if (!(value instanceof Map) || value.size !== names.length || names.some(name => !value.has(key(name)))) throw new Error('Invalid Explorer record');
  };
  let controller = null;
  let mounted = null;
  let host = null;
  let owner = null;
  let generation = 0;
  let pending = null;
  let builtinRuntime = null;
  let rowRuntime = null;
  let namespaceRuntime = null;
  let filterRuntime = null;
  let viewRuntime = null;
  let diffRuntime = null;
  let treeSheet = null;
  let choiceSheet = null;
  let diffSheet = null;
  const diffStyles = {item: null, lens: null};
  const diffStyleSignatures = {item: null, lens: null};
  const choiceStyles = {filter: null, view: null};
  const choiceStyleSignatures = {filter: null, view: null};
  const treeStyles = {row: null, namespace: null};
  const treeStyleSignatures = {row: null, namespace: null};
  let rowFallback = false;
  let namespaceFallback = false;
  let filterFallback = false;
  let viewFallback = false;
  let diffFallback = false;
  const rows = new Set();
  const namespaces = new Set();
  const filters = new Set();
  const views = new Set();
  const diffItems = new Set();
  const diffByHost = new WeakMap();
  let diffLens = null;
  const choiceStyleRoots = new Set();
  const namespaceByHost = new WeakMap();
  const viewByHost = new WeakMap();
  const rowDefaults = {name: '', secret: false, 'secret-path': '', service: false,
    'service-state': '', app: false, 'app-hosts': '', type: false, test: false,
    'test-status': '', 'test-error': '', failed: 0, 'type-errors': 0, lint: 0, effects: ''};
  const namespaceDefaults = {name: '', expanded: false, pseudo: false, count: 0,
    'type-errors': 0, failed: 0, lint: 0};
  const filterDefaults = {axis: '', name: '', selected: false, missing: false};
  const context = () => JSON.stringify([window.gdPrefOwner || null,
    typeof graphdenCurrentOrg === 'string' ? graphdenCurrentOrg : null,
    typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : null]);
  function count(name) {
    const value = typeof window[name] === 'function' ? window[name]() : null;
    return Number.isSafeInteger(value) && value >= 0 ? value : null;
  }
  function inputs() {
    return record({surface: 'toolbar', row: record(rowDefaults), namespace: record(namespaceDefaults),
      filter: record(filterDefaults),
      selected: new Map(kinds.slice(1).map(kind => [kind, lensKinds.has(kind)])),
      counts: new Map([['services', count('getAllServiceFnIdCount')], ['apps', count('getAppRouteCount')],
        ['tests', count('getTestStatusCount')], ['failed', count('getFailureTotal')],
        ['type-errors', count('getTypeErrorTotal')], ['lint', count('getLintTotal')]]),
      failed: count('getTestFailedCount') || 0,
      authenticated: typeof isAuthenticated === 'function' && isAuthenticated(),
      'apps-available': !!window.API?.api_orgs_apps, fx: !!treeDetails.fx});
  }
  function validateState(state) {
    exact(state, ['handled']);
    if (typeof field(state, 'handled') !== 'boolean') throw new Error('Invalid Explorer state');
  }
  const children = node => [node?.props?.children].flat().filter(value => value != null);
  function validateView(view) {
    exact(view, ['tree', 'styles', 'actions']);
    const actions = field(view, 'actions');
    exact(actions, ['secret', 'dismiss', 'run']);
    if (['secret', 'dismiss', 'run'].some(name => typeof field(actions, name) !== 'boolean')) throw new Error('Invalid Explorer actions');
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-explorer', 96);
    function checkIds(node) {
      if (!node || typeof node !== 'object') return;
      if (node.props?.id) throw new Error('Invalid Explorer ID ownership');
      for (const child of children(node)) checkIds(child);
    }
    checkIds(tree);
    const buttons = children(tree);
    if (tree.type !== 'div' || tree.props.className !== 'gd-explorer-kinds' || buttons.length !== kinds.length + 1) throw new Error('Invalid Explorer toolbar');
    buttons.forEach((button, index) => {
      if (button.type !== 'button' || button.props.type !== 'button' || button.props.id
        || !['true', 'false'].includes(button.props['aria-pressed'])
        || (index < kinds.length ? button.props['data-kind'] !== kinds[index] : button.props['data-detail'] !== 'fx')) throw new Error('Invalid Explorer toggle');
    });
  }
  function rowInputs(projection) {
    return record({surface: 'row', row: record(projection), namespace: record(namespaceDefaults),
      filter: record(filterDefaults),
      selected: new Map(), counts: new Map(),
      failed: 0, authenticated: false, 'apps-available': false, fx: false});
  }
  function namespaceInputs(projection) {
    return record({surface: 'namespace', row: record(rowDefaults), namespace: record(projection),
      filter: record(filterDefaults),
      selected: new Map(), counts: new Map(), failed: 0, authenticated: false,
      'apps-available': false, fx: false});
  }
  function filterInputs(surface, projection) {
    return record({surface, row: record(rowDefaults), namespace: record(namespaceDefaults),
      filter: record(projection), selected: new Map(), counts: new Map(), failed: 0,
      authenticated: false, 'apps-available': false, fx: false});
  }
  function formInputs(facts) {
    return record({surface: 'view-form', row: record(rowDefaults), namespace: record(namespaceDefaults),
      filter: record({...filterDefaults, name: facts.name, selected: facts.filtersActive}),
      selected: new Map([['view-editing', facts.editing], ['share-available', facts.shareAvailable],
        ['pending', facts.pending]]), counts: new Map(), failed: 0,
      authenticated: facts.authenticated, 'apps-available': false, fx: false});
  }
  function diffInputs(surface, selected) {
    const value = filterInputs(surface, filterDefaults);
    value.set(key('selected'), new Map(selected));
    return value;
  }
  function diffFacts(facts) {
    if (!facts || ![null, 'added', 'modified', 'missing', 'inside'].includes(facts.kind)
      || typeof facts.title !== 'string' || !(facts.summary === null || typeof facts.summary === 'string')
      || typeof facts.branch !== 'string' || !facts.counts || typeof facts.counts !== 'object'
      || Object.keys(facts.counts).some(name => !['added', 'modified', 'missing', 'inside'].includes(name)
        || !Number.isSafeInteger(facts.counts[name]) || facts.counts[name] < 0)) {
      throw new Error('Invalid Explorer diff facts');
    }
    return record({kind: facts.kind, title: facts.title, summary: facts.summary,
      branch: facts.branch, counts: new Map(Object.entries(facts.counts))});
  }
  function validateRow(view, projection) {
    exact(view, ['tree', 'styles', 'actions']);
    const actions = field(view, 'actions');
    exact(actions, ['secret', 'dismiss', 'run']);
    if (['secret', 'dismiss', 'run'].some(name => field(actions, name) !== false)) throw new Error('Explorer row cannot request actions');
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-explorer-row', 48);
    const parts = children(tree);
    const classes = ['secret-lock-icon', 'kind-marker-service', 'kind-marker-app', 'kind-marker-type',
      'kind-marker-test', 'kind-marker-failed', 'kind-marker-type-error', 'kind-marker-lint',
      'kind-marker-fx', 'name', 'secret-path'];
    const visible = [projection.secret, projection.service, projection.app, projection.type,
      projection.test, projection.failed > 0, projection['type-errors'] > 0,
      projection.lint > 0, !!projection.effects, true, !!projection['secret-path']];
    const expected = classes.filter((_, index) => visible[index]);
    if (tree.type !== 'span' || tree.props.className !== 'gd-explorer-row'
      || Object.keys(tree.props).some(name => name !== 'className' && name !== 'children')
      || parts.length !== expected.length) throw new Error('Invalid Explorer row');
    parts.forEach((part, index) => {
      if (part.type !== 'span' || !part.props.className?.split(' ').includes(expected[index])
        || Object.keys(part.props).some(name => !['className', 'title', 'hidden', 'children', 'key'].includes(name))
        || children(part).some(value => typeof value !== 'string' && !Number.isSafeInteger(value))
        || (expected[index] !== 'name' && part.props.hidden !== false)) throw new Error('Invalid Explorer row field');
    });
    const name = parts[expected.indexOf('name')];
    const path = parts[expected.indexOf('secret-path')];
    if (children(name).join('') !== projection.name
      || (path && children(path).join('') !== projection['secret-path'])) {
      throw new Error('Explorer row identity mismatch');
    }
    return window.GraphdenStyles.normalize(field(view, 'styles'));
  }
  function validateNamespace(view) {
    exact(view, ['tree', 'styles', 'actions']);
    const actions = field(view, 'actions');
    exact(actions, ['secret', 'dismiss', 'run']);
    if (['secret', 'dismiss', 'run'].some(name => field(actions, name) !== false)) throw new Error('Explorer namespace cannot request actions');
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-explorer-namespace', 48);
    const passive = new Set(['div', 'span', 'ul', 'li', 'p', 'strong', 'small', 'section']);
    let textLength = 0;
    function inspect(node) {
      if (typeof node === 'string' || typeof node === 'number') {
        textLength += String(node).length;
        return;
      }
      if (!node || !passive.has(node.type)
        || Object.keys(node.props).some(name => !['className', 'title', 'aria-hidden', 'children', 'key'].includes(name))) {
        throw new Error('Invalid Explorer namespace body');
      }
      if (/(^|\s)(?:ns-header|ns-children|entity-item|ns-row-actions|gd-explorer-namespace-host|gd-explorer-row-host|gd-explorer-row|gd-explorer-kinds)(\s|$)/.test(node.props.className || '')) {
        throw new Error('Explorer namespace cannot forge host classes');
      }
      for (const child of children(node)) inspect(child);
    }
    inspect(tree);
    if (textLength > 8192) throw new Error('Explorer namespace body too large');
    const rules = window.GraphdenStyles.normalize(field(view, 'styles'));
    if (rules.some(rule => !rule.selector.startsWith('& .gd-explorer-namespace-host')
      || !['', ' ', '.', ':', '[', '>'].includes(rule.selector['& .gd-explorer-namespace-host'.length] || ''))) {
      throw new Error('Explorer namespace style must stay inside its body');
    }
    return rules;
  }
  function validateFilter(view) {
    exact(view, ['tree', 'styles', 'actions']);
    const actions = field(view, 'actions');
    exact(actions, ['secret', 'dismiss', 'run']);
    if (['secret', 'dismiss', 'run'].some(name => field(actions, name) !== false)) {
      throw new Error('Explorer filter body cannot request actions');
    }
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-explorer-filter', 48);
    const passive = new Set(['div', 'span', 'strong', 'small']);
    let textLength = 0;
    function inspect(node) {
      if (typeof node === 'string' || typeof node === 'number') {
        textLength += String(node).length;
        return;
      }
      if (!node || !passive.has(node.type)
        || Object.keys(node.props).some(name => !['className', 'title', 'aria-hidden', 'children', 'key'].includes(name))) {
        throw new Error('Invalid Explorer filter body');
      }
      if (/(^|\s)(?:gd-filter-graph-host|gd-filter-chip|gd-views-row|gd-filter-add-pop|kind-toggle)(\s|$)/.test(node.props.className || '')) {
        throw new Error('Explorer filter cannot forge host classes');
      }
      for (const child of children(node)) inspect(child);
    }
    inspect(tree);
    if (textLength > 8192) throw new Error('Explorer filter body too large');
    const rules = window.GraphdenStyles.normalize(field(view, 'styles'));
    const root = '& .gd-filter-graph-host';
    if (rules.some(rule => !rule.selector.startsWith(root)
      || !['', ' ', '.', ':', '[', '>'].includes(rule.selector[root.length] || ''))) {
      throw new Error('Explorer filter style must stay inside its body');
    }
    return rules;
  }
  function validateViewItem(view) {
    exact(view, ['tree', 'styles', 'actions']);
    const actions = field(view, 'actions');
    exact(actions, ['secret', 'dismiss', 'run']);
    if (['secret', 'dismiss', 'run'].some(name => field(actions, name) !== false)) {
      throw new Error('Explorer view body cannot request actions');
    }
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-explorer-view', 48);
    const passive = new Set(['div', 'span', 'strong', 'small']);
    let textLength = 0;
    function inspect(node) {
      if (typeof node === 'string' || typeof node === 'number') { textLength += String(node).length; return; }
      if (!node || !passive.has(node.type)
        || Object.keys(node.props).some(name => !['className', 'title', 'aria-hidden', 'children', 'key'].includes(name))) {
        throw new Error('Invalid Explorer view body');
      }
      if (/(^|\s)(?:gd-view-graph-host|gd-views-row|gd-views-input|gd-views-save|gd-views-apply|gd-views-del|gd-view-pop)(\s|$)/.test(node.props.className || '')) {
        throw new Error('Explorer view cannot forge host classes');
      }
      for (const child of children(node)) inspect(child);
    }
    inspect(tree);
    if (textLength > 8192) throw new Error('Explorer view body too large');
    const rules = window.GraphdenStyles.normalize(field(view, 'styles'));
    const root = '& .gd-view-graph-host';
    if (rules.some(rule => !rule.selector.startsWith(root)
      || !['', ' ', '.', ':', '[', '>'].includes(rule.selector[root.length] || ''))) {
      throw new Error('Explorer view style must stay inside its body');
    }
    return rules;
  }
  function validateDiffItem(view) {
    exact(view, ['tree', 'styles', 'actions']);
    const actions = field(view, 'actions');
    exact(actions, ['secret', 'dismiss', 'run']);
    if (['secret', 'dismiss', 'run'].some(name => field(actions, name) !== false)) {
      throw new Error('Explorer diff body cannot request actions');
    }
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-explorer-diff', 48);
    const passive = new Set(['div', 'span', 'strong', 'small']);
    let textLength = 0;
    function inspect(node) {
      if (typeof node === 'string' || typeof node === 'number') {
        textLength += String(node).length;
        return;
      }
      if (!node || !passive.has(node.type)
        || Object.keys(node.props).some(name => !['className', 'title', 'hidden', 'aria-hidden', 'children', 'key'].includes(name))) {
        throw new Error('Invalid Explorer diff body');
      }
      if (/(^|\s)(?:gd-diff-graph-host|ns-header|ns-children|entity-item|gd-diff-ghost|gd-diff-lens|kind-toggle|ns-row-actions)(\s|$)/.test(node.props.className || '')) {
        throw new Error('Explorer diff cannot forge native controls');
      }
      for (const child of children(node)) inspect(child);
    }
    inspect(tree);
    if (textLength > 8192) throw new Error('Explorer diff body too large');
    const rules = window.GraphdenStyles.normalize(field(view, 'styles'));
    if (rules.some(rule => !rule.selector.startsWith('&'))) {
      throw new Error('Explorer diff style must stay inside its body');
    }
    return rules;
  }
  function validateDiffLens(view, selected) {
    exact(view, ['tree', 'styles', 'actions']);
    const actions = field(view, 'actions');
    exact(actions, ['secret', 'dismiss', 'run']);
    if (['secret', 'dismiss', 'run'].some(name => field(actions, name) !== false)) {
      throw new Error('Explorer diff lens cannot request toolbar actions');
    }
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-explorer-diff-lens', 96);
    const buttons = children(tree);
    if (tree.type !== 'div' || !tree.props.className?.split(' ').includes('gd-diff-lens-kinds')
      || Object.keys(tree.props).some(name => !['className', 'children', 'key'].includes(name))
      || buttons.length !== diffLenses.length) throw new Error('Invalid Explorer diff lens');
    buttons.forEach((button, index) => {
      const name = diffLenses[index];
      if (button.type !== 'button' || button.props.type !== 'button'
        || button.props['data-kind'] !== name
        || button.props['aria-pressed'] !== String(selected[name])
        || Object.keys(button.props).some(prop => !['type', 'className', 'data-kind', 'aria-pressed', 'title', 'hidden', 'children', 'key'].includes(prop))
        || !button.props.className?.split(' ').includes('gd-diff-lens-chip')) {
        throw new Error('Invalid Explorer diff lens button');
      }
      for (const part of children(button)) {
        if (part.type !== 'span' || Object.keys(part.props).some(prop => !['className', 'aria-hidden', 'children', 'key'].includes(prop))
          || children(part).some(value => typeof value !== 'string' || value.length > 2048)) {
          throw new Error('Invalid Explorer diff lens label');
        }
      }
    });
    return window.GraphdenStyles.normalize(field(view, 'styles'));
  }
  function clearChoiceStyles(surface) {
    choiceStyles[surface] = null;
    choiceStyleSignatures[surface] = null;
    const combined = [...(choiceStyles.filter || []), ...(choiceStyles.view || [])];
    const next = combined.length ? window.GraphdenStyles.acquire(combined) : null;
    const old = choiceSheet;
    choiceSheet = next;
    for (const root of choiceStyleRoots) {
      if (next) root.dataset.gdUiStyle = next.id;
      else delete root.dataset.gdUiStyle;
    }
    window.GraphdenStyles.release(old);
  }
  function updateChoiceStyles(surface, host, normalized) {
    const signature = JSON.stringify(normalized);
    if (choiceStyleSignatures[surface] !== null && signature !== choiceStyleSignatures[surface]) {
      throw new Error(`Explorer ${surface} styles vary by choice`);
    }
    if (choiceStyleSignatures[surface] === null) {
      choiceStyles[surface] = normalized;
      choiceStyleSignatures[surface] = signature;
      const combined = [...(choiceStyles.filter || []), ...(choiceStyles.view || [])];
      if (combined.length > 128) throw new Error('Explorer choice style limit exceeded');
      const next = window.GraphdenStyles.acquire(combined);
      const old = choiceSheet;
      choiceSheet = next;
      for (const root of choiceStyleRoots) root.dataset.gdUiStyle = next.id;
      window.GraphdenStyles.release(old);
    }
    const root = surface === 'view' ? host.closest('.gd-view-pop')
      : host.closest('#gd-filter-chips') || host.closest('.gd-filter-add-pop');
    if (!root) throw new Error('Explorer choice host has no scope');
    root.dataset.gdUiStyle = choiceSheet.id;
    choiceStyleRoots.add(root);
  }
  function renderFilter(instance) {
    let view;
    try {
      view = filterRuntime.run('view', {inputs: filterInputs(instance.surface, instance.projection)});
      updateChoiceStyles('filter', instance.host, validateFilter(view));
    } catch (error) {
      if (filterRuntime === builtinRuntime || filterFallback) throw error;
      filterRuntime = builtinRuntime;
      filterFallback = true;
      clearChoiceStyles('filter');
      window.gdUIComponentSectionFallback?.('explorer', 'filters');
      for (const item of filters) renderFilter(item);
      return;
    }
    instance.renderer.render(field(view, 'tree'));
  }
  function mountFilter(body, surface, projection) {
    if (!['filter-chip', 'filter-option'].includes(surface)) throw new Error('Invalid Explorer filter surface');
    if (!builtinRuntime) builtinRuntime = builtin();
    if (!filterRuntime) filterRuntime = builtinRuntime;
    const renderer = window.GraphdenRenderer.mount(body, {idPrefix: 'gd-explorer-filter', nodeLimit: 48});
    const instance = {host: body, surface, projection, renderer};
    filters.add(instance);
    try { renderFilter(instance); }
    catch (error) { filters.delete(instance); renderer.dispose(); throw error; }
  }
  function renderViewItem(instance) {
    let view;
    try {
      view = viewRuntime.run('view', {inputs: filterInputs('view-item', instance.projection)});
      updateChoiceStyles('view', instance.host, validateViewItem(view));
    } catch (error) {
      if (viewRuntime === builtinRuntime || viewFallback) throw error;
      fallbackViews();
      return;
    }
    instance.renderer.render(field(view, 'tree'));
  }
  function mountViewItem(body, projection) {
    if (!builtinRuntime) builtinRuntime = builtin();
    if (!viewRuntime) viewRuntime = builtinRuntime;
    const renderer = window.GraphdenRenderer.mount(body, {idPrefix: 'gd-explorer-view', nodeLimit: 48});
    const instance = {host: body, projection, renderer};
    views.add(instance);
    viewByHost.set(body, instance);
    try { renderViewItem(instance); }
    catch (error) { views.delete(instance); viewByHost.delete(body); renderer.dispose(); throw error; }
  }
  function updateViewItem(body, projection) {
    const instance = viewByHost.get(body);
    if (!instance) throw new Error('Explorer view body was disposed');
    instance.projection = projection;
    renderViewItem(instance);
  }
  function disposeChoices(scope, items) {
    if (!scope) return;
    for (const instance of items) {
      if (scope === instance.host || scope.contains(instance.host)) {
        instance.renderer.dispose();
        items.delete(instance);
        if (items === views) viewByHost.delete(instance.host);
      }
    }
    for (const root of choiceStyleRoots) {
      if (scope === root || scope.contains(root)) {
        delete root.dataset.gdUiStyle;
        choiceStyleRoots.delete(root);
      }
    }
  }
  function disposeFilters(scope) {
    disposeChoices(scope, filters);
    if (!filters.size) clearChoiceStyles('filter');
  }
  function disposeViews(scope) {
    disposeChoices(scope, views);
    if (!views.size) clearChoiceStyles('view');
  }
  function fallbackViews() {
    viewRuntime = builtinRuntime;
    viewFallback = true;
    clearChoiceStyles('view');
    window.gdUIComponentSectionFallback?.('explorer', 'views');
    for (const item of views) renderViewItem(item);
  }
  function validateFormPolicy(value) {
    const names = ['visible', 'mode', 'device-available', 'graph-visible', 'graph-available',
      'enter-target', 'name-valid', 'validation-message'];
    exact(value, names);
    for (const name of ['visible', 'device-available', 'graph-visible', 'graph-available', 'name-valid']) {
      if (typeof field(value, name) !== 'boolean') throw new Error('Invalid Explorer view form policy');
    }
    if (!['save', 'edit'].includes(field(value, 'mode'))
      || !['none', 'device', 'graph'].includes(field(value, 'enter-target'))
      || typeof field(value, 'validation-message') !== 'string'
      || field(value, 'validation-message').length > 120) throw new Error('Invalid Explorer view form policy');
    return Object.fromEntries(names.map(name => [name, field(value, name)]));
  }
  function formPolicy(facts) {
    if (!builtinRuntime) builtinRuntime = builtin();
    if (!viewRuntime) viewRuntime = builtinRuntime;
    if (typeof facts.name !== 'string'
      || ['filtersActive', 'editing', 'authenticated', 'shareAvailable', 'pending']
        .some(name => typeof facts[name] !== 'boolean')) throw new Error('Invalid Explorer view form facts');
    try { return validateFormPolicy(viewRuntime.run('initial', {inputs: formInputs(facts)})); }
    catch (error) {
      if (viewRuntime === builtinRuntime || viewFallback) throw error;
      fallbackViews();
      return formPolicy(facts);
    }
  }
  function selectChoice(surface, kind, value, execute) {
    const runtime = surface === 'view' ? viewRuntime : filterRuntime;
    if (!runtime || typeof kind !== 'string' || typeof value !== 'string') return false;
    try {
      const result = runtime.run('update', {state: record({handled: false}), event: record({kind, value})});
      exact(result, ['state', 'requests']);
      validateState(field(result, 'state'));
      const requests = field(result, 'requests');
      if (!Array.isArray(requests) && !api.isSequence(requests)) throw new Error('Invalid Explorer filter requests');
      const iterator = requests[Symbol.iterator]();
      const first = iterator.next();
      if (first.done) return false;
      if (!iterator.next().done) throw new Error('Explorer filter may request one native choice');
      const req = first.value;
      exact(req, ['kind', 'value']);
      if (field(req, 'kind') !== kind || field(req, 'value') !== value) {
        throw new Error('Explorer filter request differs from its native choice');
      }
    } catch (error) {
      const fallback = surface === 'view' ? viewFallback : filterFallback;
      if (runtime === builtinRuntime || fallback) throw error;
      if (surface === 'view') {
        fallbackViews();
      } else {
        filterRuntime = builtinRuntime; filterFallback = true;
        clearChoiceStyles('filter');
        window.gdUIComponentSectionFallback?.('explorer', 'filters');
        for (const item of filters) renderFilter(item);
      }
      return selectChoice(surface, kind, value, execute);
    }
    execute();
    return true;
  }
  const selectFilter = (kind, value, execute) => selectChoice('filter', kind, value, execute);
  const selectView = (kind, value, execute) => selectChoice('view', kind, value, execute);
  function updateDiffStyles(surface, normalized) {
    const signature = JSON.stringify(normalized);
    if (diffStyleSignatures[surface] !== null) {
      if (signature !== diffStyleSignatures[surface]) throw new Error('Explorer diff styles vary by item');
      return;
    }
    const next = {...diffStyles, [surface]: normalized};
    const combined = [...(next.item || []), ...(next.lens || [])];
    if (combined.length > 128) throw new Error('Explorer diff style limit exceeded');
    const sheet = window.GraphdenStyles.acquire(combined);
    window.GraphdenStyles.release(diffSheet);
    diffSheet = sheet;
    diffStyles[surface] = normalized;
    diffStyleSignatures[surface] = signature;
    for (const root of [...Array.from(diffItems, instance => instance.host), diffLens?.host]) {
      if (root) root.dataset.gdUiStyle = sheet.id;
    }
  }
  function clearDiffStyles() {
    window.GraphdenStyles.release(diffSheet);
    diffSheet = null;
    for (const surface of ['item', 'lens']) {
      diffStyles[surface] = null;
      diffStyleSignatures[surface] = null;
    }
    for (const root of [...Array.from(diffItems, instance => instance.host), diffLens?.host]) {
      if (root) delete root.dataset.gdUiStyle;
    }
  }
  function fallbackDiff() {
    diffRuntime = builtinRuntime;
    diffFallback = true;
    clearDiffStyles();
    window.gdUIComponentSectionFallback?.('explorer', 'diff');
    for (const instance of diffItems) renderDiffItem(instance);
    if (diffLens) renderDiffLens();
  }
  function renderDiffItem(instance) {
    let view;
    try {
      view = diffRuntime.run('view', {inputs: diffInputs('diff-item', []), diff: diffFacts(instance.facts)});
      updateDiffStyles('item', validateDiffItem(view));
    } catch (error) {
      if (diffRuntime === builtinRuntime || diffFallback) throw error;
      fallbackDiff();
      return;
    }
    instance.renderer.render(field(view, 'tree'));
    if (diffSheet) instance.host.dataset.gdUiStyle = diffSheet.id;
  }
  function renderDiffLens() {
    let view;
    try {
      view = diffRuntime.run('view', {inputs: diffInputs('diff-lens',
        diffLenses.map(name => [name, !!diffLens.selected[name]]))});
      updateDiffStyles('lens', validateDiffLens(view, diffLens.selected));
    } catch (error) {
      if (diffRuntime === builtinRuntime || diffFallback) throw error;
      fallbackDiff();
      return;
    }
    diffLens.renderer.render(field(view, 'tree'));
    if (diffSheet) diffLens.host.dataset.gdUiStyle = diffSheet.id;
  }
  function mountDiffItem(body, facts) {
    if (!controller || owner !== context()) refresh();
    if (!diffRuntime) diffRuntime = builtinRuntime;
    const existing = diffByHost.get(body);
    if (existing) {
      existing.facts = facts;
      renderDiffItem(existing);
      return;
    }
    const renderer = window.GraphdenRenderer.mount(body, {idPrefix: 'gd-explorer-diff', nodeLimit: 48});
    const instance = {host: body, facts, renderer};
    diffItems.add(instance);
    diffByHost.set(body, instance);
    try { renderDiffItem(instance); }
    catch (error) { diffItems.delete(instance); diffByHost.delete(body); renderer.dispose(); throw error; }
  }
  function mountDiffLens(body, selected) {
    if (!controller || owner !== context()) refresh();
    if (!diffRuntime) diffRuntime = builtinRuntime;
    if (!diffLenses.every(name => typeof selected?.[name] === 'boolean')) {
      throw new Error('Invalid Explorer diff lens facts');
    }
    if (diffLens && diffLens.host !== body) {
      diffLens.renderer.dispose();
      diffLens = null;
    }
    if (!diffLens) {
      diffLens = {host: body, renderer: window.GraphdenRenderer.mount(body,
        {idPrefix: 'gd-explorer-diff-lens', nodeLimit: 96}), selected};
    } else diffLens.selected = selected;
    renderDiffLens();
  }
  function disposeDiff(scope) {
    if (!scope) return;
    for (const instance of diffItems) {
      if (scope === instance.host || scope.contains(instance.host)) {
        instance.renderer.dispose();
        diffByHost.delete(instance.host);
        diffItems.delete(instance);
      }
    }
    if (diffLens && (scope === diffLens.host || scope.contains(diffLens.host))) {
      delete diffLens.host.dataset.gdUiStyle;
      diffLens.renderer.dispose();
      diffLens = null;
    }
    if (!diffItems.size && !diffLens) clearDiffStyles();
  }
  function sweepDiff() {
    for (const instance of diffItems) {
      if (!instance.host.isConnected) disposeDiff(instance.host);
    }
    if (diffLens && !diffLens.host.isConnected) disposeDiff(diffLens.host);
  }
  function selectDiffLens(button, execute) {
    const name = button?.dataset.kind;
    if (!diffLens?.host.contains(button) || !diffLenses.includes(name)
      || owner !== context() || !diffLens.selected || !Object.hasOwn(diffLens.selected, name)) return false;
    try {
      const result = diffRuntime.run('update', {state: record({handled: false}),
        event: record({kind: 'toggle-diff-lens', value: name})});
      exact(result, ['state', 'requests']);
      validateState(field(result, 'state'));
      const requests = field(result, 'requests');
      if (!Array.isArray(requests) && !api.isSequence(requests)) throw new Error('Invalid Explorer diff request');
      const iterator = requests[Symbol.iterator]();
      const first = iterator.next();
      if (first.done || !iterator.next().done) throw new Error('Invalid Explorer diff request count');
      exact(first.value, ['kind', 'value']);
      if (field(first.value, 'kind') !== 'toggle-diff-lens' || field(first.value, 'value') !== name) {
        throw new Error('Explorer diff request differs from selected lens');
      }
    } catch (error) {
      if (diffRuntime === builtinRuntime || diffFallback) throw error;
      fallbackDiff();
      return selectDiffLens(Array.from(diffLens.host.querySelectorAll('button[data-kind]'))
        .find(candidate => candidate.dataset.kind === name), execute);
    }
    if (owner !== context() || !diffLens.host.contains(button)) return false;
    execute(name);
    return true;
  }
  function updateTreeStyles(surface, normalized) {
    const signature = JSON.stringify(normalized);
    if (treeStyleSignatures[surface] !== null && signature !== treeStyleSignatures[surface]) {
      throw new Error(`Explorer ${surface} styles vary by item`);
    }
    if (treeStyleSignatures[surface] !== null) return;
    const next = {...treeStyles, [surface]: normalized};
    const combined = [...(next.row || []), ...(next.namespace || [])];
    if (combined.length > 128) throw new Error('Explorer tree style limit exceeded');
    const sheet = window.GraphdenStyles.acquire(combined);
    const old = treeSheet;
    treeSheet = sheet;
    treeStyles[surface] = normalized;
    treeStyleSignatures[surface] = signature;
    const list = document.getElementById('entity-list');
    if (list) list.dataset.gdUiStyle = sheet.id;
    window.GraphdenStyles.release(old);
  }
  function clearTreeStyles(surface) {
    if (surface) {
      treeStyles[surface] = null;
      treeStyleSignatures[surface] = null;
      const combined = [...(treeStyles.row || []), ...(treeStyles.namespace || [])];
      const sheet = combined.length ? window.GraphdenStyles.acquire(combined) : null;
      window.GraphdenStyles.release(treeSheet);
      treeSheet = sheet;
    } else {
      window.GraphdenStyles.release(treeSheet);
      treeSheet = null;
      for (const name of ['row', 'namespace']) {
        treeStyles[name] = null;
        treeStyleSignatures[name] = null;
      }
    }
    const list = document.getElementById('entity-list');
    if (list) {
      if (treeSheet) list.dataset.gdUiStyle = treeSheet.id;
      else delete list.dataset.gdUiStyle;
    }
  }
  function renderRow(instance) {
    let view;
    let normalized;
    try {
      view = rowRuntime.run('view', {inputs: rowInputs(instance.projection)});
      normalized = validateRow(view, instance.projection);
      updateTreeStyles('row', normalized);
    } catch (error) {
      if (rowRuntime === builtinRuntime || rowFallback) throw error;
      rowRuntime = builtinRuntime;
      rowFallback = true;
      clearTreeStyles('row');
      window.gdUIComponentSectionFallback?.('explorer', 'rows');
      for (const row of rows) renderRow(row);
      return;
    }
    instance.renderer.render(field(view, 'tree'));
  }
  function renderNamespace(instance) {
    let view;
    try {
      view = namespaceRuntime.run('view', {inputs: namespaceInputs(instance.projection)});
      updateTreeStyles('namespace', validateNamespace(view));
    } catch (error) {
      if (namespaceRuntime === builtinRuntime || namespaceFallback) throw error;
      namespaceRuntime = builtinRuntime;
      namespaceFallback = true;
      clearTreeStyles('namespace');
      window.gdUIComponentSectionFallback?.('explorer', 'namespaces');
      for (const item of namespaces) renderNamespace(item);
      return;
    }
    instance.renderer.render(field(view, 'tree'));
  }
  function mountNamespace(body, projection) {
    if (!builtinRuntime) builtinRuntime = builtin();
    if (!namespaceRuntime) namespaceRuntime = builtinRuntime;
    const renderer = window.GraphdenRenderer.mount(body, {idPrefix: 'gd-explorer-namespace', nodeLimit: 48});
    const instance = {host: body, projection, renderer};
    namespaces.add(instance);
    namespaceByHost.set(body, instance);
    try { renderNamespace(instance); }
    catch (error) { namespaces.delete(instance); namespaceByHost.delete(body); renderer.dispose(); throw error; }
  }
  function updateNamespace(body, expanded) {
    const instance = namespaceByHost.get(body);
    if (!instance) return;
    instance.projection.expanded = expanded;
    renderNamespace(instance);
  }
  function mountRow(rowHost, projection) {
    if (!builtinRuntime) builtinRuntime = builtin();
    if (!rowRuntime) rowRuntime = builtinRuntime;
    if (!namespaceRuntime) namespaceRuntime = builtinRuntime;
    const renderer = window.GraphdenRenderer.mount(rowHost, {idPrefix: 'gd-explorer-row', nodeLimit: 48});
    const instance = {host: rowHost, projection, renderer};
    rows.add(instance);
    try { renderRow(instance); }
    catch (error) { rows.delete(instance); renderer.dispose(); throw error; }
  }
  function disposeRows(scope) {
    if (!scope) return;
    for (const instance of rows) {
      if (scope === instance.host || scope.contains(instance.host)) {
        instance.renderer.dispose();
        rows.delete(instance);
      }
    }
    for (const instance of namespaces) {
      if (scope === instance.host || scope.contains(instance.host)) {
        instance.renderer.dispose();
        namespaces.delete(instance);
        namespaceByHost.delete(instance.host);
      }
    }
  }
  function resetTreeBodies(runtime) {
    rowRuntime = runtime;
    namespaceRuntime = runtime;
    rowFallback = false;
    namespaceFallback = false;
    clearTreeStyles();
    for (const instance of rows) renderRow(instance);
    for (const instance of namespaces) renderNamespace(instance);
  }
  function resetFilterBodies(runtime) {
    filterRuntime = runtime;
    filterFallback = false;
    clearChoiceStyles('filter');
    for (const instance of filters) renderFilter(instance);
  }
  function resetViewBodies(runtime) {
    viewRuntime = runtime;
    viewFallback = false;
    clearChoiceStyles('view');
    for (const instance of views) renderViewItem(instance);
  }
  function resetDiffBodies(runtime) {
    diffRuntime = runtime;
    diffFallback = false;
    clearDiffStyles();
    for (const instance of diffItems) renderDiffItem(instance);
    if (diffLens) renderDiffLens();
  }
  function paint(view) {
    mounted.render(field(view, 'tree'), field(view, 'styles'));
    // These siblings retain exact nodes/disabled labels while their POST is
    // pending. A graph projection changes visibility, never their ownership.
    for (const [name, id] of [['secret', 'secret-add-btn'], ['dismiss', 'failed-ack-all-btn'], ['run', 'tests-run-all-btn']]) {
      const button = document.getElementById(id);
      if (button) button.hidden = !field(field(view, 'actions'), name);
    }
  }
  function request(value, kind, allowed) {
    exact(value, ['kind', 'value']);
    const argument = field(value, 'value');
    if (field(value, 'kind') !== kind || !allowed.includes(argument)) throw new Error('Invalid Explorer request');
    return argument;
  }
  function builtin() {
    const plan = window.GraphdenBuiltinPlans.plans.explorer;
    const runtime = api.createRuntime(plan);
    return {run(entry, supplied) {
      const accepted = new Set(plan.inputs[plan.entries[entry]].accepted);
      return runtime.run(entry, Object.fromEntries(Object.entries(supplied).filter(([name]) => accepted.has(name))));
    }};
  }
  function click(event) {
    const button = event.target.closest?.('button');
    if (!button || !host.contains(button)) return;
    const detail = button.dataset.detail;
    controller.dispatch(record({kind: detail ? 'detail' : 'toggle', value: detail || button.dataset.kind || ''}));
  }
  function create() {
    if (controller) return;
    host = document.getElementById('kind-filters');
    if (!host) return;
    owner = context();
    mounted = window.GraphdenRenderer.mount(host, {idPrefix: 'gd-explorer', nodeLimit: 96});
    if (!builtinRuntime) builtinRuntime = builtin();
    if (!rowRuntime) rowRuntime = builtinRuntime;
    if (!filterRuntime) filterRuntime = builtinRuntime;
    if (!viewRuntime) viewRuntime = builtinRuntime;
    controller = window.GraphdenComponent.create(builtinRuntime, {inputs, validateState, validateView, render: paint,
      rendererOptions: {idPrefix: 'gd-explorer', nodeLimit: 96}, requests: {
        'toggle-filter': {validate: value => request(value, 'toggle-filter', kinds), execute: kind => { toggleKind(kind); }},
        'toggle-detail': {validate: value => request(value, 'toggle-detail', ['fx']), execute: value => { toggleTreeDetail(value); }},
      }});
    host.addEventListener('click', click);
    void reload();
  }
  async function reload() {
    if (!controller || typeof window.gdLoadUIComponentRuntime !== 'function') return;
    if (owner !== context()) { resetContext(); refresh(); return; }
    const own = ++generation;
    const active = controller;
    const started = context();
    pending?.abort();
    const loading = new AbortController(); pending = loading;
    try {
      const runtime = await window.gdLoadUIComponentRuntime('explorer', window.GraphdenBuiltinPlans.plans.explorer, {}, loading.signal);
      if (loading.signal.aborted || own !== generation || active !== controller || started !== context()) return;
      active.replaceRuntime(runtime);
      resetTreeBodies(runtime);
      resetFilterBodies(runtime);
      resetViewBodies(runtime);
      resetDiffBodies(runtime);
    } catch (error) {
      if (loading.signal.aborted || own !== generation || active !== controller || started !== context() || error.name === 'AbortError') return;
      window.gdUIComponentFailed?.('explorer');
      active.replaceRuntime(builtinRuntime);
      resetTreeBodies(builtinRuntime);
      resetFilterBodies(builtinRuntime);
      resetViewBodies(builtinRuntime);
      resetDiffBodies(builtinRuntime);
    } finally { if (pending === loading) pending = null; }
  }
  function disposeToolbar() {
    generation++;
    pending?.abort(); pending = null;
    host?.removeEventListener('click', click);
    controller?.dispose(); controller = null;
    mounted?.dispose(); mounted = null;
    host = null; owner = null;
  }
  function resetContext() {
    disposeToolbar();
    // A preference or view-context refresh can preserve native loaded rows.
    // Auth changes use dispose(), which drops rows and their stylesheet.
    if (rows.size || namespaces.size) resetTreeBodies(builtinRuntime);
    else {
      clearTreeStyles();
      rowRuntime = builtinRuntime; namespaceRuntime = builtinRuntime;
      rowFallback = false; namespaceFallback = false;
    }
    if (filters.size) resetFilterBodies(builtinRuntime);
    else { clearChoiceStyles('filter'); filterRuntime = builtinRuntime; filterFallback = false; }
    if (views.size) resetViewBodies(builtinRuntime);
    else { clearChoiceStyles('view'); viewRuntime = builtinRuntime; viewFallback = false; }
    if (diffItems.size || diffLens) resetDiffBodies(builtinRuntime);
    else { clearDiffStyles(); diffRuntime = builtinRuntime; diffFallback = false; }
  }
  function dispose() {
    disposeToolbar();
    disposeRows(document.getElementById('entity-list'));
    clearTreeStyles();
    disposeFilters(document.body);
    disposeViews(document.body);
    disposeDiff(document.body);
    clearChoiceStyles('filter');
    clearChoiceStyles('view');
    rowRuntime = builtinRuntime; namespaceRuntime = builtinRuntime;
    filterRuntime = builtinRuntime;
    viewRuntime = builtinRuntime;
    diffRuntime = builtinRuntime;
    rowFallback = false; namespaceFallback = false; filterFallback = false; viewFallback = false; diffFallback = false;
  }
  function refresh() {
    if (controller && owner !== context()) resetContext();
    create();
    controller?.refresh();
  }
  window.gdExplorerGraph = {refresh, reload, dispose, mountRow, mountNamespace, updateNamespace,
    disposeRows, mountFilter, disposeFilters, mountViewItem, updateViewItem, disposeViews,
    formPolicy, selectFilter, selectView, mountDiffItem, mountDiffLens, selectDiffLens,
    disposeDiff, sweepDiff};
  window.addEventListener('gd-auth-changed', () => { dispose(); refresh(); });
})();
