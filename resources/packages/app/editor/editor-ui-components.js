// The protected resolver chooses ordinary graph identities. The bounded evaluator
// supplies data; callbacks, search, navigation and the DOM ABI stay host-owned.
(() => {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  const statuses = new Map();
  const runtimeIdentities = new Map();
  let catalog = null;
  const organization = () => typeof graphdenCurrentOrg === 'string' && graphdenCurrentOrg ? graphdenCurrentOrg : 'public';
  let configuration = null;
  let prepared = null;
  let generation = 0;
  const branch = () => typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : 'main';
  function contextStamp() {
    return JSON.stringify([window.gdPrefOwner, organization(), branch()]);
  }
  function stamp() { return contextStamp() + ':' + generation; }
  function selection() {
    return configuration?.stamp === stamp() ? configuration.selection : null;
  }
  function eligible() {
    return !!window.gdPrefsReady && !!window.gdPrefOwner
      && !(typeof window.graphdenAccountsMode === 'function' && window.graphdenAccountsMode()
        && !window.isAuthenticated?.());
  }
  async function prepareLegacy(started, signal) {
    const value = window.gdPrefRead('components');
    if (!value || value.org !== organization() || !uuid.test(value['fn-id'] || '')) return;
    const key = contextStamp() + ':' + JSON.stringify(value);
    if (prepared?.key !== key) {
      // The explicit compatibility operation preserves existing edits by ref;
      // it never creates a new root when the ordinary editor already exists.
      const promise = window.authFetch(window.API.api_ui_configuration_prepare, {
        method: 'POST', cache: 'no-store', headers: {'Content-Type': 'application/json'},
        body: '{}',
      }).then(async response => {
        const result = await response.json();
        if (!response.ok || !result.ok) throw new Error('Legacy migration unavailable');
      }).catch(error => {
        if (prepared?.promise === promise) prepared = null;
        throw error;
      });
      prepared = {key, promise};
    }
    await prepared.promise;
    if (started !== stamp() || signal?.aborted) throw new DOMException('Selection changed', 'AbortError');
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
  async function loadPlan(component, started, signal) {
    for (let attempt = 0; attempt < 3; attempt++) {
      if (started !== stamp() || signal?.aborted) throw new DOMException('Selection changed', 'AbortError');
      const response = await window.authFetch(window.API.api_ui_configuration_plan, {
        method: 'POST', signal, cache: 'no-store',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({component}),
      });
      const result = await response.json();
      if (started !== stamp() || signal?.aborted) throw new DOMException('Selection changed', 'AbortError');
      if (attempt < 2 && response.status === 422 && !response.ok && result.ok === false
        && result.code === 'policy-refresh-required' && result.retryable === true) {
        await window.gdWaitForGraphPolicy(signal);
        continue;
      }
      return {response, result};
    }
  }
  function acceptConfiguration(response, result, started) {
    if (!response.ok || !result.ok || !uuid.test(result['selection-id'] || '')
      || !uuid.test(result['configuration-branch-id'] || '')
      || !/^[0-9a-f]{64}$/.test(result['configuration-revision'] || '')
      || !uuid.test(result['configuration-defaults-id'] || '')
      || typeof result['configuration-preference']?.present !== 'boolean'
      || !Object.hasOwn(result['configuration-preference'], 'value')
      || (!result['configuration-preference'].present && result['configuration-preference'].value !== null)) throw new Error('unavailable');
    configuration = {stamp: started, selection: {'fn-id': result['selection-id'],
      'branch-id': result['configuration-branch-id'], branch: branch(), org: organization()},
      revision: result['configuration-revision'], defaults: result['configuration-defaults-id'],
      preference: result['configuration-preference'], custom: result['custom-components']};
    catalog = {stamp: started, roots: result.roots};
  }
  window.gdLoadUIComponentRuntime = async (component, builtin, options = {}, signal) => {
    if (window.gdAccountsReady) await window.gdAccountsReady;
    if (!eligible()) {
      runtimeIdentities.delete(component);
      status(component, '');
      return runtime(builtin, options);
    }
    const started = stamp();
    try {
      if (window.gdPrefRead('components')) await prepareLegacy(started, signal);
      const {response, result} = await loadPlan(component, started, signal);
      // A partial personal manifest deliberately leaves other components built in.
      if (response.status === 422 && !response.ok && result.ok === false
        && result.code === 'component-missing' && result.fallback === 'builtin') {
        runtimeIdentities.delete(component);
        status(component, '');
        return runtime(builtin, options);
      }
      const checked = runtime(result.plan, options);
      acceptConfiguration(response, result, started);
      runtimeIdentities.set(component, {stamp: started, id: result['selection-id']});
      status(component, '');
      window.dispatchEvent(new Event('gd-ui-components-status'));
      return checked;
    } catch (error) {
      if (error.name === 'AbortError' || signal?.aborted || started !== stamp()) throw new DOMException('Selection changed', 'AbortError');
      runtimeIdentities.delete(component);
      status(component, 'Personal UI graphs are unavailable. Using built-in components.');
      return runtime(builtin, options);
    }
  };
  let refreshTimer = null;
  function refresh() {
    generation++;
    configuration = null;
    catalog = null;
    runtimeIdentities.clear();
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(() => {
      void window.gdShellMenuGraph?.reload();
      void window.gdFnPickerGraph?.reload();
      void window.gdRecentsGraph?.reload();
      void window.gdInspectorGraph?.reload();
      void window.gdExplorerGraph?.reload();
    }, 0);
  }
  window.gdPrefOnChange((key) => { if (key === 'components') refresh(); });
  window.addEventListener('gd-auth-changed', () => {
    prepared = null;
    statuses.clear();
    refresh();
    window.dispatchEvent(new Event('gd-ui-components-status'));
  });
  window.addEventListener('gd-graph-changed', () => { if (eligible()) refresh(); });
  window.addEventListener('focus', () => { if (eligible()) refresh(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && eligible()) refresh(); });
  window.gdUIComponentFailed = component => { runtimeIdentities.delete(component); status(component, 'Personal UI graph does not match the component contract. Using built-in controls.'); };
  window.gdUIComponentRuntimeIdentity = component => { const value = runtimeIdentities.get(component); return value?.stamp === stamp() ? value.id : null; };
  window.gdUIComponentsStatus = () => [...statuses.values()].find(Boolean) || '';
  window.gdUIComponentsSelection = selection;
  window.gdUIConfigurationState = () => configuration?.stamp === stamp() ? {
    selection: configuration.selection, revision: configuration.revision,
    defaults: configuration.defaults, preference: configuration.preference,
    custom: configuration.custom || {},
  } : null;
  window.gdCaptureUIConfiguration = async signal => {
    const started = stamp();
    const {response, result} = await loadPlan('account-menu', started, signal);
    acceptConfiguration(response, result, started);
    window.dispatchEvent(new Event('gd-ui-components-status'));
    return window.gdUIConfigurationState();
  };
  window.gdRefreshUIConfiguration = refresh;
  window.gdUIComponentsRoots = () => {
    const selected = selection();
    const theme = window.gdPrefRead('theme')?.graph;
    return {...(catalog?.stamp === stamp() ? catalog.roots : {}),
      'configuration-id': selected?.['fn-id'],
      'theme-id': theme?.org === organization() ? theme['fn-id'] : null};
  };
})();
