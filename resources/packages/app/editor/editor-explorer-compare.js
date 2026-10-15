// Compare-mode Explorer presentation and exact lens receipts. The shared
// Explorer adapter supplies runtime, authority stamp and generic safe DOM
// helpers; this module owns only compare body/style lifetime.
(() => {
  const create = ({api, key, field, record, exact, children, validateState, filterInputs, filterDefaults,
    context, refresh, ready, getOwner, getBuiltinRuntime}) => {
    const diffLenses = ['changedOnly', 'added', 'modified', 'missing', 'inside',
      'substantiveOnly', 'notes', 'effectsOnly'];
    let builtinRuntime = null;
    let diffRuntime = null;
    let diffSheet = null;
    const diffStyles = {item: null, lens: null};
    const diffStyleSignatures = {item: null, lens: null};
    let diffFallback = false;
    const diffItems = new Set();
    const diffByHost = new WeakMap();
    let diffLens = null;

  function diffInputs(surface, selected) {
    const value = filterInputs(surface, filterDefaults);
    value.set(key('selected'), new Map(selected));
    return value;
  }
  function diffFacts(facts) {
    if (!facts || ![null, 'added', 'modified', 'missing', 'inside'].includes(facts.kind)
      || typeof facts.title !== 'string' || !(facts.summary === null || typeof facts.summary === 'string')
      || typeof facts.branch !== 'string' || !facts.counts || typeof facts.counts !== 'object'
      || Object.keys(facts.counts).some(name => !['added', 'modified', 'missing', 'inside'].includes(name)
        || !Number.isSafeInteger(facts.counts[name]) || facts.counts[name] < 0)) {
      throw new Error('Invalid Explorer diff facts');
    }
    return record({kind: facts.kind, title: facts.title, summary: facts.summary,
      branch: facts.branch, counts: new Map(Object.entries(facts.counts))});
  }
  function validateDiffItem(view) {
    exact(view, ['tree', 'styles', 'actions']);
    const actions = field(view, 'actions');
    exact(actions, ['secret', 'dismiss', 'run']);
    if (['secret', 'dismiss', 'run'].some(name => field(actions, name) !== false)) {
      throw new Error('Explorer diff body cannot request actions');
    }
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-explorer-diff', 48);
    const passive = new Set(['div', 'span', 'strong', 'small']);
    let textLength = 0;
    function inspect(node) {
      if (typeof node === 'string' || typeof node === 'number') {
        textLength += String(node).length;
        return;
      }
      if (!node || !passive.has(node.type)
        || Object.keys(node.props).some(name => !['className', 'title', 'hidden', 'aria-hidden', 'children', 'key'].includes(name))) {
        throw new Error('Invalid Explorer diff body');
      }
      if (/(^|\s)(?:gd-diff-graph-host|ns-header|ns-children|entity-item|gd-diff-ghost|gd-diff-lens|kind-toggle|ns-row-actions)(\s|$)/.test(node.props.className || '')) {
        throw new Error('Explorer diff cannot forge native controls');
      }
      for (const child of children(node)) inspect(child);
    }
    inspect(tree);
    if (textLength > 8192) throw new Error('Explorer diff body too large');
    const rules = window.GraphdenStyles.normalize(field(view, 'styles'));
    if (rules.some(rule => !rule.selector.startsWith('&'))) {
      throw new Error('Explorer diff style must stay inside its body');
    }
    return rules;
  }
  function validateDiffLens(view, selected) {
    exact(view, ['tree', 'styles', 'actions']);
    const actions = field(view, 'actions');
    exact(actions, ['secret', 'dismiss', 'run']);
    if (['secret', 'dismiss', 'run'].some(name => field(actions, name) !== false)) {
      throw new Error('Explorer diff lens cannot request toolbar actions');
    }
    const tree = window.GraphdenRenderer.vnode(field(view, 'tree'), 'gd-explorer-diff-lens', 96);
    const buttons = children(tree);
    if (tree.type !== 'div' || !tree.props.className?.split(' ').includes('gd-diff-lens-kinds')
      || Object.keys(tree.props).some(name => !['className', 'children', 'key'].includes(name))
      || buttons.length !== diffLenses.length) throw new Error('Invalid Explorer diff lens');
    buttons.forEach((button, index) => {
      const name = diffLenses[index];
      if (button.type !== 'button' || button.props.type !== 'button'
        || button.props['data-kind'] !== name
        || button.props['aria-pressed'] !== String(selected[name])
        || Object.keys(button.props).some(prop => !['type', 'className', 'data-kind', 'aria-pressed', 'title', 'hidden', 'children', 'key'].includes(prop))
        || !button.props.className?.split(' ').includes('gd-diff-lens-chip')) {
        throw new Error('Invalid Explorer diff lens button');
      }
      for (const part of children(button)) {
        if (part.type !== 'span' || Object.keys(part.props).some(prop => !['className', 'aria-hidden', 'children', 'key'].includes(prop))
          || children(part).some(value => typeof value !== 'string' || value.length > 2048)) {
          throw new Error('Invalid Explorer diff lens label');
        }
      }
    });
    return window.GraphdenStyles.normalize(field(view, 'styles'));
  }
  function updateDiffStyles(surface, normalized) {
    const signature = JSON.stringify(normalized);
    if (diffStyleSignatures[surface] !== null) {
      if (signature !== diffStyleSignatures[surface]) throw new Error('Explorer diff styles vary by item');
      return;
    }
    const next = {...diffStyles, [surface]: normalized};
    const combined = [...(next.item || []), ...(next.lens || [])];
    if (combined.length > 128) throw new Error('Explorer diff style limit exceeded');
    const sheet = window.GraphdenStyles.acquire(combined);
    window.GraphdenStyles.release(diffSheet);
    diffSheet = sheet;
    diffStyles[surface] = normalized;
    diffStyleSignatures[surface] = signature;
    for (const root of [...Array.from(diffItems, instance => instance.host), diffLens?.host]) {
      if (root) root.dataset.gdUiStyle = sheet.id;
    }
  }
  function clearDiffStyles() {
    window.GraphdenStyles.release(diffSheet);
    diffSheet = null;
    for (const surface of ['item', 'lens']) {
      diffStyles[surface] = null;
      diffStyleSignatures[surface] = null;
    }
    for (const root of [...Array.from(diffItems, instance => instance.host), diffLens?.host]) {
      if (root) delete root.dataset.gdUiStyle;
    }
  }
  function fallbackDiff() {
    diffRuntime = builtinRuntime;
    diffFallback = true;
    clearDiffStyles();
    window.gdUIComponentSectionFallback?.('explorer', 'diff');
    for (const instance of diffItems) renderDiffItem(instance);
    if (diffLens) renderDiffLens();
  }
  function renderDiffItem(instance) {
    let view;
    try {
      view = diffRuntime.run('view', {inputs: diffInputs('diff-item', []), diff: diffFacts(instance.facts)});
      updateDiffStyles('item', validateDiffItem(view));
    } catch (error) {
      if (diffRuntime === builtinRuntime || diffFallback) throw error;
      fallbackDiff();
      return;
    }
    instance.renderer.render(field(view, 'tree'));
    if (diffSheet) instance.host.dataset.gdUiStyle = diffSheet.id;
  }
  function renderDiffLens() {
    let view;
    try {
      view = diffRuntime.run('view', {inputs: diffInputs('diff-lens',
        diffLenses.map(name => [name, !!diffLens.selected[name]]))});
      updateDiffStyles('lens', validateDiffLens(view, diffLens.selected));
    } catch (error) {
      if (diffRuntime === builtinRuntime || diffFallback) throw error;
      fallbackDiff();
      return;
    }
    diffLens.renderer.render(field(view, 'tree'));
    if (diffSheet) diffLens.host.dataset.gdUiStyle = diffSheet.id;
  }
  function mountDiffItem(body, facts) {
    if (!ready() || getOwner() !== context()) refresh();
    if (!diffRuntime) { builtinRuntime = getBuiltinRuntime(); diffRuntime = builtinRuntime; }
    const existing = diffByHost.get(body);
    if (existing) {
      existing.facts = facts;
      renderDiffItem(existing);
      return;
    }
    const renderer = window.GraphdenRenderer.mount(body, {idPrefix: 'gd-explorer-diff', nodeLimit: 48});
    const instance = {host: body, facts, renderer};
    diffItems.add(instance);
    diffByHost.set(body, instance);
    try { renderDiffItem(instance); }
    catch (error) { diffItems.delete(instance); diffByHost.delete(body); renderer.dispose(); throw error; }
  }
  function mountDiffLens(body, selected) {
    if (!ready() || getOwner() !== context()) refresh();
    if (!diffRuntime) { builtinRuntime = getBuiltinRuntime(); diffRuntime = builtinRuntime; }
    if (!diffLenses.every(name => typeof selected?.[name] === 'boolean')) {
      throw new Error('Invalid Explorer diff lens facts');
    }
    if (diffLens && diffLens.host !== body) {
      diffLens.renderer.dispose();
      diffLens = null;
    }
    if (!diffLens) {
      diffLens = {host: body, renderer: window.GraphdenRenderer.mount(body,
        {idPrefix: 'gd-explorer-diff-lens', nodeLimit: 96}), selected};
    } else diffLens.selected = selected;
    renderDiffLens();
  }
  function disposeDiff(scope) {
    if (!scope) return;
    for (const instance of diffItems) {
      if (scope === instance.host || scope.contains(instance.host)) {
        instance.renderer.dispose();
        diffByHost.delete(instance.host);
        diffItems.delete(instance);
      }
    }
    if (diffLens && (scope === diffLens.host || scope.contains(diffLens.host))) {
      delete diffLens.host.dataset.gdUiStyle;
      diffLens.renderer.dispose();
      diffLens = null;
    }
    if (!diffItems.size && !diffLens) clearDiffStyles();
  }
  function sweepDiff() {
    for (const instance of diffItems) {
      if (!instance.host.isConnected) disposeDiff(instance.host);
    }
    if (diffLens && !diffLens.host.isConnected) disposeDiff(diffLens.host);
  }
  function selectDiffLens(button, execute) {
    const name = button?.dataset.kind;
    if (!diffLens?.host.contains(button) || !diffLenses.includes(name)
      || getOwner() !== context() || !diffLens.selected || !Object.hasOwn(diffLens.selected, name)) return false;
    try {
      const result = diffRuntime.run('update', {state: record({handled: false}),
        event: record({kind: 'toggle-diff-lens', value: name})});
      exact(result, ['state', 'requests']);
      validateState(field(result, 'state'));
      const requests = field(result, 'requests');
      if (!Array.isArray(requests) && !api.isSequence(requests)) throw new Error('Invalid Explorer diff request');
      const iterator = requests[Symbol.iterator]();
      const first = iterator.next();
      if (first.done || !iterator.next().done) throw new Error('Invalid Explorer diff request count');
      exact(first.value, ['kind', 'value']);
      if (field(first.value, 'kind') !== 'toggle-diff-lens' || field(first.value, 'value') !== name) {
        throw new Error('Explorer diff request differs from selected lens');
      }
    } catch (error) {
      if (diffRuntime === builtinRuntime || diffFallback) throw error;
      fallbackDiff();
      return selectDiffLens(Array.from(diffLens.host.querySelectorAll('button[data-kind]'))
        .find(candidate => candidate.dataset.kind === name), execute);
    }
    if (getOwner() !== context() || !diffLens.host.contains(button)) return false;
    execute(name);
    return true;
  }
  function resetDiffBodies(runtime) {
    builtinRuntime = getBuiltinRuntime();
    diffRuntime = runtime;
    diffFallback = false;
    clearDiffStyles();
    for (const instance of diffItems) renderDiffItem(instance);
    if (diffLens) renderDiffLens();
  }
    return {mountDiffItem, mountDiffLens, selectDiffLens, disposeDiff,
      sweepDiff, resetDiffBodies};
  };
  window.GraphdenExplorerCompare = {create};
})();
