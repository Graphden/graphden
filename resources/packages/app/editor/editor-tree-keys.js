// Explorer tree — keyboard navigation (WAI-ARIA tree pattern).
//
// The tree is the editor's primary navigation and it was mouse-only: rows
// were `div`s with an `onclick` and nothing else. This module adds the
// keyboard half without touching how the rows are built, beyond the ARIA
// attributes editor-sidebar.js now stamps on them.
//
// Two structural facts drive the design:
//
//   1. The tree renders FLAT. A namespace header and its `.ns-children` are
//      siblings, not parent and child, so "the next visible row" is simply
//      the next `[role=treeitem]` in DOM order, and the parent of a row has
//      to be found by walking backwards to a lower aria-level.
//
//   2. It is rebuilt from scratch constantly — `updateEntityList` does
//      `innerHTML = ''`, and selecting a fn calls it. A naive roving
//      tabindex would therefore lose both the tab stop and the focus on the
//      first keystroke that selects something. So the "current" row is
//      remembered as a KEY (fn id / namespace path), not an element, and
//      restored after every rebuild by a MutationObserver.
//
// Delegated listeners, for the same reason: per-row handlers would die with
// the rows.

const TREE_ID = 'entity-list';

// The row the tab stop currently sits on, remembered as a stable key so it
// survives a rebuild. `{kind: 'fn'|'ns', key: string}`.
let _activeKey = null;
// Whether focus was inside the tree just before a rebuild, so the observer
// knows whether restoring focus is wanted or would be a theft.
let _hadFocus = false;

function treeEl() {
  return document.getElementById(TREE_ID);
}

/** Every row a user can currently move to, in visual order. */
function treeItems() {
  const root = treeEl();
  if (!root) return [];
  return Array.from(root.querySelectorAll('[role="treeitem"]')).filter((el) => {
    if (el.hidden) return false;
    // Inside a collapsed `internal N` group, or any other hidden container.
    return el.offsetParent !== null;
  });
}

function keyOf(el) {
  if (!el) return null;
  if (el.dataset.fnId) return {kind: 'fn', key: el.dataset.fnId};
  // Compare-mode ghost rows have no fn row on THIS branch — their key
  // is the compared branch's fn id (decorate re-creates them wholesale,
  // so without this the focused ghost loses the tab stop on every
  // decorate pass).
  if (el.dataset.ghostFnId) return {kind: 'ghost', key: el.dataset.ghostFnId};
  if (el.dataset.nsPath) return {kind: 'ns', key: el.dataset.nsPath};
  // The root "(primitives)" pseudo-header carries neither.
  if (el.classList.contains('ns-header-pseudo')) return {kind: 'ns', key: '__root__'};
  return null;
}

function elementFor(k) {
  const root = treeEl();
  if (!root || !k) return null;
  if (k.kind === 'fn') return root.querySelector('[role="treeitem"][data-fn-id="' + k.key + '"]');
  if (k.kind === 'ghost') return root.querySelector('[role="treeitem"][data-ghost-fn-id="' + k.key + '"]');
  if (k.key === '__root__') return root.querySelector('.ns-header-pseudo');
  return root.querySelector('[role="treeitem"][data-ns-path="' + CSS.escape(k.key) + '"]');
}

/**
 * Move the single tab stop onto `el`. Exactly one row is tabbable at a time,
 * so Tab enters the tree once and then arrows take over.
 */
function setActive(el) {
  const items = treeItems();
  for (const it of items) it.setAttribute('tabindex', '-1');
  const target = el && items.includes(el) ? el : items[0];
  if (!target) return null;
  target.setAttribute('tabindex', '0');
  _activeKey = keyOf(target);
  return target;
}

function focusItem(el) {
  const target = setActive(el);
  if (!target) return;
  focusSafely(target);
  // The tree scrolls independently of the page; keep the focused row in view
  // without yanking the whole document around.
  if (typeof target.scrollIntoView === 'function') {
    window.scrollIntoViewMotionSafe(target, {block: 'nearest'});
  }
}

function levelOf(el) {
  return Number.parseInt(el.getAttribute('aria-level') || '1', 10);
}

function isExpandable(el) {
  return el.hasAttribute('aria-expanded');
}

function isExpanded(el) {
  return el.getAttribute('aria-expanded') === 'true';
}

/** The row that owns `el` — the nearest preceding row at a shallower level. */
function parentOf(el, items) {
  const idx = items.indexOf(el);
  const lvl = levelOf(el);
  for (let i = idx - 1; i >= 0; i--) {
    if (levelOf(items[i]) < lvl) return items[i];
  }
  return null;
}

// Rows are toggled/opened by their existing click handlers — this module
// drives the same behaviour rather than duplicating it, so the two paths
// cannot drift.
function activate(el) {
  el.click();
}

// A row's own controls (rename, +, publish, hide, delete, the ⚙ toggle).
// They are NOT tab stops — with four per namespace header, Tab from the
// tree never reached the canvas — so they are reached THROUGH the row:
// `.` / `m` (the canvas rows' actions key) moves onto the first, ← → walk
// them, Escape returns to the row. `stampRowControls` re-applies the
// tabindex after every rebuild, whichever module built the buttons.
function rowControls(row) {
  return Array.from(row.querySelectorAll('button, a[href]'))
    .filter((el) => !el.disabled && el.closest('[role="treeitem"]') === row);
}

function stampRowControls(root) {
  for (const el of root.querySelectorAll('[role="treeitem"] button, [role="treeitem"] a[href]')) {
    if (el.getAttribute('tabindex') !== '-1') el.setAttribute('tabindex', '-1');
  }
}

function onControlKeydown(e, row, control) {
  const controls = rowControls(row);
  const idx = controls.indexOf(control);
  switch (e.key) {
    case 'ArrowRight':
    case 'ArrowLeft': {
      e.preventDefault();
      const next = controls[e.key === 'ArrowRight' ? idx + 1 : idx - 1];
      if (next) focusSafely(next);
      break;
    }
    case 'Escape':
      e.preventDefault();
      e.stopPropagation();
      focusItem(row);
      break;
    default:
      break;
  }
}

function onKeydown(e) {
  const root = treeEl();
  if (!root) return;
  const current = e.target.closest?.('[role="treeitem"]');
  if (!current || !root.contains(current)) return;
  // A keystroke aimed at one of the row's own controls: ← → Escape move
  // between them and back to the row; anything else (Enter, Space) is the
  // control's business. An inline INPUT (rename / create) owns every key.
  if (e.target !== current && e.target.closest('button, a[href], input')) {
    const control = e.target.closest('button, a[href]');
    if (control && !e.target.closest('input')) onControlKeydown(e, current, control);
    return;
  }

  const items = treeItems();
  const idx = items.indexOf(current);
  if (idx < 0) return;

  switch (e.key) {
    case 'ArrowDown':
      e.preventDefault();
      focusItem(items[Math.min(idx + 1, items.length - 1)]);
      break;
    case 'ArrowUp':
      e.preventDefault();
      focusItem(items[Math.max(idx - 1, 0)]);
      break;
    case 'ArrowRight':
      e.preventDefault();
      if (isExpandable(current) && !isExpanded(current)) {
        activate(current);            // expand in place
      } else if (isExpandable(current)) {
        focusItem(items[Math.min(idx + 1, items.length - 1)]);  // into the children
      }
      break;
    case 'ArrowLeft':
      e.preventDefault();
      if (isExpandable(current) && isExpanded(current)) {
        activate(current);            // collapse
      } else {
        const parent = parentOf(current, items);
        if (parent) focusItem(parent);
      }
      break;
    case 'Home':
      e.preventDefault();
      focusItem(items[0]);
      break;
    case 'End':
      e.preventDefault();
      focusItem(items[items.length - 1]);
      break;
    case 'Enter':
      // Enter alone activates. Space is the LEADER (editor-shortcuts.js):
      // a tree that swallowed it left the reader with no way to open the
      // menu from a row — `Space g g` into the graph never fired.
      e.preventDefault();
      activate(current);
      break;
    case '.':
    case 'm': {
      // The row's actions — same key as a canvas row's ⋯ menu.
      const first = rowControls(current)[0];
      if (first) { e.preventDefault(); focusSafely(first); }
      break;
    }
    default:
      break;
  }
}

// ── Surviving a rebuild ─────────────────────────────────────────────────────

function rememberFocus() {
  const root = treeEl();
  _hadFocus = !!root && root.contains(document.activeElement);
}

/**
 * After any rebuild: re-place the single tab stop, and put focus back if the
 * rebuild is what took it away.
 *
 * The guard matters. `updateEntityList` runs on plenty of occasions the user
 * did not initiate from the tree (a graph reload, a branch switch); grabbing
 * focus then would drag the keyboard out of wherever they actually were.
 */
function restoreAfterRebuild() {
  const root = treeEl();
  if (!root) return;
  const items = treeItems();
  if (items.length === 0) return;

  stampRowControls(root);
  const wanted = elementFor(_activeKey);
  const tabbable = root.querySelector('[role="treeitem"][tabindex="0"]');
  if (!tabbable) setActive(wanted || items[0]);

  if (_hadFocus && !root.contains(document.activeElement)) {
    // Focus was in the tree and the rebuild dropped it on the document.
    const target = wanted || items[0];
    if (target) focusSafely(target);
  }
  _hadFocus = false;
}

// Put the keyboard on the tree: the roving row, else the first row. The
// leader's `Space j e` and the filter field's exit both land here.
function focusTree() {
  const root = treeEl();
  if (!root) return false;
  const target = root.querySelector('[role="treeitem"][tabindex="0"]') || treeItems()[0];
  if (!target) return false;
  focusItem(target);
  return true;
}
window.gdFocusTree = focusTree;

// The Explorer filter is the tree's entry field, and it was a dead end:
// while a text field has focus the bare keys (`/`, `?`, `Space`) type,
// so a reader who pressed `/` had no key that led anywhere — Escape did
// nothing and Tab walked a dozen chips. ↓ (the combobox convention) and
// Escape both move onto the tree; the typed filter stays applied, so the
// rows under the keyboard are the matches. With no rows to land on
// (nothing matched) Escape still leaves the field, so the bare keys work.
function installFilterExit() {
  const input = document.getElementById('search-input');
  if (!input) return;
  input.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' && e.key !== 'ArrowDown') return;
    if (e.key === 'ArrowDown' && !treeItems().length) return;
    e.preventDefault();
    if (!focusTree()) input.blur();
  });
}

function installTreeKeys() {
  const root = treeEl();
  if (!root) return;

  // Delegated: the rows themselves are replaced constantly.
  root.addEventListener('keydown', onKeydown);
  installFilterExit();
  // Clicking a row makes it the tab stop too, so mouse and keyboard agree on
  // where "here" is.
  root.addEventListener('focusin', (e) => {
    const item = e.target.closest?.('[role="treeitem"]');
    if (item) setActive(item);
  });
  root.addEventListener('pointerdown', (e) => {
    const item = e.target.closest?.('[role="treeitem"]');
    if (item) _activeKey = keyOf(item);
  });

  const observer = new MutationObserver(() => restoreAfterRebuild());
  observer.observe(root, {childList: true, subtree: true});

  // `rememberFocus` has to run BEFORE the DOM changes, so it hangs off the
  // events that precede a rebuild rather than off the observer.
  document.addEventListener('pointerdown', rememberFocus, true);
  document.addEventListener('keydown', rememberFocus, true);

  restoreAfterRebuild();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', installTreeKeys);
} else {
  installTreeKeys();
}
