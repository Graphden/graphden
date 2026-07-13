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
const RELOAD_SVG = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>';

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

function buildPrefsButtons() {
  const mount = document.getElementById('prefs-mount');
  const actions = document.querySelector('.menu-header-actions');
  if (!mount || !actions) return;
  // Theme + hard-reload sit in #prefs-mount (leftmost). The collapse
  // button is appended to the actions row directly, AFTER #auth-mount,
  // so the visual order is: theme | reload | lock | collapse.
  mount.innerHTML =
    '<button id="theme-toggle-btn" class="prefs-btn" title="Toggle theme"></button>'
    + '<button id="hard-reload-btn"  class="prefs-btn" title="Reload (drop cache)">' + RELOAD_SVG + '</button>';
  const collapseBtn = document.createElement('button');
  collapseBtn.id = 'sidebar-collapse-btn';
  collapseBtn.className = 'prefs-btn';
  collapseBtn.title = 'Collapse sidebar';
  actions.appendChild(collapseBtn);
  document.getElementById('theme-toggle-btn').addEventListener('click', () => {
    const dark = !document.body.classList.contains('theme-dark');
    applyTheme(dark);
    setDarkStored(dark);
  });
  document.getElementById('hard-reload-btn').addEventListener('click', hardReload);
  collapseBtn.addEventListener('click', () => toggleCollapsed(true));
}

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

// Floating expand button shown only when the sidebar is collapsed. Lives
// outside #side-menu so it isn't clipped when the sidebar shrinks to 0.
function installFloatingExpandBtn() {
  if (document.getElementById('sidebar-expand-floating')) return;
  const btn = document.createElement('button');
  btn.id = 'sidebar-expand-floating';
  btn.className = 'sidebar-expand-floating';
  btn.title = 'Expand sidebar';
  btn.innerHTML = EXPAND_SVG;
  btn.addEventListener('click', () => toggleCollapsed(false));
  document.body.appendChild(btn);
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

window.initPrefsEarly = initPrefsEarly;
window.initPrefsLate  = initPrefsLate;
