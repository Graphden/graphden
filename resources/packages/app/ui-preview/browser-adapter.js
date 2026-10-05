// Browser boundary: DOM, focus and animation. Content and event decisions come
// from the ordinary menu graph; each mount owns its own graph state.
(() => {
  
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const name = (value) => value instanceof api.Keyword ? value.name : String(value);
  const allowed = new Set(['div', 'button']);
  function attributes(element, attrs) {
    const next = new Set();
    for (const [raw, value] of attrs) {
      const attr = name(raw); next.add(attr);
      if (attr === 'style') {
        if (!(value instanceof Map)) throw new Error('Graph style must be a map');
        element.removeAttribute('style');
        for (const [property, item] of value) {
          if (!name(property).startsWith('--ui-')) throw new Error('Unsupported graph style');
          element.style.setProperty(name(property), String(item));
        }
      } else {
        if (!/^(class|type|role|hidden|disabled|aria-[a-z-]+|data-[a-z-]+)$/.test(attr)) throw new Error('Unsupported graph attribute');
        if (value === null || ((attr === 'hidden' || attr === 'disabled') && value === false)) element.removeAttribute(attr);
        else element.setAttribute(attr, value === true && (attr === 'hidden' || attr === 'disabled') ? '' : String(value));
      }
    }
    for (const attr of [...element.attributes]) if (!next.has(attr.name)) element.removeAttribute(attr.name);
  }
  function patch(parent, tree, index = 0) {
    let node = parent.childNodes[index];
    if (!Array.isArray(tree)) {
      const text = tree === null ? '' : String(tree);
      if (!node || node.nodeType !== Node.TEXT_NODE) { const replacement = document.createTextNode(text); if (node) node.replaceWith(replacement); else parent.appendChild(replacement); }
      else node.textContent = text;
      return;
    }
    const tag = name(tree[0]);
    if (!allowed.has(tag)) throw new Error('Unsupported preview tag: ' + tag);
    if (!node || node.nodeType !== Node.ELEMENT_NODE || node.localName !== tag) {
      const replacement = document.createElement(tag); if (node) node.replaceWith(replacement); else parent.appendChild(replacement); node = replacement;
    }
    const hasAttrs = tree[1] instanceof Map;
    attributes(node, hasAttrs ? tree[1] : new Map());
    const children = tree.slice(hasAttrs ? 2 : 1);
    children.forEach((child, childIndex) => { patch(node, child, childIndex); });
    while (node.childNodes.length > children.length) node.lastChild.remove();
  }
  function mount(host, runtime) {
    let state = runtime.run('initial'); let animation; let generation = 0;
    const controller = new AbortController(); const options = {signal: controller.signal};
    const phase = () => state.get(key('phase'));
    function focusActive() { host.querySelector('[data-active="true"]')?.focus(); }
    function render(previous) {
      const oldList = host.querySelector('[role="menu"]');
      let interrupted = null;
      if (animation && previous !== phase() && (previous === 'opening' || previous === 'closing') && oldList) {
        const style = getComputedStyle(oldList);
        interrupted = {opacity: style.opacity, transform: style.transform};
      }
      patch(host, runtime.run('view', {state}));
      const current = phase();
      if (previous === current) return;
      generation++; const ownGeneration = generation;
      animation?.cancel();
      if (current === 'opening' || current === 'closing') {
        const list = host.querySelector('[role="menu"]');
        const frames = [{opacity: 0, transform: 'translateY(-6px)'}, {opacity: 1, transform: 'translateY(0)'}];
        if (current === 'closing') frames.reverse();
        if (interrupted) frames[0] = interrupted;
        const duration = matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : Number(host.firstChild.dataset.duration);
        animation = list.animate(frames, {duration, easing: 'ease-out'});
        animation.finished.then(() => {
          if (ownGeneration !== generation) return;
          dispatch({kind: 'animation-finished'});
          if (phase() === 'closed') {
            if (document.activeElement === document.body || host.contains(document.activeElement)) host.querySelector('[data-event="open"]')?.focus();
          } else if (host.contains(document.activeElement)) focusActive();
        }).catch(() => {});
      }
    }
    function dispatch(event) {
      const previous = phase();
      try {
        state = runtime.run('update', {state, event: new Map(Object.entries({key: '', index: -1, ...event}).map(([k, v]) => [key(k), v]))});
        render(previous);
      } catch (error) {
        document.getElementById('ui-preview-status').textContent = 'Graph error: ' + error.message;
      }
    }
    host.addEventListener('click', (event) => {
      const button = event.target.closest('[data-event]');
      if (button && host.contains(button)) dispatch({kind: button.dataset.event, ...(button.dataset.index === undefined ? {} : {index: Number(button.dataset.index)})});
    }, options);
    host.addEventListener('pointerover', (event) => {
      const button = event.target.closest('[data-index]');
      if (button && host.contains(button)) dispatch({kind: 'hover', index: Number(button.dataset.index)});
    }, options);
    host.addEventListener('keydown', (event) => {
      const before = state; dispatch({kind: 'keydown', key: event.key});
      if (!api.equal(before, state)) { event.preventDefault(); if (phase() === 'open') focusActive(); }
    }, options);
    document.addEventListener('pointerdown', (event) => { if (!host.contains(event.target)) dispatch({kind: 'outside'}); }, options);
    render();
    return {dispatch, get state() { return state; }, dispose() { generation++; animation?.cancel(); controller.abort(); host.replaceChildren(); }};
  }
  let mounts = [];
  const params = new URLSearchParams(location.search);
  const namespace = params.get('graph');
  const planId = params.get('plan');
  const branchQuery = new URLSearchParams();
  if (params.has('branch')) branchQuery.set('branch', params.get('branch'));
  const query = branchQuery.size ? '?' + branchQuery : '';
  for (const link of document.querySelectorAll('[data-ui-graph-function]')) {
    link.href = '/' + query + '#' + (namespace || 'app.ui-preview') + '.' + link.dataset.uiGraphFunction;
  }
  async function reload() {
    const status = document.getElementById('ui-preview-status');
    try {
      const headers = {}; const token = localStorage.getItem('graphden.auth.password');
      if (token) headers.Authorization = 'Bearer ' + token;
      if ((namespace || planId) && (!namespace || !planId
        || !/^[a-zA-Z][a-zA-Z0-9_-]*(?:\.[a-zA-Z][a-zA-Z0-9_-]*)*$/.test(namespace)
        || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(planId))) throw new Error('Invalid preview graph selection');
      const response = planId
        ? await fetch('/api/execute' + query, {method: 'POST', headers: {...headers, 'Content-Type': 'application/json'},
          body: JSON.stringify({'fn-id': planId, args: {}, 'timeout-ms': 15000}), cache: 'no-store'})
        : await fetch('/ui-preview/plan' + query, {headers, cache: 'no-store'});
      const payload = await response.json();
      if (!response.ok || payload.ok === false || payload.status === 'failed') throw new Error(payload.reason || payload.error || 'Graph export failed');
      const plan = planId ? payload.result : payload;
      const runtime = api.createRuntime(plan);
      mounts.forEach((item) => { item.dispose(); });
      mounts = ['ui-preview-first', 'ui-preview-second'].map((id) => mount(document.getElementById(id), runtime));
      window.uiGraphPreview = {runtime, plan, mounts, reload};
      status.textContent = 'Graph loaded. Events run in the browser.';
    } catch (error) { status.textContent = 'Could not load graph: ' + error.message; }
  }
  document.getElementById('ui-preview-reload').addEventListener('click', reload);
  reload();
})();
