// Editor Smart Views — saved VIRTUAL groupings of the namespace tree.
//
// A namespace is one physical home; a working set often isn't ("every
// fn that talks HTTP under the hood", "everything built on :render-
// hiccup"). A smart view is a named rule the server evaluates over the
// whole graph (`?scope=view` — `uses:` reverse transitive closure /
// `effect:` footprint / `name:` substring, AND-combined), rendered
// through the same force-expanded tree pipeline search uses — so
// membership is graph-computed, overlapping, and never something the
// user maintains by hand.
//
// Views are personal view-state like workspace roots and lenses:
// localStorage, never written to the graph.
//
// Globals consumed: updateEntityList, graphData, installPopoverDismiss,
// focusIntoDialog, API, authFetch (optional), gdToast (optional).

const SMART_VIEWS_KEY = 'graphden.smartViews';

let _activeSmartView = null;   // {name, rule} | null
let _smartViewResults = null;  // array | null (loading)
let _smartViewsPopEl = null;
let _smartViewsPopAnchor = null;

function gdReadSmartViews() {
  try {
    const raw = localStorage.getItem(SMART_VIEWS_KEY);
    const arr = raw ? JSON.parse(raw) : [];
    return Array.isArray(arr) ? arr : [];
  } catch (_) { return []; }
}

function gdWriteSmartViews(views) {
  try { localStorage.setItem(SMART_VIEWS_KEY, JSON.stringify(views)); }
  catch (_) { /* private mode */ }
}

function gdActiveSmartView() { return _activeSmartView; }
function gdSmartViewResults() { return _smartViewResults; }

function gdApplySmartView(view) {
  _activeSmartView = view;
  _smartViewResults = null;
  _syncSmartViewsBtn();
  if (typeof updateEntityList === 'function') updateEntityList(graphData);
  const doFetch = (typeof authFetch === 'function') ? authFetch : fetch;
  doFetch(API.api_graph_entities + '?scope=view&q=' + encodeURIComponent(view.rule))
    .then((r) => (r.ok ? r.json() : null))
    .then((d) => {
      // A newer apply/clear won the race — drop this response.
      if (_activeSmartView !== view) return;
      _smartViewResults = d?.fns || [];
      if (d?.['truncated?'] && typeof gdToast === 'function') {
        gdToast('View "' + view.name + '" is truncated — narrow its rule');
      }
      // The whole tree just changed under a screen reader with no focus
      // move — say so (ACCESSIBILITY.md: state change → gdAnnounce).
      if (typeof window.gdAnnounce === 'function') {
        window.gdAnnounce('View ' + view.name + ' — '
          + _smartViewResults.length + ' functions');
      }
      if (typeof updateEntityList === 'function') updateEntityList(graphData);
    })
    .catch(() => {
      if (_activeSmartView !== view) return;
      _smartViewResults = [];
      if (typeof updateEntityList === 'function') updateEntityList(graphData);
    });
}

function gdClearSmartView() {
  _activeSmartView = null;
  _smartViewResults = null;
  _syncSmartViewsBtn();
  if (typeof window.gdAnnounce === 'function') {
    window.gdAnnounce('Whole tree');
  }
  if (typeof updateEntityList === 'function') updateEntityList(graphData);
}

function _syncSmartViewsBtn() {
  const btn = document.getElementById('gd-views-btn');
  if (!btn) return;
  const label = btn.querySelector('.kind-label');
  btn.classList.toggle('gd-views-active', !!_activeSmartView);
  btn.setAttribute('aria-pressed', _activeSmartView ? 'true' : 'false');
  if (label) label.textContent = _activeSmartView ? _activeSmartView.name : 'views';
}

// ---------------------------------------------------------------------------
// The popover: saved views (apply / delete) + the new-view mini-form.
// ---------------------------------------------------------------------------

function gdSmartViewsPopVisible() { return !!_smartViewsPopEl; }

function gdCloseSmartViewsPop() {
  if (_smartViewsPopEl) {
    const hadFocus = _smartViewsPopEl.contains(document.activeElement);
    _smartViewsPopEl.remove();
    _smartViewsPopEl = null;
    if (hadFocus && typeof returnFocusTo === 'function') {
      returnFocusTo(_smartViewsPopAnchor);
    }
  }
}

function gdOpenSmartViewsPop(anchorEl) {
  if (_smartViewsPopEl) { gdCloseSmartViewsPop(); return; }
  const el = document.createElement('div');
  el.className = 'gd-views-pop';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'false');
  el.setAttribute('aria-label', 'Smart views');
  _renderSmartViewsPop(el);
  document.body.appendChild(el);
  _smartViewsPopEl = el;
  _smartViewsPopAnchor = anchorEl || null;
  if (typeof anchorBelowClamped === 'function' && anchorEl) {
    anchorBelowClamped(el, anchorEl);
  }
  if (typeof focusIntoDialog === 'function') focusIntoDialog(el);
}

function _renderSmartViewsPop(el) {
  el.replaceChildren();
  const head = document.createElement('div');
  head.className = 'gd-views-pop-head';
  head.textContent = 'Smart views';
  el.appendChild(head);
  const hint = document.createElement('div');
  hint.className = 'gd-views-pop-hint';
  hint.textContent = 'A saved rule the graph answers: uses:<fn> · effect:<kind> · name:<text> · ns:<path> · unused:true. Rules AND-combine.';
  el.appendChild(hint);

  const views = gdReadSmartViews();
  const list = document.createElement('div');
  list.className = 'gd-views-list';
  if (_activeSmartView) {
    const clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'gd-views-row gd-views-row-clear';
    clear.textContent = '× Show the whole tree';
    clear.addEventListener('click', () => { gdClearSmartView(); gdCloseSmartViewsPop(); });
    list.appendChild(clear);
  }
  for (const v of views) {
    const row = document.createElement('div');
    row.className = 'gd-views-row';
    const apply = document.createElement('button');
    apply.type = 'button';
    apply.className = 'gd-views-apply';
    apply.setAttribute('aria-label', 'Apply view ' + v.name);
    const active = _activeSmartView && _activeSmartView.name === v.name;
    apply.textContent = (active ? '● ' : '') + v.name;
    apply.title = v.rule;
    apply.addEventListener('click', () => {
      gdApplySmartView(v);
      gdCloseSmartViewsPop();
    });
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'gd-views-del';
    del.textContent = '×';
    del.setAttribute('aria-label', 'Delete view ' + v.name);
    del.addEventListener('click', () => {
      gdWriteSmartViews(gdReadSmartViews().filter((x) => x.name !== v.name));
      if (_activeSmartView && _activeSmartView.name === v.name) gdClearSmartView();
      _renderSmartViewsPop(el);
    });
    row.appendChild(apply);
    row.appendChild(del);
    list.appendChild(row);
  }
  if (!views.length) {
    const empty = document.createElement('div');
    empty.className = 'gd-views-empty';
    empty.textContent = 'No views yet.';
    list.appendChild(empty);
  }
  el.appendChild(list);

  // New-view mini-form: name + rule.
  const form = document.createElement('div');
  form.className = 'gd-views-form';
  const nameIn = document.createElement('input');
  nameIn.type = 'text';
  nameIn.placeholder = 'View name';
  nameIn.className = 'gd-views-input';
  nameIn.setAttribute('aria-label', 'New view name');
  const ruleIn = document.createElement('input');
  ruleIn.type = 'text';
  ruleIn.placeholder = 'uses:core.web.http-get effect:io';
  ruleIn.className = 'gd-views-input';
  ruleIn.setAttribute('aria-label', 'New view rule');
  const save = document.createElement('button');
  save.type = 'button';
  save.className = 'gd-views-save';
  save.textContent = 'Save view';
  save.addEventListener('click', () => {
    const name = nameIn.value.trim();
    const rule = ruleIn.value.trim();
    if (!name || !rule) return;
    const views2 = gdReadSmartViews().filter((x) => x.name !== name);
    views2.unshift({ name, rule });
    gdWriteSmartViews(views2);
    gdApplySmartView({ name, rule });
    gdCloseSmartViewsPop();
  });
  form.appendChild(nameIn);
  form.appendChild(ruleIn);
  form.appendChild(save);
  el.appendChild(form);
}

installPopoverDismiss({
  getEl: () => _smartViewsPopEl,
  getAnchor: () => _smartViewsPopAnchor,
  isVisible: gdSmartViewsPopVisible,
  onDismiss: gdCloseSmartViewsPop,
  trapFocus: true,
  getReturnFocus: () => _smartViewsPopAnchor,
});

window.gdOpenSmartViewsPop = gdOpenSmartViewsPop;
window.gdActiveSmartView = gdActiveSmartView;
window.gdSmartViewResults = gdSmartViewResults;
window.gdClearSmartView = gdClearSmartView;
window.gdApplySmartView = gdApplySmartView;
