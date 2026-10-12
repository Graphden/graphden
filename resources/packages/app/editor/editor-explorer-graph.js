// The finite kind/status toolbar owns its empty host. Filters, lazy rows,
// persistence, stream lifetimes and pending POST action buttons remain native.
(() => {
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const kinds = ['all', 'fn', 'types', 'secrets', 'services', 'apps', 'tests', 'failed', 'type-errors', 'lint'];
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
  const context = () => JSON.stringify([window.gdPrefOwner || null,
    typeof graphdenCurrentOrg === 'string' ? graphdenCurrentOrg : null,
    typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : null]);
  function count(name) {
    const value = typeof window[name] === 'function' ? window[name]() : null;
    return Number.isSafeInteger(value) && value >= 0 ? value : null;
  }
  function inputs() {
    return record({selected: new Map(kinds.slice(1).map(kind => [kind, lensKinds.has(kind)])),
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
    controller = window.GraphdenComponent.create(builtin(), {inputs, validateState, validateView, render: paint,
      rendererOptions: {idPrefix: 'gd-explorer', nodeLimit: 96}, requests: {
        'toggle-filter': {validate: value => request(value, 'toggle-filter', kinds), execute: kind => { toggleKind(kind); }},
        'toggle-detail': {validate: value => request(value, 'toggle-detail', ['fx']), execute: value => { toggleTreeDetail(value); }},
      }});
    host.addEventListener('click', click);
    void reload();
  }
  async function reload() {
    if (!controller || typeof window.gdLoadUIComponentRuntime !== 'function') return;
    if (owner !== context()) { dispose(); refresh(); return; }
    const own = ++generation;
    const active = controller;
    const started = context();
    pending?.abort();
    const loading = new AbortController(); pending = loading;
    try {
      const runtime = await window.gdLoadUIComponentRuntime('explorer', window.GraphdenBuiltinPlans.plans.explorer, {}, loading.signal);
      if (loading.signal.aborted || own !== generation || active !== controller || started !== context()) return;
      active.replaceRuntime(runtime);
    } catch (error) {
      if (loading.signal.aborted || own !== generation || active !== controller || started !== context() || error.name === 'AbortError') return;
      window.gdUIComponentFailed?.('explorer');
      active.replaceRuntime(builtin());
    } finally { if (pending === loading) pending = null; }
  }
  function dispose() {
    generation++;
    pending?.abort(); pending = null;
    host?.removeEventListener('click', click);
    controller?.dispose(); controller = null;
    mounted?.dispose(); mounted = null;
    host = null; owner = null;
  }
  function refresh() {
    if (controller && owner !== context()) dispose();
    create();
    controller?.refresh();
  }
  window.gdExplorerGraph = {refresh, reload, dispose};
  window.addEventListener('gd-auth-changed', () => { dispose(); refresh(); });
})();
