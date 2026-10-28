// Graph-owned namespace choice body. The host owns candidate identity, focus,
// popup placement and the eventual namespace mutation.
(() => {
  const browser = window.GraphdenBrowser;
  const key = browser.keyword;
  const record = object => new Map(Object.entries(object).map(([name, value]) => [key(name), value]));

  function renderTree(runtime, input) {
    const result = runtime.run('view', {inputs: input});
    if (!(result instanceof Map) || result.size !== 1 || !result.has(key('tree'))) {
      throw new Error('Invalid authoring graph view');
    }
    const vnode = window.GraphdenRenderer.vnode(result.get(key('tree')), 'gd-authoring', 500);
    const scratch = document.createElement('div');
    window.GraphdenPreact.render(vnode, scratch);
    const tree = scratch.firstElementChild || scratch.firstChild;
    if (scratch.children.length !== 1) throw new Error('Invalid authoring graph root');
    return tree;
  }

  function renderNamespace(runtime, candidates, query, activeKey) {
    const input = record({surface: 'namespace-picker', query, 'active-key': activeKey,
      namespaces: candidates.map(candidate => record({key: candidate.key, path: candidate.path})),
      kind: '', editing: false, namespace: '', name: '', description: '',
      placeholder: '', 'initial-value': ''});
    const tree = renderTree(runtime, input);
    if (tree?.className !== 'ns-picker-graph-body') {
      throw new Error('Invalid authoring choice root');
    }
    const allowed = new Set(candidates.map(candidate => candidate.key));
    const rows = [...tree.children];
    if (rows.length === 1 && rows[0].className === 'fn-picker-empty'
        && rows[0].textContent === 'No matches') return tree;
    const seen = new Set();
    if (rows.length > 80 || rows.some(row => {
      const choiceKey = row.dataset.nsKey;
      if (row.tagName !== 'DIV' || row.getAttribute('role') !== 'option'
          || !allowed.has(choiceKey) || seen.has(choiceKey)
          || row.id !== 'gd-authoring-ns-' + choiceKey
          || !['true', 'false'].includes(row.getAttribute('aria-selected'))
          || row.querySelectorAll('input, button, select, textarea').length) return true;
      seen.add(choiceKey);
      return false;
    })) throw new Error('Invalid authoring namespace rows');
    if (rows.filter(row => row.getAttribute('aria-selected') === 'true').length > 1) {
      throw new Error('Invalid authoring selection');
    }
    return tree;
  }

  function renderTypeShell(runtime, kind, editing, namespace, name, description) {
    const input = record({surface: 'type-shell', query: '', 'active-key': '', namespaces: [],
      kind, editing, namespace, name, description, placeholder: '', 'initial-value': ''});
    const tree = renderTree(runtime, input);
    const form = tree?.querySelector('.type-create-form');
    const expectedKinds = ['refinement', 'record', 'union', 'variant', 'list'];
    const tabs = [...(form?.querySelectorAll('.type-create-tab') || [])];
    if (tree?.className !== 'type-create-shell' || form?.tagName !== 'FORM'
        || tree.querySelectorAll('.type-create-head').length !== 1
        || tree.querySelectorAll('.type-create-close').length !== 1
        || form.querySelectorAll('input.type-create-input').length !== 1
        || form.querySelectorAll('textarea.type-create-textarea').length !== 1
        || form.querySelectorAll('.type-create-extra-host').length !== 1
        || form.querySelectorAll('.type-create-error').length !== 1
        || form.querySelectorAll('.type-create-back').length !== 1
        || form.querySelectorAll('.type-create-submit').length !== 1
        || tabs.length !== (editing ? 0 : 5)
        || tabs.some((tab, i) => tab.dataset.kind !== expectedKinds[i]
          || tab.getAttribute('type') !== 'button'
          || tab.getAttribute('role') !== 'tab'
          || tab.getAttribute('aria-selected') !== (tab.dataset.kind === kind ? 'true' : 'false'))
        || tree.querySelectorAll('button, input, textarea').length !== (editing ? 5 : 10)) {
      throw new Error('Invalid authoring type shell');
    }
    return tree;
  }

  function renderInlineRow(runtime, placeholder, initialValue) {
    const input = record({surface: 'inline-row', query: '', 'active-key': '', namespaces: [],
      kind: '', editing: false, namespace: '', name: '', description: '',
      placeholder, 'initial-value': initialValue});
    const tree = renderTree(runtime, input);
    const parts = [...(tree?.children || [])];
    if (tree?.className !== 'inline-input-row' || parts.length !== 4
        || parts[0].tagName !== 'INPUT' || parts[0].className !== 'inline-input'
        || parts[0].getAttribute('type') !== 'text'
        || parts[1].tagName !== 'BUTTON' || parts[1].className !== 'inline-btn inline-btn-save'
        || parts[1].getAttribute('type') !== 'button'
        || parts[2].tagName !== 'BUTTON' || parts[2].className !== 'inline-btn inline-btn-cancel'
        || parts[2].getAttribute('type') !== 'button'
        || parts[3].tagName !== 'SPAN' || parts[3].className !== 'inline-error') {
      throw new Error('Invalid authoring inline row');
    }
    return tree;
  }

  function renderCreateMenu(runtime) {
    const input = record({surface: 'create-menu', query: '', 'active-key': '', namespaces: [],
      kind: '', editing: false, namespace: '', name: '', description: '',
      placeholder: '', 'initial-value': ''});
    const tree = renderTree(runtime, input);
    const types = ['ns', 'fn', 'type'];
    const items = [...(tree?.children || [])];
    if (tree?.className !== 'create-menu' || tree.getAttribute('role') !== 'menu'
        || items.length !== types.length || items.some((item, i) =>
          item.tagName !== 'BUTTON' || item.className !== 'create-menu-item'
          || item.dataset.type !== types[i] || item.getAttribute('role') !== 'menuitem'
          || item.getAttribute('type') !== 'button')) {
      throw new Error('Invalid authoring create menu');
    }
    return tree;
  }

  function renderNamespaceChooser(runtime, candidates) {
    const input = record({surface: 'namespace-chooser', query: '', 'active-key': '',
      namespaces: candidates.map(candidate => record({key: candidate.key, path: candidate.path})),
      kind: '', editing: false, namespace: '', name: '', description: '',
      placeholder: '', 'initial-value': ''});
    const tree = renderTree(runtime, input);
    const parts = [...(tree?.children || [])];
    const select = parts[1];
    const options = [...(select?.children || [])];
    if (tree?.tagName !== 'LABEL' || tree.className !== 'extend-ns-row'
        || parts.length !== 4 || parts[0].className !== 'extend-ns-cap'
        || select?.tagName !== 'SELECT' || select.className !== 'extend-ns-select'
        || parts[2].className !== 'extend-ns-btn extend-ns-up'
        || parts[3].className !== 'extend-ns-btn extend-ns-plus'
        || options.length !== candidates.length + 1
        || options[0].getAttribute('value') !== ''
        || options.some((option, i) => i > 0
          && (option.tagName !== 'OPTION' || option.getAttribute('value') !== candidates[i - 1].key
            || option.textContent !== candidates[i - 1].path))) {
      throw new Error('Invalid authoring namespace chooser');
    }
    return tree;
  }

  function selectedOrBuiltin(render) {
    const builtin = browser.createRuntime(window.GraphdenBuiltinPlans.plans.authoring, {operationLimit: 30000});
    let selected = null;
    try { selected = window.gdEditorRoot?.componentNow('authoring') || null; }
    catch (_) { window.gdUIComponentFailed?.('authoring'); }
    if (selected) {
      try {
        const tree = render(selected);
        if (!window.gdEditorRoot.acceptComponent(selected)) throw new Error('Editor root changed');
        return tree;
      } catch (_) {
        window.gdEditorRoot?.releaseComponent(selected);
        window.gdUIComponentFailed?.('authoring');
      }
    }
    return render(builtin);
  }

  window.gdAuthoringNamespaceRows = (candidates, query, activeKey) =>
    selectedOrBuiltin(runtime => renderNamespace(runtime, candidates, query, activeKey));
  window.gdAuthoringTypeShell = (kind, editing, namespace, name, description) =>
    selectedOrBuiltin(runtime => renderTypeShell(runtime, kind, editing, namespace, name, description));
  window.gdAuthoringInlineRow = (placeholder, initialValue) =>
    selectedOrBuiltin(runtime => renderInlineRow(runtime, placeholder, initialValue));
  window.gdAuthoringCreateMenu = () =>
    selectedOrBuiltin(runtime => renderCreateMenu(runtime));
  window.gdAuthoringNamespaceChooser = candidates =>
    selectedOrBuiltin(runtime => renderNamespaceChooser(runtime, candidates));
})();
