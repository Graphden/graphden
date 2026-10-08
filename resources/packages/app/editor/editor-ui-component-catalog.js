// Appearance's graph-owned catalog. This module only coordinates the existing
// namespace/function pickers, exact identity navigation and preference owners.
(() => {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  let generation = 0;
  let pending = null;
  let message = '';
  const organization = () => typeof graphdenCurrentOrg === 'string' && graphdenCurrentOrg ? graphdenCurrentOrg : 'public';
  const context = () => JSON.stringify([window.gdPrefOwner, organization(), getCurrentBranchName()]);
  const selected = () => window.gdUIComponentsSelection();
  function render() {
    const root = document.getElementById('gd-ui-components-root');
    if (!root) return;
    const chosen = selected();
    const ready = !!window.gdPrefsReady && !!window.gdPrefOwner;
    root.querySelector('#gd-ui-components-label').textContent = chosen?.label || (chosen ? 'Personal UI graphs' : 'Built-in components');
    for (const button of root.querySelectorAll('button')) button.disabled = !!pending || !ready;
    root.querySelector('#gd-ui-components-reset').disabled = !!pending || !ready || !chosen;
    for (const button of root.querySelectorAll('[data-ui-graph]')) {
      const kind = button.dataset.uiGraph;
      const id = window.gdUIComponentsRoots()[kind];
      button.disabled = !!pending || !uuid.test(id || '');
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
    if (!manifest || !Array.isArray(manifest.namespaces) || !Array.isArray(manifest.functions)
      || manifest.namespaces.length !== 4 || !manifest.functions.length || manifest.functions.length > 256) return false;
    const namespaces = new Set(manifest.namespaces.map(row => row.id));
    const identities = new Set([...namespaces, ...manifest.functions.map(row => row.id)]);
    if (identities.size !== 4 + manifest.functions.length) return false;
    if (manifest.namespaces.some(row => !uuid.test(row.id) || typeof row.name !== 'string'
      || !(row['parent-id'] === null || uuid.test(row['parent-id'])))) return false;
    if (manifest.functions.some(row => !uuid.test(row.id) || typeof row.name !== 'string'
      || !namespaces.has(row['namespace-id']))) return false;
    return ['configuration-id', 'theme-id', 'menu-id', 'menu-update-id', 'picker-id'].every(key =>
      manifest.functions.some(row => row.id === manifest.roots?.[key]));
  }
  async function create(namespace, started, branch) {
    const own = ++generation;
    const controller = new AbortController();
    pending = controller;
    const current = () => own === generation && started === context();
    let ticket = null;
    notify('Creating personal UI graphs…');
    try {
      const preview = await json(window.API.api_ui_components_create_preview,
        {owner: window.gdPrefOwner, 'namespace-id': namespace?.id || null}, controller.signal);
      if (!current()) return;
      if (!validManifest(preview.manifest) || preview.request?.owner !== window.gdPrefOwner
        || preview.request?.org !== organization() || !uuid.test(preview.request?.['branch-id'] || '')) throw new Error('Creation manifest is unavailable.');
      // The tour ledger stages exact server-proposed identities before APPLY;
      // ambiguous transport outcomes retain them for guarded reconciliation.
      ticket = window.gdTourStageUIComponentsManifest?.(preview.request, preview.manifest);
      const result = await json(window.API.api_ui_components_create_apply, preview.request, controller.signal);
      if (!current()) return;
      if (!result.committed || JSON.stringify(result.manifest) !== JSON.stringify(preview.manifest)) throw new Error('Creation receipt needs review. Refresh before trying again.');
      const receiptConfirmed = !ticket || window.gdTourConfirmUIComponentsManifest?.(ticket, result.manifest);
      const roots = result.manifest.roots;
      const root = result.manifest.namespaces[0];
      const preference = {'fn-id': roots['configuration-id'], 'branch-id': preview.request['branch-id'],
        branch, org: organization(), label: root.path, roots};
      const saved = await window.gdPrefWrite('components', preference);
      if (!current()) return;
      await loadGraphData();
      if (!current()) return;
      await ensureSubtreeFor(roots['theme-id']);
      if (!current()) return;
      const theme = lookups.fnMap.get(roots['theme-id']);
      if (theme) await window.gdUseThemeGraph(theme);
      if (!current()) return;
      notify(!receiptConfirmed ? 'UI graphs were created; the tutorial receipt needs review before cleanup.'
        : result['publication-warnings']?.length ? result['publication-warnings'][0].reason
        : saved ? 'Personal UI graphs are active. Open a group to edit its dependencies.'
          : 'UI graphs were created. Saving their selection failed; choose the configuration again.');
    } catch (error) {
      if (current() && ticket && error.status >= 400 && error.status < 500 && error.body?.committed === false) window.gdTourRejectUIComponentsManifest?.(ticket);
      if (current() && error.name !== 'AbortError') notify(error.message);
    } finally { if (pending === controller) { pending = null; render(); } }
  }
  async function beginCreate(anchor) {
    const started = context();
    const branch = getCurrentBranchName();
    if (!window.gdPrefsReady || !window.gdPrefOwner || pending || !anchor?.isConnected || anchor.disabled) return;
    if (!Array.isArray(graphData?.namespaces)) {
      anchor.disabled = true;
      try { await loadGraphData(); }
      catch (_) { if (started === context()) notify('Namespaces could not be loaded. Try again.'); return; }
      finally { render(); }
    }
    if (started !== context() || !anchor.isConnected || !Array.isArray(graphData?.namespaces)) return;
    openNamespacePicker({anchorEl: anchor, onPick(namespace) {
      if (started === context() && anchor.isConnected && !pending) void create(namespace, started, branch);
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
    try {
      const response = await window.authFetch(window.API.api_branches_ref(getCurrentBranchName()), {cache: 'no-store'});
      const result = await response.json();
      if (started !== context()) return;
      if (!response.ok || !result.ok || !uuid.test(result.branch?.id || '')) throw new Error('unavailable');
      const saved = await window.gdPrefWrite('components', {'fn-id': fn.id, org: organization(),
        branch: result.branch.name, 'branch-id': result.branch.id, label: getQualifiedFnName(fn)});
      if (started === context() && !saved) notify('Saving the configuration selection failed.');
    } catch (_) { if (started === context()) notify('The configuration could not be selected in this branch.'); }
  }
  window.gdRenderUIComponentCatalog = () => {
    const root = document.getElementById('gd-ui-components-root');
    if (!root || root.dataset.wired) { render(); return; }
    root.dataset.wired = '1';
    root.querySelector('#gd-ui-components-create').addEventListener('click', event => { void beginCreate(event.currentTarget); });
    root.querySelector('#gd-ui-components-choose').addEventListener('click', event => {
      const started = context();
      openFnPicker({anchorEl: event.currentTarget,
        expectedType: {'menu-initial': 'uuid', 'menu-update': 'uuid', 'menu-view': 'uuid', 'picker-view': 'uuid'},
        onPick(fn) {
          if (started !== context()) return;
          void choose(fn, started);
        }});
    });
    root.querySelector('#gd-ui-components-reset').addEventListener('click', () => { void window.gdPrefWrite('components', null); });
    for (const button of root.querySelectorAll('[data-ui-graph]')) button.addEventListener('click', () => { void open(button.dataset.uiGraph); });
    render();
  };
  window.gdPrefOnChange(key => { if (key === 'components' || key === 'theme') { message = ''; render(); } });
  window.addEventListener('gd-ui-components-status', render);
  window.addEventListener('gd-auth-changed', () => { generation++; pending?.abort(); pending = null; message = ''; render(); });
})();
