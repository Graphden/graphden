// Ordinary passive value/type presentation. Type/refinement resolution, literal
// projection, secret refusal, sanitized reprs and all controls remain native.
(() => {
  const surfaces = new Set(['value-label', 'type-chip', 'type-history']);
  const defaults = {label: '', secondary: '', 'secondary-kind': 'none', 'secondary-title': '', history: []};
  function projection(surface, facts) {
    const input = {...defaults, surface, ...facts};
    if (!surfaces.has(surface) || input.surface !== surface
      || ['label', 'secondary', 'secondary-title'].some(key => typeof input[key] !== 'string')
      || !['none', 'refinement', 'narrowing'].includes(input['secondary-kind'])
      || !Array.isArray(input.history) || input.history.some(row => !row || typeof row.label !== 'string'
        || typeof row['source-label'] !== 'string'
        || !['none', 'binding-override', 'slot'].includes(row.kind)
        || Object.keys(row).some(key => !['label', 'source-label', 'kind'].includes(key)))
      || Object.keys(input).some(key => key !== 'surface' && !Object.hasOwn(defaults, key))) {
      throw new Error('Invalid canvas value display facts');
    }
    return input;
  }
  // This new role promises in-flow content: geometry cannot account for
  // positioned descendants, negative margins or a fixed height hiding content.
  // Keep the older canvas role's accepted style contract unchanged.
  const flowProperties = new Set(['display', 'flex-direction', 'align-items', 'justify-content',
    'gap', 'padding', 'padding-inline', 'padding-block', 'padding-left',
    'margin', 'margin-inline', 'margin-block', 'margin-top', 'margin-bottom', 'margin-left',
    'width', 'min-width', 'max-width', 'color', 'background-color',
    'border', 'border-color', 'border-width', 'border-style', 'border-radius',
    'font-size', 'font-weight', 'font-family', 'font-style', 'line-height',
    'letter-spacing', 'text-transform', 'text-align', 'white-space', 'overflow-wrap', 'opacity']);
  const lengths = /^(?:0|(?:\d+(?:\.\d+)?|\.\d+)(?:px|rem|em))(?:\s+(?:0|(?:\d+(?:\.\d+)?|\.\d+)(?:px|rem|em))){0,3}$/;
  function checkStyles(styles) {
    for (const rule of styles) for (const [property, value] of rule.declarations) {
      if (!flowProperties.has(property)
        || (property === 'display' && !['inline', 'block', 'inline-flex', 'flex', 'contents', 'none'].includes(value))
        || (/^(?:margin|padding|gap)/.test(property) && !lengths.test(value))
        || (['width', 'min-width', 'max-width'].includes(property)
          && !['auto', 'min-content', 'max-content', 'fit-content', 'none'].includes(value) && !lengths.test(value))
        || (property === 'line-height' && value !== 'normal' && !(Number(value) >= 1 && Number.isFinite(Number(value))))
        || (property === 'letter-spacing' && value !== 'normal' && !lengths.test(value))) {
        throw new Error('Canvas value styles must stay in flow');
      }
    }
  }
  window.gdCanvasValuesGraph = window.gdCreatePassiveCanvas({
    identity: 'canvas-values', prefix: 'gd-canvas-values', classes: new Set(),
    plan: () => window.GraphdenBuiltinPlans.plans.canvasValues,
    projection, checkStyles,
    failureText: input => input.surface === 'type-history'
      ? input.history.length + ' type history entries — presentation unavailable'
      : 'Value presentation unavailable',
  });
})();
