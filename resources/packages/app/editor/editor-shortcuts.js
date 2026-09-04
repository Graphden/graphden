// Editor Shortcuts — the one place a keyboard binding is declared.
//
// Before this, 32 independent `keydown` listeners lived across 25 modules and
// the only cross-module protocol was "whoever handles Escape calls
// preventDefault so the tour can tell a dismissal from a quit". Nothing owned
// the key space, nothing could list what was bound, and not a single
// modifier combination or leader key existed — so the whole space was free.
//
// The model is a leader key, as in Spacemacs: Space opens a menu of what is
// available, and each further key walks deeper into it. That buys three
// things at once for the price of one mechanism:
//   - discoverability: you press Space and the editor tells you the options,
//     rather than requiring you to have read a manual;
//   - self-documentation: `?` renders the cheatsheet FROM the registry, so it
//     cannot drift from what is actually bound;
//   - screen-reader access: the menu is a real listbox of named commands, so
//     the same mechanism that helps sighted users learn the keys gives a
//     blind user a command surface for the whole editor.
//
// Interop rules with the pre-existing listeners (both load-bearing):
//   1. This dispatcher listens on WINDOW — the last stop in the bubble path,
//      after every document-level handler — and ignores any event already
//      marked `defaultPrevented`. A dialog that closed on Escape has consumed
//      the key; we must not also act on it.
//   2. Single-key bindings are suppressed while the user is typing. A guard
//      on the focused element, not on a flag, so it cannot get out of sync.

// ── Registry ────────────────────────────────────────────────────────────────

// { id, keys, leader, when, run, description, group }
//   keys   — 'x' | 'g f' (a sequence)
//   leader — true: reachable only after Space. false: a bare key, live
//            whenever the user is not typing. Bare keys are scarce and get
//            spent deliberately — the canvas navigation in a later change
//            wants the letter keys, so everything that can live behind the
//            leader does.
//   when   — optional predicate; the binding is inert when it returns false
//   group  — heading in the leader menu and the cheatsheet
const _shortcuts = [];

const LEADER = ' ';
const CHEATSHEET_KEY = '?';

/**
 * Declare a keyboard binding. Later registrations of the same id replace the
 * earlier one, so a module can re-register without accumulating duplicates.
 */
function registerShortcut(spec) {
  if (!spec?.keys || typeof spec.run !== 'function') return;
  const existing = _shortcuts.findIndex((s) => s.id === spec.id);
  const entry = {
    id: spec.id,
    keys: String(spec.keys).trim(),
    leader: spec.leader !== false,
    when: spec.when || null,
    run: spec.run,
    description: spec.description || spec.id,
    group: spec.group || 'Other',
  };
  if (existing >= 0) _shortcuts[existing] = entry;
  else _shortcuts.push(entry);
}

/** Bindings currently applicable, given each one's `when` predicate. */
function activeShortcuts() {
  return _shortcuts.filter((s) => {
    if (!s.when) return true;
    try { return !!s.when(); } catch (_) { return false; }
  });
}

/** Bindings grouped for display, in registration order within each group. */
function shortcutGroups() {
  const groups = new Map();
  for (const s of activeShortcuts()) {
    if (!groups.has(s.group)) groups.set(s.group, []);
    groups.get(s.group).push(s);
  }
  return groups;
}

// ── Typing guard ────────────────────────────────────────────────────────────

const TEXT_ENTRY = new Set(['INPUT', 'TEXTAREA', 'SELECT']);

/**
 * True when a bare letter belongs to whatever the user is typing into rather
 * than to us. Deliberately reads the live focus instead of a mode flag: a
 * flag can be left set when a field is removed mid-edit, focus cannot.
 */
function isTyping(el) {
  const target = el || document.activeElement;
  if (!target) return false;
  if (TEXT_ENTRY.has(target.tagName)) return true;
  if (target.isContentEditable) return true;
  // CodeMirror and other editors put focus on a div with a textarea inside.
  return !!target.closest?.('.CodeMirror, .cm-editor, [contenteditable="true"]');
}

/**
 * Space activates a focused button or link — it must keep doing that, so the
 * leader only claims Space when focus is not on something Space already means
 * something to.
 */
function spaceIsActivation(el) {
  const target = el || document.activeElement;
  if (!target?.tagName) return false;
  if (target.tagName === 'BUTTON') return true;
  if (target.tagName === 'A' && target.hasAttribute('href')) return true;
  const role = target.getAttribute?.('role');
  return role === 'button' || role === 'checkbox' || role === 'tab' || role === 'option';
}

// ── Sequence state ──────────────────────────────────────────────────────────

let _pending = [];        // keys typed since the leader
let _leaderOpen = false;  // the which-key menu is showing

function resetSequence() {
  _pending = [];
  _leaderOpen = false;
  hideWhichKey();
}

/** Bindings whose key sequence starts with the keys typed so far. */
function candidatesFor(prefix) {
  const leaderOnly = activeShortcuts().filter((s) => s.leader);
  if (prefix.length === 0) return leaderOnly;
  const p = prefix.join(' ');
  return leaderOnly.filter((s) => s.keys === p || s.keys.startsWith(p + ' '));
}

function exactMatch(prefix) {
  const p = prefix.join(' ');
  return activeShortcuts().find((s) => s.leader && s.keys === p) || null;
}

// ── Dispatch ────────────────────────────────────────────────────────────────

function handleKey(e) {
  // Rule 1: someone already consumed this key (a dialog closing on Escape).
  if (e.defaultPrevented) return;
  if (e.metaKey || e.ctrlKey || e.altKey) return;

  const key = e.key;

  if (_leaderOpen) {
    if (key === 'Escape') { resetSequence(); e.preventDefault(); return; }
    if (key === 'Shift' || key === 'Control' || key === 'Alt' || key === 'Meta') return;
    const next = _pending.concat([key]);
    const hit = exactMatch(next);
    if (hit) {
      resetSequence();
      e.preventDefault();
      runShortcut(hit);
      return;
    }
    if (candidatesFor(next).length > 0) {
      _pending = next;
      showWhichKey(_pending);
      e.preventDefault();
      return;
    }
    // Dead end — leave the menu rather than swallowing further keys.
    resetSequence();
    e.preventDefault();
    return;
  }

  if (isTyping()) return;

  if (key === LEADER && !spaceIsActivation()) {
    _leaderOpen = true;
    _pending = [];
    showWhichKey(_pending);
    e.preventDefault();
    return;
  }

  if (key === CHEATSHEET_KEY) {
    e.preventDefault();
    openCheatsheet();
    return;
  }

  // Bare keys — only the few registered with `leader: false`.
  const direct = activeShortcuts().find((s) => !s.leader && s.keys === key);
  if (direct) {
    e.preventDefault();
    runShortcut(direct);
  }
}

function runShortcut(s) {
  try {
    s.run();
  } catch (err) {
    console.error('shortcut ' + s.id + ' failed', err);
  }
}

// ── which-key menu ──────────────────────────────────────────────────────────
//
// A live list of what the next keypress can be. It is also the screen-reader
// surface for the editor's commands, so it is a real listbox with named
// options rather than a decorative hint strip.

let _whichKeyEl = null;

function ensureWhichKeyEl() {
  if (_whichKeyEl) return _whichKeyEl;
  const el = document.createElement('div');
  el.id = 'gd-which-key';
  el.className = 'gd-which-key';
  el.setAttribute('role', 'listbox');
  el.setAttribute('aria-label', 'Keyboard commands');
  el.setAttribute('tabindex', '-1');
  document.body.appendChild(el);
  _whichKeyEl = el;
  return el;
}

function keyCap(text) {
  const kbd = document.createElement('kbd');
  kbd.className = 'gd-key-cap';
  kbd.textContent = text === ' ' ? 'Space' : text;
  return kbd;
}

function showWhichKey(prefix) {
  const el = ensureWhichKeyEl();
  el.replaceChildren();

  const head = document.createElement('div');
  head.className = 'gd-which-key-head';
  head.appendChild(document.createTextNode(prefix.length ? 'Space ' + prefix.join(' ') + ' …' : 'Space …'));
  el.appendChild(head);

  const rows = document.createElement('div');
  rows.className = 'gd-which-key-rows';
  // What each next key does. For a sequence still in progress, show the key
  // that comes next, not the whole binding.
  const seen = new Set();
  for (const s of candidatesFor(prefix)) {
    const rest = s.keys.split(' ').slice(prefix.length);
    if (rest.length === 0) continue;
    const nextKey = rest[0];
    if (seen.has(nextKey)) continue;
    seen.add(nextKey);
    const leaf = rest.length === 1;

    const row = document.createElement('div');
    row.className = 'gd-which-key-row';
    row.setAttribute('role', 'option');
    row.setAttribute('aria-selected', 'false');
    // The accessible name carries both halves; the visual row splits them.
    row.setAttribute('aria-label', (nextKey === ' ' ? 'Space' : nextKey) + ': '
                     + (leaf ? s.description : s.group + ' commands'));
    row.appendChild(keyCap(nextKey));
    const label = document.createElement('span');
    label.className = 'gd-which-key-label';
    label.textContent = leaf ? s.description : s.group + '…';
    if (!leaf) label.classList.add('is-group');
    row.appendChild(label);
    rows.appendChild(row);
  }
  el.appendChild(rows);

  const foot = document.createElement('div');
  foot.className = 'gd-which-key-foot';
  // Mention the bare keys here: they are not reachable through the leader,
  // so the menu is the only place a user would learn they exist.
  foot.textContent = 'Esc cancel · / search · +/− zoom · ? all shortcuts';
  el.appendChild(foot);

  el.classList.add('visible');
  // Focus the menu so a screen reader reads it; the dispatcher keeps
  // handling keys from the document either way.
  if (typeof focusSafely === 'function') focusSafely(el);
}

function hideWhichKey() {
  if (_whichKeyEl) _whichKeyEl.classList.remove('visible');
}

// ── Cheatsheet ──────────────────────────────────────────────────────────────

let _cheatsheetEl = null;

function ensureCheatsheetEl() {
  if (_cheatsheetEl) return _cheatsheetEl;
  const el = document.createElement('div');
  el.id = 'gd-cheatsheet';
  el.className = 'gd-cheatsheet';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-label', 'Keyboard shortcuts');
  document.body.appendChild(el);
  _cheatsheetEl = el;
  if (typeof installTabTrap === 'function') {
    installTabTrap({
      getEl: () => _cheatsheetEl,
      isVisible: () => !!_cheatsheetEl && _cheatsheetEl.classList.contains('visible'),
    });
  }
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || !el.classList.contains('visible')) return;
    e.preventDefault();   // consumed — see graphden-popover.js
    closeCheatsheet();
  });
  return el;
}

let _cheatsheetTrigger = null;

function openCheatsheet() {
  const el = ensureCheatsheetEl();
  _cheatsheetTrigger = document.activeElement;
  el.replaceChildren();

  const head = document.createElement('div');
  head.className = 'gd-cheatsheet-head';
  const title = document.createElement('h2');
  title.textContent = 'Keyboard shortcuts';
  head.appendChild(title);
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'gd-cheatsheet-close';
  close.setAttribute('aria-label', 'Close');
  close.textContent = '×';
  close.addEventListener('click', closeCheatsheet);
  head.appendChild(close);
  el.appendChild(head);

  // Generated from the registry: a hand-maintained list would be wrong the
  // first time someone added a binding without updating it.
  for (const [group, entries] of shortcutGroups()) {
    const section = document.createElement('section');
    section.className = 'gd-cheatsheet-group';
    const h = document.createElement('h3');
    h.textContent = group;
    section.appendChild(h);
    const dl = document.createElement('dl');
    for (const s of entries) {
      const dt = document.createElement('dt');
      // Draw the leader cap from the binding's OWN flag, not from whether
      // its key string happens to contain a space: `Space b` is one key
      // behind the leader, and printing it as a bare `b` documents a
      // keystroke that does nothing.
      if (s.leader) dt.appendChild(keyCap('Space'));
      for (const p of s.keys.split(' ')) dt.appendChild(keyCap(p));
      const dd = document.createElement('dd');
      dd.textContent = s.description;
      dl.appendChild(dt);
      dl.appendChild(dd);
    }
    section.appendChild(dl);
    el.appendChild(section);
  }

  el.classList.add('visible');
  if (typeof setSiblingsInert === 'function') setSiblingsInert(el, true);
  if (typeof focusIntoDialog === 'function') focusIntoDialog(el);
}

function closeCheatsheet() {
  if (!_cheatsheetEl) return;
  const hadFocus = _cheatsheetEl.contains(document.activeElement);
  _cheatsheetEl.classList.remove('visible');
  if (typeof setSiblingsInert === 'function') setSiblingsInert(_cheatsheetEl, false);
  if (hadFocus && typeof returnFocusTo === 'function') returnFocusTo(_cheatsheetTrigger);
  _cheatsheetTrigger = null;
}

// ── Built-in bindings ───────────────────────────────────────────────────────
//
// Deliberately small. The canvas navigation keys register themselves from
// the module that owns the canvas, so the registry stays the only list.

function clickIfPresent(selector) {
  const el = document.querySelector(selector);
  if (el) el.click();
  return !!el;
}

function registerBuiltinShortcuts() {
  // Bare keys — spent only where the convention is already universal.
  registerShortcut({
    id: 'search', keys: '/', leader: false, group: 'Navigate',
    description: 'Search functions',
    run: () => {
      const input = document.getElementById('search-input');
      if (input) focusSafely(input);
    },
  });
  registerShortcut({
    id: 'zoom-in', keys: '+', leader: false, group: 'Graph',
    description: 'Zoom in',
    run: () => navZoom(1),
    when: () => typeof navZoom === 'function',
  });
  registerShortcut({
    id: 'zoom-out', keys: '-', leader: false, group: 'Graph',
    description: 'Zoom out',
    run: () => navZoom(-1),
    when: () => typeof navZoom === 'function',
  });

  // Everything else lives behind Space, where it is discoverable and where
  // it leaves the letter keys free for canvas navigation.
  registerShortcut({
    id: 'graph-fit', keys: 'g f', group: 'Graph',
    description: 'Fit the graph in view',
    run: () => navResetZoom(),
    when: () => typeof navResetZoom === 'function',
  });
  registerShortcut({
    id: 'graph-root', keys: 'g r', group: 'Graph',
    description: 'Go to the root node',
    run: () => navGoToRoot(),
    when: () => typeof navGoToRoot === 'function',
  });
  registerShortcut({
    id: 'graph-reset-positions', keys: 'g p', group: 'Graph',
    description: 'Reset node positions',
    run: () => navResetPositions(),
    when: () => typeof navResetPositions === 'function',
  });
  registerShortcut({
    id: 'branches', keys: 'b', group: 'Workspace',
    description: 'Branches',
    run: () => clickIfPresent('#branch-chip-btn'),
    when: () => !!document.getElementById('branch-chip-btn'),
  });
  registerShortcut({
    id: 'sidebar', keys: 'e', group: 'Workspace',
    description: 'Explorer (show / hide)',
    run: () => clickIfPresent('#sidebar-collapse-btn, #sidebar-expand-floating'),
  });
  registerShortcut({
    id: 'inspector-runs', keys: 'r', group: 'Function',
    description: 'Runs tab',
    run: () => clickIfPresent('[data-insp-tab="stats"]'),
    when: () => !!document.querySelector('[data-insp-tab="stats"]'),
  });
  registerShortcut({
    id: 'inspector-overview', keys: 'o', group: 'Function',
    description: 'Overview tab',
    run: () => clickIfPresent('[data-insp-tab="overview"]'),
    when: () => !!document.querySelector('[data-insp-tab="overview"]'),
  });
  registerShortcut({
    id: 'diagnostics-tests', keys: 't', group: 'Diagnostics',
    description: 'Tests',
    run: () => clickIfPresent('#gd-diag-nav button[data-section="tests"], [data-diag-tab="tests"], .gd-diag-tab[data-tab="tests"]'),
  });
  // The problem LENSES — the Explorer focus that replaced the Failed runs /
  // Type errors / Lint drawer tabs.
  registerShortcut({
    id: 'lens-failed', keys: 'x', group: 'Diagnostics',
    description: 'Failed-runs lens',
    run: () => clickIfPresent('#kind-filters .kind-toggle[data-kind="failed"]'),
  });
  registerShortcut({
    id: 'lens-type-errors', keys: 'y', group: 'Diagnostics',
    description: 'Type-errors lens',
    run: () => clickIfPresent('#kind-filters .kind-toggle[data-kind="type-errors"]'),
  });
  registerShortcut({
    id: 'lens-lint', keys: 'w', group: 'Diagnostics',
    description: 'Lint lens',
    run: () => clickIfPresent('#kind-filters .kind-toggle[data-kind="lint"]'),
  });
  // Surfaces — the management destinations otherwise reachable only through
  // the account chip's menu (and, for Build, the brand button). Registered
  // here so they show up in the Space menu and the `?` cheatsheet; Escape on
  // a surface also returns to Build (editor-shell.js).
  const surfaces = () => typeof window.gdShellSurface === 'function';
  registerShortcut({
    id: 'surface-build', keys: 'v b', group: 'Surfaces',
    description: 'Back to Build (the editor)',
    run: () => window.gdShellSurface('build'),
    when: surfaces,
  });
  registerShortcut({
    id: 'surface-settings', keys: 'v s', group: 'Surfaces',
    description: 'Settings',
    run: () => window.gdShellSurface('settings'),
    when: surfaces,
  });
  registerShortcut({
    id: 'surface-organization', keys: 'v o', group: 'Surfaces',
    description: 'Organization',
    run: () => window.gdShellSurface('operate'),
    when: surfaces,
  });
  registerShortcut({
    id: 'surface-platform', keys: 'v p', group: 'Surfaces',
    description: 'Platform',
    run: () => window.gdShellSurface('platform'),
    // Same gate as the menu row: the body class stamped by the capability
    // fetch, or a live platform-admin capability probe.
    when: () => surfaces()
      && (document.body.classList.contains('gd-platform')
          || ((typeof window.graphdenHasCap === 'function') && window.graphdenHasCap('platform-admin'))),
  });
  registerShortcut({
    id: 'help', keys: '?', group: 'Help',
    description: 'All keyboard shortcuts',
    run: openCheatsheet,
  });
}


// ── Install ─────────────────────────────────────────────────────────────────

function initShortcuts() {
  registerBuiltinShortcuts();
  // On WINDOW, not document. Both are bubble-phase, but a document listener
  // only runs after the ones registered before it — and this module loads
  // early, so it would have run BEFORE the dialogs' own document handlers
  // and seen defaultPrevented still false. window is the last stop in the
  // bubble path, so every other handler has already had the key and marked
  // it if they consumed it. (Caught by edit-shortcuts.test.js, which fired a
  // consumed key and watched the shortcut run anyway.)
  window.addEventListener('keydown', handleKey);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initShortcuts);
} else {
  initShortcuts();
}

window.registerShortcut = registerShortcut;
window.gdShortcutGroups = shortcutGroups;
window.gdOpenCheatsheet = openCheatsheet;
