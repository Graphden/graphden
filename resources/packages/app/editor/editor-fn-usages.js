// Used-by data remains server-authorized. Ordinary Inspector graphs own its
// grouping, rows, filter state and styles in an empty sibling host.
(() => {
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const field = (record, name) => record instanceof Map ? record.get(key(name)) : undefined;
  const value = object => new Map(Object.entries(object).map(([name, item]) => [key(name), item]));
  const mounted = new WeakMap();
  const rendererOptions = {idPrefix: 'gd-insp-usages', nodeLimit: 10000};
  function fields(record, names) {
    if (!(record instanceof Map) || record.size !== names.length || names.some(name => !record.has(key(name)))) throw new Error('Invalid Used-by record');
  }
  function normalize(payload) {
    if (!payload || payload.ok === false || !Array.isArray(payload.usages) || payload.usages.length > 400
      || !Number.isSafeInteger(payload.count) || payload.count < 0 || typeof payload['truncated?'] !== 'boolean') throw new Error('Invalid Used-by data');
    const normalizeRow = row => {
      if (typeof row.kind !== 'string' || typeof row['fn-id'] !== 'string' || row.anonymous != null && typeof row.anonymous !== 'boolean') throw new Error('Invalid Used-by row');
      const result = {kind: row.kind, 'fn-id': row['fn-id'], anonymous: row.anonymous === true};
      for (const name of ['fn-name', 'fn-namespace', 'slot-name']) {
        if (row[name] != null && typeof row[name] !== 'string') throw new Error('Invalid Used-by metadata');
        result[name] = row[name] || '';
      }
      return value(result);
    };
    const rows = payload.usages.map(normalizeRow);
    const reach = payload.reachability;
    if (!reach || !['reachable', 'unreachable', 'unknown'].includes(reach.state) || !Array.isArray(reach.path)
      || reach.path.length > 1000 || reach.state !== 'reachable' && reach.path.length) throw new Error('Invalid reachability data');
    const path = reach.path.map(normalizeRow);
    return value({rows, count: payload.count, truncated: payload['truncated?'],
      reachability: value({state: reach.state, path})});
  }
  function builtin() { return api.createRuntime(window.GraphdenBuiltinPlans.plans.inspector, {operationLimit: 150000}); }
  window.gdMountFnUsages = (host, fnId, {owns, captureRead}) => {
    if (!host || !fnId || !window.API?.api_fns_usages) return;
    mounted.get(host)?.dispose();
    let payload = value({rows: [], count: 0, truncated: false,
      reachability: value({state: 'unknown', path: []})});
    let disposed = false;
    let output = null;
    const renderer = window.GraphdenRenderer.mount(host, rendererOptions);
    const current = () => !disposed && host.isConnected && owns();
    const inputs = () => value({surface: 'usages', usages: payload, selected: true,
      name: '', namespace: '', description: '', parents: [], 'has-return': false, narrow: false});
    const validateState = state => {
      fields(state, ['query', 'handled']);
      if (typeof field(state, 'query') !== 'string' || typeof field(state, 'handled') !== 'boolean') throw new Error('Invalid Used-by state');
    };
    const validateView = view => {
      fields(view, ['tree', 'styles', 'tab', 'open', 'handled']);
      if (field(view, 'tab') !== 'overview' || field(view, 'open') !== false || typeof field(view, 'handled') !== 'boolean') throw new Error('Missing Used-by section contract');
      const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), rendererOptions.idPrefix, rendererOptions.nodeLimit);
      if (tree.type !== 'div' || tree.props.className !== 'gd-insp-usages') throw new Error('Missing Used-by section contract');
    };
    const request = entry => {
      fields(entry, ['kind', 'tab']);
      if (typeof field(entry, 'tab') !== 'string') throw new Error('Invalid Used-by request');
      return field(entry, 'tab');
    };
    const controller = window.GraphdenComponent.create(builtin(), {
      inputs, validateState, validateView, rendererOptions,
      render(view) { if (current()) renderer.render(field(view, 'tree'), field(view, 'styles')); output = view; },
      requests: {
        navigate: {validate(entry) {
          const id = request(entry);
          const row = [...field(payload, 'rows'), ...field(field(payload, 'reachability'), 'path')]
            .find(item => field(item, 'fn-id') === id && !field(item, 'anonymous'));
          if (!row) throw new Error('Invalid Used-by navigation');
          return row;
        }, execute(row) {
          if (!current()) return;
          const namespace = field(row, 'fn-namespace');
          const name = field(row, 'fn-name');
          window.gdNavigateToFn?.(field(row, 'fn-id'), namespace ? namespace + '.' + name : name);
        }},
        'blur-filter': {validate(entry) { if (request(entry) !== '') throw new Error('Invalid Used-by focus request'); },
          execute() { if (current()) host.querySelector('.gd-insp-usage-filter')?.blur(); }},
      },
    });
    function fallback() {
      host.dataset.gdUiFallback = 'missing-usages-contract';
      controller.replaceRuntime(builtin());
    }
    const unsubscribe = window.gdInspectorGraph.subscribeRuntime(runtime => {
      if (!current()) return;
      try { controller.replaceRuntime(runtime); delete host.dataset.gdUiFallback; }
      catch (_) { fallback(); }
    });
    function dispatch(kind, tab = '', pressed = '') {
      if (!current()) return;
      try { controller.dispatch(value({kind, tab, key: pressed})); }
      catch (_) { fallback(); controller.dispatch(value({kind, tab, key: pressed})); }
    }
    function input(event) {
      if (event.target.matches('.gd-insp-usage-filter')) dispatch('filter', event.target.value);
    }
    function click(event) {
      const row = event.target.closest('[data-usage-id]');
      if (!row || !host.contains(row)) return;
      if (![...field(payload, 'rows'), ...field(field(payload, 'reachability'), 'path')]
        .some(item => field(item, 'fn-id') === row.dataset.usageId && !field(item, 'anonymous'))) return;
      event.preventDefault(); dispatch('navigate', row.dataset.usageId);
    }
    function keydown(event) {
      if (!event.target.matches('.gd-insp-usage-filter') || event.key !== 'Escape') return;
      dispatch('keydown', '', event.key);
      if (field(output, 'handled')) { event.preventDefault(); event.stopPropagation(); }
    }
    host.addEventListener('input', input);
    host.addEventListener('click', click);
    host.addEventListener('keydown', keydown);
    window.addEventListener('gd-editor-root-ready', load);
    function load() {
      const read = captureRead(host, load);
      if (!read.start) return;
      const doFetch = typeof authFetch === 'function' ? authFetch : fetch;
      doFetch(API.api_fns_usages, {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({'fn-id': fnId, 'root-id': window.gdEditorRoot?.rootId()}), signal: read.signal})
        .then(response => response.ok ? response.json() : null)
        .then(data => {
          if (!read.current() || !data || data.ok === false) return;
          payload = normalize(data);
          try { controller.refresh(); } catch (_) { fallback(); }
        }).catch(() => { /* Supplementary data may be unavailable; keep the Overview. */ }).finally(read.finish);
    }
    const lifecycle = {dispose() {
      disposed = true;
      unsubscribe(); controller.dispose(); renderer.dispose();
      host.removeEventListener('input', input); host.removeEventListener('click', click); host.removeEventListener('keydown', keydown);
      window.removeEventListener('gd-editor-root-ready', load);
      mounted.delete(host);
    }};
    mounted.set(host, lifecycle);
    load();
  };
  window.gdDisposeFnUsages = body => {
    for (const host of body.querySelectorAll('[data-gd-usages-host]')) mounted.get(host)?.dispose();
  };
})();
