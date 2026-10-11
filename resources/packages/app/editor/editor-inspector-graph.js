// Graph chrome owns an empty host. Server panels and comparison conversations
// remain siblings with their own DOM, request and CodeMirror lifetimes.
(() => {
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const tabs = ['overview', 'bindings', 'stats', 'history'];
  const field = (value, name) => value instanceof Map ? value.get(key(name)) : undefined;
  const value = object => new Map(Object.entries(object).map(([name, item]) => [key(name), item]));
  const fields = (record, names) => {
    if (!(record instanceof Map) || record.size !== names.length || names.some(name => !record.has(key(name)))) throw new Error('Invalid Inspector record');
  };
  const narrow = matchMedia('(max-width: 1100px)');
  let metadata = {selected: false, name: '', namespace: '', description: '', parents: [], 'has-return': false};
  let owner = null;
  let controller = null;
  let mounted = null;
  let host = null;
  let output = null;
  let handlers = null;
  let generation = 0;
  let pending = null;
  let presetOnly = false;
  let sharedRuntime = null;
  const runtimeListeners = new Set();
  function visible() { return !!output && metadata.selected && (!narrow.matches || field(output, 'open')); }
  function inputs() { return value({...metadata, narrow: narrow.matches, surface: 'chrome', usages: value({rows: [], count: 0, truncated: false})}); }
  function validateState(state) {
    fields(state, ['tab', 'open', 'handled']);
    if (!tabs.includes(field(state, 'tab')) || typeof field(state, 'open') !== 'boolean' || typeof field(state, 'handled') !== 'boolean') throw new Error('Invalid Inspector state');
  }
  function children(node) { return [node?.props?.children].flat().filter(item => item != null); }
  function validateIds(tree) {
    const allowed = new Set(metadata.selected ? tabs.map(tab => 'gd-insp-tab-' + tab) : []);
    const seen = new Set();
    function visit(node) {
      if (!node || typeof node !== 'object') return;
      const id = node.props?.id;
      if (id && (!allowed.has(id) || seen.has(id))) throw new Error('Invalid Inspector ID ownership');
      if (id) seen.add(id);
      for (const child of children(node)) visit(child);
    }
    visit(tree);
  }
  function validateView(view) {
    fields(view, ['tree', 'styles', 'tab', 'open', 'handled']);
    if (!tabs.includes(field(view, 'tab')) || typeof field(view, 'open') !== 'boolean' || typeof field(view, 'handled') !== 'boolean') throw new Error('Invalid Inspector view');
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-insp', 128);
    validateIds(tree);
    if (!metadata.selected) {
      if (tree.type !== 'div' || tree.props.className !== 'gd-insp-empty') throw new Error('Invalid empty Inspector');
      return;
    }
    const [head, strip] = children(tree);
    const close = children(head)[0];
    if (tree.type !== 'div' || tree.props.className !== 'gd-insp-chrome' || children(tree).length !== 2
      || head?.type !== 'div' || head.props.className !== 'gd-insp-head'
      || close?.type !== 'button' || close.props.type !== 'button' || close.props.className !== 'gd-insp-close' || close.props['data-insp-action'] !== 'close'
      || close.props['aria-label'] !== 'Close inspector' || strip?.type !== 'div'
      || strip.props.role !== 'tablist' || strip.props['aria-label'] !== 'Inspector sections') throw new Error('Invalid Inspector chrome');
    const buttons = children(strip);
    if (buttons.length !== tabs.length || buttons.some((button, index) => {
      const tab = tabs[index];
      const selected = tab === field(view, 'tab');
      return button.type !== 'button' || button.props.type !== 'button' || button.props.role !== 'tab'
        || button.props.id !== 'gd-insp-tab-' + tab || button.props['data-insp-tab'] !== tab
        || button.props['aria-controls'] !== 'gd-insp-tabbody' || button.props['aria-selected'] !== String(selected)
        || button.props.tabIndex !== (selected ? 0 : -1);
    })) throw new Error('Invalid Inspector tab contract');
  }
  function paint(view) {
    if (presetOnly) { output = view; return; }
    mounted?.render(field(view, 'tree'), field(view, 'styles'));
    output = view;
    document.body.classList.toggle('gd-insp-open', metadata.selected && field(view, 'open'));
    handlers?.panel?.setAttribute('aria-labelledby', 'gd-insp-tab-' + field(view, 'tab'));
  }
  function request(request) {
    fields(request, ['kind', 'tab']);
    const tab = field(request, 'tab');
    if (!tabs.includes(tab)) throw new Error('Invalid Inspector request');
    return tab;
  }
  function builtin() { return api.createRuntime(window.GraphdenBuiltinPlans.plans.inspector, {operationLimit: 150000}); }
  function publishRuntime(runtime) {
    sharedRuntime = runtime;
    for (const listener of runtimeListeners) listener(runtime);
  }
  function create() {
    if (controller) return controller;
    owner = window.gdPrefOwner || null;
    const initialRuntime = builtin();
    controller = window.GraphdenComponent.create(initialRuntime, {
      inputs, validateState, validateView, render: paint, rendererOptions: {idPrefix: 'gd-insp', nodeLimit: 128},
      requests: {
        'load-tab': {validate: request, execute(tab) {
          if (field(output, 'tab') !== tab) throw new Error('Inspector request does not match its view');
          handlers?.load(tab, {keyboard: field(output, 'handled')});
        }},
        'focus-tab': {validate: request, execute(tab) {
          if (host?.isConnected && visible()) focusSafely(host.querySelector('#gd-insp-tab-' + tab));
        }},
        'resume-tab': {validate: request, execute(tab) {
          if (field(output, 'tab') !== tab) throw new Error('Inspector request does not match its view');
          if (visible()) handlers?.resume();
        }},
        'close-sheet': {validate: request, execute() { handlers?.close(); }},
      },
    });
    publishRuntime(initialRuntime);
    Promise.resolve().then(() => { if (controller) void reload(); });
    return controller;
  }
  function dispatch(kind, tab = '', pressed = '') {
    const active = create();
    try { active.dispatch(value({kind, tab, key: pressed})); }
    catch (_) {
      window.gdUIComponentFailed?.('inspector');
      const fallback = builtin();
      active.replaceRuntime(fallback);
      publishRuntime(fallback);
      active.dispatch(value({kind, tab, key: pressed}));
    }
  }
  async function reload() {
    if (!controller || typeof window.gdLoadUIComponentRuntime !== 'function') return;
    const own = ++generation;
    const active = controller;
    pending?.abort();
    const loading = new AbortController();
    pending = loading;
    try {
      const candidate = await window.gdLoadUIComponentRuntime('inspector', window.GraphdenBuiltinPlans.plans.inspector, {operationLimit: 150000}, loading.signal);
      if (loading.signal.aborted || own !== generation || active !== controller || owner !== (window.gdPrefOwner || null)) return;
      active.replaceRuntime(candidate);
      publishRuntime(candidate);
    } catch (error) {
      if (loading.signal.aborted || own !== generation || active !== controller || error.name === 'AbortError') return;
      window.gdUIComponentFailed?.('inspector');
      const fallback = builtin();
      active.replaceRuntime(fallback);
      publishRuntime(fallback);
    } finally { if (pending === loading) pending = null; }
  }
  function click(event) {
    const target = event.target.closest?.('button');
    if (!target || !host?.contains(target)) return;
    if (target.dataset.inspAction === 'close') dispatch('close');
    else if (target.dataset.inspTab) dispatch('tab', target.dataset.inspTab);
  }
  function keydown(event) {
    if (!event.target.closest?.('[role="tablist"]')) return;
    dispatch('keydown', '', event.key);
    if (field(output, 'handled')) event.preventDefault();
  }
  function dispose() {
    generation++;
    pending?.abort(); pending = null;
    host?.removeEventListener('click', click);
    host?.removeEventListener('keydown', keydown);
    mounted?.dispose(); mounted = null;
    controller?.dispose(); controller = null;
    host = null; handlers = null; output = null;
    sharedRuntime = null;
  }
  window.gdInspectorGraph = {
    subscribeRuntime(listener) {
      runtimeListeners.add(listener);
      listener(sharedRuntime || builtin());
      return () => runtimeListeners.delete(listener);
    },
    render(nextHost, nextMetadata, nextHandlers) {
      metadata = nextMetadata;
      if (host !== nextHost) {
        mounted?.dispose();
        host?.removeEventListener('click', click);
        host?.removeEventListener('keydown', keydown);
        host = nextHost;
        mounted = window.GraphdenRenderer.mount(host, {idPrefix: 'gd-insp', nodeLimit: 128});
        host.addEventListener('click', click);
        host.addEventListener('keydown', keydown);
      }
      handlers = nextHandlers;
      dispatch('selection');
    },
    preset(tab) {
      presetOnly = true;
      try { dispatch('preset', tab); } finally { presetOnly = false; }
    },
    close() { if (controller) dispatch('close'); },
    isOpen: visible,
    reload, dispose,
  };
  narrow.addEventListener('change', () => { if (controller) dispatch('viewport'); });
  window.addEventListener('pagehide', dispose);
  window.addEventListener('gd-auth-changed', () => { dispose(); window.gdInspectorContextChanged?.(); });
})();
