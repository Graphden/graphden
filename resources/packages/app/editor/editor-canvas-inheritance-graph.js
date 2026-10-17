// Pure presentation transitions over native BFS ordinals. The host traverses
// and materializes declared groups; graph code decides every transition.
(() => {
  const seed = () => ({group: -1, root: true, mi: -1, 'previous-mi': false});
  const levelSeed = {position: 0, depth: 0, mi: false, clickable: false, 'duplicate-next': false};
  const defaults = {surface: 'groups', level: levelSeed, prior: seed(), 'use-site': false,
    root: false, cell: false, clickable: false, within: false, 'partial-next': false, expanded: false};
  const api = () => window.GraphdenBrowser;
  function fields(value, keys) {
    if (!(value instanceof Map) || value.size !== keys.length
      || keys.some(key => !value.has(api().keyword(key)))) throw new Error('Invalid inheritance receipt');
    return Object.fromEntries(keys.map(key => [key, value.get(api().keyword(key))]));
  }
  function groupReceipt(output, level, prior, useSite) {
    const result = fields(output, ['row', 'state']);
    const row = fields(result.row, ['position', 'depth', 'visible', 'group', 'root', 'follows-mi']);
    const state = fields(result.state, ['group', 'root', 'mi', 'previous-mi']);
    const ints = [row.position, row.depth, row.group, row['follows-mi'], state.group, state.mi];
    if (ints.some(value => !Number.isSafeInteger(value))
      || [row.visible, row.root, state.root, state['previous-mi']].some(value => typeof value !== 'boolean')
      || row.position !== level.position || row.depth !== level.depth
      || row.visible !== !level['duplicate-next']) throw new Error('Invalid inheritance ownership');
    // These are receipt constraints on the original topology, not a fallback
    // implementation. No personal result can erase a native control or give
    // an expansion a manufactured group endpoint / MI column owner.
    const first = prior.group === -1;
    const boundary = first || level.clickable || level.mi || prior['previous-mi'];
    const rootBreak = (useSite || !first) && level.clickable;
    const legalGroup = boundary ? row.group === prior.group + 1
      : row.group === prior.group || row.group === prior.group + 1;
    const expectedRoot = prior.root && !rootBreak;
    const expectedMi = level.mi ? level.position : level.clickable ? -1 : prior.mi;
    const expectedFollow = level.mi || level.clickable ? -1 : prior.mi;
    if (!legalGroup || row.root !== expectedRoot || row['follows-mi'] !== expectedFollow
      || state.group !== (row.visible ? row.group : prior.group)
      || state.root !== (row.visible ? row.root : prior.root)
      || state.mi !== (row.visible ? expectedMi : prior.mi)
      || state['previous-mi'] !== (row.visible ? level.mi : prior['previous-mi'])) {
      throw new Error('Invalid inheritance transition');
    }
    return {row, state};
  }
  const bindings = new Map();
  function repaint() {
    for (const [owner, update] of bindings) {
      if (!owner.isConnected) { bindings.delete(owner); continue; }
      update();
    }
  }
  const component = window.gdCreatePassiveCanvas({
    operationLimit: 2048, identity: 'canvas-inheritance', prefix: 'gd-canvas-inheritance', classes: new Set(),
    plan: () => window.GraphdenBuiltinPlans.plans.canvasInheritance,
    projection(surface, facts) {
      if (surface !== 'label' || typeof facts.label !== 'string' || Object.keys(facts).length !== 1) {
        throw new Error('Invalid inheritance label');
      }
      return facts;
    },
    checkStyles: window.gdCheckCanvasFlowStyles,
    failureText: () => 'Inheritance presentation unavailable', onRuntimeChanged: repaint,
  });
  function groups(levels, useSite) {
    // Re-running this entire closure starts with the original seed. A refused
    // later personal step can never retain an earlier personal group's state.
    return component.initialBatch(run => {
      let prior = seed();
      const receipts = levels.map(level => {
        const result = run({...defaults, surface: 'groups', level, prior, 'use-site': useSite},
          output => groupReceipt(output, level, prior, useSite));
        prior = result.state;
        return result.row;
      });
      return receipts;
    });
  }
  const tones = {root: ['var(--card-header-bg)', 'var(--card-header-fg)'],
    highlight: ['var(--card-row-highlight)', ''], body: ['var(--card-bg)', '']};
  function paintBatch(facts) {
    return component.initialBatch(run => facts.map(fact => run({...defaults, surface: 'paint', ...fact}, output => {
      const value = fields(output, ['tone', 'bold']);
      if (!Object.hasOwn(tones, value.tone) || typeof value.bold !== 'boolean') {
        throw new Error('Invalid inheritance paint');
      }
      return {bg: tones[value.tone][0], fg: tones[value.tone][1], bold: value.bold};
    })));
  }
  window.addEventListener('gd-auth-changed', () => bindings.clear());
  window.gdCanvasInheritanceGraph = {...component, groups, paintBatch,
    bind(owner, update) { bindings.set(owner, update); },
    dispose(owner) { bindings.delete(owner); component.dispose(owner); },
  };
})();
