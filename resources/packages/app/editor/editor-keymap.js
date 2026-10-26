// Settings → Keyboard: the graph owns presentation; the host captures keys and writes preferences.
(() => {
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const builtin = api.createRuntime(window.GraphdenBuiltinPlans.plans.keymap, {operationLimit: 100000});
  const record = value => new Map(Object.entries(value).map(([name, item]) => [key(name), item]));
  let host = null;
  let recording = null;
  let selected = null;
  let rejected = false;
  let wired = false;
  const recordingScope = () => JSON.stringify([window.gdPrefOwner,
    typeof graphdenCurrentOrg === 'string' ? graphdenCurrentOrg : 'public',
    typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : 'main']);

  const pref = () => window.gdPrefRead?.('keymap') || null;
  const overrides = () => ({...(pref()?.payload?.bindings || {})});
  function commit(bindings, source) {
    window.gdApplyKeymap?.(bindings);
    window.gdPrefWrite?.('keymap', {source: source || null, payload: {bindings}});
  }
  function inputs() {
    const current = pref();
    const bindings = overrides();
    return {
      entries: window.gdShortcutEntries().map(entry => record({
        id: entry.id, group: entry.group, description: entry.description,
        keys: entry.keys, 'key-parts': String(entry.keys).split(' '),
        leader: entry.leader, 'default-keys': entry.defaultKeys,
        'default-leader': entry.defaultLeader, active: entry.active,
        overridden: !!bindings[entry.id],
      })),
      'source-name': current?.source?.name || '',
      'source-version': current?.source?.version || '',
      'change-count': Object.keys(bindings).length,
      marketplace: !!window.gdMarketPresent?.(),
      'recording-id': recording?.id || '',
      'recording-keys': recording?.keys.join(' ') || '',
    };
  }
  function view(runtime, data) {
    const output = runtime.run('view', {inputs: record(data)});
    if (!(output instanceof Map) || output.size !== 1 || !output.has(key('tree'))) {
      throw new Error('Invalid keyboard graph view');
    }
    const vnode = window.GraphdenRenderer.vnode(output.get(key('tree')), 'gd-keymap', 3000);
    const scratch = document.createElement('div');
    window.GraphdenPreact.render(vnode, scratch);
    if (scratch.childElementCount !== 1) throw new Error('Invalid keyboard graph tree');
    const pane = scratch.firstElementChild;
    const rows = [...pane.querySelectorAll('.gd-km-row[data-shortcut]')];
    if (rows.length !== data.entries.length || rows.some((row, index) =>
      row.dataset.shortcut !== data.entries[index].get(key('id')))) {
      throw new Error('Invalid keyboard graph rows');
    }
    return pane;
  }
  function render() {
    host = document.getElementById('gd-keymap-root');
    if (!host || typeof window.gdShortcutEntries !== 'function') return;
    const focused = host.contains(document.activeElement) ? document.activeElement : null;
    const focusTarget = focused && {
      action: focused.dataset.action || '', shortcut: focused.dataset.shortcut || '',
      leader: focused.classList.contains('gd-km-leader'),
    };
    const data = inputs();
    if (!selected && !rejected) {
      try { selected = window.gdEditorRoot?.componentNow('keymap') || null; }
      catch (_) { rejected = true; window.gdUIComponentFailed?.('keymap'); }
    }
    let pane;
    if (selected) {
      try {
        pane = view(selected, data);
        if (!window.gdEditorRoot.acceptComponent(selected)) throw new Error('Editor root changed');
      } catch (_) {
        window.gdEditorRoot?.releaseComponent(selected);
        selected = null;
        rejected = true;
        window.gdUIComponentFailed?.('keymap');
      }
    }
    host.replaceChildren(pane || view(builtin, data));
    if (focusTarget) {
      const replacement = [...host.querySelectorAll('button[data-action], .gd-km-leader')].find(item =>
        (item.dataset.action || '') === focusTarget.action
        && (item.dataset.shortcut || '') === focusTarget.shortcut
        && item.classList.contains('gd-km-leader') === focusTarget.leader);
      replacement?.focus();
    }
    if (recording) recording.row = [...host.querySelectorAll('.gd-km-row')]
      .find(row => row.dataset.shortcut === recording.id) || null;
  }
  function stopRecording() {
    if (!recording) return;
    window.removeEventListener('keydown', onRecordKey, true);
    window.removeEventListener('pointerdown', onRecordPointer, true);
    recording = null;
    render();
  }
  function onRecordPointer(event) {
    if (recording && !recording.row?.contains(event.target)) stopRecording();
  }
  function onRecordKey(event) {
    if (!recording) return;
    if (!recording.row?.isConnected || document.body.getAttribute('data-surface') !== recording.surface
        || recording.scope !== recordingScope()) {
      stopRecording();
      return;
    }
    // Composition is owned by the IME, never by the shortcut recorder.
    if (event.isComposing || event.key === 'Process' || event.key === 'Dead') return;
    event.preventDefault();
    event.stopPropagation();
    if (event.key === 'Escape') { stopRecording(); return; }
    if (event.key === 'Enter') {
      if (recording.keys.length) {
        const bindings = overrides();
        bindings[recording.id] = {keys: recording.keys.join(' '), leader: recording.leader};
        commit(bindings, pref()?.source);
      }
      stopRecording();
      return;
    }
    if (event.key === 'Backspace') recording.keys.pop();
    else if (!['Shift', 'Control', 'Alt', 'Meta', 'Tab'].includes(event.key)
      && recording.keys.length < 3) recording.keys.push(event.key === ' ' ? 'Space' : event.key);
    const output = recording.row.querySelector('.gd-km-recording');
    if (output) output.textContent = recording.keys.length
      ? recording.keys.join(' ') + '  (Enter to keep, Esc to cancel)'
      : 'Press the keys… (Enter to keep, Esc to cancel)';
  }
  function change(id) {
    const entry = window.gdShortcutEntries().find(candidate => candidate.id === id);
    if (!entry || id === 'help') return;
    if (recording) stopRecording();
    recording = {id, keys: [], leader: entry.leader, row: null,
      surface: document.body.getAttribute('data-surface'), scope: recordingScope()};
    window.addEventListener('keydown', onRecordKey, true);
    window.addEventListener('pointerdown', onRecordPointer, true);
    render();
    recording.row?.querySelector('.gd-km-leader')?.focus();
  }
  function click(event) {
    const button = event.target.closest?.('button[data-action]');
    if (!button || !host?.contains(button)) return;
    const id = button.dataset.shortcut;
    const current = pref();
    if (button.dataset.action === 'change') change(id);
    else if (button.dataset.action === 'undo' && window.gdShortcutEntries().some(entry => entry.id === id)) {
      const bindings = overrides();
      delete bindings[id];
      commit(bindings, current?.source);
      render();
    } else if (button.dataset.action === 'reset') {
      window.gdApplyKeymap?.(null);
      window.gdPrefWrite?.('keymap', null);
      render();
    } else if (button.dataset.action === 'save' && window.gdMarketPresent?.()) {
      window.gdMarketOpenPublishDialog({kind: 'keymap', payload: {bindings: overrides()},
        name: current?.source?.name || '',
        onDone: result => { commit(overrides(), {name: result.name, version: result.version}); render(); }});
    } else if (button.dataset.action === 'browse' && window.gdMarketPresent?.()) {
      window.gdMarketOpen?.({kind: 'keymap'});
    }
  }
  function changeLeader(event) {
    const control = event.target.closest?.('.gd-km-leader[data-shortcut]');
    if (!control || !host?.contains(control)) return;
    const entry = window.gdShortcutEntries().find(candidate => candidate.id === control.dataset.shortcut);
    if (!entry || entry.id === 'help') return;
    const bindings = overrides();
    bindings[entry.id] = {keys: entry.keys, leader: control.checked};
    if (control.checked === entry.defaultLeader && entry.keys === entry.defaultKeys) delete bindings[entry.id];
    commit(bindings, pref()?.source);
    render();
  }
  function gdRenderKeymapPane() {
    const next = document.getElementById('gd-keymap-root');
    if (host !== next) {
      host?.removeEventListener('click', click);
      host?.removeEventListener('change', changeLeader);
      next?.addEventListener('click', click);
      next?.addEventListener('change', changeLeader);
    }
    render();
    if (wired) return;
    wired = true;
    window.gdPrefOnChange?.(name => {
      if (name === 'keymap' && document.getElementById('gd-keymap-root')?.isConnected && !recording) render();
    });
    window.addEventListener('gd-editor-root-changing', () => {
      selected = null;
      rejected = false;
      // A same-account graph refresh may finish between Change and Enter.
      // Keep the keyboard gesture; its row is rebound when the root is ready.
      if (recording && recording.scope !== recordingScope()) stopRecording();
    });
    window.addEventListener('gd-editor-root-ready', () => {
      if (document.getElementById('gd-keymap-root')?.isConnected) render();
    });
  }
  window.gdRenderKeymapPane = gdRenderKeymapPane;
})();
