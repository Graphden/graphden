// Editor Recents — the Explorer's navigation trail (extracted from
// editor-sidebar.js before it grew another surface).
//
// Deep reading is a chain of jumps (a named ref is a leaf — you re-root
// to read it); the last few selected NAMED fns render as rows above the
// tree, each navigating back via gdNavigateToFn (by id when loaded, by
// qualified name when not). localStorage-persisted like every other
// view pref. `renderRecentFns` is called by updateEntityList on every
// tree paint; the list hides while a filter or a smart view owns the
// tree.

const RECENT_FNS_KEY = 'graphden.recentFns';
const RECENT_FNS_MAX = 6;
// Pinned fns — the trail's permanent half. A recent is one selection
// from eviction; a ★ keeps it at the top until unpinned.
const PINNED_FNS_KEY = 'graphden.pinnedFns';

function gdReadPinnedFns() {
  try {
    const raw = localStorage.getItem(PINNED_FNS_KEY);
    const arr = raw ? JSON.parse(raw) : [];
    return Array.isArray(arr) ? arr : [];
  } catch (_) { return []; }
}

function gdTogglePinnedFn(entry) {
  const pins = gdReadPinnedFns();
  const without = pins.filter((p) => p.id !== entry.id);
  try {
    localStorage.setItem(PINNED_FNS_KEY,
      JSON.stringify(without.length === pins.length
        ? [entry].concat(pins)
        : without));
  } catch (_) { /* private mode */ }
  renderRecentFns();
}

function gdReadRecentFns() {
  try {
    const raw = localStorage.getItem(RECENT_FNS_KEY);
    const arr = raw ? JSON.parse(raw) : [];
    return Array.isArray(arr) ? arr : [];
  } catch (_) { return []; }
}

function gdPushRecentFn(fnId) {
  const fn = (typeof lookups !== 'undefined') ? lookups?.fnMap?.get(fnId) : null;
  // Anonymous / auto-named rows have no recognisable identity to return
  // to — the trail keeps named fns only.
  if (!fn?.name || fn.name.startsWith('_anon-')) return;
  const nsPath = (fn['namespace-id'] && lookups?.nsPathMap)
    ? (lookups.nsPathMap.get(fn['namespace-id']) || '') : '';
  const entry = { id: fnId, name: fn.name,
                  qname: nsPath ? nsPath + '.' + fn.name : fn.name };
  const rest = gdReadRecentFns().filter((r) => r.id !== fnId);
  try {
    localStorage.setItem(RECENT_FNS_KEY,
      JSON.stringify([entry].concat(rest).slice(0, RECENT_FNS_MAX)));
  } catch (_) { /* private mode — the trail just doesn't persist */ }
}

// One row: name navigates, the trailing ★/☆ pins or unpins. The pin
// control is a sibling (not nested — a button inside a button is
// invalid and unreachable), visible on hover/focus like tree actions.
function gdRecentRow(entry, pinned) {
  const row = document.createElement('div');
  row.className = 'gd-recent-line';
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'gd-recent-row';
  btn.title = entry.qname;
  btn.setAttribute('aria-label', 'Back to ' + entry.qname);
  btn.textContent = (pinned ? '★ ' : '') + entry.name;
  btn.addEventListener('click', () => {
    if (typeof gdNavigateToFn === 'function') gdNavigateToFn(entry.id, entry.qname);
  });
  const pin = document.createElement('button');
  pin.type = 'button';
  pin.className = 'gd-recent-pin';
  pin.textContent = pinned ? '×' : '☆';
  pin.title = pinned ? 'Unpin' : 'Pin — keep above the trail';
  pin.setAttribute('aria-label',
    (pinned ? 'Unpin ' : 'Pin ') + entry.qname);
  pin.addEventListener('click', (e) => {
    e.stopPropagation();
    gdTogglePinnedFn(entry);
  });
  row.appendChild(btn);
  row.appendChild(pin);
  return row;
}

function renderRecentFns() {
  const host = document.getElementById('gd-recent-fns');
  if (!host) return;
  const selected = (typeof selectedFnId !== 'undefined') ? selectedFnId : null;
  const pins = gdReadPinnedFns();
  const pinnedIds = new Set(pins.map((p) => p.id));
  // The current selection heads the list by construction — showing it
  // as "recent" is noise, so the trail starts at the previous stop.
  // Pinned fns render above and never repeat in the trail half.
  const rows = gdReadRecentFns()
    .filter((r) => r.id !== selected && !pinnedIds.has(r.id))
    .slice(0, RECENT_FNS_MAX - 1);
  const searching = !!searchFilter
    || ((typeof gdActiveSmartView === 'function') && !!gdActiveSmartView());
  host.replaceChildren();
  if ((!rows.length && !pins.length) || searching) {
    host.hidden = true;
    return;
  }
  host.hidden = false;
  const cap = document.createElement('div');
  cap.className = 'gd-recent-cap';
  cap.textContent = pins.length ? 'Pinned · Recent' : 'Recent';
  host.appendChild(cap);
  for (const p of pins) host.appendChild(gdRecentRow(p, true));
  for (const r of rows) host.appendChild(gdRecentRow(r, false));
}
