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
  const n = raw == null ? SIDEBAR_DEFAULT_WIDTH : Number.parseInt(raw, 10);
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
    const n = Number.parseInt(raw, 10);
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
  // the mirrored theme, before first paint (the server copy reconciles later)
  gdPrefApply('theme');
}

function initPrefsLate() {
  buildPrefsButtons();
  installFloatingExpandBtn();
  installResizeHandle();
  // Re-apply collapsed/theme so the freshly-mounted buttons show the
  // correct icon + title.
  applyCollapsed(decideCollapsed());
  applyTheme(isDarkStored());
  gdPrefApply('theme');
  gdPrefApply('keymap');
  installViewportWatcher();
  // the per-user server copy (theme + keymap) — wins over the mirror
  gdPrefsRefresh();
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


// =============================================================================
// THEMES + SERVER-SIDE PREFERENCES (docs/MARKETPLACE.md § Themes / Preferences)
// =============================================================================
//
// A THEME is a payload `{mode, tokens, fonts, scale}`:
//   mode   — 'light' | 'dark' (which base the tokens sit on; sets body.theme-dark)
//   tokens — {'--gd-paper': '#eff1f0', …} over the allow-listed custom
//            properties below (the same list Settings → Appearance edits)
//   fonts  — {ui, mono, body} font-family stacks
//   scale  — 70..160, the root font-size percentage (everything is rem)
// It is applied as INLINE custom properties on <body> — inline beats
// `body.theme-dark { … }`, so a theme wins in both modes and clears cleanly.
//
// The active theme (and keymap) is a PER-USER server preference
// (`/api/prefs`, the `:ui-pref` row) with a localStorage MIRROR so the very
// first paint already has it; the server copy wins once it answers.

const THEME_TOKENS = [
  // group, name, label
  ['Grounds', '--gd-paper', 'Paper'], ['Grounds', '--gd-paper-2', 'Paper (raised)'], ['Grounds', '--gd-panel', 'Panel'],
  ['Ink', '--gd-ink', 'Ink'], ['Ink', '--gd-ink-2', 'Ink 2'], ['Ink', '--gd-ink-3', 'Ink 3 (muted)'], ['Ink', '--gd-ink-4', 'Ink 4 (faint)'],
  ['Lines', '--gd-line', 'Line'], ['Lines', '--gd-line-2', 'Line 2'], ['Lines', '--gd-grid', 'Dot grid'],
  ['Accent', '--gd-flow', 'Flow (accent)'], ['Accent', '--gd-flow-ink', 'Flow ink'], ['Accent', '--gd-flow-wash', 'Flow wash'], ['Accent', '--gd-flow-2', 'Flow 2'],
  ['Bindings', '--gd-lit', 'Literal'], ['Bindings', '--gd-lit-wash', 'Literal wash'], ['Bindings', '--gd-ref', 'Reference'], ['Bindings', '--gd-ref-wash', 'Reference wash'], ['Bindings', '--gd-free', 'Free arg'], ['Bindings', '--gd-free-wash', 'Free-arg wash'],
  ['Status', '--gd-ok', 'OK'], ['Status', '--gd-warn', 'Warning'], ['Status', '--gd-crit', 'Critical'], ['Status', '--gd-crit-wash', 'Critical wash'],
  ['Canvas', '--bg', 'Canvas background'], ['Canvas', '--fg', 'Canvas text'], ['Canvas', '--muted-fg', 'Canvas muted text'], ['Canvas', '--border', 'Canvas border'], ['Canvas', '--accent', 'Canvas accent'],
  ['Canvas', '--card-bg', 'Card background'], ['Canvas', '--card-fg', 'Card text'], ['Canvas', '--card-border', 'Card border'], ['Canvas', '--card-header-bg', 'Card header'], ['Canvas', '--card-header-fg', 'Card header text'],
  ['Canvas', '--hover-bg', 'Hover'], ['Canvas', '--selected-bg', 'Selected'], ['Canvas', '--sidebar-bg', 'Explorer background'], ['Canvas', '--header-bg', 'Top bar'], ['Canvas', '--header-fg', 'Top bar text'],
];
const THEME_TOKEN_NAMES = new Set(THEME_TOKENS.map((t) => t[1]));
const THEME_FONT_VARS = { ui: '--gd-ui-font', mono: '--gd-mono', body: '--gd-body-font' };
const THEME_SCALE_MIN = 70;
const THEME_SCALE_MAX = 160;

// A colour is a hex / rgb() / hsl() literal — nothing that could reach the
// network (`url(…)`) or another declaration. A shared theme is data from
// another user, so the allow-list is the boundary.
const THEME_COLOR_RE = /^(#[0-9a-fA-F]{3,8}|(rgb|rgba|hsl|hsla)\([0-9.,%\s/]+\)|transparent)$/;
const THEME_FONT_RE = /^[A-Za-z0-9 ,'"-]{1,160}$/;

function sanitizeThemePayload(p) {
  if (!p || typeof p !== 'object') return null;
  const out = { mode: p.mode === 'dark' ? 'dark' : 'light', tokens: {}, fonts: {}, scale: 100 };
  const tokens = (p.tokens && typeof p.tokens === 'object') ? p.tokens : {};
  for (const [k, v] of Object.entries(tokens)) {
    if (THEME_TOKEN_NAMES.has(k) && typeof v === 'string' && THEME_COLOR_RE.test(v.trim())) out.tokens[k] = v.trim();
  }
  const fonts = (p.fonts && typeof p.fonts === 'object') ? p.fonts : {};
  for (const k of Object.keys(THEME_FONT_VARS)) {
    const v = fonts[k];
    if (typeof v === 'string' && v.trim() && THEME_FONT_RE.test(v.trim())) out.fonts[k] = v.trim();
  }
  const sc = Number(p.scale);
  out.scale = Number.isFinite(sc) ? Math.max(THEME_SCALE_MIN, Math.min(THEME_SCALE_MAX, Math.round(sc))) : 100;
  return out;
}

let _activeThemePayload = null;

// Apply a theme payload (null = the built-in look: clear every inline token).
function gdApplyThemePayload(payload) {
  const body = document.body;
  if (!body) return;
  const clean = sanitizeThemePayload(payload);
  // clear what the previous theme set
  for (const name of THEME_TOKEN_NAMES) body.style.removeProperty(name);
  for (const v of Object.values(THEME_FONT_VARS)) body.style.removeProperty(v);
  body.style.removeProperty('--mono');
  document.documentElement.style.removeProperty('font-size');
  _activeThemePayload = clean;
  if (!clean) { body.classList.toggle('gd-custom-theme', false); return; }
  applyTheme(clean.mode === 'dark');
  for (const [k, v] of Object.entries(clean.tokens)) body.style.setProperty(k, v);
  for (const [k, v] of Object.entries(clean.fonts)) {
    body.style.setProperty(THEME_FONT_VARS[k], v);
    if (k === 'mono') body.style.setProperty('--mono', v);
  }
  if (clean.scale !== 100) document.documentElement.style.fontSize = clean.scale + '%';
  body.classList.toggle('gd-custom-theme', true);
}

function gdActiveThemePayload() { return _activeThemePayload; }

// The effective value of a token right now (inline theme or the stylesheet's
// light/dark value) — what the theme editor starts from.
function gdThemeTokenValue(name) {
  try { return getComputedStyle(document.body).getPropertyValue(name).trim(); } catch (_) { return ''; }
}

// ---- the preference store ----------------------------------------------
const PREFS_MIRROR_KEY = 'graphden.prefs.server';

function readPrefsMirror() {
  try { const raw = localStorage.getItem(PREFS_MIRROR_KEY); return raw ? JSON.parse(raw) : {}; }
  catch (_) { return {}; }
}
function writePrefsMirror(map) {
  try { localStorage.setItem(PREFS_MIRROR_KEY, JSON.stringify(map || {})); } catch (_) {}
}

let _prefs = readPrefsMirror();
const _prefListeners = new Set();

function gdPrefRead(key) { return _prefs?.[key] ?? null; }

// Apply a preference to the running editor (theme → tokens; keymap → the
// shortcut registry). Idempotent — safe to call on every refresh.
function gdPrefApply(key) {
  const v = gdPrefRead(key);
  if (key === 'theme') gdApplyThemePayload(v?.payload || null);
  if (key === 'keymap' && typeof window.gdApplyKeymap === 'function') window.gdApplyKeymap(v?.payload?.bindings || null);
}

function gdPrefNotify(key) {
  for (const fn of _prefListeners) { try { fn(key, gdPrefRead(key)); } catch (_) {} }
}

// Write a preference: apply now, mirror locally, persist server-side.
async function gdPrefWrite(key, value) {
  _prefs = Object.assign({}, _prefs, { [key]: value });
  writePrefsMirror(_prefs);
  gdPrefApply(key);
  gdPrefNotify(key);
  const api = window.API;
  if (!api || typeof api.api_prefs_key !== 'function') return false;
  try {
    const f = window.authFetch || fetch;
    const r = await f(api.api_prefs_key(key), {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ value }),
    });
    return r.ok;
  } catch (_) { return false; }
}

// Pull the server's map (per-principal) and apply what changed.
async function gdPrefsRefresh() {
  const api = window.API;
  if (!api || typeof api.api_prefs !== 'string') return;
  try {
    const f = window.authFetch || fetch;
    const r = await f(api.api_prefs);
    if (!r.ok) return;
    const map = await r.json();
    if (!map || typeof map !== 'object') return;
    _prefs = map;
    writePrefsMirror(_prefs);
    gdPrefApply('theme');
    gdPrefApply('keymap');
    gdPrefNotify('theme');
    gdPrefNotify('keymap');
  } catch (_) { /* offline / signed out — the mirror stands */ }
}

function gdPrefOnChange(fn) { _prefListeners.add(fn); return () => _prefListeners.delete(fn); }

window.gdThemeTokens = THEME_TOKENS;
window.gdThemeFontVars = THEME_FONT_VARS;
window.gdThemeScaleRange = [THEME_SCALE_MIN, THEME_SCALE_MAX];
window.gdSanitizeThemePayload = sanitizeThemePayload;
window.gdApplyThemePayload = gdApplyThemePayload;
window.gdActiveThemePayload = gdActiveThemePayload;
window.gdThemeTokenValue = gdThemeTokenValue;
window.gdPrefRead = gdPrefRead;
window.gdPrefWrite = gdPrefWrite;
window.gdPrefApply = gdPrefApply;
window.gdPrefsRefresh = gdPrefsRefresh;
window.gdPrefOnChange = gdPrefOnChange;

window.initPrefsEarly = initPrefsEarly;
window.initPrefsLate  = initPrefsLate;
