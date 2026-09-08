// Editor Theme pane — Settings → Appearance (docs/MARKETPLACE.md § Themes).
//
// The user edits the editor's design tokens (colours, fonts, size) LIVE; the
// working copy is the `theme` preference (`{source, payload}` — see
// editor-prefs.js for the payload shape and its apply path). Saving publishes
// the payload as a versioned THEME package (private, or public = shared in
// the marketplace); "roll back" applies an earlier version of the same
// package; "reset" clears the preference (the built-in light / dark look).

(() => {
  

  const DEBOUNCE_MS = 250;
  let _root = null;
  let _saveTimer = 0;

  function present() { return typeof window.gdMarketPresent === 'function' && window.gdMarketPresent(); }
  function pref() { return (typeof window.gdPrefRead === 'function') ? window.gdPrefRead('theme') : null; }

  // The payload to edit: the active custom theme, else the CURRENT built-in
  // values read off the stylesheet (so an edit starts from what's on screen).
  function workingPayload() {
    const p = pref()?.payload;
    if (p) return window.gdSanitizeThemePayload(p);
    const tokens = {};
    for (const [, name] of window.gdThemeTokens) {
      const v = window.gdThemeTokenValue(name);
      if (v) tokens[name] = v;
    }
    const fonts = {};
    for (const [k, v] of Object.entries(window.gdThemeFontVars)) {
      const val = window.gdThemeTokenValue(v);
      if (val) fonts[k] = val;
    }
    return { mode: document.body.classList.contains('theme-dark') ? 'dark' : 'light', tokens, fonts, scale: 100 };
  }

  // relative luminance / contrast (WCAG) for the ink-on-paper hint
  function parseColor(c) {
    const s = String(c || '').trim();
    let m = /^#([0-9a-f]{3,8})$/i.exec(s);
    if (m) {
      let h = m[1];
      if (h.length === 3 || h.length === 4) h = h.split('').map((x) => x + x).join('');
      return [Number.parseInt(h.slice(0, 2), 16), Number.parseInt(h.slice(2, 4), 16), Number.parseInt(h.slice(4, 6), 16)];
    }
    m = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/.exec(s);
    if (m) return [Number(m[1]), Number(m[2]), Number(m[3])];
    return null;
  }
  function luminance(rgb) {
    const f = (v) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2]);
  }
  function contrast(a, b) {
    const ca = parseColor(a); const cb = parseColor(b);
    if (!ca || !cb) return null;
    const la = luminance(ca); const lb = luminance(cb);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }
  function toHex(c) {
    const rgb = parseColor(c);
    if (!rgb) return '#000000';
    return '#' + rgb.map((v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('');
  }

  // ---- persistence ----
  function commit(payload, source, immediate) {
    const value = { source: source || null, payload: window.gdSanitizeThemePayload(payload) };
    if (typeof window.gdApplyThemePayload === 'function') window.gdApplyThemePayload(value.payload);
    clearTimeout(_saveTimer);
    const write = () => { if (typeof window.gdPrefWrite === 'function') window.gdPrefWrite('theme', value); };
    if (immediate) write(); else _saveTimer = setTimeout(write, DEBOUNCE_MS);
  }

  // ---- render ----
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function renderActiveRow(host, current) {
    const row = el('div', 'gd-set-row');
    const copy = el('div', 'gd-set-copy');
    copy.appendChild(el('div', 'gd-set-label', 'Custom theme'));
    const hint = el('div', 'gd-set-hint');
    const src = current?.source;
    hint.textContent = current?.payload
      ? (src?.name ? 'Based on ' + src.name + '@' + src.version + (current.dirty ? ' (edited)' : '') : 'Unsaved local edits')
      : 'Built-in look — the Theme toggle above picks light or dark.';
    copy.appendChild(hint);
    row.appendChild(copy);
    const btns = el('div', 'gd-theme-btns');
    const edit = el('button', 'gd-set-btn', _root.dataset.editing === '1' ? 'Close editor' : 'Customize…');
    edit.type = 'button';
    edit.id = 'gd-theme-edit';
    edit.setAttribute('aria-expanded', _root.dataset.editing === '1' ? 'true' : 'false');
    edit.addEventListener('click', () => { _root.dataset.editing = _root.dataset.editing === '1' ? '0' : '1'; render(); });
    btns.appendChild(edit);
    if (current?.payload) {
      const reset = el('button', 'gd-set-btn', 'Reset to built-in');
      reset.type = 'button';
      reset.id = 'gd-theme-reset';
      reset.addEventListener('click', () => {
        if (typeof window.gdApplyThemePayload === 'function') window.gdApplyThemePayload(null);
        if (typeof window.gdPrefWrite === 'function') window.gdPrefWrite('theme', null);
        render();
      });
      btns.appendChild(reset);
    }
    row.appendChild(btns);
    host.appendChild(row);
  }

  async function renderSavedRow(host, current) {
    if (!present()) return;
    const row = el('div', 'gd-set-row');
    const copy = el('div', 'gd-set-copy');
    copy.appendChild(el('div', 'gd-set-label', 'Saved themes'));
    copy.appendChild(el('div', 'gd-set-hint', 'Your saved versions and anything you applied from the marketplace. Pick one to apply it; an older version of the same theme is a roll-back.'));
    row.appendChild(copy);
    const ctl = el('div', 'gd-theme-btns');
    const sel = el('select', 'mk-select gd-theme-select');
    sel.id = 'gd-theme-select';
    sel.setAttribute('aria-label', 'Saved themes');
    const none = el('option', null, '— pick a saved theme —');
    none.value = '';
    sel.appendChild(none);
    const cards = await window.gdMarketOwnCards('theme');
    const src = current?.source;
    if (src?.name && !cards.some((c) => c.name === src.name)) {
      // applied from someone else's listing — still offer its versions
      cards.push({ name: src.name, versions: [src.version], latest: src.version });
    }
    for (const c of cards) {
      const og = document.createElement('optgroup');
      og.label = c.name;
      for (const v of (c.versions || [])) {
        const o = el('option', null, c.name + '@' + v + (src?.name === c.name && src?.version === v ? ' (active)' : ''));
        o.value = c.name + '@' + v;
        if (src?.name === c.name && src?.version === v) o.selected = true;
        og.appendChild(o);
      }
      sel.appendChild(og);
    }
    sel.addEventListener('change', async () => {
      const [name, version] = sel.value.split('@');
      if (!name) return;
      const payload = await window.gdMarketFetchPayload(name, version);
      if (!payload) return;
      commit(payload, { name, version }, true);
      render();
    });
    ctl.appendChild(sel);
    const browse = el('button', 'gd-set-btn', 'Browse marketplace');
    browse.type = 'button';
    browse.id = 'gd-theme-browse';
    browse.addEventListener('click', () => { if (typeof window.gdMarketOpen === 'function') window.gdMarketOpen({ kind: 'theme' }); });
    ctl.appendChild(browse);
    row.appendChild(ctl);
    host.appendChild(row);
  }

  function renderEditor(host, current) {
    if (_root.dataset.editing !== '1') return;
    const payload = workingPayload();
    const box = el('div', 'gd-theme-editor');
    box.id = 'gd-theme-editor';

    // mode + scale + fonts
    const top = el('div', 'gd-theme-top');
    const modeL = el('label', 'gd-theme-field');
    modeL.appendChild(el('span', null, 'Base'));
    const mode = el('select', 'mk-select');
    mode.id = 'gd-theme-mode';
    for (const m of ['light', 'dark']) { const o = el('option', null, m); o.value = m; if (payload.mode === m) o.selected = true; mode.appendChild(o); }
    mode.addEventListener('change', () => { payload.mode = mode.value; commit(payload, current?.source, true); render(); });
    modeL.appendChild(mode);
    top.appendChild(modeL);

    const scaleL = el('label', 'gd-theme-field');
    const [smin, smax] = window.gdThemeScaleRange;
    const scaleLbl = el('span', null, 'Text size ' + payload.scale + '%');
    scaleL.appendChild(scaleLbl);
    const scale = document.createElement('input');
    scale.type = 'range'; scale.min = String(smin); scale.max = String(smax); scale.step = '5'; scale.value = String(payload.scale);
    scale.id = 'gd-theme-scale';
    scale.addEventListener('input', () => { payload.scale = Number(scale.value); scaleLbl.textContent = 'Text size ' + payload.scale + '%'; commit(payload, current?.source); });
    scaleL.appendChild(scale);
    top.appendChild(scaleL);
    box.appendChild(top);

    const fonts = el('div', 'gd-theme-fonts');
    for (const [k, label] of [['ui', 'UI font'], ['mono', 'Code font'], ['body', 'Canvas font']]) {
      const l = el('label', 'gd-theme-field');
      l.appendChild(el('span', null, label));
      const inp = document.createElement('input');
      inp.type = 'text'; inp.className = 'packages-publish-input gd-theme-font'; inp.value = payload.fonts?.[k] || '';
      inp.dataset.font = k;
      inp.placeholder = 'font-family stack';
      inp.addEventListener('input', () => { payload.fonts = payload.fonts || {}; payload.fonts[k] = inp.value; commit(payload, current?.source); });
      l.appendChild(inp);
      fonts.appendChild(l);
    }
    box.appendChild(fonts);

    // contrast hint
    const hint = el('div', 'gd-theme-contrast');
    hint.id = 'gd-theme-contrast';
    const updateHint = () => {
      const c = contrast(payload.tokens['--gd-ink'] || window.gdThemeTokenValue('--gd-ink'),
        payload.tokens['--gd-paper'] || window.gdThemeTokenValue('--gd-paper'));
      if (c == null) { hint.textContent = ''; return; }
      hint.textContent = 'Ink on paper contrast ' + c.toFixed(1) + ':1' + (c < 4.5 ? ' — below WCAG AA (4.5:1)' : ' ✓');
      hint.classList.toggle('is-low', c < 4.5);
    };
    updateHint();
    box.appendChild(hint);

    // token groups
    const groups = new Map();
    for (const [g, name, label] of window.gdThemeTokens) {
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g).push([name, label]);
    }
    for (const [g, items] of groups) {
      const d = document.createElement('details');
      d.className = 'gd-theme-group';
      d.open = g === 'Grounds' || g === 'Ink' || g === 'Accent';
      const sum = el('summary', null, g);
      d.appendChild(sum);
      const grid = el('div', 'gd-theme-grid');
      for (const [name, label] of items) {
        const l = el('label', 'gd-theme-token');
        const sw = document.createElement('input');
        sw.type = 'color';
        sw.className = 'gd-theme-swatch';
        sw.dataset.token = name;
        sw.value = toHex(payload.tokens[name] || window.gdThemeTokenValue(name));
        sw.setAttribute('aria-label', label);
        sw.addEventListener('input', () => { payload.tokens[name] = sw.value; commit(payload, current?.source); updateHint(); });
        l.appendChild(sw);
        l.appendChild(el('span', 'gd-theme-token-name', label));
        l.appendChild(el('code', 'gd-theme-token-var', name));
        grid.appendChild(l);
      }
      d.appendChild(grid);
      box.appendChild(d);
    }

    // save / share
    const actions = el('div', 'gd-theme-actions');
    if (present()) {
      const save = el('button', 'packages-install-btn gd-theme-save', 'Save / share…');
      save.type = 'button';
      save.id = 'gd-theme-save';
      save.addEventListener('click', () => {
        const src = current?.source;
        window.gdMarketOpenPublishDialog({
          kind: 'theme', payload: workingPayload(), name: src?.name || '',
          onDone: (j) => { commit(workingPayload(), { name: j.name, version: j.version }, true); render(); },
        });
      });
      actions.appendChild(save);
    } else {
      actions.appendChild(el('span', 'gd-set-hint', 'Saving and sharing need the registry package.'));
    }
    box.appendChild(actions);
    host.appendChild(box);
  }

  function render() {
    _root = document.getElementById('gd-theme-root');
    if (!_root) return;
    const current = pref();
    _root.replaceChildren();
    renderActiveRow(_root, current);
    renderSavedRow(_root, current);
    renderEditor(_root, current);
  }

  let _wired = false;
  function gdRenderThemePane() {
    render();
    if (!_wired && typeof window.gdPrefOnChange === 'function') {
      _wired = true;
      // a marketplace "Apply" (another surface) changes the active theme
      window.gdPrefOnChange((key) => { if (key === 'theme' && document.getElementById('gd-theme-root')?.isConnected && _root?.dataset.editing !== '1') render(); });
    }
  }
  window.gdRenderThemePane = gdRenderThemePane;
  window.gdThemeWorkingPayload = workingPayload;
  window.gdThemeContrast = contrast;
})();
