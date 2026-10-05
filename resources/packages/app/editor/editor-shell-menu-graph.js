// A review-only graph controls the real account menu's markup and transitions.
// Auth, capabilities, callbacks and positioning stay in their existing owners.
(() => {
  const params = new URLSearchParams(location.search);
  const names = ['ui-initial', 'ui-update', 'ui-view'];
  if (!names.some((name) => params.has(name))) return;
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const get = (map, name) => map instanceof Map ? map.get(key(name)) : undefined;
  const tagName = (value) => value instanceof api.Keyword ? value.name : value;
  let runtime = null;
  let state = null;
  let theme = null;
  let mounted = null;
  let requestGeneration = 0;
  let ready = false;

  function report(error) {
    const close = mounted?.closeNow;
    dispose();
    close?.();
    window.gdClearGraphTheme();
    runtime = null;
    window.gdShellMenuGraph.ready = false;
    if (typeof gdToast === 'function') gdToast('Menu graph unavailable: ' + error.message);
  }
  function treeNode(tree, depth = 0) {
    if (depth > 8 || !Array.isArray(tree)) throw new Error('Invalid menu graph markup');
    const tag = tagName(tree[0]);
    if (tag !== 'div' && tag !== 'button') throw new Error('Unsupported menu graph element');
    const element = document.createElement(tag);
    const attrs = tree[1];
    if (!(attrs instanceof Map)) throw new Error('Menu graph attributes must be a map');
    for (const [raw, value] of attrs) {
      const name = tagName(raw);
      if (!['class', 'role', 'type', 'tabindex', 'aria-label', 'data-action', 'data-item'].includes(name)
        || (typeof value !== 'string' && !(name === 'tabindex' && value === -1))) throw new Error('Unsupported menu graph attribute');
      element.setAttribute(name, String(value));
    }
    for (const child of tree.slice(2)) {
      if (Array.isArray(child)) element.appendChild(treeNode(child, depth + 1));
      else if (typeof child === 'string') element.appendChild(document.createTextNode(child));
      else throw new Error('Unsupported menu graph content');
    }
    return element;
  }
  function view() { return runtime.run('view', {state, theme}); }
  function themeTokens(value) {
    if (!(value instanceof Map)) throw new Error('Invalid graph theme');
    const tokens = Object.fromEntries(value);
    if (Object.keys(tokens).some((name) => !['--gd-flow', '--bg'].includes(name))) throw new Error('Unsupported graph theme token');
    const clean = window.gdSanitizeThemePayload({tokens});
    if (Object.keys(clean.tokens).length !== Object.keys(tokens).length) throw new Error('Invalid graph theme color');
    return clean.tokens;
  }
  function validate(value) {
    const frame = treeNode(get(value, 'frame'));
    if (frame.localName !== 'div' || frame.className !== 'auth-menu'
      || frame.getAttribute('role') !== 'menu' || frame.childNodes.length) throw new Error('Invalid account menu frame');
    const rows = [...get(value, 'common-rows')].map((tree) => treeNode(tree));
    if (rows.length !== 2 || rows.some((row, index) => row.localName !== 'button'
      || row.className !== 'auth-menu-item' || row.getAttribute('role') !== 'menuitem'
      || row.type !== 'button' || row.getAttribute('tabindex') !== '-1'
      || row.dataset.action !== ['settings', 'operate'][index]
      || row.dataset.item !== ['Settings', 'Organization'][index])) throw new Error('Invalid account menu destinations');
    themeTokens(get(value, 'theme-tokens'));
    const scoped = get(value, 'menu-tokens');
    if (!(scoped instanceof Map) || scoped.size !== 1 || !scoped.has('--gd-account-menu-hover')) throw new Error('Invalid scoped menu theme');
    const hover = scoped.get('--gd-account-menu-hover');
    if (window.gdSanitizeThemePayload({tokens: {'--bg': hover}}).tokens['--bg'] !== hover) throw new Error('Invalid menu hover color');
    const motion = get(value, 'motion');
    const duration = get(motion, 'duration-ms');
    const offset = get(motion, 'offset-y');
    const opacity = get(motion, 'opacity');
    if (!Number.isSafeInteger(duration) || duration < 0 || duration > 500
      || !Number.isSafeInteger(offset) || Math.abs(offset) > 32 || (opacity !== 0 && opacity !== 1)
      || !['closed', 'opening', 'open', 'closing'].includes(get(value, 'phase'))
      || !Number.isSafeInteger(get(value, 'active')) || typeof get(value, 'handled') !== 'boolean') throw new Error('Invalid menu graph state');
    return {frame, rows, hover, duration, offset, opacity};
  }
  function refreshTheme() {
    if (!runtime) return;
    try {
      theme = new Map(Object.entries(window.gdGraphThemeBase()).map(([name, value]) => [key(name), value]));
      const value = view();
      const checked = validate(value);
      window.gdApplyGraphTheme(themeTokens(get(value, 'theme-tokens')));
      mounted?.menu.style.setProperty('--gd-account-menu-hover', checked.hover);
    } catch (error) { report(error); }
  }
  function context() {
    return new Map([[key('items'), mounted ? [...mounted.menu.querySelectorAll('[role="menuitem"]')].map((item) => item.textContent) : []]]);
  }
  function dispatch(kind, extras = {}) {
    state = runtime.run('update', {state, context: context(),
      event: new Map(Object.entries({kind, key: '', index: -1, ...extras}).map(([name, value]) => [key(name), value]))});
    const value = view();
    validate(value);
    return value;
  }
  function dispose() {
    if (!mounted) return;
    mounted.generation++;
    mounted.animation?.cancel();
    mounted.controller.abort();
    mounted = null;
  }
  function mount(menu, pop, closeNow) {
    dispose();
    const controller = new AbortController();
    state = runtime.run('initial');
    const initial = validate(view());
    mounted = {menu, pop, controller, generation: 0, animation: null, closeNow, phase: 'closed', motion: initial};
    const own = mounted;
    const options = {signal: controller.signal};
    const items = () => [...menu.querySelectorAll('[role="menuitem"]')];
    function apply(value, animate = true) {
      const checked = validate(value);
      menu.style.setProperty('--gd-account-menu-hover', checked.hover);
      const phase = get(value, 'phase');
      const previous = own.phase;
      const previousMotion = own.motion;
      own.motion = checked;
      own.phase = phase;
      if (phase === 'closed') { dispose(); closeNow(); return; }
      const active = items()[get(value, 'active')];
      if (active && get(value, 'handled') && phase !== 'closing') active.focus();
      if (!animate || previous === phase || (phase !== 'opening' && phase !== 'closing')) return;
      const oldStyle = getComputedStyle(menu);
      const from = own.animation ? {opacity: oldStyle.opacity, transform: oldStyle.transform}
        : {opacity: previousMotion.opacity, transform: 'translateY(' + previousMotion.offset + 'px)'};
      own.generation++;
      const generation = own.generation;
      own.animation?.cancel();
      const target = {opacity: checked.opacity, transform: 'translateY(' + checked.offset + 'px)'};
      own.animation = menu.animate([from, target], {duration: matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : checked.duration, easing: 'ease-out', fill: 'forwards'});
      own.animation.finished.then(() => {
        if (mounted !== own || generation !== own.generation) return;
        const next = dispatch('animation-finished');
        own.animation.cancel(); own.animation = null;
        apply(next, false);
      }).catch(() => {});
    }
    menu.addEventListener('keydown', (event) => {
      try { const value = dispatch('keydown', {key: event.key}); if (get(value, 'handled')) event.preventDefault(); apply(value); }
      catch (error) { dispose(); closeNow(); report(error); }
    }, options);
    menu.addEventListener('focusin', (event) => {
      const index = items().indexOf(event.target);
      if (index >= 0) dispatch('focus', {index});
    }, options);
    // Native callbacks remain attached to their original nodes. The graph never
    // manufactures a click or invokes an action when it loads or changes.
    menu.addEventListener('click', (event) => {
      if (!event.target.closest('[role="menuitem"]')) return;
      if (mounted === own) { dispatch('activate'); dispose(); closeNow(); }
    }, options);
    own.apply = apply;
    apply(dispatch('open'));
    items()[0]?.focus();
  }
  const integration = {
    ready: false,
    frame() {
      if (!runtime) return null;
      try { return validate(view()); } catch (error) { report(error); return null; }
    },
    mount,
    toggle() {
      if (!mounted) return false;
      mounted.apply(dispatch('open'));
      return true;
    },
    close(immediate = false) {
      if (!mounted) return false;
      if (immediate) { const close = mounted.closeNow; dispatch('close'); dispose(); close(); }
      else mounted.apply(dispatch('outside'));
      return true;
    },
    dispose,
    refreshTheme,
    async reload() {
      const generation = ++requestGeneration;
      try {
        if (names.some((name) => !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(params.get(name) || ''))
          || !params.get('branch') || params.get('branch') === 'main') throw new Error('Choose an explicit review branch and three entry functions');
        const query = new URLSearchParams({branch: getCurrentBranchName()});
        names.forEach((name) => { query.set(name.slice(3), params.get(name)); });
        const response = await window.authFetch('/ui-preview/plan?' + query, {cache: 'no-store'});
        const plan = await response.json();
        if (!response.ok || plan.ok === false) throw new Error(plan.reason || 'Graph export refused');
        if (generation !== requestGeneration) return;
        if (mounted) { const close = mounted.closeNow; dispose(); close(); }
        runtime = api.createRuntime(plan);
        state = runtime.run('initial');
        theme = new Map(Object.entries(window.gdGraphThemeBase()).map(([name, value]) => [key(name), value]));
        validate(view());
        integration.ready = true;
        ready = true;
        refreshTheme();
      } catch (error) { if (generation === requestGeneration) report(error); }
    },
    get state() { return state; },
    get runtime() { return runtime; },
  };
  window.gdShellMenuGraph = integration;
  window.gdRefreshGraphTheme = refreshTheme;
  document.addEventListener('DOMContentLoaded', () => { void integration.reload(); }, {once: true});
  window.addEventListener('gd-auth-changed', () => {
    if (ready || runtime) { dispose(); window.gdClearGraphTheme(); runtime = null; integration.ready = false; }
    if (isAuthenticated() || accountsAuthed) void integration.reload();
  });
})();
