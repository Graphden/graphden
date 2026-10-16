// Passive canvas bodies from the selected ordinary graph. Native overlay owners
// retain controls, identity, visibility, event handling and measured geometry.
(() => {
  const api = window.GraphdenBrowser;
  const field = (map, name) => map instanceof Map ? map.get(api.keyword(name)) : undefined;
  const surfaces = new Set(['fn-name', 'root-diagnostics', 'branch-local', 'deep-free']);
  const defaults = {name: null, secret: false, root: false, 'type-errors': 0,
    'failed-runs': 0, lint: 0, 'branch-state': 'off', 'branch-seed': null, 'deep-free': []};
  const tags = new Set(['span', 'small', 'strong']);
  const props = new Set(['className', 'title', 'aria-label', 'aria-hidden', 'children', 'key']);
  const classes = new Set(['fn-row-secret-mark', 'type-error-badge', 'failed-run-badge',
    'lint-badge', 'branch-local-strip-glyph', 'branch-local-strip-label']);
  const owners = new Map();
  let selected = null;
  let builtinRuntime = null;
  let pending = null;
  let generation = 0;
  let sheet = null;
  let signature = null;
  let frame = null;
  const rejected = new WeakSet();
  const styleContracts = new WeakMap();
  let fallbackRefresh = false;
  let reconciledScope = null;
  const context = () => ({owner: window.gdPrefOwner || null, ready: !!window.gdPrefsReady,
    location: JSON.stringify([
      typeof graphdenCurrentOrg === 'string' ? graphdenCurrentOrg : 'public',
      typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : 'main']),
    auth: JSON.stringify([window.gdAccount?.id || null,
      typeof isAuthenticated === 'function' ? !!isAuthenticated() : null]),
  });
  function compatible(previous, next) {
    return previous.location === next.location && previous.auth === next.auth
      && (previous.owner === next.owner || (previous.owner === null && !previous.ready && next.ready));
  }
  function reconcileScopes() {
    const next = context();
    if (reconciledScope && next.owner === reconciledScope.owner && next.ready === reconciledScope.ready
      && next.auth === reconciledScope.auth && next.location === reconciledScope.location) return;
    if (reconciledScope && !compatible(reconciledScope, next)) {
      generation++;
      pending?.abort();
      pending = null;
      selected = null;
    }
    for (const [owner, bodies] of owners) {
      if ([...bodies].some(body => !compatible(body.context, next))) dispose(owner);
      else for (const body of bodies) body.context = next;
    }
    reconciledScope = next;
  }

  function record(input) {
    return new Map(Object.entries(input).map(([key, value]) => [api.keyword(key), value]));
  }
  function projection(surface, facts) {
    const input = {...defaults, surface, ...facts};
    if (!surfaces.has(surface) || input.surface !== surface
      || !(input.name === null || typeof input.name === 'string')
      || typeof input.secret !== 'boolean' || typeof input.root !== 'boolean'
      || ['type-errors', 'failed-runs', 'lint'].some(key => !Number.isSafeInteger(input[key]) || input[key] < 0)
      || !['off', 'own', 'inherited'].includes(input['branch-state'])
      || !(input['branch-seed'] === null || typeof input['branch-seed'] === 'string')
      || !Array.isArray(input['deep-free']) || input['deep-free'].some(name => typeof name !== 'string')
      || Object.keys(input).some(key => key !== 'surface' && !Object.hasOwn(defaults, key))) {
      throw new Error('Invalid canvas display facts');
    }
    // The caller supplies only authorized labels/classifications, never entity
    // records, secret contents, paths, IDs or mutation authority.
    return input;
  }
  function builtin() {
    if (!builtinRuntime) builtinRuntime = api.createRuntime(window.GraphdenBuiltinPlans.plans.canvas,
      {operationLimit: 150000});
    return builtinRuntime;
  }
  function passive(tree) {
    const root = window.GraphdenRenderer.vnode(tree, 'gd-canvas', 1024);
    function visit(node) {
      if (node == null || typeof node !== 'object') return;
      if (!tags.has(node.type) || Object.keys(node.props).some(key => !props.has(key))
        || String(node.props.className || '').split(/\s+/).filter(Boolean)
          .some(name => !classes.has(name) && !/^gd-canvas-[a-z0-9-]+$/.test(name))) {
        throw new Error('Canvas graph must remain passive');
      }
      for (const child of [node.props.children].flat()) visit(child);
    }
    visit(root);
  }
  function checked(runtime, input) {
    const output = runtime.run('view', {inputs: record(input)});
    if (!(output instanceof Map) || output.size !== 3
      || !['tree', 'title', 'styles'].every(key => output.has(api.keyword(key)))
      || typeof field(output, 'title') !== 'string' || field(output, 'title').length > 4096) {
      throw new Error('Invalid canvas view');
    }
    const tree = field(output, 'tree');
    passive(tree);
    const styles = window.GraphdenStyles.normalize(field(output, 'styles'));
    const contract = JSON.stringify(styles);
    if (styleContracts.has(runtime) && styleContracts.get(runtime) !== contract) {
      throw new Error('Canvas surfaces must share one stylesheet');
    }
    styleContracts.set(runtime, contract);
    return {tree, title: field(output, 'title'), styles};
  }
  function acquireStyles(styles) {
    const nextSignature = JSON.stringify(styles);
    if (signature === nextSignature) return;
    const next = styles.length ? window.GraphdenStyles.acquire(styles) : null;
    for (const bodies of owners.values()) for (const body of bodies) body.scope(next);
    window.GraphdenStyles.release(sheet);
    sheet = next;
    signature = nextSignature;
  }
  function fail() { window.gdUIComponentFailed?.('canvas'); }
  function draw(body) {
    const runtime = selected && !rejected.has(selected) ? selected : builtin();
    let output;
    try { output = checked(runtime, body.input); }
    catch (error) {
      if (runtime === builtin()) throw error;
      rejected.add(runtime);
      fail();
      if (!fallbackRefresh) {
        fallbackRefresh = true;
        queueMicrotask(() => { fallbackRefresh = false; refresh(); });
      }
      output = checked(builtin(), body.input);
    }
    acquireStyles(output.styles);
    body.renderer.render(output.tree);
    // A renderer called without rules removes an externally assigned scope.
    body.scope(sheet);
    if (body.options.titleElement) {
      body.options.titleElement.title = (output.title || body.options.nativeTitle)
        + (body.options.titleSuffix || '');
    }
  }
  function geometry() {
    if (frame !== null) return;
    frame = requestAnimationFrame(() => {
      frame = null;
      reconcileScopes();
      if (!owners.size || typeof gv === 'undefined' || !gv.ready()) return;
      const layout = new Map([...graph.nodes].map(([id, node]) => [id,
        {x: node.x, y: node.y, height: gv.node(id)?.height()}]));
      if (reflowFromMeasuredHeights(layout)) settleNodes(layout);
      updateOverlayPositions();
    });
  }
  function refresh() {
    reconcileScopes();
    for (const bodies of owners.values()) for (const body of bodies) body.refresh();
    if (owners.size) geometry();
  }
  function releaseStyles() {
    window.GraphdenStyles.release(sheet);
    sheet = null;
    signature = null;
  }
  function mountCanvasBody(owner, host, surface, facts, options = {}) {
    reconcileScopes();
    const input = projection(surface, facts);
    // Root visibility is a host guard even when a personal view ignores root.
    if (surface === 'root-diagnostics' && !input.root) throw new Error('Root diagnostics need a root owner');
    const renderer = window.GraphdenRenderer.mount(host, {idPrefix: 'gd-canvas', nodeLimit: 1024});
    const body = {input, renderer, options, context: context(),
      scope(value) {
        if (value) host.dataset.gdUiStyle = value.id;
        else delete host.dataset.gdUiStyle;
      },
      refresh() {
        try { draw(body); }
        catch (_) {
          fail();
          // Never silently truncate a large free-argument presentation.
          renderer.render(surface === 'deep-free'
            ? input['deep-free'].length + ' free arguments — canvas presentation unavailable'
            : 'Canvas presentation unavailable');
          body.scope(null);
        }
      },
      dispose() { renderer.dispose(); },
    };
    let bodies = owners.get(owner);
    if (!bodies) { bodies = new Set(); owners.set(owner, bodies); }
    bodies.add(body);
    body.refresh();
    return body;
  }
  function dispose(owner) {
    const bodies = owners.get(owner);
    if (!bodies) return;
    for (const body of bodies) body.dispose();
    owners.delete(owner);
    if (!owners.size) releaseStyles();
  }
  function invalidate(clearOwners = false) {
    generation++;
    pending?.abort();
    pending = null;
    selected = null;
    if (clearOwners) {
      for (const owner of [...owners.keys()]) dispose(owner);
      if (frame !== null) cancelAnimationFrame(frame);
      frame = null;
    } else refresh();
  }
  async function reload() {
    invalidate();
    const own = generation;
    const started = context();
    const controller = new AbortController();
    pending = controller;
    try {
      const runtime = await window.gdLoadUIComponentRuntime('canvas',
        window.GraphdenBuiltinPlans.plans.canvas, {operationLimit: 150000}, controller.signal);
      if (own !== generation || controller.signal.aborted || !compatible(started, context())) return;
      selected = runtime;
      refresh();
    } catch (error) {
      if (own === generation && error.name !== 'AbortError') fail();
    } finally { if (pending === controller) pending = null; }
  }
  window.gdCanvasGraph = {mount: mountCanvasBody, dispose, reload, invalidate};
  document.addEventListener('DOMContentLoaded', () => { void reload(); }, {once: true});
  window.addEventListener('gd-auth-changed', () => invalidate(true));
  window.addEventListener('gd-graph-changed', () => invalidate());
})();
