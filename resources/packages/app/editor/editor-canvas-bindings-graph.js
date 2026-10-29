// Passive binding labels and explanations. Native owners retain identifiers,
// permissions, binder reasons, checked/disabled state and every callback.
(() => {
  const defaults = {label: '', secondary: '', source: '', base: '', key: '',
    'terminal-source': '', 'list-source': '', 'required-source': '',
    ancestor: false, 'has-list': false, authenticated: false, any: false,
    editable: false, optional: false, terminal: false, 'list-closed': false,
    required: false, entries: []};
  const surfaces = new Set(['label', 'source', 'provenance', 'seal-badge', 'seal-header',
    'seal-inherited', 'seal-checkbox', 'seal-hint', 'seal-empty', 'seal-reason',
    'lambda-head', 'lambda-mode-derived-label', 'lambda-mode-derived-hint',
    'lambda-mode-none-label', 'lambda-mode-none-hint', 'lambda-mode-named-label',
    'lambda-mode-named-hint', 'branch-local-label', 'branch-local-hint']);
  function projection(surface, facts) {
    const input = {surface, ...defaults, ...facts};
    if (!surfaces.has(surface) || input.surface !== surface
      || Object.keys(input).some(key => key !== 'surface' && !Object.hasOwn(defaults, key))
      || Object.entries(defaults).some(([key, value]) => key !== 'entries' && typeof input[key] !== typeof value)
      || !Array.isArray(input.entries) || input.entries.some((row, index) => !row
        || typeof row.name !== 'string' || row.first !== (index === 0)
        || Object.keys(row).some(key => !['name', 'first'].includes(key)))) {
      throw new Error('Invalid binding display facts');
    }
    return input;
  }
  const geometry = new Map();
  const component = window.gdCreatePassiveCanvas({identity: 'canvas-bindings',
    prefix: 'gd-canvas-bindings', classes: new Set(),
    plan: () => window.GraphdenBuiltinPlans.plans.canvasBindings,
    projection,
    checkStyles: styles => window.gdCheckCanvasFlowStyles(styles),
    failureText: () => 'Binding presentation unavailable',
    geometry(owners) {
      let layout = false;
      for (const owner of owners) if (geometry.get(owner)?.() === true) layout = true;
      return layout;
    },
  });
  const field = (value, name) => value instanceof Map
    ? value.get(window.GraphdenBrowser.keyword(name)) : undefined;
  function flagPolicy(facts) {
    if (!facts || !['lambda', 'branch-local'].includes(facts.surface)
      || typeof facts.declared !== 'boolean' || !Number.isSafeInteger(facts['names-count'])
      || facts['names-count'] < 0 || typeof facts.own !== 'boolean'
      || typeof facts.inherited !== 'boolean') throw new Error('Invalid flag facts');
    return component.initialBatch(run => run(facts, output => {
      const mode = field(output, 'mode');
      const checked = field(output, 'checked');
      const disabled = field(output, 'disabled');
      const expectedMode = facts.declared
        ? (facts['names-count'] === 0 ? 'none' : 'named') : 'derived';
      if (!(output instanceof Map) || output.size !== 3
        || mode !== (facts.surface === 'lambda' ? expectedMode : '')
        || checked !== (facts.surface === 'branch-local' && (facts.inherited || facts.own))
        || disabled !== (facts.surface === 'branch-local' && facts.inherited)) {
        throw new Error('Invalid flag policy');
      }
      return {mode, checked, disabled};
    }));
  }
  window.gdCanvasBindingsGraph = {...component,
    flagPolicy,
    bindGeometry(owner, callback) { geometry.set(owner, callback); },
    dispose(owner) { geometry.delete(owner); component.dispose(owner); },
    disposeWithin(root) {
      for (const owner of geometry.keys()) if (owner === root || root.contains(owner)) geometry.delete(owner);
      component.disposeWithin(root);
    },
  };
  window.addEventListener('gd-auth-changed', () => geometry.clear());
  window.gdMountBindingBody = (owner, element, surface, facts, options = {}) => {
    const frame = document.createElement('span');
    frame.className = 'canvas-bindings-body-frame';
    const host = document.createElement('span');
    host.className = 'gd-canvas-body-host';
    frame.appendChild(host);
    element.appendChild(frame);
    return component.mount(owner, host, surface, facts, options);
  };
  window.gdAppendBindingControlMark = (element, label) => {
    element.classList.add('canvas-bindings-control');
    const mark = document.createElement('span');
    mark.className = 'canvas-bindings-control-mark';
    mark.setAttribute('aria-hidden', 'true');
    mark.textContent = label;
    element.appendChild(mark);
    return mark;
  };
})();
