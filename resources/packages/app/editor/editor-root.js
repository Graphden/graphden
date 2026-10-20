// The server selects the root through the editor handler's ordinary HOF binding.
// The first bootstrap contract selects the explicit adapter for existing hosts.
(() => {
  let generation = 0;
  let pending = null;
  let host = null;
  const field = (value, name) => value instanceof Map ? value.get(window.GraphdenBrowser.keyword(name)) : undefined;
  const stamp = () => JSON.stringify([window.gdPrefOwner,
    typeof graphdenCurrentOrg === 'string' ? graphdenCurrentOrg : 'public',
    typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : 'main']);
  function dispose() {
    host?.remove();
    host = null;
    const legacy = document.getElementById('app');
    if (legacy) delete legacy.dataset.gdRootAdapter;
  }
  async function reload() {
    const own = ++generation;
    pending?.abort();
    pending = new AbortController();
    const {signal} = pending;
    // Dispose synchronously on a scope change, before any authorization await.
    dispose();
    let scope;
    const current = () => own === generation && (scope === undefined || scope === stamp()) && !signal.aborted;
    try {
      if (window.gdAccountsReady) await window.gdAccountsReady;
      if (!current()) return;
      scope = stamp();
      const response = await window.authFetch(window.API.api_ui_root_plan, {signal, cache: 'no-store'});
      const result = await response.json();
      if (!current()) return;
      if (!response.ok || result.ok !== true) throw new Error('Editor root source is unavailable');
      const runtime = window.GraphdenBrowser.createRuntime(result.plan);
      const argument = result['argument-name'];
      if (argument !== null && typeof argument !== 'string') throw new Error('Invalid editor root argument');
      const output = runtime.run('root', argument === null ? {} : {[argument]: new Map()});
      if (!(output instanceof Map) || output.size !== 1 || typeof field(output, 'legacy') !== 'boolean') throw new Error('Invalid editor root result');
      if (!field(output, 'legacy')) throw new Error('Unsupported editor root adapter');
      const legacy = document.getElementById('app');
      if (!legacy) throw new Error('Editor legacy host is unavailable');
      legacy.dataset.gdRootAdapter = 'legacy';
    } catch (error) {
      if (!current() || error.name === 'AbortError') return;
      dispose();
      host = document.createElement('div');
      host.setAttribute('role', 'status');
      host.textContent = 'Editor configuration could not be loaded.';
      document.getElementById('app')?.before(host);
      window.dispatchEvent(new CustomEvent('gd-editor-root-error', {detail: {message: error.message}}));
    }
  }
  window.gdEditorRoot = {reload, dispose() { generation++; pending?.abort(); dispose(); }};
  for (const event of ['gd-auth-changed', 'gd-graph-changed']) window.addEventListener(event, () => { void reload(); });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => { void reload(); }, {once: true});
  else void reload();
})();
