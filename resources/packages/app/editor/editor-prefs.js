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

function isCollapsedStored() { return readPref(PREFS_COLLAPSED_KEY, '0') === '1'; }
function setCollapsedStored(v) { writePref(PREFS_COLLAPSED_KEY, v ? '1' : '0'); }

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
  // Cytoscape draws to canvas — its style needs to be re-resolved against
  // the new CSS vars after the body class flips.
  if (typeof applyThemeToCytoscape === 'function') applyThemeToCytoscape();
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
    // Cytoscape needs to recompute its container size after the
    // sidebar width changes — the canvas is fixed-pixel.
    if (window.cy && typeof window.cy.resize === 'function') window.cy.resize();
  });
}

// =============================================================================
// HEADER ACTION BUTTONS (collapse + theme)
// =============================================================================

function buildPrefsButtons() {
  const mount = document.getElementById('prefs-mount');
  if (!mount) return;
  mount.innerHTML =
    '<button id="theme-toggle-btn"     class="prefs-btn" title="Toggle theme"></button>' +
    '<button id="sidebar-collapse-btn" class="prefs-btn" title="Collapse sidebar"></button>';
  document.getElementById('theme-toggle-btn').addEventListener('click', () => {
    const dark = !document.body.classList.contains('theme-dark');
    applyTheme(dark);
    setDarkStored(dark);
  });
  document.getElementById('sidebar-collapse-btn').addEventListener('click', () => {
    const collapsed = !document.body.classList.contains('sidebar-collapsed');
    applyCollapsed(collapsed);
    setCollapsedStored(collapsed);
    if (window.cy && typeof window.cy.resize === 'function') {
      // Cytoscape redraw after the width transition settles.
      setTimeout(() => window.cy.resize(), 220);
    }
  });
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
  applyCollapsed(isCollapsedStored());
}

function initPrefsLate() {
  buildPrefsButtons();
  installResizeHandle();
  // Re-apply collapsed/theme so the freshly-mounted buttons show the
  // correct icon + title.
  applyCollapsed(isCollapsedStored());
  applyTheme(isDarkStored());
}

window.initPrefsEarly = initPrefsEarly;
window.initPrefsLate  = initPrefsLate;
