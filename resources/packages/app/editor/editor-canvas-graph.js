// Existing canvas ABI stays intact; only its passive-host machinery is shared.
(() => {
  const surfaces = new Set(['fn-name', 'root-diagnostics', 'branch-local', 'deep-free']);
  const defaults = {name: null, secret: false, root: false, 'type-errors': 0,
    'failed-runs': 0, lint: 0, 'branch-state': 'off', 'branch-seed': null, 'deep-free': []};
  const classes = new Set(['fn-row-secret-mark', 'type-error-badge', 'failed-run-badge',
    'lint-badge', 'branch-local-strip-glyph', 'branch-local-strip-label']);
  function projection(surface, facts) {
    const input = {...defaults, surface, ...facts};
    if (!surfaces.has(surface) || input.surface !== surface
      || !(input.name === null || typeof input.name === 'string')
      || typeof input.secret !== 'boolean' || typeof input.root !== 'boolean'
      || ['type-errors', 'failed-runs', 'lint'].some(key => !Number.isSafeInteger(input[key]) || input[key] < 0)
      || !['off', 'own', 'inherited'].includes(input['branch-state'])
      || !(input['branch-seed'] === null || typeof input['branch-seed'] === 'string')
      || !Array.isArray(input['deep-free']) || input['deep-free'].some(name => typeof name !== 'string')
      || Object.keys(input).some(key => key !== 'surface' && !Object.hasOwn(defaults, key))) {
      throw new Error('Invalid canvas display facts');
    }
    // The caller supplies only authorized labels/classifications, never entity
    // records, secret contents, paths, IDs or mutation authority.
    // Root visibility is native authority even when a custom view ignores it.
    if (surface === 'root-diagnostics' && !input.root) throw new Error('Root diagnostics need a root owner');
    return input;
  }
  window.gdCanvasGraph = window.gdCreatePassiveCanvas({
    identity: 'canvas', prefix: 'gd-canvas', classes,
    plan: () => window.GraphdenBuiltinPlans.plans.canvas, projection,
    failureText: input => input.surface === 'deep-free'
      ? input['deep-free'].length + ' free arguments — canvas presentation unavailable'
      : 'Canvas presentation unavailable',
  });
})();
