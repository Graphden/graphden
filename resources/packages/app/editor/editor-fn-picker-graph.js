// Review-only list rendering. Search IO, type verdicts and selection stay native.
(() => {
  const params = new URLSearchParams(location.search);
  if (!params.has('ui-picker-view')) return;
  const api = window.GraphdenBrowser;
  const get = (value, name) => value instanceof Map ? value.get(api.keyword(name)) : undefined;
  const value = (item) => Array.isArray(item) ? item.map(value)
    : item && typeof item === 'object' ? new Map(Object.entries(item).map(([name, field]) => [api.keyword(name), value(field)])) : item;
  let runtime = null;
  let generation = 0;
  const instances = new Set();
  function invalidate() {
    generation++;
    runtime = null;
    integration.ready = false;
    if (instances.size) window.closeFnPicker?.();
    for (const instance of [...instances]) instance.dispose();
  }
  async function load() {
    const own = ++generation;
    try {
      const entries = {initial: params.get('ui-initial'), update: params.get('ui-update'), view: params.get('ui-picker-view')};
      if (!params.get('branch') || params.get('branch') === 'main'
        || Object.values(entries).some((id) => !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id || ''))) throw new Error('Choose an explicit review branch and picker entry');
      const response = await window.authFetch('/ui-preview/plan?' + new URLSearchParams({branch: getCurrentBranchName(), ...entries}), {cache: 'no-store'});
      const plan = await response.json();
      if (!response.ok || plan.ok === false) throw new Error(plan.reason || 'Graph export refused');
      if (own !== generation) return;
      runtime = api.createRuntime(plan, {operationLimit: 100000});
      integration.ready = true;
    } catch (error) {
      if (own !== generation) return;
      invalidate();
      if (typeof gdToast === 'function') gdToast('Picker graph unavailable: ' + error.message);
    }
  }
  const integration = {
    ready: false,
    mount(host, formatCandidate) {
      if (!runtime) return null;
      const evaluator = runtime;
      const idPrefix = 'gd-picker-' + crypto.randomUUID();
      const component = window.GraphdenRenderer.mount(host, {idPrefix});
      const candidateKeys = new Map();
      let nextCandidateKey = 0;
      let disposed = false;
      const instance = {
        render(arranged, options) {
          if (disposed) throw new Error('Picker graph is disposed');
          const entries = [];
          const row = (candidate, bareName) => {
            const nameKey = 'name:' + candidate.qualified;
            const identityKey = candidate.id ? 'id:' + candidate.id : null;
            const prior = candidateKeys.get(nameKey);
            const nameMatch = prior && (!candidate.id || !prior.id || prior.id === candidate.id) ? prior.key : null;
            const key = (identityKey && candidateKeys.get(identityKey)) || nameMatch || 'candidate-' + nextCandidateKey++;
            candidateKeys.set(nameKey, {key, id: candidate.id || prior?.id || null});
            if (identityKey) candidateKeys.set(identityKey, key);
            const index = entries.length;
            entries.push({key, c: candidate});
            return {key, 'option-id': idPrefix + '-option-' + index, 'qualified-name': candidate.qualified,
              active: index === options.activeIdx, ...formatCandidate(candidate, bareName)};
          };
          const sections = [];
          if (arranged.exact.length) sections.push({key: 'exact', kind: 'exact', 'show-header': true,
            count: arranged.exact.length, open: true, foldable: false, rows: arranged.exact.map((candidate) => row(candidate, false))});
          for (const group of arranged.groups) {
            const key = group.ns === null ? 'group-unnamespaced' : 'group-namespace-' + group.ns;
            const showHeader = group.ns !== null || arranged.groups.length > 1 || arranged.exact.length > 0;
            const index = entries.length;
            if (showHeader && !options.q) entries.push({key: key + '-header', group});
            sections.push({key, 'header-key': key + '-header', 'option-id': idPrefix + '-option-' + index,
              kind: 'group', label: group.ns || '', count: options.q ? group.rows.length : options.expected ? group.compat : group.rows.length,
              'other-count': options.expected ? group.other || 0 : 0, 'show-header': showHeader, foldable: !options.q,
              open: group.open, active: index === options.activeIdx, truncated: !!group.truncated,
              rows: group.open ? group.rows.map((candidate) => row(candidate, true)) : []});
          }
          const activeKey = entries[Math.min(options.activeIdx, Math.max(0, entries.length - 1))]?.key;
          for (const section of sections) {
            section.active = section['header-key'] === activeKey;
            for (const candidate of section.rows) candidate.active = candidate.key === activeKey;
          }
          const model = {sections, 'empty-kind': arranged.total === 0 ? options.q ? 'search' : options.expected ? 'compatible' : 'all' : 'none',
            'show-other-toggle': !!options.expected && !options.q && (arranged.hiddenOther > 0 || options.showOther),
            'show-other': options.showOther, 'hidden-other': arranged.hiddenOther};
          const output = evaluator.run('view', {model: value(model)});
          component.render(get(output, 'tree'), get(output, 'styles'));
          const nodes = new Map();
          for (const node of host.querySelectorAll('[data-picker-key]')) {
            const key = node.dataset.pickerKey;
            if (nodes.has(key) || node.getAttribute('role') !== 'option') throw new Error('Invalid picker graph options');
            nodes.set(key, node);
          }
          if (nodes.size !== entries.length) throw new Error('Picker graph lost a navigation destination');
          for (const entry of entries) {
            entry.rowEl = nodes.get(entry.key);
            if (!entry.rowEl) throw new Error('Picker graph changed a navigation identity');
          }
          return entries;
        },
        dispose() {
          if (disposed) return;
          disposed = true;
          instances.delete(instance);
          candidateKeys.clear();
          component.dispose();
        },
      };
      instances.add(instance);
      return instance;
    },
  };
  window.gdFnPickerGraph = integration;
  document.addEventListener('DOMContentLoaded', () => { if (isAuthenticated() || accountsAuthed) void load(); }, {once: true});
  window.addEventListener('gd-auth-changed', () => {
    invalidate();
    if (isAuthenticated() || accountsAuthed) void load();
  });
})();
