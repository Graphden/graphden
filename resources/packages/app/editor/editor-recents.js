// Explorer navigation trail: persistence/navigation belong to the host;
// ordering, pin transitions, visibility, markup and styles are ordinary graphs.
const RECENT_FNS_KEY = 'graphden.recentFns';
const PINNED_FNS_KEY = 'graphden.pinnedFns';
let gdRecentsRuntime = null;
let gdRecentsMount = null;
let gdRecentsHost = null;

function gdReadFnTrail(key) {
  try {
    const raw = localStorage.getItem(key);
    const entries = raw ? JSON.parse(raw) : [];
    if (!Array.isArray(entries)) return [];
    const seen = new Set();
    return entries.filter(entry => {
      if (!entry || ['id', 'name', 'qname'].some(key => typeof entry[key] !== 'string' || !entry[key] || entry[key].length > 4096) || seen.has(entry.id)) return false;
      seen.add(entry.id);
      return true;
    });
  } catch (_) { return []; }
}
function gdReadPinnedFns() { return gdReadFnTrail(PINNED_FNS_KEY); }
function gdReadRecentFns() { return gdReadFnTrail(RECENT_FNS_KEY); }
function gdRecentsValue(value) {
  const api = window.GraphdenBrowser;
  return Array.isArray(value) ? value.map(gdRecentsValue)
    : value && typeof value === 'object'
      ? new Map(Object.entries(value).map(([key, item]) => [api.keyword(key), gdRecentsValue(item)])) : value;
}
function gdRecentsField(value, key) { return value.get(window.GraphdenBrowser.keyword(key)); }
function gdRecentsNative(value) {
  const api = window.GraphdenBrowser;
  if (value instanceof Map) return Object.fromEntries([...value].map(([key, item]) => [key.name, gdRecentsNative(item)]));
  if (Array.isArray(value) || api.isSequence(value)) return [...value].map(gdRecentsNative);
  return value;
}
function gdRecentsState() {
  return gdRecentsRuntime.run('initial', {context: gdRecentsValue({pins: gdReadPinnedFns(), trail: gdReadRecentFns()})});
}
function gdRecentsWrite(kind, entry, key, field) {
  const state = gdRecentsRuntime.run('update', {state: gdRecentsState(), event: gdRecentsValue({kind, entry})});
  try { localStorage.setItem(key, JSON.stringify(gdRecentsNative(gdRecentsField(state, field)))); } catch (_) { /* private mode */ }
}
function gdTogglePinnedFn(entry) {
  if (!gdRecentsRuntime) return;
  gdRecentsWrite('toggle-pin', entry, PINNED_FNS_KEY, 'pins');
  renderRecentFns();
}
function gdPushRecentFn(fnId) {
  if (!gdRecentsRuntime) gdLoadRecents();
  const fn = (typeof lookups !== 'undefined') ? lookups?.fnMap?.get(fnId) : null;
  if (!fn) return;
  const path = fn['namespace-id'] ? lookups?.nsPathMap?.get(fn['namespace-id']) || '' : '';
  const entry = {id: fnId, name: fn.name || '', qname: path ? path + '.' + fn.name : fn.name || ''};
  gdRecentsWrite('push', entry, RECENT_FNS_KEY, 'trail');
}
function gdDisposeRecents() {
  gdRecentsHost?.removeEventListener('click', gdRecentsClick);
  gdRecentsMount?.dispose();
  gdRecentsMount = null;
  gdRecentsHost = null;
}
function gdRecentsClick(event) {
  const button = event.target.closest?.('button[data-action]');
  if (!button || !gdRecentsHost?.contains(button)) return;
  if (button.dataset.action === 'toggle-pin') {
    event.stopPropagation();
    gdTogglePinnedFn({id: button.dataset.fnId, name: button.dataset.name, qname: button.dataset.qname});
  } else if (button.dataset.action === 'navigate' && typeof gdNavigateToFn === 'function') {
    gdNavigateToFn(button.dataset.fnId, button.dataset.qname);
  }
}
function gdLoadRecents() {
  if (!gdRecentsRuntime) gdRecentsRuntime = window.GraphdenBrowser.createRuntime(window.GraphdenBuiltinPlans.plans.recents, {operationLimit: 150000});
  return gdRecentsRuntime;
}
function renderRecentFns() {
  const host = document.getElementById('gd-recent-fns');
  if (host !== gdRecentsHost) gdDisposeRecents();
  if (!host) return;
  if (!gdRecentsRuntime) gdLoadRecents();
  if (!gdRecentsMount) {
    gdRecentsHost = host;
    gdRecentsMount = window.GraphdenRenderer.mount(host);
    host.addEventListener('click', gdRecentsClick);
  }
  const selected = (typeof selectedFnId !== 'undefined') ? selectedFnId : null;
  const searching = !!searchFilter || (typeof gdFiltersActive === 'function' && gdFiltersActive());
  const output = gdRecentsRuntime.run('view', {state: gdRecentsState(), context: gdRecentsValue({selected, searching})});
  host.hidden = gdRecentsField(output, 'hidden');
  gdRecentsMount.render(gdRecentsField(output, 'tree'), gdRecentsField(output, 'styles'));
}
window.addEventListener('pagehide', gdDisposeRecents);
