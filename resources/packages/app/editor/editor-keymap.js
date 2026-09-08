// Editor Keymap pane — Settings → Keyboard (docs/MARKETPLACE.md § Keymaps).
//
// Every binding the shortcut registry knows (editor-shortcuts.js) in a table:
// group, what it does, its keys, and a "Change" control that records the
// next key sequence. Only the registry's bindings are rebindable — the
// canvas's arrow / hjkl navigation and the dialogs' Escape / Enter are the
// platform's, documented in docs/ACCESSIBILITY.md. The layout is the
// `keymap` preference `{source, payload: {bindings: {id: {keys, leader}}}}`
// — only overrides are stored; saving publishes it as a KEYMAP package.

(() => {
  

  let _root = null;
  let _recording = null; // {id, keys: [], row}

  function present() { return typeof window.gdMarketPresent === 'function' && window.gdMarketPresent(); }
  function pref() { return (typeof window.gdPrefRead === 'function') ? window.gdPrefRead('keymap') : null; }
  function overrides() { return Object.assign({}, pref()?.payload?.bindings || {}); }

  function commit(bindings, source) {
    const value = { source: source || null, payload: { bindings } };
    if (typeof window.gdApplyKeymap === 'function') window.gdApplyKeymap(bindings);
    if (typeof window.gdPrefWrite === 'function') window.gdPrefWrite('keymap', value);
  }

  function keycaps(keys, leader) {
    const frag = document.createDocumentFragment();
    const parts = (leader ? ['Space'] : []).concat(String(keys).split(' '));
    parts.forEach((k, i) => {
      if (i) frag.appendChild(document.createTextNode(' '));
      const cap = document.createElement('kbd');
      cap.className = 'gd-key-cap';
      cap.textContent = k;
      frag.appendChild(cap);
    });
    return frag;
  }

  function conflicts(entries) {
    const seen = new Map();
    const bad = new Set();
    for (const e of entries) {
      const k = (e.leader ? 'L ' : 'B ') + e.keys;
      if (seen.has(k)) { bad.add(e.id); bad.add(seen.get(k)); } else seen.set(k, e.id);
    }
    return bad;
  }

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function stopRecording() {
    if (!_recording) return;
    window.removeEventListener('keydown', onRecordKey, true);
    _recording = null;
    render();
  }
  function onRecordKey(e) {
    if (!_recording) return;
    e.preventDefault();
    e.stopPropagation();
    const k = e.key;
    if (k === 'Escape') { stopRecording(); return; }
    if (k === 'Enter') {
      if (_recording.keys.length) {
        const ov = overrides();
        ov[_recording.id] = { keys: _recording.keys.join(' '), leader: _recording.leader };
        commit(ov, pref()?.source ? Object.assign({}, pref().source) : null);
      }
      stopRecording();
      return;
    }
    if (k === 'Backspace') { _recording.keys.pop(); showRecording(); return; }
    if (['Shift', 'Control', 'Alt', 'Meta', 'Tab'].includes(k)) return;
    if (_recording.keys.length >= 3) return;
    _recording.keys.push(k === ' ' ? 'Space' : k);
    showRecording();
  }
  function showRecording() {
    const out = _recording?.row?.querySelector('.gd-km-recording');
    if (out) out.textContent = _recording.keys.length ? _recording.keys.join(' ') + '  (Enter to keep, Esc to cancel)' : 'Press the keys… (Enter to keep, Esc to cancel)';
  }

  function render() {
    _root = document.getElementById('gd-keymap-root');
    if (!_root || typeof window.gdShortcutEntries !== 'function') return;
    const entries = window.gdShortcutEntries();
    const ov = overrides();
    const bad = conflicts(entries);
    const current = pref();
    _root.replaceChildren();

    // status row
    const row = el('div', 'gd-set-row');
    const copy = el('div', 'gd-set-copy');
    copy.appendChild(el('div', 'gd-set-label', 'Keyboard layout'));
    const src = current?.source;
    const n = Object.keys(ov).length;
    copy.appendChild(el('div', 'gd-set-hint', src?.name
      ? 'Based on ' + src.name + '@' + src.version + (n ? ' — ' + n + ' binding(s) changed' : '')
      : (n ? n + ' binding(s) changed from the defaults' : 'The default bindings. Change a key below; Space is the leader for everything marked with it.')));
    row.appendChild(copy);
    const btns = el('div', 'gd-theme-btns');
    if (n || src) {
      const reset = el('button', 'gd-set-btn', 'Reset to defaults');
      reset.type = 'button';
      reset.id = 'gd-keymap-reset';
      reset.addEventListener('click', () => {
        if (typeof window.gdApplyKeymap === 'function') window.gdApplyKeymap(null);
        if (typeof window.gdPrefWrite === 'function') window.gdPrefWrite('keymap', null);
        render();
      });
      btns.appendChild(reset);
    }
    if (present()) {
      const save = el('button', 'packages-install-btn gd-theme-save', 'Save / share…');
      save.type = 'button';
      save.id = 'gd-keymap-save';
      save.addEventListener('click', () => {
        window.gdMarketOpenPublishDialog({
          kind: 'keymap', payload: { bindings: overrides() }, name: src?.name || '',
          onDone: (j) => { commit(overrides(), { name: j.name, version: j.version }); render(); },
        });
      });
      btns.appendChild(save);
      const browse = el('button', 'gd-set-btn', 'Browse marketplace');
      browse.type = 'button';
      browse.id = 'gd-keymap-browse';
      browse.addEventListener('click', () => { if (typeof window.gdMarketOpen === 'function') window.gdMarketOpen({ kind: 'keymap' }); });
      btns.appendChild(browse);
    }
    row.appendChild(btns);
    _root.appendChild(row);

    if (bad.size) {
      _root.appendChild(el('div', 'packages-fork-note packages-fork-err gd-km-conflict', 'Two bindings share the same keys — the first registered wins; change one of them.'));
    }

    // table
    const table = el('table', 'packages-panel-table gd-km-table');
    const thead = el('thead');
    const hr = el('tr');
    for (const h of ['Group', 'Action', 'Keys', '']) hr.appendChild(el('th', null, h));
    thead.appendChild(hr);
    table.appendChild(thead);
    const tbody = el('tbody');
    for (const e of entries) {
      const tr = el('tr', 'gd-km-row' + (bad.has(e.id) ? ' is-conflict' : '') + (e.active ? '' : ' is-inert'));
      tr.dataset.shortcut = e.id;
      tr.appendChild(el('td', null, e.group));
      tr.appendChild(el('td', null, e.description));
      const kt = el('td', 'gd-km-keys');
      if (_recording?.id === e.id) {
        kt.appendChild(el('span', 'gd-km-recording', 'Press the keys… (Enter to keep, Esc to cancel)'));
      } else {
        kt.appendChild(keycaps(e.keys, e.leader));
        if (ov[e.id]) {
          const d = el('span', 'gd-km-default', ' (default: ' + (e.defaultLeader ? 'Space ' : '') + e.defaultKeys + ')');
          kt.appendChild(d);
        }
      }
      tr.appendChild(kt);
      const at = el('td', 'gd-km-actions');
      if (e.id !== 'help') {
        const change = el('button', 'gd-set-btn gd-km-change', _recording?.id === e.id ? 'Recording…' : 'Change');
        change.type = 'button';
        change.setAttribute('aria-label', 'Change keys for ' + e.description);
        change.addEventListener('click', () => {
          if (_recording) stopRecording();
          _recording = { id: e.id, keys: [], leader: e.leader, row: tr };
          window.addEventListener('keydown', onRecordKey, true);
          render();
          _root.querySelector('.gd-km-row[data-shortcut="' + e.id + '"] .gd-km-leader')?.focus();
        });
        at.appendChild(change);
        const leaderL = el('label', 'gd-km-leader-l');
        const leader = document.createElement('input');
        leader.type = 'checkbox';
        leader.className = 'gd-km-leader';
        leader.checked = !!e.leader;
        leader.setAttribute('aria-label', 'Behind Space for ' + e.description);
        leader.addEventListener('change', () => {
          const o = overrides();
          o[e.id] = { keys: e.keys, leader: leader.checked };
          if (leader.checked === e.defaultLeader && e.keys === e.defaultKeys) delete o[e.id];
          commit(o, src ? Object.assign({}, src) : null);
          render();
        });
        leaderL.appendChild(leader);
        leaderL.appendChild(document.createTextNode(' Space'));
        at.appendChild(leaderL);
        if (ov[e.id]) {
          const undo = el('button', 'packages-uninstall gd-km-undo', '×');
          undo.type = 'button';
          undo.title = 'Back to the default keys';
          undo.setAttribute('aria-label', 'Default keys for ' + e.description);
          undo.addEventListener('click', () => { const o = overrides(); delete o[e.id]; commit(o, src ? Object.assign({}, src) : null); render(); });
          at.appendChild(undo);
        }
      }
      tr.appendChild(at);
      tbody.appendChild(tr);
    }
    table.appendChild(tbody);
    _root.appendChild(table);
    _root.appendChild(el('p', 'gd-set-hint', 'Not listed: the canvas arrows / h j k l walk, the Explorer tree keys and Escape / Enter inside dialogs — those are fixed (docs/ACCESSIBILITY.md).'));
    if (_recording) showRecording();
  }

  let _wired = false;
  function gdRenderKeymapPane() {
    render();
    if (!_wired && typeof window.gdPrefOnChange === 'function') {
      _wired = true;
      window.gdPrefOnChange((key) => { if (key === 'keymap' && document.getElementById('gd-keymap-root')?.isConnected && !_recording) render(); });
    }
  }
  window.gdRenderKeymapPane = gdRenderKeymapPane;
  window.gdKeymapConflicts = conflicts;
})();
