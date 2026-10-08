// Personal preferences choose ordinary graph identities. The bounded evaluator
// supplies data; callbacks, search, navigation and the DOM ABI stay host-owned.
(() => {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  const statuses = new Map();
  const runtimeIdentities = new Map();
  let catalog = null;
  const organization = () => typeof graphdenCurrentOrg === 'string' && graphdenCurrentOrg ? graphdenCurrentOrg : 'public';
  function selection() {
    if (!window.gdPrefsReady || !window.gdPrefOwner) return null;
    const value = window.gdPrefRead('components');
    return value && uuid.test(value['fn-id'] || '') && uuid.test(value['branch-id'] || '')
      && value.org === organization() ? value : null;
  }
  function stamp() {
    const value = selection();
    return JSON.stringify([window.gdPrefOwner, organization(), value?.['fn-id'], value?.['branch-id']]);
  }
  function status(component, message) {
    if (statuses.get(component) === message) return;
    statuses.set(component, message);
    window.dispatchEvent(new Event('gd-ui-components-status'));
  }
  function runtime(plan, options) {
    const evaluator = window.GraphdenBrowser.createRuntime(plan, options);
    return {...evaluator, run(entry, supplied = {}) {
      const identity = plan.entries[entry];
      const accepted = new Set(plan.inputs[identity].accepted);
      // A graph edit can remove a formerly free input. Only the fixed host's
      // supplied fields can flow in; the evaluator still checks required ones.
      const argumentsForGraph = Object.fromEntries(Object.entries(supplied).filter(([name]) => accepted.has(name)));
      return evaluator.run(entry, argumentsForGraph);
    }};
  }
  window.gdLoadUIComponentRuntime = async (component, builtin, options = {}, signal) => {
    const chosen = selection();
    if (!chosen) {
      runtimeIdentities.delete(component);
      status(component, window.gdPrefsReady && window.gdPrefRead('components')
        ? 'Personal UI graphs are unavailable here. Using built-in components.' : '');
      return runtime(builtin, options);
    }
    const started = stamp();
    try {
      const response = await window.authFetch(window.API.api_ui_components_plan, {
        method: 'POST', signal, cache: 'no-store',
        headers: {'Content-Type': 'application/json', 'X-Graphden-Branch': chosen['branch-id']},
        body: JSON.stringify({component}),
      });
      const result = await response.json();
      if (started !== stamp() || signal?.aborted) throw new DOMException('Selection changed', 'AbortError');
      if (!response.ok || !result.ok || result['selection-id'] !== chosen['fn-id']) throw new Error('unavailable');
      const checked = runtime(result.plan, options);
      runtimeIdentities.set(component, {stamp: started, id: chosen['fn-id']});
      catalog = {stamp: started, roots: result.roots};
      status(component, '');
      window.dispatchEvent(new Event('gd-ui-components-status'));
      return checked;
    } catch (error) {
      if (error.name === 'AbortError' || started !== stamp()) throw new DOMException('Selection changed', 'AbortError');
      runtimeIdentities.delete(component);
      status(component, 'Personal UI graphs are unavailable. Using built-in components.');
      return runtime(builtin, options);
    }
  };
  let refreshTimer = null;
  function refresh() {
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(() => {
      void window.gdShellMenuGraph?.reload();
      void window.gdFnPickerGraph?.reload();
    }, 0);
  }
  window.gdPrefOnChange((key) => { if (key === 'components') refresh(); });
  window.addEventListener('gd-graph-changed', () => { if (selection()) refresh(); });
  window.addEventListener('focus', () => { if (selection()) refresh(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && selection()) refresh(); });
  window.gdUIComponentFailed = component => { runtimeIdentities.delete(component); status(component, 'Personal UI graph does not match the component contract. Using built-in controls.'); };
  window.gdUIComponentRuntimeIdentity = component => { const value = runtimeIdentities.get(component); return value?.stamp === stamp() ? value.id : null; };
  window.gdUIComponentsStatus = () => [...statuses.values()].find(Boolean) || '';
  window.gdUIComponentsSelection = selection;
  window.gdUIComponentsRoots = () => {
    const selected = selection();
    const theme = window.gdPrefRead('theme')?.graph;
    return {...(catalog?.stamp === stamp() ? catalog.roots : {}),
      'configuration-id': selected?.['fn-id'],
      'theme-id': theme?.org === organization() ? theme['fn-id'] : null};
  };
})();
