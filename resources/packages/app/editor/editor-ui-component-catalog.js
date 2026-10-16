// Appearance's graph-owned catalog. This module only coordinates the existing
// namespace/function pickers, authenticated configuration CAS and navigation.
(() => {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  let generation = 0;
  let pending = null;
  let message = '';
  const organization = () => typeof graphdenCurrentOrg === 'string' && graphdenCurrentOrg ? graphdenCurrentOrg : 'public';
  const context = () => JSON.stringify([window.gdPrefOwner, organization(), getCurrentBranchName()]);
  const groups = { 'account-menu': 'menu-id', 'fn-picker': 'picker-id', recents: 'recents-id', inspector: 'inspector-id', explorer: 'explorer-id', 'tutorial-catalog': 'tutorial-catalog-id', canvas: 'canvas-id', 'canvas-values': 'canvas-values-id' };
  const selectionStamp = () => JSON.stringify(window.gdPrefRead('components') ?? null);
  const selected = () => window.gdUIComponentsSelection();
  function render() {
    const root = document.getElementById('gd-ui-components-root');
    if (!root) return;
    const chosen = selected();
    const ready = !!window.gdPrefsReady && !!window.gdPrefOwner;
    root.querySelector('#gd-ui-components-label').textContent = chosen?.label
      || (chosen?.['fn-id'] === window.gdUIConfigurationState?.()?.defaults ? 'Shared default graphs'
        : chosen ? 'Personal UI graphs' : 'Built-in components');
    for (const button of root.querySelectorAll('button')) button.disabled = !!pending || !ready;
    root.querySelector('#gd-ui-components-reset').disabled = !!pending || !ready
      || !chosen || chosen['fn-id'] === window.gdUIConfigurationState?.()?.defaults;
    for (const button of root.querySelectorAll('[data-ui-graph]')) {
      const kind = button.dataset.uiGraph;
      const id = window.gdUIComponentsRoots()[kind];
      button.disabled = !!pending || !uuid.test(id || '');
    }
    for (const button of root.querySelectorAll('[data-ui-component]')) {
      const present = window.gdUIConfigurationState?.()?.custom?.[button.dataset.uiComponent] === true;
      button.textContent = (present ? 'Open ' : 'Personalize ') + button.dataset.uiLabel;
    }
    root.querySelector('#gd-ui-components-status').textContent = message || window.gdUIComponentsStatus();
  }
  function notify(value) { message = value; render(); }
  async function json(route, body, signal) {
    const response = await window.authFetch(route, {method: 'POST', signal,
      headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    const result = await response.json();
    if (!response.ok || !result.ok) {
      const error = new Error(result.reason || 'UI graphs could not be created.');
      error.status = response.status; error.body = result;
      throw error;
    }
    return result;
  }
  function validManifest(manifest) {
    const fullLimit = window.GraphdenAtomicFunctionBatchMax;
    if (!manifest || !Array.isArray(manifest.namespaces) || !Array.isArray(manifest.functions)
      || !manifest.namespaces.length || manifest.namespaces.length > 16
      || !Number.isInteger(fullLimit) || fullLimit < 1
      || !manifest.functions.length || manifest.functions.length > (manifest.component ? 512 : fullLimit)) return false;
    const namespaces = new Set(manifest.namespaces.map(row => row.id));
    const identities = new Set([...namespaces, ...manifest.functions.map(row => row.id)]);
    if (identities.size !== manifest.namespaces.length + manifest.functions.length) return false;
    if (manifest.namespaces.some(row => !uuid.test(row.id) || typeof row.name !== 'string'
      || !(row['parent-id'] === null || uuid.test(row['parent-id'])))) return false;
    if (manifest.functions.some(row => !uuid.test(row.id) || typeof row.name !== 'string'
      || !namespaces.has(row['namespace-id']))) return false;
    const required = manifest.component
      ? ['configuration-id', groups[manifest.component], ...(manifest.component === 'account-menu' ? ['menu-update-id']
        : manifest.component === 'recents' ? ['recents-initial-id', 'recents-update-id']
        : manifest.component === 'explorer' ? ['explorer-initial-id', 'explorer-update-id']
        : manifest.component === 'inspector' ? ['inspector-initial-id', 'inspector-update-id'] : [])]
      : ['configuration-id', 'theme-id', 'menu-id', 'menu-update-id', 'picker-id'];
    if (manifest.component) {
      const expected = manifest['expected-preference'];
      if (!Object.hasOwn(groups, manifest.component) || !expected || typeof expected.present !== 'boolean'
        || !Object.hasOwn(expected, 'value') || (!expected.present && expected.value !== null)) return false;
    }
    return required.every(key =>
      manifest.functions.some(row => row.id === manifest.roots?.[key]));
  }
  function validConfigurationReceipt(receipt) {
    if (!receipt || typeof receipt.created !== 'boolean' || !uuid.test(receipt.id || '')
      || !/^[0-9a-f]{64}$/.test(receipt['configuration-revision'] || '')
      || !Array.isArray(receipt.functions) || receipt.functions.length > 512
      || !Array.isArray(receipt.namespaces) || receipt.namespaces.length > 16) return false;
    if (!receipt.created) return receipt.functions.length === 0 && receipt.namespaces.length === 0;
    if (!/^[0-9a-f]{64}$/.test(receipt['restore-revision'] || '') || !uuid.test(receipt['restore-id'] || '')) return false;
    if (!/^[0-9a-f]{64}$/.test(receipt['cohort-revision'] || '') || !receipt.functions.length
      || !Array.isArray(receipt['function-receipts'])) return false;
    const ids = new Set(receipt.functions);
    return ids.size === receipt.functions.length && ids.has(receipt.id)
      && receipt.namespaces.every(id => uuid.test(id))
      && receipt['function-receipts'].length === ids.size
      && new Set(receipt['function-receipts'].map(row => row.id)).size === ids.size
      && receipt['function-receipts'].every(row => ids.has(row.id) && uuid.test(row.id)
        && uuid.test(row['namespace-id']) && typeof row.name === 'string' && row.name.length > 0)
      && (!receipt.previous || (uuid.test(receipt.previous.id) && !ids.has(receipt.previous.id)
        && uuid.test(receipt.previous['namespace-id']) && typeof receipt.previous.name === 'string'
        && /^[0-9a-f]{64}$/.test(receipt.previous.revision || '')));
  }
  function canonical(value) {
    if (Array.isArray(value)) return value.map(canonical);
    if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
    return value;
  }
  async function previewApply(operation, command, controller, current) {
    const state = window.gdUIConfigurationState?.();
    if (!state?.revision || !state.preference) throw new Error('Refresh the editor configuration before changing it.');
    const preview = await json(window.API.api_ui_configuration_preview, {operation,
      command: {...command, 'expected-preference': state.preference,
        'expected-configuration-revision': state.revision}}, controller.signal);
    if (!current()) return null;
    if (!validConfigurationReceipt(preview.receipt) || preview.request?.operation !== operation
      || preview.request?.scope?.org !== organization() || !uuid.test(preview.request?.scope?.['branch-id'] || '')
      || !uuid.test(preview.request?.nonce || '') || !/^[0-9a-f]{64}$/.test(preview.request?.['expected-proposal'] || '')) {
      throw new Error('The configuration preview is unavailable.');
    }
    const beforeRoots = window.gdUIComponentsRoots?.();
    const ticket = preview.receipt.created
      ? window.gdTourStageEditorConfiguration?.(preview.request, preview.receipt) : null;
    try {
      const result = await json(window.API.api_ui_configuration_apply, preview.request, controller.signal);
      if (!current()) return null;
      const comparable = {...result};
      for (const key of ['ok', 'committed', 'changed-functions', 'publication-warnings']) delete comparable[key];
      if ((preview.receipt.created && result.committed !== true)
        || JSON.stringify(canonical(comparable)) !== JSON.stringify(canonical(preview.receipt))) {
        throw new Error('Configuration outcome needs review. The original receipt is retained.');
      }
      if (ticket && window.gdTourConfirmEditorConfiguration?.(ticket, result) !== true) {
        result['publication-warnings'] = [{reason: 'Your configuration is active. The tutorial receipt needs review before cleanup.'},
          ...(result['publication-warnings'] || [])];
      }
      if (result.created === false) window.gdTourObserveExistingUIComponent?.(preview.request, result, beforeRoots);
      window.gdRefreshUIConfiguration?.();
      return result;
    } catch (error) {
      if (ticket && error.status >= 400 && error.status < 500) window.gdTourRejectEditorConfiguration?.(ticket);
      throw error;
    }
  }
  async function changeConfiguration(operation, command) {
    if (pending || !window.gdPrefsReady || !window.gdPrefOwner) return;
    const started = context();
    const own = ++generation;
    const controller = new AbortController();
    pending = controller;
    const current = () => own === generation && started === context();
    notify('Preparing personal UI graphs…');
    try {
      await json(window.API.api_ui_configuration_prepare, {home: true}, controller.signal);
      if (!current()) return;
      await window.gdCaptureUIConfiguration(controller.signal);
      if (!current()) return;
      const result = await previewApply(operation, command, controller, current);
      if (!result || !current()) return;
      await loadGraphData();
      if (!current()) return;
      if (result['entry-id']) {
        if (await ensureSubtreeFor(result['entry-id']) && current()) selectFn(result['entry-id']);
      }
      if (current()) notify(result['publication-warnings']?.[0]?.reason
        || 'Ordinary editor configuration is active. Shared dependencies remain referenced.');
    } catch (error) {
      if (current() && error.name !== 'AbortError') notify(error.message);
    } finally { if (pending === controller) { pending = null; render(); } }
  }
  async function create(namespace, started, component, stamp) {
    const own = ++generation;
    const controller = new AbortController();
    pending = controller;
    const current = () => own === generation && started === context();
    let ticket = null;
    notify('Creating personal UI graphs…');
    try {
      await json(window.API.api_ui_configuration_prepare, {home: true}, controller.signal);
      if (!current()) return;
      await window.gdCaptureUIConfiguration(controller.signal);
      if (!current()) return;
      const preview = await json(window.API.api_ui_components_create_preview,
        {owner: window.gdPrefOwner, 'namespace-id': namespace?.id || null, ...(component ? {component} : {})}, controller.signal);
      if (!current()) return;
      if (!validManifest(preview.manifest) || (component && preview.manifest.component !== component) || preview.request?.owner !== window.gdPrefOwner
        || preview.request?.org !== organization() || !uuid.test(preview.request?.['branch-id'] || '')) throw new Error('Creation manifest is unavailable.');
      // The tour ledger stages exact server-proposed identities before APPLY;
      // ambiguous transport outcomes retain them for guarded reconciliation.
      ticket = window.gdTourStageUIComponentsManifest?.(preview.request, preview.manifest);
      const result = await json(window.API.api_ui_components_create_apply, preview.request, controller.signal);
      if (!current()) return;
      if (!result.committed || JSON.stringify(result.manifest) !== JSON.stringify(preview.manifest)) throw new Error('Creation receipt needs review. Refresh before trying again.');
      const receiptConfirmed = !ticket || window.gdTourConfirmUIComponentsManifest?.(ticket, result.manifest);
      const roots = result.manifest.roots;
      if (stamp !== selectionStamp()) {
        await loadGraphData();
        notify('UI graphs were created. Your configuration selection changed; the new graphs remain available.');
        return;
      }
      const activated = await previewApply('activate', {'selection-id': roots['configuration-id']}, controller, current);
      const saved = !!activated;
      if (!current()) return;
      await loadGraphData();
      if (!current()) return;
      if (!component && saved) {
        await ensureSubtreeFor(roots['theme-id']);
        if (!current()) return;
        const theme = lookups.fnMap.get(roots['theme-id']);
        if (theme) await window.gdUseThemeGraph(theme);
      }
      if (!current()) return;
      notify(!receiptConfirmed ? 'UI graphs were created; the tutorial receipt needs review before cleanup.'
        : result['publication-warnings']?.length ? result['publication-warnings'][0].reason
        : saved ? 'Personal UI graphs are active. Open a group to edit its dependencies.'
          : 'UI graphs were created. Their selection changed or could not be saved; choose the new configuration to activate it.');
    } catch (error) {
      if (current() && ticket && error.status >= 400 && error.status < 500 && error.body?.committed === false) window.gdTourRejectUIComponentsManifest?.(ticket);
      if (current() && error.name !== 'AbortError') notify(error.message);
    } finally { if (pending === controller) { pending = null; render(); } }
  }
  async function beginCreate(anchor, component) {
    const started = context();
    const stamp = selectionStamp();
    if (!window.gdPrefsReady || !window.gdPrefOwner || pending || !anchor?.isConnected || anchor.disabled) return;
    if (!Array.isArray(graphData?.namespaces)) {
      anchor.disabled = true;
      try { await loadGraphData(); }
      catch (_) { if (started === context()) notify('Namespaces could not be loaded. Try again.'); return; }
      finally { render(); }
    }
    if (started !== context() || !anchor.isConnected || !Array.isArray(graphData?.namespaces)) return;
    openNamespacePicker({anchorEl: anchor, onPick(namespace) {
      if (started === context() && stamp === selectionStamp() && anchor.isConnected && !pending) void create(namespace, started, component, stamp);
    }});
  }
  async function open(kind) {
    const chosen = selected();
    const id = window.gdUIComponentsRoots()[kind];
    if (!uuid.test(id || '')) return;
    const destination = kind === 'theme-id' ? window.gdPrefRead('theme')?.graph : chosen;
    if (!destination) return;
    if (destination.branch !== getCurrentBranchName() && destination['branch-id'] !== getCurrentBranchName()) {
      const url = new URL(location.href);
      url.searchParams.set('branch', destination['branch-id'] || destination.branch);
      url.hash = 'fn:' + id;
      location.assign(url.toString());
      return;
    }
    const started = context();
    try {
      if (!await ensureSubtreeFor(id) || started !== context()) return;
      if (!lookups.fnMap.has(id)) throw new Error('unavailable');
      window.gdShellSurface('build');
      selectFn(id);
    } catch (_) { notify('This UI graph is unavailable in the current branch.'); }
  }
  async function choose(fn, started) {
    if (started === context()) await changeConfiguration('activate', {'selection-id': fn.id});
  }
  window.gdRenderUIComponentCatalog = () => {
    const root = document.getElementById('gd-ui-components-root');
    if (!root || root.dataset.wired) { render(); return; }
    root.dataset.wired = '1';
    root.querySelector('#gd-ui-components-create').addEventListener('click', event => { void beginCreate(event.currentTarget); });
    for (const button of root.querySelectorAll('[data-ui-component]')) button.addEventListener('click', () => {
      const component = button.dataset.uiComponent;
      const kind = groups[component];
      if (!kind) return;
      if (window.gdUIConfigurationState?.()?.custom?.[component] === true) void open(kind);
      else void changeConfiguration('personalize', {component});
    });
    root.querySelector('#gd-ui-components-choose').addEventListener('click', event => {
      const started = context();
      openFnPicker({anchorEl: event.currentTarget,
        expectedType: ['union',
          {'menu-initial': 'uuid', 'menu-update': 'uuid', 'menu-view': 'uuid', 'picker-view': 'uuid'},
          ['list', {'component-id': 'uuid', entries: ['list', {role: 'keyword', 'fn-id': 'uuid'}]}],
          ['map', 'uuid', ['list', {role: 'keyword', 'fn-id': 'uuid'}]]],
        onPick(fn) {
          if (started !== context()) return;
          void choose(fn, started);
        }});
    });
    root.querySelector('#gd-ui-components-reset').addEventListener('click', () => { const id = window.gdUIConfigurationState?.()?.defaults;
      if (uuid.test(id || '')) void changeConfiguration('activate', {'selection-id': id}); });
    for (const button of root.querySelectorAll('[data-ui-graph]')) button.addEventListener('click', () => { void open(button.dataset.uiGraph); });
    render();
  };
  window.gdPrefOnChange(key => { if (key === 'components' || key === 'theme') { message = ''; render(); } });
  window.addEventListener('gd-ui-components-status', render);
  window.addEventListener('gd-auth-changed', () => { generation++; pending?.abort(); pending = null; message = ''; render(); });
})();
