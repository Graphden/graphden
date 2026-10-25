// The handler binding selects the root. Its callable fields select graph behavior;
// the explicit legacy adapter retains the editor's other DOM owners.
(() => {
  const api = window.GraphdenBrowser;
  const field = (value, name) => value instanceof Map ? value.get(api.keyword(name)) : undefined;
  let generation = 0;
  let pending = null;
  let diagnostic = null;
  let installed = null;
  let loading = null;
  const facades = new WeakMap();
  const stamp = () => JSON.stringify([window.gdPrefOwner,
    typeof graphdenCurrentOrg === 'string' ? graphdenCurrentOrg : 'public',
    typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : 'main', window.gdPrefRead?.('components')]);
  async function loadPlan(signal, current) {
    return window.gdReadGraphResponse(
      () => window.authFetch(window.API.api_ui_root_plan, {signal, cache: 'no-store'}),
      signal, current,
      (response, result) => response.status === 422 && !response.ok && result.ok === false
        && result.code === 'policy-refresh-required' && result.retryable === true);
  }
  function dispose() {
    installed = null;
    diagnostic?.remove();
    diagnostic = null;
    const legacy = document.getElementById('app');
    if (legacy) delete legacy.dataset.gdRootAdapter;
    window.dispatchEvent(new Event('gd-editor-root-changing'));
  }
  function validateCallbacks(callbacks) {
    if (!(callbacks instanceof Map) || !api.isCallable(field(callbacks, 'view'))) throw new Error('Invalid editor component callback');
    for (const [entry, callback] of callbacks) {
      if (!['initial', 'update', 'view', 'query'].some(name => entry === api.keyword(name)) || !api.isCallable(callback)) throw new Error('Invalid editor component callback');
    }
  }
  function componentNow(name) {
    const owner = installed;
    if (!owner || owner.scope !== stamp()) return null;
    const callbacks = field(owner.output, name);
    if (callbacks === undefined) return null;
    validateCallbacks(callbacks);
    const facade = {
      query: field(callbacks, 'query'),
      run(entry, supplied = {}, budget = null) {
        if (installed !== owner || owner.scope !== stamp()) throw new DOMException('Editor root changed', 'AbortError');
        const input = new Map(Object.entries(supplied).map(([key, value]) => [api.keyword(key), value]));
        const result = api.invoke(field(callbacks, entry), input, budget);
        if (result && typeof result.then === 'function') throw new Error('Async result in a synchronous editor callback');
        return result;
      },
    };
    facades.set(facade, {owner, name});
    return Object.freeze(facade);
  }
  function releaseComponent(facade) {
    const receipt = facades.get(facade);
    if (receipt?.owner === installed && installed.accepted.get(receipt.name) === facade) installed.accepted.delete(receipt.name);
  }
  function acceptComponent(facade) {
    const receipt = facades.get(facade);
    if (!receipt || receipt.owner !== installed || installed.scope !== stamp()) return false;
    installed.accepted.set(receipt.name, facade);
    return true;
  }
  function componentIdentity(name) {
    return installed?.scope === stamp() && installed.accepted.has(name) ? installed.selection : null;
  }
  function rootId() { return installed?.scope === stamp() ? installed.rootId : null; }
  function reload() {
    const own = ++generation;
    pending?.abort();
    pending = new AbortController();
    const {signal} = pending;
    dispose();
    let scope;
    const current = () => own === generation && (scope === undefined || scope === stamp()) && !signal.aborted;
    loading = (async () => {
      try {
        if (window.gdAccountsReady) await window.gdAccountsReady;
        if (window.gdPrefsInitialReady) await window.gdPrefsInitialReady;
        if (!current()) return;
        scope = stamp();
        const {response, result} = await loadPlan(signal, current);
        if (!current()) return;
        if (!response.ok || result.ok !== true) throw new Error('Editor root source is unavailable');
        const runtime = api.createRuntime(result.plan, {operationLimit: 3000000,
          valueNodeLimit: 120000, serverCall: window.GraphdenServer?.call});
        const argument = result['argument-name'];
        if (argument !== null && typeof argument !== 'string') throw new Error('Invalid editor root argument');
        const output = runtime.run('root', argument === null ? {} : {[argument]: new Map()});
        if (!(output instanceof Map) || typeof field(output, 'legacy') !== 'boolean') throw new Error('Invalid editor root result');
        for (const [key, callbacks] of output) if (key !== api.keyword('legacy')) validateCallbacks(callbacks);
        if (!field(output, 'legacy')) throw new Error('Unsupported editor root adapter');
        const legacy = document.getElementById('app');
        if (!legacy) throw new Error('Editor legacy host is unavailable');
        const selection = result['component-selection-id'];
        if (selection != null && (typeof selection !== 'string' || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(selection))) throw new Error('Invalid editor selection receipt');
        const rootId = result['root-id'];
        if (typeof rootId !== 'string' || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(rootId)) throw new Error('Invalid editor root identity');
        installed = {scope, output, selection: selection ?? null, rootId, accepted: new Map()};
        legacy.dataset.gdRootAdapter = 'legacy';
        window.dispatchEvent(new Event('gd-editor-root-ready'));
      } catch (error) {
        if (!current() || error.name === 'AbortError') return;
        dispose();
        diagnostic = document.createElement('div');
        diagnostic.setAttribute('role', 'status');
        diagnostic.textContent = 'Editor configuration could not be loaded.';
        document.getElementById('app')?.before(diagnostic);
        window.dispatchEvent(new CustomEvent('gd-editor-root-error', {detail: {message: error.message}}));
      }
    })();
    return loading;
  }
  window.gdEditorRoot = {
    reload, componentNow, acceptComponent, releaseComponent, componentIdentity, rootId,
    async component(name) {
      await (loading || reload());
      const result = componentNow(name);
      if (!result) throw new Error('Editor root component is unavailable');
      return result;
    },
    dispose() { generation++; pending?.abort(); dispose(); },
  };
  window.addEventListener('pagehide', () => window.gdEditorRoot.dispose());
  for (const event of ['gd-auth-changed', 'gd-graph-changed']) window.addEventListener(event, () => { void reload(); });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => { void reload(); }, {once: true});
  else void reload();
})();
