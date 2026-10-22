// Pure catalogue presentation. The tour owner supplies only display facts and
// keeps history, requirements, navigation, links, focus and native controls.
(() => {
  const api = window.GraphdenBrowser;
  const keyword = api.keyword;
  const field = (map, name) => map instanceof Map ? map.get(keyword(name)) : undefined;
  const list = value => Array.isArray(value) || api.isSequence(value) ? [...value] : null;
  const value = item => Array.isArray(item) ? item.map(value)
    : item && typeof item === 'object' ? new Map(Object.entries(item).map(([name, part]) => [keyword(name), value(part)])) : item;
  const record = (item, names) => item instanceof Map && item.size === names.length
    && names.every(name => item.has(keyword(name)));
  const passiveTags = new Set(['div', 'span', 'small', 'strong', 'em']);
  const passiveProps = new Set(['className', 'title', 'aria-hidden', 'aria-label', 'children', 'key']);
  const nativeClasses = new Set(['gd-tour-btn', 'gd-tour-unmark', 'gd-tour-read-btn',
    'gd-tour-lesson-row', 'gd-tour-chapter', 'gd-tour-lesson-list', 'gd-tour-filter', 'gd-tour-picker-foot']);
  const sessions = new Set();
  let selected = null;
  let pending = null;
  let generation = 0;

  function safeTree(tree, limit) {
    const root = window.GraphdenRenderer.vnode(tree, 'gd-tour-catalog', limit);
    function visit(node) {
      if (node == null || typeof node !== 'object') return;
      if (!passiveTags.has(node.type) || Object.keys(node.props).some(name => !passiveProps.has(name))) {
        throw new Error('Catalogue graph must be passive');
      }
      if (String(node.props.className || '').split(/\s+/).some(name => nativeClasses.has(name))) {
        throw new Error('Catalogue graph cannot own native controls');
      }
      for (const child of [node.props.children].flat()) visit(child);
    }
    visit(root);
    return tree;
  }
  function checkedView(runtime, input, allowed) {
    const output = runtime.run('view', {inputs: value(input)});
    if (!record(output, ['counts', 'sections', 'empty', 'intro', 'resume-label', 'styles'])) {
      throw new Error('Invalid catalogue view');
    }
    const sections = list(field(output, 'sections'));
    const authorized = new Map(allowed.map((lesson, index) => [lesson.id, index]));
    if (!sections || sections.length > allowed.length) throw new Error('Invalid catalogue sections');
    let previous = -1;
    const rows = sections.map(section => {
      if (!record(section, ['chapter', 'rows']) || typeof field(section, 'chapter') !== 'string') {
        throw new Error('Invalid catalogue section');
      }
      const items = list(field(section, 'rows'));
      if (!items?.length || items.length > allowed.length) throw new Error('Invalid catalogue rows');
      return {chapter: field(section, 'chapter'), rows: items.map(row => {
        if (!record(row, ['id', 'tree', 'done', 'fresh', 'stale', 'locked', 'text-only'])) {
          throw new Error('Invalid catalogue row');
        }
        const id = field(row, 'id');
        const index = authorized.get(id);
        if (!Number.isInteger(index) || index <= previous) throw new Error('Catalogue graph changed lesson order or identity');
        previous = index;
        const flags = Object.fromEntries(['done', 'fresh', 'stale', 'locked', 'text-only'].map(name => [name, field(row, name)]));
        if (Object.values(flags).some(flag => typeof flag !== 'boolean')) throw new Error('Invalid catalogue status');
        return {id, tree: safeTree(field(row, 'tree'), 48), ...flags};
      })};
    });
    const empty = field(output, 'empty');
    const resumeLabel = field(output, 'resume-label');
    if (typeof resumeLabel !== 'string' || resumeLabel.length > 512) throw new Error('Invalid catalogue resume label');
    if (empty !== null) safeTree(empty, 48);
    const counts = safeTree(field(output, 'counts'), 96);
    const intro = safeTree(field(output, 'intro'), 48);
    const styles = window.GraphdenStyles.normalize(field(output, 'styles'));
    return {counts, sections: rows, empty, intro, resumeLabel, styles};
  }
  function invalidate() {
    generation++;
    selected = null;
    pending = null;
    window.gdTourCatalogClose?.();
    for (const session of [...sessions]) session.dispose();
  }
  async function prepare() {
    if (selected) return;
    if (pending) return pending;
    // component() may synchronously start the first root load. Register this
    // load after that changing event, while later root changes still retire it.
    const loading = window.gdEditorRoot.component('tutorial-catalog');
    const own = generation;
    const request = (async () => {
      const runtime = await loading;
      if (own !== generation) throw new DOMException('Editor root changed', 'AbortError');
      if (!runtime) throw new Error('Tutorial catalogue missing from editor root');
      selected = runtime;
    })();
    pending = request;
    try { await request; }
    finally { if (pending === request) pending = null; }
  }
  function mountCatalog() {
    const evaluator = selected;
    const own = generation;
    if (!evaluator) throw new Error('Tutorial catalogue unavailable');
    let painted = null;
    let proposed = null;
    let sheet = null;
    let signature = null;
    let disposed = false;
    const hosts = new Set();
    const session = {
      evaluator,
      accepted: () => painted !== null,
      own(host) {
        if (disposed || !host) return;
        hosts.add(host);
        if (sheet) host.dataset.gdUiStyle = sheet.id;
      },
      view(input) {
        if (disposed) throw new Error('Catalogue graph is disposed');
        if (own !== generation || evaluator !== selected) throw new DOMException('Editor root changed', 'AbortError');
        proposed = checkedView(evaluator, input, input.lessons);
        return proposed;
      },
      painted(output) {
        if (disposed || own !== generation || evaluator !== selected || proposed !== output) {
          throw new DOMException('Editor root changed', 'AbortError');
        }
        const nextSignature = JSON.stringify(output.styles);
        if (signature !== nextSignature) {
          const next = window.GraphdenStyles.acquire(output.styles);
          for (const host of hosts) {
            if (next) host.dataset.gdUiStyle = next.id;
            else delete host.dataset.gdUiStyle;
          }
          window.GraphdenStyles.release(sheet);
          sheet = next;
          signature = nextSignature;
        }
        if (!window.gdEditorRoot.acceptComponent(evaluator)) throw new DOMException('Editor root changed', 'AbortError');
        painted = output;
      },
      dispose() {
        if (disposed) return;
        disposed = true;
        sessions.delete(session);
        proposed = null;
        painted = null;
        if (![...sessions].some(item => item.evaluator === evaluator && item.accepted())) {
          window.gdEditorRoot.releaseComponent(evaluator);
        }
        for (const host of hosts) delete host.dataset.gdUiStyle;
        hosts.clear();
        window.GraphdenStyles.release(sheet);
      },
    };
    sessions.add(session);
    return session;
  }
  window.gdTourCatalogGraph = {mount: mountCatalog, prepare, invalidate};
  window.addEventListener('gd-editor-root-changing', invalidate);
})();
