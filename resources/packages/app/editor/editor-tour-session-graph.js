// Pure step and end-card layout. The tour engine owns checks, links, controls,
// focus, cleanup and spotlight geometry; graph output is passive presentation.
(() => {
  const api = window.GraphdenBrowser;
  const keyword = api.keyword;
  const builtin = api.createRuntime(window.GraphdenBuiltinPlans.plans.tutorialCatalog,
    {operationLimit: 10000});
  const fields = ['head', 'title', 'body', 'foot', 'next'];
  const classes = new Set(['gd-tour-head', 'gd-tour-progress', 'gd-tour-title',
    'gd-tour-body', 'gd-tour-foot', 'gd-tour-next', 'gd-tour-next-label', 'gd-tour-hint',
    'gd-tour-hint gd-tour-next-note']);
  const field = (map, name) => map instanceof Map ? map.get(keyword(name)) : undefined;

  function node(tree) {
    if (tree == null) return null;
    const vnode = window.GraphdenRenderer.vnode(tree, 'gd-tour', 80);
    function check(current) {
      if (!current || typeof current !== 'object') return;
      if (!['div', 'span'].includes(current.type)
          || Object.keys(current.props).some(name => !['className', 'id', 'children'].includes(name))
          || !classes.has(current.props.className)
          || (current.props.id && !['gd-tour-title', 'gd-tour-body'].includes(current.props.id))) {
        throw new Error('Tutorial graph must contain passive session layout');
      }
      for (const child of [current.props.children].flat()) check(child);
    }
    check(vnode);
    const host = document.createElement('div');
    window.GraphdenPreact.render(vnode, host);
    if (host.children.length !== 1) throw new Error('Invalid tutorial session tree');
    return host.children[0];
  }

  function view(facts) {
    const selected = window.gdEditorRoot?.componentNow('tutorial-catalog');
    const evaluator = selected?.query ? selected : builtin;
    const inputs = new Map(Object.entries(facts).map(([name, value]) => [keyword(name), value]));
    const output = evaluator.run('query', {inputs});
    if (!(output instanceof Map) || output.size !== fields.length
        || fields.some(name => !output.has(keyword(name)))) {
      throw new Error('Invalid tutorial session view');
    }
    const result = Object.fromEntries(fields.map(name => [name, node(field(output, name))]));
    if (result.title?.className !== 'gd-tour-title' || result.title.id !== 'gd-tour-title'
        || result.body?.className !== 'gd-tour-body' || result.body.id !== 'gd-tour-body'
        || result.foot?.className !== 'gd-tour-foot'
        || (facts.surface === 'step' ? result.head?.className !== 'gd-tour-head' : result.head)
        || (facts['next-label'] ? result.next?.className !== 'gd-tour-next' : result.next)
        || result.title.textContent.length > 512 || result.body.textContent.length > 8192
        || (result.next?.textContent.length || 0) > 2048) {
      throw new Error('Invalid tutorial session structure');
    }
    result.accept = () => { if (evaluator === selected) window.gdEditorRoot.acceptComponent(selected); };
    return result;
  }

  window.gdTourSessionGraph = {view};
})();
