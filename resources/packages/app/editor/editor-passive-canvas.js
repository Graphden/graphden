// Shared passive-host lifetime extracted from the canvas adapter. Component
// bindings supply their existing finite projection and local passive policy.
(() => {
  const waiting = new Set();
  let frame = null;
  function cancelLayout(check) {
    waiting.delete(check);
    if (!waiting.size && frame !== null) { cancelAnimationFrame(frame); frame = null; }
  }
  function scheduleLayout(check) {
    waiting.add(check);
    if (frame !== null) return;
    frame = requestAnimationFrame(() => {
      frame = null;
      const checks = [...waiting];
      waiting.clear();
      const ready = checks.map(current => current()).some(Boolean);
      if (!ready || typeof gv === 'undefined' || !gv.ready()) return;
      const layout = new Map([...graph.nodes].map(([id, node]) => [id,
        {x: node.x, y: node.y, width: gv.node(id)?.width(), height: gv.node(id)?.height()}]));
      if (reflowFromMeasuredHeights(layout)) settleNodes(layout);
      updateOverlayPositions();
    });
  }
window.gdCreatePassiveCanvas = config => {
  const api = window.GraphdenBrowser;
  const field = (map, name) => map instanceof Map ? map.get(api.keyword(name)) : undefined;
  const tags = new Set(['span', 'small', 'strong']);
  const props = new Set(['className', 'title', 'aria-label', 'aria-hidden', 'children', 'key']);
  const owners = new Map();
  let selected = null;
  let builtinRuntime = null;
  let pending = null;
  let generation = 0;
  let sheet = null;
  let signature = null;
  const rejected = new WeakSet();
  const styleContracts = new WeakMap();
  let fallbackRefresh = false;
  let reconciledScope = null;
  let policyRefresh = false;
  function changed() {
    if (!config.onRuntimeChanged || policyRefresh) return;
    policyRefresh = true;
    queueMicrotask(() => { policyRefresh = false; config.onRuntimeChanged(); });
  }
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
    if (Array.isArray(input)) return input.map(record);
    if (input && Object.getPrototypeOf(input) === Object.prototype) {
      return new Map(Object.entries(input).map(([key, value]) => [api.keyword(key), record(value)]));
    }
    return input;
  }
  function builtin() {
    if (!builtinRuntime) builtinRuntime = api.createRuntime(config.plan(),
      {operationLimit: config.operationLimit || 150000});
    return builtinRuntime;
  }
  function passive(tree) {
    const root = window.GraphdenRenderer.vnode(tree, config.prefix, 1024);
    function visit(node) {
      if (node == null || typeof node !== 'object') return;
      if (!tags.has(node.type) || Object.keys(node.props).some(key => !props.has(key))
        || String(node.props.className || '').split(/\s+/).filter(Boolean)
          .some(name => !config.classes.has(name) && !new RegExp('^' + config.prefix + '-[a-z0-9-]+$').test(name))) {
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
    config.checkStyles?.(styles);
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
  function fail() { window.gdUIComponentFailed?.(config.identity); }
  function reject(runtime) {
    rejected.add(runtime);
    fail();
    changed();
    if (!fallbackRefresh) {
      fallbackRefresh = true;
      queueMicrotask(() => { fallbackRefresh = false; refresh(); });
    }
  }
  // A passive component may also expose a pure finite initial policy. The
  // adapter validates it before the native owner consumes any receipt.
  function initialBatch(build) {
    reconcileScopes();
    const runtime = selected && !rejected.has(selected) ? selected : builtin();
    const own = generation;
    const started = context();
    const run = current => {
      const result = build((input, validate) => validate(current.run('initial', {inputs: record(input)})));
      if (own !== generation || !compatible(started, context())) throw new Error('Retired canvas policy');
      return result;
    };
    try { return run(runtime); }
    catch (error) {
      if (runtime === builtin()) throw error;
      reject(runtime);
      // A bad later receipt retires every staged earlier personal receipt.
      return run(builtin());
    }
  }
  function draw(body) {
    const runtime = selected && !rejected.has(selected) ? selected : builtin();
    let output;
    try { output = checked(runtime, body.input); }
    catch (error) {
      if (runtime === builtin()) throw error;
      reject(runtime);
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
  function layoutReady() { reconcileScopes(); return owners.size > 0; }
  function geometry() { scheduleLayout(layoutReady); }
  const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(geometry) : null;
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
    const input = config.projection(surface, facts);
    const renderer = window.GraphdenRenderer.mount(host, {idPrefix: config.prefix, nodeLimit: 1024});
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
          renderer.render(config.failureText(input));
          body.scope(null);
        }
      },
      dispose() { renderer.dispose(); },
    };
    let bodies = owners.get(owner);
    if (!bodies) { bodies = new Set(); owners.set(owner, bodies); observer?.observe(owner); }
    bodies.add(body);
    body.refresh();
    return body;
  }
  function dispose(owner) {
    const bodies = owners.get(owner);
    if (!bodies) return;
    for (const body of bodies) body.dispose();
    owners.delete(owner);
    observer?.unobserve(owner);
    if (!owners.size) { releaseStyles(); cancelLayout(layoutReady); }
  }
  function invalidate(clearOwners = false) {
    generation++;
    pending?.abort();
    pending = null;
    selected = null;
    if (clearOwners) {
      for (const owner of [...owners.keys()]) dispose(owner);
      cancelLayout(layoutReady);
    } else refresh();
    changed();
  }
  async function reload() {
    invalidate();
    const own = generation;
    const started = context();
    const controller = new AbortController();
    pending = controller;
    try {
      const runtime = await window.gdLoadUIComponentRuntime(config.identity,
        config.plan(), {operationLimit: config.operationLimit || 150000}, controller.signal);
      if (own !== generation || controller.signal.aborted || !compatible(started, context())) return;
      selected = runtime;
      refresh();
      changed();
    } catch (error) {
      if (own === generation && error.name !== 'AbortError') fail();
    } finally { if (pending === controller) pending = null; }
  }
  document.addEventListener('DOMContentLoaded', () => { void reload(); }, {once: true});
  window.addEventListener('gd-auth-changed', () => invalidate(true));
  window.addEventListener('gd-graph-changed', () => invalidate());
  window.addEventListener('resize', geometry);
  document.fonts?.addEventListener('loadingdone', geometry);
  return {mount: mountCanvasBody, dispose, reload, invalidate, initialBatch};
};

})();
