// The protected resolver chooses ordinary graph identities. The bounded evaluator
// supplies data; callbacks, search, navigation and the DOM ABI stay host-owned.
(() => {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  const statuses = new Map();
  const runtimeIdentities = new Map();
  let catalog = null;
  const organization = () => typeof graphdenCurrentOrg === 'string' && graphdenCurrentOrg ? graphdenCurrentOrg : 'public';
  let configuration = null;
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
  function status(component, message) {
    if (statuses.get(component) === message) return;
    statuses.set(component, message);
    window.dispatchEvent(new Event('gd-ui-components-status'));
  }
  function runtime(plan, options) {
    const evaluator = window.GraphdenBrowser.createRuntime(plan, options);
    return {...evaluator, hasEntry(entry) { return Object.hasOwn(plan.entries, entry); },
      run(entry, supplied = {}, operationBudget = null) {
      const identity = plan.entries[entry];
      const accepted = new Set(plan.inputs[identity].accepted);
      // A graph edit can remove a formerly free input. Only the fixed host's
      // supplied fields can flow in; the evaluator still checks required ones.
      const argumentsForGraph = Object.fromEntries(Object.entries(supplied).filter(([name]) => accepted.has(name)));
      return evaluator.run(entry, argumentsForGraph, operationBudget);
    }};
  }
  async function loadSinglePlan(component, started, signal) {
    return window.gdReadGraphResponse(
      () => window.authFetch(window.API.api_ui_configuration_plan, {
        method: 'POST', signal, cache: 'no-store',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({component}),
      }), signal, () => started === stamp(),
      (response, result) => response.status === 422 && !response.ok && result.ok === false
        && result.code === 'policy-refresh-required' && result.retryable === true);
  }
  const queuedPlans = new Map();
  const activePlans = new Set();
  const aborted = () => new DOMException('Selection changed', 'AbortError');
  function removeSubscription(batch, subscription) {
    subscription.signal?.removeEventListener('abort', subscription.abort);
    batch.subscriptions.delete(subscription);
    if (batch.subscriptions.size === 0) {
      clearTimeout(batch.timer);
      if (queuedPlans.get(batch.started) === batch) queuedPlans.delete(batch.started);
      activePlans.delete(batch);
      batch.controller.abort();
    }
  }
  function settleSubscription(batch, subscription, value, error) {
    const obsolete = subscription.signal?.aborted || batch.started !== stamp();
    removeSubscription(batch, subscription);
    if (obsolete) subscription.reject(aborted());
    else if (error) subscription.reject(error);
    else subscription.resolve(value);
  }
  function batchItem(response, result, component) {
    if (!response.ok || result.ok !== true) return {response, result};
    const item = result.results?.[component];
    if (!item || typeof item.ok !== 'boolean') throw new Error('Invalid component batch');
    const status = item.ok ? 200 : item['http-status'];
    if (!item.ok && (!Number.isInteger(status) || status < 400 || status > 599)) throw new Error('Invalid component refusal');
    return {response: {ok: item.ok, status}, result: item};
  }
  async function sendPlans(batch) {
    queuedPlans.delete(batch.started);
    const components = [...new Set([...batch.subscriptions].map(subscription => subscription.component))];
    if (!components.length) return;
    const signal = batch.controller.signal;
    try {
      const {response, result} = await window.gdReadGraphResponse(
        () => window.authFetch(window.API.api_ui_configuration_plans, {
          method: 'POST', signal, cache: 'no-store',
          headers: {'Content-Type': 'application/json'}, body: JSON.stringify({components}),
        }), signal, () => batch.started === stamp(),
        (reply, body) => reply.status === 422 && !reply.ok && body.ok === false
          && body.code === 'policy-refresh-required' && body.retryable === true);
      // Validate the complete envelope before publishing any item. Individual
      // plans retain the runtime/contract validation in their own consumer.
      if (response.ok && result.ok === true
        && (!result.results || Array.isArray(result.results)
          || Object.keys(result.results).length !== components.length
          || components.some(component => !Object.hasOwn(result.results, component)))) throw new Error('Invalid component batch');
      const items = new Map(components.map(component => [component, batchItem(response, result, component)]));
      for (const subscription of [...batch.subscriptions]) settleSubscription(batch, subscription, items.get(subscription.component));
    } catch (error) {
      for (const subscription of [...batch.subscriptions]) settleSubscription(batch, subscription, null, error);
    }
  }
  function cancelPlans() {
    for (const batch of [...activePlans]) {
      for (const subscription of [...batch.subscriptions]) settleSubscription(batch, subscription, null, aborted());
    }
  }
  function loadPlan(component, started, signal) {
    if (!window.API.api_ui_configuration_plans) return loadSinglePlan(component, started, signal);
    if (signal?.aborted || started !== stamp()) return Promise.reject(aborted());
    let batch = queuedPlans.get(started);
    if (!batch) {
      batch = {started, controller: new AbortController(), subscriptions: new Set(), timer: null};
      queuedPlans.set(started, batch);
      activePlans.add(batch);
      batch.timer = setTimeout(() => { void sendPlans(batch); }, 0);
    }
    return new Promise((resolve, reject) => {
      const subscription = {component, signal, resolve, reject, abort: null};
      subscription.abort = () => settleSubscription(batch, subscription, null, aborted());
      batch.subscriptions.add(subscription);
      signal?.addEventListener('abort', subscription.abort, {once: true});
    });
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
  function refresh(root = true) {
    if (root) void window.gdEditorRoot?.reload();
    generation++;
    cancelPlans();
    configuration = null;
    catalog = null;
    runtimeIdentities.clear();
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(() => {
      void window.gdInspectorGraph?.reload();
      void window.gdExplorerGraph?.reload();
      void window.gdCanvasGraph?.reload();
      void window.gdCanvasValuesGraph?.reload();
      void window.gdCanvasInheritanceGraph?.reload();
      void window.gdCanvasMetadataGraph?.reload();
      void window.gdCanvasTypeStructureGraph?.reload();
      void window.gdCanvasBindingsGraph?.reload();
    }, 0);
  }
  window.gdPrefOnChange((key, _value, detail) => { if (key === 'components') refresh(!detail?.initial); });
  window.addEventListener('gd-auth-changed', () => {
    statuses.clear();
    refresh(false);
    window.dispatchEvent(new Event('gd-ui-components-status'));
  });
  window.addEventListener('gd-graph-changed', () => { if (eligible()) refresh(false); });
  window.addEventListener('focus', () => { if (eligible()) refresh(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && eligible()) refresh(); });
  window.gdUIComponentFailed = component => { runtimeIdentities.delete(component); status(component, 'Personal UI graph does not match the component contract. Using built-in controls.'); };
  // A legacy Explorer view can still own its valid toolbar while a newer
  // tree-body section uses the shipped graph. Keep that selection identity.
  window.gdUIComponentSectionFallback = (component, section) => {
    if (component === 'explorer' && section === 'rows') status(component, 'Personal Explorer rows are unavailable. Using built-in row presentation.');
    if (component === 'explorer' && section === 'namespaces') status(component, 'Personal Explorer namespaces are unavailable. Using built-in namespace presentation.');
    if (component === 'explorer' && section === 'diff') status(component, 'Personal Explorer compare presentation is unavailable. Using built-in compare controls.');
  };
  window.gdUIComponentRuntimeIdentity = component => {
    if (['account-menu', 'recents', 'fn-picker', 'tutorial-catalog', 'execution-status', 'execution-path', 'feedback', 'keymap', 'appearance'].includes(component) && window.gdEditorRoot) return window.gdEditorRoot.componentIdentity(component);
    const value = runtimeIdentities.get(component); return value?.stamp === stamp() ? value.id : null; };
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
  const editableRoles = {'menu-id': ['account-menu', 'view'], 'menu-update-id': ['account-menu', 'update'],
      'picker-id': ['fn-picker', 'view'], 'recents-id': ['recents', 'view'],
      'recents-initial-id': ['recents', 'initial'], 'recents-update-id': ['recents', 'update'],
      'tutorial-catalog-id': ['tutorial-catalog', 'view'],
      'execution-status-id': ['execution-status', 'view'], 'execution-path-id': ['execution-path', 'view'],
      'feedback-id': ['feedback', 'view'], 'keymap-id': ['keymap', 'view'], 'appearance-id': ['appearance', 'view']};
  window.gdUIComponentsRoots = () => {
    const selected = selection();
    const theme = window.gdPrefRead('theme')?.graph;
    return {...(catalog?.stamp === stamp() ? catalog.roots : {}),
      'configuration-id': selected?.['fn-id'],
      'theme-id': theme?.org === organization() ? theme['fn-id'] : null};
  };
  window.gdUIComponentsEditableRoot = key => {
    const source = window.gdUIComponentsRoots()[key];
    const [component, role] = editableRoles[key] || [];
    return window.gdEditorRoot?.editableRoleId(component, role, source) || source;
  };
})();
