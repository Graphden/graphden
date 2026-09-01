// Editor Prefs — sidebar width, collapse state, and theme.
//
// All three preferences live in localStorage and are applied to
// `<body>` / `#side-menu` on DOMContentLoaded so the user's choice
// survives reloads. The mount also installs:
//   - a drag-handle on the sidebar's right edge (resize)
//   - a "collapse" button that hides the sidebar to a thin strip
//   - a "theme" button that toggles `body.theme-dark`
// Both action buttons sit in the menu header next to the auth lock.

const PREFS_WIDTH_KEY     = 'graphden.prefs.sidebar-width';
const PREFS_COLLAPSED_KEY = 'graphden.prefs.sidebar-collapsed';
const PREFS_THEME_KEY     = 'graphden.prefs.theme';

const SIDEBAR_MIN_WIDTH = 160;
const SIDEBAR_MAX_WIDTH = 720;
const SIDEBAR_DEFAULT_WIDTH = 280;

const SUN_SVG  = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>';
const MOON_SVG = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>';
const COLLAPSE_SVG = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>';
const EXPAND_SVG   = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>';

// =============================================================================
// PERSISTENCE HELPERS
// =============================================================================

function readPref(key, fallback) {
  try { const v = localStorage.getItem(key); return v == null ? fallback : v; }
  catch (_) { return fallback; }
}
function writePref(key, value) {
  try { localStorage.setItem(key, value); } catch (_) {}
}

function getStoredWidth() {
  const raw = readPref(PREFS_WIDTH_KEY, null);
  const n = raw == null ? SIDEBAR_DEFAULT_WIDTH : parseInt(raw, 10);
  if (!Number.isFinite(n)) return SIDEBAR_DEFAULT_WIDTH;
  return Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, n));
}
function setStoredWidth(px) { writePref(PREFS_WIDTH_KEY, String(px)); }

// Three-valued: '1' / '0' / null (never explicitly set). Knowing
// whether the user has chosen lets the auto-collapse logic kick in
// only on first visit — once they've toggled the sidebar, their
// choice survives every reload regardless of viewport.
function readStoredCollapsedRaw() { return readPref(PREFS_COLLAPSED_KEY, null); }
function setCollapsedStored(v) { writePref(PREFS_COLLAPSED_KEY, v ? '1' : '0'); }

// Reads the CSS-defined breakpoint so the JS and the @media rules
// stay in sync — the variable lives on :root in editor-styles.css.
// Falls back to 900 if the var isn't set yet (very first paint of an
// older cached stylesheet).
function getNarrowBreakpointPx() {
  try {
    const raw = getComputedStyle(document.documentElement)
      .getPropertyValue('--sidebar-narrow-breakpoint').trim();
    const n = parseInt(raw, 10);
    return Number.isFinite(n) ? n : 900;
  } catch (_) { return 900; }
}

function isViewportNarrow() {
  return window.innerWidth < getNarrowBreakpointPx();
}

// Collapse decision: explicit user pref ALWAYS wins; otherwise narrow
// viewports default to collapsed so the sidebar doesn't blanket the
// graph on iPad portrait / phone / iPad split-view.
function decideCollapsed() {
  const stored = readStoredCollapsedRaw();
  if (stored === '1') return true;
  if (stored === '0') return false;
  return isViewportNarrow();
}

function isDarkStored() { return readPref(PREFS_THEME_KEY, 'light') === 'dark'; }
function setDarkStored(v) { writePref(PREFS_THEME_KEY, v ? 'dark' : 'light'); }

// =============================================================================
// APPLICATION
// =============================================================================

function applyWidth(px) {
  document.documentElement.style.setProperty('--sidebar-width', px + 'px');
}

function applyCollapsed(collapsed) {
  document.body.classList.toggle('sidebar-collapsed', collapsed);
  const btn = document.getElementById('sidebar-collapse-btn');
  if (btn) {
    btn.innerHTML = collapsed ? EXPAND_SVG : COLLAPSE_SVG;
    btn.title = collapsed ? 'Expand sidebar' : 'Collapse sidebar';
  }
}

function applyTheme(dark) {
  document.body.classList.toggle('theme-dark', dark);
  const btn = document.getElementById('theme-toggle-btn');
  if (btn) {
    btn.innerHTML = dark ? SUN_SVG : MOON_SVG;
    btn.title = dark ? 'Switch to light theme' : 'Switch to dark theme';
  }
  // Nothing else to do: edges are SVG (`stroke: var(--fg)`) and cards are HTML,
  // so both re-resolve their tokens when the body class flips. The canvas
  // stylesheet used to have to be rebuilt by hand here.
}

// =============================================================================
// RESIZE HANDLE
// =============================================================================

function installResizeHandle() {
  const sidebar = document.getElementById('side-menu');
  if (!sidebar) return;
  const handle = document.createElement('div');
  handle.id = 'sidebar-resizer';
  handle.title = 'Drag to resize';
  sidebar.appendChild(handle);
  let dragging = false;
  let startX = 0;
  let startW = 0;
  handle.addEventListener('mousedown', (e) => {
    if (document.body.classList.contains('sidebar-collapsed')) return;
    dragging = true;
    startX = e.clientX;
    startW = sidebar.getBoundingClientRect().width;
    document.body.classList.add('resizing-sidebar');
    e.preventDefault();
  });
  window.addEventListener('mousemove', (e) => {
    if (!dragging) return;
    const w = Math.max(SIDEBAR_MIN_WIDTH,
              Math.min(SIDEBAR_MAX_WIDTH, startW + (e.clientX - startX)));
    applyWidth(w);
  });
  window.addEventListener('mouseup', () => {
    if (!dragging) return;
    dragging = false;
    document.body.classList.remove('resizing-sidebar');
    const finalW = sidebar.getBoundingClientRect().width;
    setStoredWidth(Math.round(finalW));
    // Nothing to resize: the graph surface is anchored to the
    // full-viewport graph-container, not the shrinking sidebar.
  });
}

// =============================================================================
// HEADER ACTION BUTTONS (collapse + theme)
// =============================================================================

// Toggles the sidebar collapsed state. With overlay layout the sidebar
// slides via CSS `transform: translateX(...)` over a graph-container
// that never reflows — the graph stays completely idle, edges don't
// recompute, and no pan compensation is needed. The whole animation
// is GPU-composited.
function toggleCollapsed(targetCollapsed) {
  if (document.body.classList.contains('sidebar-collapsed') === targetCollapsed) return;
  applyCollapsed(targetCollapsed);
  setCollapsedStored(targetCollapsed);
}

// The top-bar quick-actions cluster (theme + reload) was REMOVED: both were
// duplicates of Settings controls — theme is Settings → Appearance, "Reload
// (drop cache)" is Settings → About → "Reload editor" (both now call
// applyTheme/setDarkStored/hardReload directly). Dropping the cluster declutters
// the context bar (which had already lost the sidebar toggle). Kept as a no-op
// so the boot call site stays stable; the #prefs-mount div is gone from fns.edn.
function buildPrefsButtons() { /* intentionally empty — see comment above */ }

// One toggle for the Explorer, wired to BOTH affordances: the chevron in the
// Explorer header (visible while open) and the left-edge tab (visible while
// collapsed). Exposed globally so the server-rendered header chevron
// (fns.edn) can call it inline.
function gdToggleSidebar() {
  toggleCollapsed(!document.body.classList.contains('sidebar-collapsed'));
}
window.gdToggleSidebar = gdToggleSidebar;

// Drop in-page caches (Cache API entries from any service worker) and
// reload with a cache-busting query param so the browser can't serve a
// stale disk-cached page. Mimics Ctrl+Shift+R.
async function hardReload() {
  if (typeof window.caches !== 'undefined') {
    try {
      const keys = await window.caches.keys();
      await Promise.all(keys.map(k => window.caches.delete(k)));
    } catch (_) { /* not fatal */ }
  }
  const url = new URL(window.location.href);
  url.searchParams.set('_r', String(Date.now()));
  window.location.replace(url.toString());
}

// The Explorer's EXPAND affordance for the collapsed state: a slim tab at the
// left edge of the canvas. Lives outside #side-menu so it isn't clipped when
// the sidebar shrinks to 0. CSS shows it ONLY on the Build surface while
// collapsed (`body[data-surface="build"].sidebar-collapsed`) — the Explorer
// doesn't exist on Operate/Settings/Workspaces, so neither does its toggle.
function installFloatingExpandBtn() {
  if (document.getElementById('sidebar-expand-floating')) return;
  const btn = document.createElement('button');
  btn.id = 'sidebar-expand-floating';
  btn.className = 'sidebar-expand-floating';
  btn.title = 'Show the function browser';
  btn.setAttribute('aria-label', 'Show the function browser');
  btn.innerHTML = EXPAND_SVG;
  btn.addEventListener('click', () => toggleCollapsed(false));
  document.body.appendChild(btn);
  // Branch badge on the tab (non-default branch only) — the branch module
  // may have rendered its chip before this tab existed.
  if (typeof window.gdSyncEdgeBranchBadge === 'function') window.gdSyncEdgeBranchBadge();
}

// =============================================================================
// EARLY-BOOT: applied as soon as `<body>` exists, BEFORE DOMContentLoaded
// =============================================================================
//
// Width and theme need to apply BEFORE first paint to avoid a flash of
// the default styling. Theme depends on `<body>` to hang the class on,
// so we run on a `DOMContentLoaded` followup but as the very first
// thing — `editor-prefs.js` is loaded right after `editor-state.js`.

function initPrefsEarly() {
  applyWidth(getStoredWidth());
  applyTheme(isDarkStored());
  applyCollapsed(decideCollapsed());
}

function initPrefsLate() {
  buildPrefsButtons();
  installFloatingExpandBtn();
  installResizeHandle();
  // Re-apply collapsed/theme so the freshly-mounted buttons show the
  // correct icon + title.
  applyCollapsed(decideCollapsed());
  applyTheme(isDarkStored());
  installViewportWatcher();
}

// Re-apply auto-collapse decision when the viewport crosses the
// narrow breakpoint — on iPad rotate, on desktop window resize, or
// when the address bar reflows on mobile. Only fires when the user
// hasn't explicitly chosen a sidebar state; their explicit choice
// always wins.
function installViewportWatcher() {
  let last = isViewportNarrow();
  let raf = 0;
  window.addEventListener('resize', () => {
    if (raf) return;
    raf = requestAnimationFrame(() => {
      raf = 0;
      const now = isViewportNarrow();
      if (now === last) return;
      last = now;
      // Only auto-apply when there's no explicit user pref. Once a
      // user has tapped collapse/expand, their pref persists.
      if (readStoredCollapsedRaw() != null) return;
      applyCollapsed(now);
    });
  });
}

// =============================================================================
// LAST-USED NAMESPACE — the extend/create default
// =============================================================================
//
// Extending a PACKAGE fn used to drop the child into the package's own
// namespace (add-10 landing in core.arithmetic) — polluting a module the
// user doesn't own and hiding the child from their workspace scope. The
// extend popover now defaults to the user's last-used namespace when the
// parent isn't theirs; this pair is that memory.

const PREFS_LAST_NS_KEY = 'graphden.lastNs';

// nsId may be null — "(root)" is a legitimate last choice.
function gdRememberLastNs(nsId) {
  writePref(PREFS_LAST_NS_KEY, nsId == null || nsId === '' ? '(root)' : String(nsId));
}

// → nsId string | null (root) | undefined (never set, or the remembered
// ns no longer exists — deleted, or another deployment's id).
function gdLastUsedNs() {
  const raw = readPref(PREFS_LAST_NS_KEY, null);
  if (raw == null) return undefined;
  if (raw === '(root)') return null;
  const known = (typeof graphData !== 'undefined')
    && Array.isArray(graphData?.namespaces)
    && graphData.namespaces.some((n) => n.id === raw);
  return known ? raw : undefined;
}

window.initPrefsEarly = initPrefsEarly;
window.initPrefsLate  = initPrefsLate;
