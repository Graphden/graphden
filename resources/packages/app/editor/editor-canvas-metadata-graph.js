// Passive metadata bodies; native owners retain type reasoning, permission,
// provenance, real buttons/anchors, accessible names and event callbacks.
(() => {
  const defaults = {'declared-return': '', 'computed-return': '', constraint: '',
    'constraint-title': '', 'lambda-mode': 'derived', params: [], effect: '',
    computed: false, declared: false, 'has-contract': false};
  function projection(surface, facts) {
    const input = {...defaults, surface, ...facts};
    if (!['return', 'lambda', 'effect'].includes(surface) || input.surface !== surface
      || ['declared-return', 'computed-return', 'constraint', 'constraint-title', 'effect']
        .some(key => typeof input[key] !== 'string')
      || !['derived', 'declared'].includes(input['lambda-mode'])
      || ['computed', 'declared', 'has-contract'].some(key => typeof input[key] !== 'boolean')
      || !Array.isArray(input.params) || input.params.some((row, index) => !row
        || typeof row.label !== 'string' || row.first !== (index === 0)
        || Object.keys(row).some(key => !['label', 'first'].includes(key)))
      || Object.keys(input).some(key => key !== 'surface' && !Object.hasOwn(defaults, key))) {
      throw new Error('Invalid metadata display facts');
    }
    return input;
  }
  window.gdCanvasMetadataGraph = window.gdCreatePassiveCanvas({
    identity: 'canvas-metadata', prefix: 'gd-canvas-metadata', classes: new Set(),
    plan: () => window.GraphdenBuiltinPlans.plans.canvasMetadata,
    projection, checkStyles: window.gdCheckCanvasFlowStyles,
    failureText: () => 'Function metadata presentation unavailable',
  });
  window.gdMountMetadataBody = (owner, element, surface, facts, className = '') => {
    const host = document.createElement('span');
    host.className = 'gd-canvas-body-host' + (className ? ' ' + className : '');
    element.appendChild(host);
    return window.gdCanvasMetadataGraph.mount(owner, host, surface, facts);
  };
})();
