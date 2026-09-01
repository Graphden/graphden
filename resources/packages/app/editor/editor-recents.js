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

function renderRecentFns() {
  const host = document.getElementById('gd-recent-fns');
  if (!host) return;
  // The current selection heads the list by construction — showing it
  // as "recent" is noise, so the trail starts at the previous stop.
  const rows = gdReadRecentFns()
    .filter((r) => r.id !== (typeof selectedFnId !== 'undefined' ? selectedFnId : null))
    .slice(0, RECENT_FNS_MAX - 1);
  const searching = !!searchFilter
    || ((typeof gdActiveSmartView === 'function') && !!gdActiveSmartView());
  host.replaceChildren();
  if (!rows.length || searching) {
    host.hidden = true;
    return;
  }
  host.hidden = false;
  const cap = document.createElement('div');
  cap.className = 'gd-recent-cap';
  cap.textContent = 'Recent';
  host.appendChild(cap);
  for (const r of rows) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'gd-recent-row';
    btn.title = r.qname;
    btn.setAttribute('aria-label', 'Back to ' + r.qname);
    btn.textContent = r.name;
    btn.addEventListener('click', () => {
      if (typeof gdNavigateToFn === 'function') gdNavigateToFn(r.id, r.qname);
    });
    host.appendChild(btn);
  }
}
