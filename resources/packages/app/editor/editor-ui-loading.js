// graph-first-exception: this host must describe pending/failed graph loading
// before any graph renderer is available. It never owns editor controls.
(() => {
  const labels = {editor: 'Editor controls', inspector: 'Inspector', explorer: 'Explorer',
    canvas: 'Cards', 'canvas-values': 'Values and types', 'canvas-inheritance': 'Inheritance',
    'canvas-metadata': 'Function details', 'canvas-type-structure': 'Expanded types', 'canvas-bindings': 'Bindings'};
  const states = new Map();
  let region = null;
  let queued = false;
  let announced = '';
  let observer = null;
  const identity = component => 'gd-ui-loading-' + component;
  function hosts(component) {
    return document.querySelectorAll('[data-ui-loading-component="' + component + '"]');
  }
  function updateHost(host, component) {
    const state = states.get(component);
    host.toggleAttribute('data-ui-loading', state?.phase === 'loading');
    host.toggleAttribute('data-ui-load-error', state?.phase === 'error');
    if (state) {
      const descriptions = (host.getAttribute('aria-describedby') || '').split(' ').filter(Boolean);
      if (!descriptions.includes(identity(component))) descriptions.push(identity(component));
      host.setAttribute('aria-describedby', descriptions.join(' '));
    } else {
      const descriptions = (host.getAttribute('aria-describedby') || '').split(' ').filter(id => id && id !== identity(component));
      if (descriptions.length) host.setAttribute('aria-describedby', descriptions.join(' '));
      else host.removeAttribute('aria-describedby');
    }
  }
  function render() {
    queued = false;
    const app = document.getElementById('app');
    if (!app) return;
    if (!region) {
      region = document.createElement('div');
      region.className = 'gd-ui-loading';
      region.setAttribute('role', 'region');
      region.setAttribute('aria-label', 'Interface status');
      app.insertBefore(region, document.getElementById('main-container'));
    }
    // Hosts can be assembled off-document. Observe only connections while
    // something is pending; unrelated graph text updates need no status pass.
    if (states.size && !observer && typeof MutationObserver === 'function') {
      observer = new MutationObserver(records => {
        if (records.some(record => [...record.addedNodes, ...record.removedNodes].some(node => node.nodeType === 1
          && (node.matches('[data-ui-loading-component]') || node.querySelector('[data-ui-loading-component]'))))) publish();
      });
      observer.observe(app, {childList: true, subtree: true});
    } else if (!states.size && observer) { observer.disconnect(); observer = null; }
    const visibleComponents = new Set();
    const bindings = Object.keys(labels).map(component => ({component,
      hosts: [...hosts(component)].map(host => ({host, visible: host.getClientRects().length > 0,
        group: host.closest('.node-overlay') || host.closest('.arg-overlay-row') || host.closest('.arg-value-edit-popover') || host})),
    }));
    for (const {component, hosts} of bindings) {
      const marked = new Set();
      for (const {host, visible, group} of hosts) {
        updateHost(host, component);
        const indicator = visible && !marked.has(group);
        host.toggleAttribute('data-ui-loading-indicator', indicator);
        if (indicator) marked.add(group);
        if (visible) visibleComponents.add(component);
      }
    }
    for (const row of [...region.children]) if (states.get(row.dataset.component)?.row !== row) row.remove();
    let visible = false;
    const announcements = [];
    for (const [component, state] of states) {
      if (!state.row) {
        const row = document.createElement('span');
        row.id = identity(component);
        row.className = 'gd-ui-loading-item';
        row.dataset.component = component;
        const text = document.createElement('span');
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.setAttribute('aria-label', 'Retry ' + labels[component].toLowerCase());
        retry.addEventListener('click', () => states.get(component)?.retry?.());
        row.append(text, retry);
        state.row = row;
      }
      const row = state.row;
      const [text, retry] = row.children;
      row.dataset.phase = state.phase;
      row.setAttribute('aria-busy', String(state.phase === 'loading'));
      row.hidden = component !== 'editor' && !visibleComponents.has(component);
      const message = labels[component] + (state.phase === 'loading' ? ' — loading…' : ' — ' + state.message);
      if (text.textContent !== message) text.textContent = message;
      retry.hidden = !(state.retry && (state.phase === 'error' || state.retrying));
      retry.disabled = state.phase === 'loading';
      retry.textContent = state.phase === 'loading' ? 'Retrying…' : 'Retry';
      visible ||= !row.hidden;
      if (!row.hidden) announcements.push(message);
      if (row.parentNode !== region) region.appendChild(row);
    }
    region.hidden = !visible;
    const summary = announcements.join('. ');
    if (summary !== announced) {
      announced = summary;
      // Use the existing polite announcer outside all busy descendants.
      if (summary) window.gdAnnounce?.(summary);
    }
  }
  function publish() {
    if (queued) return;
    queued = true;
    queueMicrotask(render);
  }
  function begin(component, retry) {
    if (!Object.hasOwn(labels, component)) throw new Error('Unknown interface component');
    const previous = states.get(component);
    const state = {phase: 'loading', retry, row: previous?.row, retrying: previous?.phase === 'error'};
    states.set(component, state);
    publish();
    return {
      finish() {
        if (states.get(component) !== state) return;
        if (state.phase !== 'error') states.delete(component);
        publish();
      },
      cancel() {
        if (states.get(component) !== state) return;
        states.delete(component);
        publish();
      },
    };
  }
  function error(component, message) {
    const state = states.get(component);
    if (!state) return;
    state.phase = 'error';
    state.message = message;
    publish();
  }
  function bind(component, host) {
    if (!host || !Object.hasOwn(labels, component)) return;
    host.dataset.uiLoadingComponent = component;
    updateHost(host, component);
    publish();
  }
  function unbind(host) {
    if (!host) return;
    const component = host.dataset.uiLoadingComponent;
    if (!component) return;
    // Remove only the attributes this host owns, keeping other descriptions.
    delete host.dataset.uiLoadingComponent;
    host.removeAttribute('data-ui-loading');
    host.removeAttribute('data-ui-loading-indicator');
    host.removeAttribute('data-ui-load-error');
    const descriptions = (host.getAttribute('aria-describedby') || '').split(' ').filter(id => id && id !== identity(component));
    if (descriptions.length) host.setAttribute('aria-describedby', descriptions.join(' '));
    else host.removeAttribute('aria-describedby');
    publish();
  }
  window.gdUILoading = {begin, error, bind, unbind,
    clear(keepEditor = false) {
      for (const component of states.keys()) if (!keepEditor || component !== 'editor') states.delete(component);
      publish();
    }};
  document.addEventListener('DOMContentLoaded', () => { bind('editor', document.getElementById('auth-mount')); render(); }, {once: true});
  window.addEventListener('pagehide', () => window.gdUILoading.clear());
})();
