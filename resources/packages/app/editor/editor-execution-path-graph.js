// The execution-path graph owns per-function timing and badge text. The host
// supplies only authorized, value-free metrics; canvas geometry and captured
// value popovers stay local to the editor.
(() => {
  const api = window.GraphdenBrowser;
  const keyword = api.keyword;
  const field = (record, name) => record instanceof Map ? record.get(keyword(name)) : undefined;
  const builtin = api.createRuntime(window.GraphdenBuiltinPlans.plans.executionPath,
    {operationLimit: 3000000, valueNodeLimit: 120000});
  let selected = null;
  let rejected = false;

  function safeInput(entries) {
    const identities = new Set();
    const secrets = new Set();
    const rows = [];
    for (const item of entries) {
      const id = item?.['fn-id'];
      if (typeof id !== 'string' || !id || id.length > 256) continue;
      identities.add(id);
      const hidden = Object.hasOwn(item, 'hidden');
      if (hidden) secrets.add(id);
      const duration = item['duration-ms'];
      rows.push(new Map([
        [keyword('fn-id'), id],
        [keyword('cache-hit'), !!item['cache-hit?']],
        [keyword('hidden'), hidden],
        [keyword('duration-ms'), Number.isSafeInteger(duration) && duration >= 0 ? duration : 0],
      ]));
    }
    return {rows, identities, secrets};
  }

  function checkedRows(runtime, source) {
    const inputs = new Map([[keyword('entries'), source.rows]]);
    const result = runtime.run('view', {inputs});
    if (!(result instanceof Map) || result.size !== 2 || !result.has(keyword('panel-tree'))) {
      throw new Error('Invalid execution path view');
    }
    const rows = field(result, 'rows');
    if (!Array.isArray(rows) && !api.isSequence(rows)) throw new Error('Invalid execution path rows');
    const byFn = new Map();
    for (const row of rows) {
      if (!(row instanceof Map) || row.size !== 9) throw new Error('Invalid execution path row');
      const id = field(row, 'fn-id');
      const hidden = field(row, 'hidden');
      const badge = field(row, 'badge');
      const title = field(row, 'title');
      if (!source.identities.has(id) || byFn.has(id) || typeof hidden !== 'boolean'
        || (source.secrets.has(id) && !hidden) || typeof badge !== 'string' || badge.length > 128
        || typeof title !== 'string' || title.length > 1024) throw new Error('Invalid execution path identity');
      const numbers = ['count', 'fresh', 'hits', 'total-ms', 'max-ms'].map(name => field(row, name));
      if (numbers.some(value => !Number.isSafeInteger(value) || value < 0 || value > 1000000000)
        || numbers[0] < 1 || numbers[0] > source.rows.length) throw new Error('Invalid execution path metrics');
      byFn.set(id, {
        count: numbers[0], fresh: numbers[1], hits: numbers[2],
        totalMs: numbers[3], maxMs: numbers[4], hidden, badge, title,
      });
    }
    if (byFn.size !== source.identities.size) throw new Error('Incomplete execution path view');
    return byFn;
  }

  function selectedOrBuiltin(render) {
    if (!selected && !rejected) {
      try { selected = window.gdEditorRoot?.componentNow('execution-path') || null; }
      catch (_) { rejected = true; window.gdUIComponentFailed?.('execution-path'); }
    }
    if (selected) {
      try {
        const result = render(selected);
        if (!window.gdEditorRoot.acceptComponent(selected)) throw new Error('Editor root changed');
        return result;
      } catch (_) {
        window.gdEditorRoot?.releaseComponent(selected);
        selected = null;
        rejected = true;
        window.gdUIComponentFailed?.('execution-path');
      }
    }
    return render(builtin);
  }

  window.gdExecutionPathMetrics = entries => {
    const source = safeInput(entries);
    if (!source.rows.length) return new Map();
    return selectedOrBuiltin(runtime => checkedRows(runtime, source));
  };

  function panel(runtime, highlightedCount, offCanvasCount, offCanvasTitle, truncated, valuesDropped) {
    const inputs = new Map([
      [keyword('entries'), []],
      [keyword('highlighted-count'), highlightedCount],
      [keyword('off-canvas-count'), offCanvasCount],
      [keyword('off-canvas-title'), offCanvasTitle.slice(0, 4096)],
      [keyword('path-truncated'), truncated],
      [keyword('values-dropped'), valuesDropped],
    ]);
    const result = runtime.run('view', {inputs});
    if (!(result instanceof Map) || result.size !== 2 || !result.has(keyword('panel-tree'))) {
      throw new Error('Invalid execution path panel');
    }
    const vnode = window.GraphdenRenderer.vnode(field(result, 'panel-tree'), null, 16);
    const host = document.createElement('div');
    window.GraphdenPreact.render(vnode, host);
    const node = host.firstElementChild;
    if (host.childElementCount !== 1 || !node.matches('.path-view-panel[role="status"]')
      || node.querySelectorAll('button').length !== 1
      || !node.querySelector('button.path-view-clear[aria-label="Clear the execution-path highlight"]')
      || node.querySelectorAll('input, select, textarea').length) {
      throw new Error('Invalid execution path panel controls');
    }
    return node;
  }

  window.gdExecutionPathPanelNode = (highlightedCount, offCanvasCount, offCanvasTitle,
    truncated, valuesDropped) => selectedOrBuiltin(runtime => panel(runtime, highlightedCount,
    offCanvasCount, offCanvasTitle, truncated, valuesDropped));
  window.addEventListener('gd-editor-root-changing', () => { selected = null; rejected = false; });
})();
