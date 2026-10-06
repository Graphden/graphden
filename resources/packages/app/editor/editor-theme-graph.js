// A personal preference selects an ordinary graph. Only its validated result
// reaches CSS; the graph runs through the server's normal execution policy.
(() => {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  let generation = 0;
  let refreshTimer = null;
  let status = '';
  // Only this exact preference object can reuse a result checked in this tab.
  // Reloaded server preferences always run through authorization again.
  const evaluatedPreferences = new WeakSet();
  const org = () => typeof graphdenCurrentOrg === 'string' && graphdenCurrentOrg ? graphdenCurrentOrg : 'public';
  const notify = (message) => {
    if (status === message) return;
    status = message;
    window.dispatchEvent(new Event('gd-theme-graph-status'));
  };
  function validSelection(graph) {
    return graph && uuid.test(graph['fn-id'] || '') && typeof graph.org === 'string'
      && typeof graph.branch === 'string' && graph.branch.length > 0 && graph.branch.length <= 200;
  }
  async function evaluate(graph, signal) {
    if (!validSelection(graph) || graph.org !== org() || !window.gdPrefOwner) throw new Error('unavailable');
    const route = window.API?.api_ui_theme_evaluate;
    if (typeof route !== 'string') throw new Error('unavailable');
    const response = await window.authFetch(route, {
      method: 'POST', signal,
      headers: {'Content-Type': 'application/json', 'X-Graphden-Branch': graph.branch},
      body: JSON.stringify({'fn-id': graph['fn-id'], org: graph.org, owner: window.gdPrefOwner, args: {}}),
    });
    const result = await response.json();
    if (!response.ok || !result.ok || !result.payload) throw new Error('unavailable');
    const payload = window.gdSanitizeThemePayload(result.payload);
    if (!payload) throw new Error('unavailable');
    return payload;
  }
  let pending = null;
  async function applyPreference(preference) {
    const own = ++generation;
    pending?.abort();
    pending = null;
    window.gdApplyThemePayload(preference?.payload || null);
    notify('');
    if (!preference?.graph) return;
    // Mirrored preferences can precede the cookie identity probe at boot.
    // Evaluate a graph only after this account's server preferences arrive.
    if (!window.gdPrefsReady) return;
    if (!validSelection(preference.graph) || preference.graph.org !== org()) {
      notify('Theme graph is unavailable here. Using saved colors.');
      return;
    }
    if (evaluatedPreferences.delete(preference)) return;
    const controller = new AbortController();
    pending = controller;
    notify('Loading theme graph…');
    try {
      const payload = await evaluate(preference.graph, controller.signal);
      if (own !== generation) return;
      window.gdApplyThemePayload(payload);
      notify('');
      if (JSON.stringify(payload) !== JSON.stringify(preference.payload)) {
        const updated = {...preference, payload};
        evaluatedPreferences.add(updated);
        if (!await window.gdPrefWrite('theme', updated) && own + 1 === generation) {
          notify('Theme applied in this tab. Saving the preference failed.');
        }
      }
    } catch (_) {
      if (own === generation) notify('Theme graph is unavailable. Using saved colors.');
    } finally {
      if (pending === controller) pending = null;
    }
  }
  async function choose(fn) {
    clearTimeout(refreshTimer);
    pending?.abort();
    const graph = {'fn-id': fn.id, org: org(), branch: getCurrentBranchName(),
      label: typeof getQualifiedFnName === 'function' ? getQualifiedFnName(fn) : fn.name || ''};
    const own = ++generation;
    try {
      const payload = await evaluate(graph);
      if (own !== generation) return;
      const preference = {source: null, payload, graph};
      evaluatedPreferences.add(preference);
      if (!await window.gdPrefWrite('theme', preference) && own + 1 === generation) {
        notify('Theme applied in this tab. Saving the preference failed.');
      }
      return own + 1 === generation ? graph : null;
    } catch (_) {
      if (own === generation) notify('Choose a pure graph that returns a theme.');
    }
  }
  async function create(namespace, owner) {
    clearTimeout(refreshTimer);
    pending?.abort();
    const own = ++generation;
    if (!owner || owner !== window.gdPrefOwner) return;
    notify('Creating theme graph…');
    try {
      const response = await window.authFetch(window.API.api_ui_theme_create, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({'namespace-id': namespace?.id || null, owner}),
      });
      const result = await response.json();
      if (own !== generation) return;
      if (!response.ok || !result.ok) {
        if (typeof result.namespace === 'string') {
          await loadGraphData();
          if (own === generation) notify('Theme could not be created. Namespace ' + result.namespace + ' was kept.');
          return;
        }
        throw new Error('unavailable');
      }
      if (typeof result.namespace !== 'string') throw new Error('unavailable');
      await loadGraphData();
      if (own !== generation) return;
      const fn = await resolveFnByName(result.namespace + '.theme');
      if (own !== generation || !fn?.id) return;
      const graph = await choose(fn);
      if (graph) await window.gdOpenThemeGraph(graph);
    } catch (_) {
      if (own === generation) notify('Theme graph could not be created. Choose a writable namespace.');
    }
  }
  window.gdApplyThemeGraphPreference = applyPreference;
  window.gdThemeGraphStatus = () => status;
  window.gdCreateThemeGraph = (anchorEl) => {
    const owner = window.gdPrefOwner;
    openNamespacePicker({anchorEl, onPick: (namespace) => { void create(namespace, owner); }});
  };
  window.gdChooseThemeGraph = (anchorEl) => openFnPicker({anchorEl,
    expectedType: {mode: 'text', tokens: ['map', 'text', 'text'], fonts: ['map', 'text', 'text'], scale: 'int'},
    onPick: (fn) => { void choose(fn); }});
  window.gdOpenThemeGraph = async (graph) => {
    if (!validSelection(graph) || graph.org !== org()) return;
    if (graph.branch !== getCurrentBranchName()) {
      const url = new URL(location.href);
      url.searchParams.set('branch', graph.branch);
      url.hash = graph.label || '';
      location.assign(url.toString());
      return;
    }
    try {
      await ensureSubtreeFor(graph['fn-id']);
      window.gdShellSurface('build');
      selectFn(graph['fn-id']);
    } catch (_) { notify('Theme graph is unavailable here.'); }
  };
  window.addEventListener('gd-graph-changed', () => {
    const graph = window.gdPrefRead('theme')?.graph;
    if (!graph || graph.branch !== getCurrentBranchName()) return;
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(() => { void applyPreference(window.gdPrefRead('theme')); }, 250);
  });
  window.addEventListener('gd-auth-changed', () => {
    generation++;
    pending?.abort();
    pending = null;
    clearTimeout(refreshTimer);
    window.gdApplyThemePayload(null);
    notify('');
    if (isAuthenticated() || accountsAuthed) void window.gdPrefsRefresh();
  });
})();
