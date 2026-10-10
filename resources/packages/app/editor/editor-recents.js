// Graph state survives DOM remounts. Persistence/navigation are fixed host effects.
const RECENT_FNS_KEY = 'graphden.recentFns';
const PINNED_FNS_KEY = 'graphden.pinnedFns';
const GD_RECENTS_UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
let gdRecentsController = null;
let gdRecentsMount = null;
let gdRecentsHost = null;
let gdRecentsSnapshot = null;
let gdRecentsLoadGeneration = 0;
let gdRecentsPending = null;
function gdRecentsText(value) { return typeof value === 'string' && value.length > 0 && value.length <= 4096 && ![...value].some(char => char.charCodeAt(0) < 32); }
function gdReadFnTrail(key) {
  try {
    const entries = JSON.parse(localStorage.getItem(key) || '[]');
    if (!Array.isArray(entries)) return [];
    const seen = new Set();
    const result = [];
    for (const entry of entries) {
      if (!entry || !GD_RECENTS_UUID.test(entry.id || '') || !gdRecentsText(entry.name) || !gdRecentsText(entry.qname) || seen.has(entry.id)) continue;
      seen.add(entry.id);
      result.push({id: entry.id, name: entry.name, qname: entry.qname});
      if (result.length >= (key === RECENT_FNS_KEY ? 6 : 1000)) break;
    }
    return result;
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
function gdRecentsField(value, key) { return value instanceof Map ? value.get(window.GraphdenBrowser.keyword(key)) : undefined; }
function gdRecentsFields(value, names) {
  const api = window.GraphdenBrowser;
  if (!(value instanceof Map) || value.size !== names.length || names.some(name => !value.has(api.keyword(name)))) throw new Error('Invalid recents record');
}
function gdRecentsEntries(value, limit) {
  const api = window.GraphdenBrowser;
  if (!Array.isArray(value) && !api.isSequence(value)) throw new Error('Invalid recents entries');
  const entries = [];
  const seen = new Set();
  for (const entry of value) {
    gdRecentsFields(entry, ['id', 'name', 'qname']);
    const id = gdRecentsField(entry, 'id');
    const name = gdRecentsField(entry, 'name');
    const qname = gdRecentsField(entry, 'qname');
    if (entries.length >= limit) throw new Error('Recent functions limit exceeded.');
    if (!GD_RECENTS_UUID.test(id || '') || !gdRecentsText(name) || !gdRecentsText(qname) || seen.has(id)) throw new Error('Invalid recents identity');
    seen.add(id);
    entries.push({id, name, qname});
  }
  return entries;
}
function gdRecentsStateValid(state) {
  gdRecentsFields(state, ['pins', 'trail']);
  gdRecentsEntries(gdRecentsField(state, 'pins'), 1000);
  gdRecentsEntries(gdRecentsField(state, 'trail'), 6);
}
function gdRecentsStorage() { return {pins: gdReadPinnedFns(), trail: gdReadRecentFns()}; }
function gdRecentsInputs() {
  const selected = typeof selectedFnId !== 'undefined' ? selectedFnId : null;
  const searching = (typeof searchFilter !== 'undefined' && !!searchFilter) || (typeof gdFiltersActive === 'function' && gdFiltersActive());
  return gdRecentsValue({...gdRecentsStorage(), selected, searching});
}
function gdRecentsPersist(key, entries) {
  try { localStorage.setItem(key, JSON.stringify(entries)); }
  catch (_) { throw new Error('Recent functions changed, but could not be saved.'); }
  finally { gdRecentsSnapshot = JSON.stringify(gdRecentsStorage()); }
}
function gdRecentsBuiltin() { return window.GraphdenBrowser.createRuntime(window.GraphdenBuiltinPlans.plans.recents, {operationLimit: 1000000}); }
async function gdReloadRecents() {
  if (!gdRecentsController || typeof window.gdLoadUIComponentRuntime !== 'function') return;
  const controller = gdRecentsController;
  const own = ++gdRecentsLoadGeneration;
  gdRecentsPending?.abort();
  const loading = new AbortController();
  gdRecentsPending = loading;
  try {
    const candidate = await window.gdLoadUIComponentRuntime('recents', window.GraphdenBuiltinPlans.plans.recents, {operationLimit: 1000000}, loading.signal);
    if (loading.signal.aborted || own !== gdRecentsLoadGeneration || controller !== gdRecentsController) return;
    controller.replaceRuntime(candidate);
  } catch (error) {
    if (loading.signal.aborted || own !== gdRecentsLoadGeneration || controller !== gdRecentsController || error.name === 'AbortError') return;
    window.gdUIComponentFailed?.('recents');
    controller.replaceRuntime(gdRecentsBuiltin());
  } finally { if (gdRecentsPending === loading) gdRecentsPending = null; }
}
function gdLoadRecents() {
  if (gdRecentsController) return gdRecentsController;
  const persist = (kind, key, limit) => ({
    validate(request) {
      gdRecentsFields(request, ['kind', 'entries']);
      if (gdRecentsField(request, 'kind') !== kind) throw new Error('Invalid recents persistence request');
      return gdRecentsEntries(gdRecentsField(request, 'entries'), limit);
    },
    execute(entries) { gdRecentsPersist(key, entries); },
  });
  gdRecentsController = window.GraphdenComponent.create(gdRecentsBuiltin(), {
    inputs: gdRecentsInputs, validateState: gdRecentsStateValid, rendererOptions: {nodeLimit: 10000},
    validateView(output) {
      gdRecentsFields(output, ['tree', 'styles', 'hidden']);
      if (typeof gdRecentsField(output, 'hidden') !== 'boolean') throw new Error('Invalid recents visibility');
    },
    requests: {
      'persist-trail': persist('persist-trail', RECENT_FNS_KEY, 6),
      'persist-pins': persist('persist-pins', PINNED_FNS_KEY, 1000),
      'navigate-fn': {
        validate(request) {
          gdRecentsFields(request, ['kind', 'fn-id', 'qname']);
          const id = gdRecentsField(request, 'fn-id');
          const qname = gdRecentsField(request, 'qname');
          if (!GD_RECENTS_UUID.test(id || '') || !gdRecentsText(qname)) throw new Error('Invalid recents navigation');
          return {id, qname};
        },
        execute({id, qname}) { if (typeof gdNavigateToFn === 'function') gdNavigateToFn(id, qname); },
      },
    },
  });
  gdRecentsSnapshot = JSON.stringify(gdRecentsStorage());
  Promise.resolve().then(() => { if (gdRecentsController) void gdReloadRecents(); });
  return gdRecentsController;
}
function gdRecentsSync(controller) {
  const snapshot = JSON.stringify(gdRecentsStorage());
  if (snapshot !== gdRecentsSnapshot) {
    controller.dispatch(gdRecentsValue({kind: 'sync', entry: {id: '', name: '', qname: ''}}));
    gdRecentsSnapshot = snapshot;
  }
}
function gdRecentsEvent(kind, entry = {id: '', name: '', qname: ''}) {
  const controller = gdLoadRecents();
  try {
    gdRecentsSync(controller);
    controller.dispatch(gdRecentsValue({kind, entry}));
  } catch (error) {
    if (typeof gdToast === 'function') gdToast(error.message);
    else console.warn('Recent functions:', error.message);
  }
}
function gdTogglePinnedFn(entry) { gdRecentsEvent('toggle-pin', entry); }
function gdPushRecentFn(fnId) {
  const fn = typeof lookups !== 'undefined' ? lookups?.fnMap?.get(fnId) : null;
  if (!fn) return;
  const path = fn['namespace-id'] ? lookups?.nsPathMap?.get(fn['namespace-id']) || '' : '';
  gdRecentsEvent('push', {id: fnId, name: fn.name || '', qname: path ? path + '.' + fn.name : fn.name || ''});
}
function gdDisposeRecents() {
  gdRecentsController?.setRender(null);
  gdRecentsHost?.removeEventListener('click', gdRecentsClick);
  gdRecentsMount?.dispose();
  gdRecentsMount = null;
  gdRecentsHost = null;
}
function gdRecentsClick(event) {
  const button = event.target.closest?.('button[data-action]');
  if (!button || !gdRecentsHost?.contains(button)) return;
  const entry = {id: button.dataset.fnId, name: button.dataset.name || '', qname: button.dataset.qname};
  if (button.dataset.action === 'toggle-pin') { event.stopPropagation(); gdRecentsEvent('toggle-pin', entry); }
  else if (button.dataset.action === 'navigate') gdRecentsEvent('navigate', entry);
}
function renderRecentFns() {
  const controller = gdLoadRecents();
  gdRecentsSync(controller);
  const host = document.getElementById('gd-recent-fns');
  if (host !== gdRecentsHost) gdDisposeRecents();
  if (!host) return;
  if (!gdRecentsMount) {
    gdRecentsHost = host;
    gdRecentsMount = window.GraphdenRenderer.mount(host, {nodeLimit: 10000});
    host.addEventListener('click', gdRecentsClick);
    controller.setRender(output => {
      gdRecentsMount.render(gdRecentsField(output, 'tree'), gdRecentsField(output, 'styles'));
      host.hidden = gdRecentsField(output, 'hidden');
    });
  }
  controller.refresh();
}
window.gdRecentsGraph = {
  reload: gdReloadRecents,
  replaceRuntime(candidate) { gdLoadRecents().replaceRuntime(candidate); },
  dispose() {
    gdRecentsLoadGeneration++;
    gdRecentsPending?.abort();
    gdRecentsPending = null;
    gdDisposeRecents();
    gdRecentsController?.dispose();
    gdRecentsController = null;
    gdRecentsSnapshot = null;
  },
};
window.addEventListener('pagehide', () => window.gdRecentsGraph.dispose());
window.addEventListener('storage', event => { if (event.key === RECENT_FNS_KEY || event.key === PINNED_FNS_KEY) renderRecentFns(); });
