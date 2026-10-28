// Pure publish form presentation. The host retains permissions, endpoint
// selection, payloads, focus, network writes, and the dialog lifetime.
(() => {
  const CONTROL_TAGS = new Map([
    ['name', 'INPUT'], ['version', 'INPUT'], ['desc', 'TEXTAREA'],
    ['category', 'SELECT'], ['tags', 'INPUT'], ['public', 'INPUT'],
    ['go', 'BUTTON'], ['cancel', 'BUTTON'],
  ]);

  function renderForm(runtime, kind, tenancy, namespace) {
    const browser = window.GraphdenBrowser;
    const key = browser.keyword;
    const inputs = new Map([
      [key('kind'), kind], [key('tenancy'), tenancy], [key('namespace'), namespace],
    ]);
    const output = runtime.run('view', { inputs });
    if (!(output instanceof Map) || output.size !== 1 || !output.has(key('tree'))) {
      throw new Error('Invalid publish form graph view');
    }
    const vnode = window.GraphdenRenderer.vnode(output.get(key('tree')), 'gd', 3000);
    const scratch = document.createElement('div');
    window.GraphdenPreact.render(vnode, scratch);
    const tree = scratch.firstElementChild || scratch.firstChild;
    if (!tree?.classList.contains('gd-publish-form') || scratch.children.length !== 1) {
      throw new Error('Invalid publish form graph root');
    }
    const prefix = kind === 'fns' ? 'gd-nspub-' : 'gd-mkpub-';
    const expected = new Map([
      ...['name', 'version', 'desc', 'category', 'tags', 'go']
        .map(field => [prefix + field, CONTROL_TAGS.get(field)]),
    ]);
    if (kind !== 'fns' || tenancy) expected.set(prefix + 'public', CONTROL_TAGS.get('public'));
    if (kind !== 'fns') expected.set('gd-mkpub-cancel', CONTROL_TAGS.get('cancel'));
    const controls = [...tree.querySelectorAll('input, textarea, select, button')];
    const labelTargets = [...tree.querySelectorAll('label')]
      .map(el => el.getAttribute('for')).filter(Boolean);
    if (controls.length !== expected.size || controls.some(el => expected.get(el.id) !== el.tagName)
        || new Set(controls.map(el => el.id)).size !== expected.size
        || labelTargets.some(id => !expected.has(id))
        || tree.querySelectorAll('.gd-nspub-result').length !== 1
        || tree.querySelector('#' + prefix + 'result')?.tagName !== 'DIV') {
      throw new Error('Invalid publish form graph controls');
    }
    if (((kind !== 'fns' || tenancy) && tree.querySelector('#' + prefix + 'public')?.getAttribute('type') !== 'checkbox')
        || tree.querySelector('#' + prefix + 'go')?.getAttribute('type') !== 'button'
        || (kind !== 'fns' && tree.querySelector('#gd-mkpub-cancel')?.getAttribute('type') !== 'button')) {
      throw new Error('Invalid publish form graph actions');
    }
    return tree;
  }

  function gdPublishGraphForm(kind, tenancy, namespace = '') {
    if (!['fns', 'theme', 'keymap'].includes(kind)) throw new Error('Invalid publish kind');
    const builtin = window.GraphdenBrowser.createRuntime(window.GraphdenBuiltinPlans.plans.publishForm,
      { operationLimit: 100000 });
    let selected = null;
    try { selected = window.gdEditorRoot?.componentNow('publish-form') || null; }
    catch (_) { window.gdUIComponentFailed?.('publish-form'); }
    if (selected) {
      try {
        const tree = renderForm(selected, kind, !!tenancy, namespace);
        if (!window.gdEditorRoot.acceptComponent(selected)) throw new Error('Editor root changed');
        return tree;
      } catch (_) {
        window.gdEditorRoot?.releaseComponent(selected);
        window.gdUIComponentFailed?.('publish-form');
      }
    }
    return renderForm(builtin, kind, !!tenancy, namespace);
  }

  window.gdPublishGraphForm = gdPublishGraphForm;
})();
