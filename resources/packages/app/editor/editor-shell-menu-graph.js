// Shipped graphs control the account menu; explicit review entries override them.
// Auth, capabilities, callbacks and positioning stay in their existing owners.
(() => {
  const params = new URLSearchParams(location.search);
  const names = ['ui-initial', 'ui-update', 'ui-view'];
  const review = names.some((name) => params.has(name));
  if (!review && !window.GraphdenBuiltinPlans) return;
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const get = (map, name) => map instanceof Map ? map.get(key(name)) : undefined;
  let runtime = null;
  let state = null;
  let theme = null;
  let mounted = null;
  let requestGeneration = 0;
  let pending = null;
  let ready = false;

  function report(error) {
    if (!review) window.gdUIComponentFailed?.('account-menu');
    closeMounted();
    window.gdClearGraphTheme();
    runtime = null;
    window.gdShellMenuGraph.ready = false;
    if (typeof gdToast === 'function') gdToast('Menu graph unavailable: ' + error.message);
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
  function rowContent(value) {
    if (Array.isArray(value)) return value.every(rowContent);
    if (value == null || typeof value === 'string' || typeof value === 'number') return true;
    return ['span', 'strong', 'small'].includes(value.type) && value.props.role !== 'menuitem'
      && value.props.tabIndex == null && value.props['data-action'] == null && rowContent(value.props.children);
  }
  function validate(value) {
    const frameTree = get(value, 'frame');
    const frame = window.GraphdenRenderer.vnode(frameTree);
    if (frame.type !== 'div' || frame.props.className !== 'auth-menu'
      || frame.props.role !== 'menu' || frame.props.children != null) throw new Error('Invalid account menu frame');
    const commonTree = get(value, 'common-tree');
    const common = window.GraphdenRenderer.vnode(commonTree);
    if (common.type !== 'div' || common.props.className !== 'auth-menu-common') throw new Error('Invalid account menu content');
    const rows = [common.props.children].flat().filter(Boolean);
    const destinations = new Map([['settings', 'Settings'], ['operate', 'Organization']]);
    if (rows.length !== destinations.size || rows.some((row) => {
      const action = row.props?.['data-action'];
      if (row.type !== 'button' || row.props.className !== 'auth-menu-item' || row.props.role !== 'menuitem'
        || row.props.type !== 'button' || row.props.tabIndex !== -1 || !destinations.has(action)
        || row.props['data-item'] !== destinations.get(action) || row.key !== action
        || !rowContent(row.props.children)) return true;
      destinations.delete(action);
      return false;
    })) throw new Error('Invalid account menu destinations');
    const rules = get(value, 'styles');
    window.GraphdenStyles.normalize(rules);
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
    return {frame, commonTree, rules, hover, duration, offset, opacity};
  }
  function refreshTheme() {
    if (!runtime) return;
    try {
      theme = new Map(Object.entries(window.gdGraphThemeBase()).map(([name, value]) => [key(name), value]));
      const value = view();
      const checked = validate(value);
      window.gdApplyGraphTheme(themeTokens(get(value, 'theme-tokens')));
      mounted?.menu.style.setProperty('--gd-account-menu-hover', checked.hover);
      mounted?.component.render(checked.commonTree, checked.rules);
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
    mounted.component.dispose();
    mounted = null;
  }
  function closeMounted() {
    const close = mounted?.closeNow;
    if (close) close();
    else dispose();
  }
  function mount(menu, pop, closeNow, graphFrame) {
    dispose();
    const controller = new AbortController();
    let initial;
    try { state = runtime.run('initial'); initial = validate(view()); }
    catch (error) { controller.abort(); closeNow(); graphFrame.component.dispose(); report(error); return; }
    mounted = {menu, pop, controller, component: graphFrame.component, generation: 0, animation: null, closeNow, phase: 'closed', motion: initial};
    const own = mounted;
    const options = {signal: controller.signal};
    const items = () => [...menu.querySelectorAll('[role="menuitem"]')];
    function apply(value, animate = true) {
      const checked = validate(value);
      own.component.render(checked.commonTree, checked.rules);
      menu.style.setProperty('--gd-account-menu-hover', checked.hover);
      const phase = get(value, 'phase');
      const previous = own.phase;
      const previousMotion = own.motion;
      own.motion = checked;
      own.phase = phase;
      if (phase === 'closed') { closeNow(); return; }
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
      own.animation = animateWithGeometry(menu, [from, target], {duration: matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : checked.duration, easing: 'ease-out', fill: 'forwards'});
      own.animation.finished.then(() => {
        if (mounted !== own || generation !== own.generation) return;
        const next = dispatch('animation-finished');
        own.animation.cancel(); own.animation = null;
        apply(next, false);
      }).catch(() => {});
    }
    menu.addEventListener('keydown', (event) => {
      try { const value = dispatch('keydown', {key: event.key}); if (get(value, 'handled')) event.preventDefault(); apply(value); }
      catch (error) { report(error); }
    }, options);
    menu.addEventListener('focusin', (event) => {
      const index = items().indexOf(event.target);
      if (index >= 0) { try { dispatch('focus', {index}); } catch (error) { report(error); } }
    }, options);
    // Native callbacks remain attached to their original nodes. The graph never
    // manufactures a click or invokes an action when it loads or changes.
    menu.addEventListener('click', (event) => {
      if (!event.target.closest('[role="menuitem"]')) return;
      if (mounted === own) {
        try { dispatch('activate'); closeNow(); } catch (error) { report(error); }
      }
    }, options);
    own.apply = apply;
    try { apply(dispatch('open')); items()[0]?.focus(); } catch (error) { report(error); }
  }
  const integration = {
    ready: false,
    frame() {
      if (!runtime) return null;
      let component;
      try {
        const checked = validate(view());
        const frame = document.createElement('div');
        for (const [name, value] of Object.entries(checked.frame.props)) {
          if (name !== 'children') frame.setAttribute(name === 'className' ? 'class' : name === 'tabIndex' ? 'tabindex' : name, String(value));
        }
        const rowHost = document.createElement('div');
        component = window.GraphdenRenderer.mount(rowHost);
        component.render(checked.commonTree, checked.rules);
        return {frame, rowHost, component};
      } catch (error) { component?.dispose(); report(error); return null; }
    },
    mount,
    toggle() {
      if (!mounted) return false;
      mounted.apply(dispatch('open'));
      return true;
    },
    close(immediate = false) {
      if (!mounted) return false;
      if (immediate) { dispatch('close'); closeMounted(); }
      else mounted.apply(dispatch('outside'));
      return true;
    },
    dispose,
    refreshTheme,
    async reload() {
      const generation = ++requestGeneration;
      pending?.abort();
      // Reauthorization never keeps an older personal plan mounted.
      closeMounted();
      window.gdClearGraphTheme();
      runtime = null;
      integration.ready = false;
      const controller = new AbortController();
      pending = controller;
      try {
        let plan;
        let loadedRuntime;
        if (review) {
          if (names.some((name) => !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(params.get(name) || ''))
            || !params.get('branch') || params.get('branch') === 'main') throw new Error('Choose an explicit review branch and three entry functions');
          const query = new URLSearchParams({branch: getCurrentBranchName()});
          names.forEach((name) => { query.set(name.slice(3), params.get(name)); });
          const response = await window.authFetch('/ui-preview/plan?' + query, {cache: 'no-store'});
          plan = await response.json();
          if (!response.ok || plan.ok === false) throw new Error(plan.reason || 'Graph export refused');
        } else {
          plan = window.GraphdenBuiltinPlans.plans.accountMenu;
          loadedRuntime = await window.gdLoadUIComponentRuntime('account-menu', plan, {}, controller.signal);
        }
        if (generation !== requestGeneration) return;
        if (mounted) closeMounted();
        runtime = loadedRuntime || api.createRuntime(plan);
        state = runtime.run('initial');
        theme = new Map(Object.entries(window.gdGraphThemeBase()).map(([name, value]) => [key(name), value]));
        validate(view());
        integration.ready = true;
        ready = true;
        refreshTheme();
      } catch (error) { if (generation === requestGeneration && error.name !== 'AbortError') report(error); }
      finally { if (pending === controller) pending = null; }
    },
    get state() { return state; },
    get runtime() { return runtime; },
  };
  window.gdShellMenuGraph = integration;
  window.gdRefreshGraphTheme = refreshTheme;
  document.addEventListener('DOMContentLoaded', () => { if (!review || isAuthenticated() || accountsAuthed) void integration.reload(); }, {once: true});
  window.addEventListener('gd-auth-changed', () => {
    requestGeneration++;
    pending?.abort();
    if (ready || runtime) { closeMounted(); window.gdClearGraphTheme(); runtime = null; integration.ready = false; }
    if (!review || isAuthenticated() || accountsAuthed) void integration.reload();
  });
})();
