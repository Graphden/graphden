// Settings → Appearance. The selected graph owns the pane tree; this host owns
// DOM events, theme validation, preference writes and the marketplace boundary.
(() => {
  const DEBOUNCE_MS = 250;
  let root = null;
  let selected = null;
  let rejected = false;
  let wired = false;
  let editing = false;
  let draft = null;
  let saveTimer = 0;
  let saved = [];
  let savedGeneration = 0;
  const groupOpen = new Map();
  const pref = () => window.gdPrefRead?.('theme') || null;
  const market = () => !!window.gdMarketPresent?.();

  function workingPayload() {
    const active = pref()?.payload;
    if (active) return window.gdSanitizeThemePayload(active);
    const tokens = {};
    for (const [, name] of window.gdThemeTokens) {
      const value = window.gdThemeTokenValue(name);
      if (value) tokens[name] = value;
    }
    const fonts = {};
    for (const [name, variable] of Object.entries(window.gdThemeFontVars)) {
      const value = window.gdThemeTokenValue(variable);
      if (value) fonts[name] = value;
    }
    return {mode: document.body.classList.contains('theme-dark') ? 'dark' : 'light', tokens, fonts, scale: 100};
  }
  function parseColor(color) {
    const value = String(color || '').trim();
    let match = /^#([0-9a-f]{3,8})$/i.exec(value);
    if (match) {
      let hex = match[1];
      if (hex.length === 3 || hex.length === 4) hex = hex.split('').map(part => part + part).join('');
      return [0, 2, 4].map(index => Number.parseInt(hex.slice(index, index + 2), 16));
    }
    match = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/.exec(value);
    return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null;
  }
  function luminance(rgb) {
    const channel = value => { const normalized = value / 255; return normalized <= 0.03928
      ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2]);
  }
  function contrast(a, b) {
    const first = parseColor(a); const second = parseColor(b);
    if (!first || !second) return null;
    const light = luminance(first); const dark = luminance(second);
    return (Math.max(light, dark) + 0.05) / (Math.min(light, dark) + 0.05);
  }
  function toHex(color) {
    const rgb = parseColor(color);
    return rgb ? '#' + rgb.map(value => Math.max(0, Math.min(255, Math.round(value)))
      .toString(16).padStart(2, '0')).join('') : '#000000';
  }
  function contrastInfo(payload) {
    const ratio = contrast(payload.tokens['--gd-ink'] || window.gdThemeTokenValue('--gd-ink'),
      payload.tokens['--gd-paper'] || window.gdThemeTokenValue('--gd-paper'));
    return {text: ratio == null ? '' : 'Ink on paper contrast ' + ratio.toFixed(1) + ':1'
      + (ratio < 4.5 ? ' — below WCAG AA (4.5:1)' : ' ✓'), low: ratio != null && ratio < 4.5};
  }
  function commit(payload, source, immediate = false) {
    const value = {source: source || null, payload: window.gdSanitizeThemePayload(payload)};
    window.gdApplyThemePayload?.(value.payload);
    clearTimeout(saveTimer);
    const write = () => { window.gdPrefWrite?.('theme', value); };
    if (immediate) write(); else saveTimer = setTimeout(write, DEBOUNCE_MS);
  }
  const api = () => window.GraphdenBrowser;
  const keyword = value => api().keyword(value);
  const record = value => new Map(Object.entries(value).map(([name, item]) => [keyword(name), item]));
  let builtin = null;
  function inputs() {
    const current = pref();
    const payload = editing && draft ? draft : workingPayload();
    const groups = new Map();
    for (const [name, token, label] of window.gdThemeTokens) {
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name).push(record({name: token, label,
        value: toHex(payload.tokens[token] || window.gdThemeTokenValue(token))}));
    }
    const source = current?.source;
    const hint = current?.graph ? 'Graph: ' + (current.graph.label || 'Theme') : current?.payload
      ? source?.name ? 'Based on ' + source.name + '@' + source.version + (current.dirty ? ' (edited)' : '')
        : 'Unsaved local edits' : 'Built-in look — the Theme toggle above picks light or dark.';
    const ratio = contrastInfo(payload);
    return {
      hint, 'graph-status': window.gdThemeGraphStatus?.() || 'Choose an ordinary function that returns a theme.',
      'has-graph': !!current?.graph, 'has-payload': !!current?.payload,
      'can-create': !!window.API?.api_ui_theme_create && !current?.graph,
      editing, marketplace: market(),
      saved: saved.map(item => record(item)), mode: payload.mode, scale: payload.scale,
      'scale-min': String(window.gdThemeScaleRange[0]), 'scale-max': String(window.gdThemeScaleRange[1]),
      fonts: [['ui', 'UI font'], ['mono', 'Code font'], ['body', 'Canvas font']]
        .map(([id, label]) => record({id, label, value: payload.fonts?.[id] || ''})),
      groups: [...groups].map(([name, items]) => record({name,
        open: groupOpen.has(name) ? groupOpen.get(name) : ['Grounds', 'Ink', 'Accent'].includes(name), items})),
      contrast: ratio.text, 'low-contrast': ratio.low,
    };
  }
  function view(runtime, data) {
    const output = runtime.run('view', {inputs: record(data)});
    if (!(output instanceof Map) || output.size !== 1 || !output.has(keyword('tree')))
      throw new Error('Invalid appearance graph view');
    const vnode = window.GraphdenRenderer.vnode(output.get(keyword('tree')), 'gd-theme', 4000);
    const scratch = document.createElement('div');
    window.GraphdenPreact.render(vnode, scratch);
    if (scratch.childElementCount !== 1) throw new Error('Invalid appearance graph tree');
    const pane = scratch.firstElementChild;
    if (data.editing && !data['has-graph'] &&
      (pane.querySelectorAll('.gd-theme-swatch[data-token]').length !== window.gdThemeTokens.length
        || pane.querySelectorAll('.gd-theme-font[data-font]').length !== 3))
      throw new Error('Incomplete appearance graph controls');
    return pane;
  }
  function render() {
    root = document.getElementById('gd-theme-root');
    if (!root || !api()?.createRuntime) return;
    const current = pref();
    const toggle = document.getElementById('gd-set-theme');
    if (toggle) {
      toggle.disabled = !!current?.graph;
      toggle.title = current?.graph ? 'Edit the mode in your theme graph, or use saved colors to stop following it.' : '';
      toggle.textContent = document.body.classList.contains('theme-dark') ? 'Dark' : 'Light';
    }
    const data = inputs();
    const focused = root.contains(document.activeElement) ? document.activeElement : null;
    const focusId = focused?.id || '';
    if (!selected && !rejected) {
      try { selected = window.gdEditorRoot?.componentNow('appearance') || null; }
      catch (_) { rejected = true; window.gdUIComponentFailed?.('appearance'); }
    }
    let pane;
    if (selected) {
      try {
        pane = view(selected, data);
        if (!window.gdEditorRoot.acceptComponent(selected)) throw new Error('Editor root changed');
      } catch (_) {
        window.gdEditorRoot?.releaseComponent(selected);
        selected = null; rejected = true;
        window.gdUIComponentFailed?.('appearance');
      }
    }
    builtin ||= api().createRuntime(window.GraphdenBuiltinPlans.plans.appearance, {operationLimit: 100000});
    root.replaceChildren(pane || view(builtin, data));
    if (focusId) root.querySelectorAll('[id]').forEach(item => { if (item.id === focusId) item.focus(); });
  }
  function updateContrast(payload) {
    const info = contrastInfo(payload);
    const hint = root?.querySelector('#gd-theme-contrast');
    if (hint) { hint.textContent = info.text; hint.classList.toggle('is-low', info.low); }
    const label = root?.querySelector('#gd-theme-scale-label');
    if (label) label.textContent = 'Text size ' + payload.scale + '%';
  }
  async function loadSaved() {
    const generation = ++savedGeneration;
    if (!market()) { saved = []; render(); return; }
    try {
      const current = pref();
      const cards = await window.gdMarketOwnCards('theme');
      if (generation !== savedGeneration || !document.getElementById('gd-theme-root')?.isConnected) return;
      const source = current?.source;
      if (source?.name && !cards.some(card => card.name === source.name))
        cards.push({name: source.name, versions: [source.version]});
      saved = cards.flatMap(card => (card.versions || []).map(version => ({
        label: card.name + '@' + version + (source?.name === card.name && source?.version === version ? ' (active)' : ''),
        value: card.name + '@' + version,
        active: source?.name === card.name && source?.version === version,
      })));
      render();
    } catch (_) { if (generation === savedGeneration) { saved = []; render(); } }
  }
  function onClick(event) {
    const button = event.target.closest?.('button[data-action]');
    if (!button || !root?.contains(button)) return;
    const current = pref();
    switch (button.dataset.action) {
      case 'edit': editing = !editing; draft = editing ? workingPayload() : null; render(); break;
      case 'reset': draft = null; clearTimeout(saveTimer); window.gdApplyThemePayload?.(null); window.gdPrefWrite?.('theme', null); render(); break;
      case 'create': window.gdCreateThemeGraph?.(button); break;
      case 'choose': window.gdChooseThemeGraph?.(button); break;
      case 'open': if (current?.graph) void window.gdOpenThemeGraph?.(current.graph); break;
      case 'detach': if (current?.graph) { draft = null; commit(window.gdActiveThemePayload() || current.payload, current.source, true); render(); } break;
      case 'browse': window.gdMarketOpen?.({kind: 'theme'}); break;
      case 'save': if (market()) window.gdMarketOpenPublishDialog({kind: 'theme', payload: draft || workingPayload(),
        name: current?.source?.name || '', onDone: result => {
          commit(draft || workingPayload(), {name: result.name, version: result.version}, true);
          void loadSaved();
        }}); break;
    }
  }
  async function onChange(event) {
    const control = event.target;
    if (!root?.contains(control)) return;
    if (control.id === 'gd-theme-select') {
      const [name, version] = control.value.split('@');
      if (!name || !market()) return;
      const payload = await window.gdMarketFetchPayload(name, version);
      if (payload) { draft = null; commit(payload, {name, version}, true); render(); }
    } else if (control.id === 'gd-theme-mode') {
      const payload = draft || workingPayload(); payload.mode = control.value;
      commit(payload, pref()?.source, true); render();
    }
  }
  function onInput(event) {
    const control = event.target;
    if (!root?.contains(control)) return;
    if (!draft) draft = workingPayload();
    const payload = draft;
    if (control.id === 'gd-theme-scale') payload.scale = Number(control.value);
    else if (control.dataset.font && Object.hasOwn(window.gdThemeFontVars, control.dataset.font))
      payload.fonts[control.dataset.font] = control.value;
    else if (control.dataset.token && window.gdThemeTokens.some(([, name]) => name === control.dataset.token))
      payload.tokens[control.dataset.token] = control.value;
    else return;
    commit(payload, pref()?.source);
    updateContrast(payload);
  }
  function gdRenderThemePane() {
    const next = document.getElementById('gd-theme-root');
    if (root !== next) {
      root?.removeEventListener('click', onClick);
      root?.removeEventListener('change', onChange);
      root?.removeEventListener('input', onInput);
      next?.addEventListener('click', onClick);
      next?.addEventListener('change', onChange);
      next?.addEventListener('input', onInput);
    }
    render();
    if (wired) return;
    wired = true;
    window.gdPrefOnChange?.(key => {
      if (key === 'theme' && document.getElementById('gd-theme-root')?.isConnected) {
        if (!editing) { render(); void loadSaved(); }
      }
    });
    window.addEventListener('gd-theme-graph-status', () => {
      const hint = document.getElementById('gd-theme-graph-status');
      if (hint) hint.textContent = window.gdThemeGraphStatus() || 'Choose an ordinary function that returns a theme.';
    });
    window.addEventListener('gd-editor-root-changing', () => { selected = null; rejected = false; });
    window.addEventListener('gd-editor-root-ready', () => {
      if (document.getElementById('gd-theme-root')?.isConnected) render();
    });
    window.addEventListener('gd-auth-changed', () => {
      clearTimeout(saveTimer); draft = null; saved = []; savedGeneration++;
    });
    void loadSaved();
  }
  window.gdRenderThemePane = gdRenderThemePane;
  window.gdThemeWorkingPayload = workingPayload;
  window.gdThemeContrast = contrast;
})();
