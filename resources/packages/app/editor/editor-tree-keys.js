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

function onKeydown(e) {
  const root = treeEl();
  if (!root) return;
  const current = e.target.closest?.('[role="treeitem"]');
  if (!current || !root.contains(current)) return;
  // A keystroke aimed at one of the row's own buttons (rename, delete) is
  // that button's business.
  if (e.target !== current && e.target.closest('button, a[href], input')) return;

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
    case ' ':
      e.preventDefault();
      activate(current);
      break;
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

function installTreeKeys() {
  const root = treeEl();
  if (!root) return;

  // Delegated: the rows themselves are replaced constantly.
  root.addEventListener('keydown', onKeydown);
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
