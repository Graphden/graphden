// Execution status is a pure root-HOF view. The editor still owns requests,
// polling, cancellation and the lifetime of each transient result host.
(() => {
  const api = window.GraphdenBrowser;
  const keyword = api.keyword;
  const field = (record, name) => record instanceof Map ? record.get(keyword(name)) : undefined;
  const passiveTags = new Set(['div', 'span', 'pre', 'strong', 'small', 'em']);
  const builtin = api.createRuntime(window.GraphdenBuiltinPlans.plans.executionStatus,
    {operationLimit: 10000});
  let selected = null;
  let rejected = false;

  function passive(node) {
    if (node == null || typeof node !== 'object') return;
    if (!passiveTags.has(node.type) || Object.keys(node.props).some(name =>
      name.startsWith('on') || name === 'data-action')) {
      throw new Error('Execution status graph must be passive');
    }
    for (const child of [node.props.children].flat()) passive(child);
  }

  function view(runtime, inputs) {
    const result = runtime.run('view', {inputs});
    if (!(result instanceof Map) || result.size !== 1 || !result.has(keyword('tree'))) {
      throw new Error('Invalid execution status view');
    }
    const node = window.GraphdenRenderer.vnode(field(result, 'tree'), null, 32);
    passive(node);
    const host = document.createElement('div');
    window.GraphdenPreact.render(node, host);
    if (host.childNodes.length !== 1) throw new Error('Invalid execution status tree');
    return host.firstElementChild;
  }

  function input(kind, message, executionId, errorData) {
    return new Map([
      [keyword('kind'), kind], [keyword('message'), message],
      [keyword('execution-id'), executionId], [keyword('error-data'), errorData],
    ]);
  }

  window.gdExecutionStatusNode = (kind, message = '', executionId = '', errorData = '') => {
    const inputs = input(kind, message, executionId, errorData);
    if (!selected && !rejected) {
      try { selected = window.gdEditorRoot?.componentNow('execution-status') || null; }
      catch (_) { rejected = true; window.gdUIComponentFailed?.('execution-status'); }
    }
    if (selected) {
      try {
        const node = view(selected, inputs);
        if (!window.gdEditorRoot.acceptComponent(selected)) throw new Error('Editor root changed');
        return node;
      } catch (_) {
        window.gdEditorRoot?.releaseComponent(selected);
        selected = null;
        rejected = true;
        window.gdUIComponentFailed?.('execution-status');
      }
    }
    return view(builtin, inputs);
  };
  window.addEventListener('gd-editor-root-changing', () => { selected = null; rejected = false; });
})();
