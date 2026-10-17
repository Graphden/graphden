// Type grammar traversal and source identity stay native. Only immutable,
// authorized display facts and prior display receipts enter the graph.
(() => {
  const bindings = new Map();
  let objectCache = new WeakMap();
  let scalarCache = new Map();
  let cachedRevision = null;
  const empty = () => ({kind: 'empty', name: '', flat: null, variable: false,
    'base-numeric': false, operator: '', zero: false, atom: '',
    'effect-mode': 'any', effects: [], 'ret-name': null});
  const atom = value => JSON.stringify(value) ?? '';
  function shape(value, flat, constraint) {
    const facts = empty();
    const children = [];
    facts.flat = typeof flat === 'string' ? flat : null;
    const child = (raw, label = '', childFlat = 'any', isConstraint = false) =>
      children.push({raw, label, flat: childFlat, constraint: isConstraint});
    if (constraint) {
      facts.kind = 'constraint-raw';
      facts.atom = atom(value);
      if (Array.isArray(value)) {
        facts.operator = String(value[0]);
        if (value[0] === 'and' || value[0] === 'or') {
          facts.kind = 'constraint-' + value[0];
          for (const item of value.slice(1)) child(item, '', null, true);
          facts.atom = '';
        } else if (value[0] === 'in' && Array.isArray(value[1])) {
          facts.kind = 'constraint-in';
          for (const item of value[1]) child(String(item), '', null, 'member');
          facts.atom = '';
        } else if (value.length === 2) {
          facts.kind = 'constraint-binary';
          facts.atom = atom(value[1]);
        }
      } else facts.atom = constraint === 'member' ? String(value) : atom(value);
    } else if (value == null) {
      facts.kind = 'empty';
    } else if (typeof value === 'string') {
      facts.kind = 'name';
      facts.name = value;
      facts.variable = /^[a-z](-\d+)?$/.test(value) && value !== flat;
    } else if (Array.isArray(value)) {
      const head = value[0];
      facts.name = typeof head === 'string' ? head : '';
      if (['refine', 'list', 'map', 'tuple', 'union', 'fn'].includes(head)) {
        facts.kind = head;
        if (head === 'refine') {
          child(value[1], '', flat);
          child(value[2], '', null, true);
          facts['base-numeric'] = value[1] === 'int' || value[1] === 'numeric';
          facts.operator = Array.isArray(value[2]) ? String(value[2][0]) : '';
          facts.zero = Array.isArray(value[2]) && value[2][1] === 0;
        } else if (head === 'fn') {
          for (const [label, raw] of Object.entries(value[1] || {})) child(raw, label);
          child(value[2]);
          facts['ret-name'] = typeof value[2] === 'string' ? value[2] : null;
          if (Array.isArray(value[3])) {
            facts['effect-mode'] = value[3].length ? 'limited' : 'pure';
            facts.effects = value[3].map((name, index) => ({name: String(name), first: index === 0}));
          }
        } else {
          const items = head === 'list' ? [value[1]] : head === 'map' ? [value[1], value[2]] : value.slice(1);
          for (const raw of items) child(raw);
        }
      } else if (value.length === 2 && typeof head === 'string') {
        facts.kind = 'marker';
        child(value[1]);
      } else {
        facts.kind = 'raw-array';
        facts.atom = atom(value);
      }
    } else if (typeof value === 'object') {
      facts.kind = 'record';
      for (const [label, raw] of Object.entries(value)) child(raw, label);
    } else {
      facts.kind = 'raw';
      facts.atom = String(value);
    }
    return {facts, children};
  }
  function snapshot(raw, flat, constraint) {
    const nodes = [];
    const ancestors = new Set();
    const stack = [{raw, flat, constraint, label: ''}];
    while (stack.length) {
      const frame = stack[stack.length - 1];
      if (!frame.shape) {
        if (frame.raw && typeof frame.raw === 'object') {
          if (ancestors.has(frame.raw)) throw new Error('Cyclic native type grammar');
          ancestors.add(frame.raw);
        }
        frame.shape = shape(frame.raw, frame.flat, frame.constraint);
        frame.next = 0;
        frame.receipts = [];
      }
      if (frame.next < frame.shape.children.length) {
        stack.push(frame.shape.children[frame.next++]);
        continue;
      }
      const index = nodes.length;
      const facts = frame.shape.facts;
      for (const effect of facts.effects) Object.freeze(effect);
      Object.freeze(facts.effects);
      nodes.push(Object.freeze({facts: Object.freeze(facts), children: Object.freeze(frame.receipts)}));
      stack.pop();
      if (frame.raw && typeof frame.raw === 'object') ancestors.delete(frame.raw);
      if (stack.length) stack[stack.length - 1].receipts.push(Object.freeze({index, label: frame.label}));
    }
    return Object.freeze(nodes);
  }
  function receipt(value) {
    const api = window.GraphdenBrowser;
    const keys = ['human', 'compact', 'constraint', 'short'];
    if (!(value instanceof Map) || value.size !== keys.length
      || keys.some(key => !value.has(api.keyword(key)))) throw new Error('Invalid type display receipt');
    const result = Object.fromEntries(keys.map(key => [key, value.get(api.keyword(key))]));
    if (typeof result.human !== 'string' || typeof result.constraint !== 'string' || typeof result.short !== 'string'
      || (result.compact !== null && typeof result.compact !== 'string')) throw new Error('Invalid type display text');
    return Object.freeze(result);
  }
  function refreshBindings() {
    objectCache = new WeakMap();
    scalarCache = new Map();
    for (const [owner, callbacks] of bindings) {
      if (!owner.isConnected) bindings.delete(owner);
      else for (const refresh of [...callbacks]) refresh();
    }
  }
  function currentCaches() {
    const revision = component.revision();
    if (cachedRevision !== revision) {
      objectCache = new WeakMap(); scalarCache = new Map(); cachedRevision = revision;
    }
  }
  const component = window.gdCreatePassiveCanvas({identity: 'canvas-type-structure',
    prefix: 'gd-canvas-type-structure', classes: new Set(), operationLimit: 150000,
    initialBatchOperationLimit: 8 * 1024 * 1024,
    initialBatchMilliseconds: 50,
    plan: () => window.GraphdenBuiltinPlans.plans.canvasTypeStructure,
    projection(surface, facts) {
      if (!['label', 'header', 'kind', 'description', 'field', 'chip', 'constraint', 'chain', 'chain-separator',
        'resolution-head', 'resolution-label', 'resolution-mark', 'resolution-tag', 'effects'].includes(surface)
        || typeof facts.label !== 'string' || typeof (facts.title ?? '') !== 'string'
        || ['winner', 'multiple'].some(key => facts[key] !== undefined && typeof facts[key] !== 'boolean')
        || !['', 'chain', 'tier', 'inherited', 'resolved', 'subtype'].includes(facts.mode ?? '')
        || Object.keys(facts).some(key => !['label', 'title', 'winner', 'multiple', 'mode'].includes(key))) {
        throw new Error('Invalid type surface');
      }
      return {surface, label: facts.label, title: facts.title || '', secondary: '',
        winner: !!facts.winner, multiple: !!facts.multiple, mode: facts.mode || ''};
    },
    checkStyles: styles => window.gdCheckCanvasFlowStyles(styles),
    failureText: () => 'Type presentation unavailable', onRuntimeChanged: refreshBindings,
    geometry() { if (typeof repositionAllInlineHosts === 'function') repositionAllInlineHosts(); },
  });
  function format(raw, flat, constraint = false) {
    currentCaches();
    const object = raw !== null && typeof raw === 'object';
    let cache = object ? objectCache : scalarCache;
    let byFlat = cache.get(raw);
    const key = JSON.stringify([constraint, typeof flat === 'string' ? flat : null]);
    if (byFlat?.has(key)) return byFlat.get(key);
    // Capture the complete native projection BEFORE selecting/running a plan.
    const nodes = snapshot(raw, flat, constraint);
    const result = component.initialBatch((run, policy) => {
      const staged = [];
      let characters = 0;
      for (const node of nodes) {
        const children = node.children.map(({index, label}, position) => ({label,
          first: position === 0, last: position === node.children.length - 1, ...staged[index]}));
        const input = {...node.facts, children};
        const pending = [input];
        while (pending.length) {
          const value = pending.pop();
          if (typeof value === 'string') characters += value.length;
          else if (value && typeof value === 'object') pending.push(...Object.values(value));
        }
        // UTF-16 code units, including repeated parent/child text copies;
        // separate from the runtime's shared aggregate operation counter.
        if (policy.personal && characters > 8 * 1024 * 1024) {
          throw new Error('Personal type display batch budget exceeded');
        }
        const output = run(input, receipt);
        characters += output.human.length + (output.compact?.length || 0) + output.constraint.length + output.short.length;
        if (policy.personal && characters > 8 * 1024 * 1024) throw new Error('Personal type output budget exceeded');
        staged.push(output);
      }
      return staged[staged.length - 1];
    });
    // A rejection may have invalidated all caches synchronously. Commit only
    // the complete result, never a staged personal child or canonical value.
    currentCaches();
    cache = object ? objectCache : scalarCache;
    byFlat = cache.get(raw);
    if (!byFlat) { byFlat = new Map(); cache.set(raw, byFlat); }
    byFlat.set(key, result);
    return result;
  }
  window.gdCanvasTypeStructureGraph = {...component, format,
    constraint(value) { return format(value, null, true).constraint; },
    bind(owner, refresh) {
      let callbacks = bindings.get(owner);
      if (!callbacks) { callbacks = new Set(); bindings.set(owner, callbacks); }
      callbacks.add(refresh);
      return () => {
        callbacks.delete(refresh);
        if (!callbacks.size) bindings.delete(owner);
      };
    },
    dispose(owner) { bindings.delete(owner); component.dispose(owner); },
    disposeWithin(root) {
      for (const owner of bindings.keys()) if (owner === root || root.contains(owner)) bindings.delete(owner);
      component.disposeWithin(root);
    },
  };
  window.addEventListener('gd-auth-changed', () => {
    objectCache = new WeakMap(); scalarCache = new Map(); bindings.clear();
  });
  window.gdMountTypeStructureBody = (owner, element, surface, facts, options = {}) => {
    const frame = document.createElement('span');
    frame.className = 'canvas-type-structure-body-frame';
    const host = document.createElement('span');
    host.className = 'gd-canvas-body-host';
    frame.appendChild(host);
    element.appendChild(frame);
    return component.mount(owner, host, surface, facts, options);
  };
  window.gdAppendTypeControlMark = (element, label) => {
    element.classList.add('canvas-type-structure-control');
    const mark = document.createElement('span');
    mark.className = 'canvas-type-structure-control-mark';
    mark.setAttribute('aria-hidden', 'true');
    mark.textContent = label;
    element.appendChild(mark);
    return mark;
  };
})();
