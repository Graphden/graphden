// Editor Undo — a 30-second "Undo" for the graph gestures a slip most often
// lands in: a fn created under the wrong parent, a mistyped name, a fn moved
// into the wrong namespace, a namespace created by accident.
//
// THE MODEL, and why it is this one (decided 2026-09-15):
//
//   * An undo is an INVERSE WRITE, never an erased one. The store is
//     append-only per entity (docs/VERSIONING.md): a rename is a new
//     fn-version row, a create is an identity row plus its first version,
//     a delete is a tombstone. Undoing a rename therefore writes the old
//     name AGAIN (one more version row), undoing a create tombstones the
//     row. Nothing is spliced out of history: the Versions tab shows the
//     slip and its reversal, an execution that ran against the slipped
//     version keeps its `fn-version-id`, a branch that already saw the row
//     is not left pointing at a hole, and tombstone GC stays the only thing
//     that ever purges. "Delete the last commit" would buy nothing the user
//     can see and would cost every one of those invariants.
//
//   * The journal is CLIENT-SIDE and per tab: the gestures THIS editor made
//     on THIS branch, most recent last. That is also the answer to "whose
//     changes?" — your own, by construction; no version row carries an
//     author, so a server-side "undo my last change" is not possible today,
//     and a branch switch is a page load, which empties the journal, so an
//     undo never crosses branches.
//
//   * Each entry is live for GD_UNDO_WINDOW_MS (30 s). The button offers a
//     quick reversal of what you JUST did, not a history browser — older
//     changes are in the Inspector's Versions tab, whose Restore already
//     exists. The window also bounds the blast radius: within 30 s nothing
//     else has been built on the slip yet, and when something has (a fn
//     someone already references), the server refuses the delete and the
//     refusal is shown, not hidden.
//
//   * No redo. The inverse of "delete the fn I just created" would be a
//     NEW create (a fresh identity, not the old row back), so a redo would
//     be a different fn under the same name — a lie dressed as symmetry.
//     Undo takes a deliberate click on a labelled button; redoing by hand
//     is the same gesture the reader just made.
//
//   * Every inverse re-checks the live state before writing (`verify`):
//     a rename is only undone while the fn still carries the name we set.
//     If the world moved on, the entry says so and steps aside.
//
// Surfaces: the `#gd-undo-toast` (bottom-centre, above the plain toast —
// "Created foo · Undo · ×", role=status, focus untouched, shown for 10 s
// and held under the pointer), the leader key `Space u` and the chord
// Mod+z (both registered here; the cheatsheet and the Space menu render
// them while an entry is live, for the whole 30 s window). Recorders live at the write sites:
// editor-edit-modes-fn.js (extend / wrap / rename / namespace move),
// editor-create.js (new graph / new namespace), editor-edit-modes.js (bind /
// change / unbind a slot) and editor-edit-modes-seq.js (append / remove /
// edit / move a list item) call the `gdUndoRecord*` builders below.

const GD_UNDO_WINDOW_MS = 30000;
// The toast shows for a fraction of the window: a dark bar sitting on the
// canvas for the whole 30 s covered the very thing the reader was working
// on (and stacked over the path panel and the tour's own toasts). The undo
// itself stays live for the full window — `Space u` / Mod+z reach it after
// the toast is gone — and the toast holds while the pointer or focus is on
// it, so a reader who is reading it never sees it vanish mid-sentence.
const GD_UNDO_TOAST_MS = 10000;

// { label, undo: async () => {ok, error}, verify?: async () => string|null,
//   at: ms }  — newest LAST.
const _gdUndoJournal = [];
let _gdUndoToastEl = null;
let _gdUndoToastTimer = null;
let _gdUndoToastLeft = 0;      // ms of display still owed when paused
let _gdUndoToastShownAt = 0;
let _gdUndoBusy = false;

function _gdUndoNow() {
  return (typeof performance !== 'undefined' && performance.now)
    ? performance.now() : Date.now();
}

// Drop entries past the window — from the front, since they are ordered.
function _gdUndoPrune() {
  const now = _gdUndoNow();
  while (_gdUndoJournal.length
         && now - _gdUndoJournal[0].at > GD_UNDO_WINDOW_MS) {
    _gdUndoJournal.shift();
  }
}

function gdUndoAvailable() {
  _gdUndoPrune();
  return !_gdUndoBusy && _gdUndoJournal.length > 0;
}

function gdUndoLastLabel() {
  _gdUndoPrune();
  const e = _gdUndoJournal[_gdUndoJournal.length - 1];
  return e ? e.label : null;
}

// Record a reversible gesture. `spec.label` names what was done ("Created
// add-10"), `spec.undo` performs the inverse and resolves to `{ok, error}`;
// optional `spec.verify` resolves to a refusal message when the live state
// no longer matches what the gesture left (null = still fine).
function gdUndoRecord(spec) {
  if (!spec || typeof spec.undo !== 'function' || !spec.label) return;
  _gdUndoPrune();
  _gdUndoJournal.push({ label: spec.label, undo: spec.undo,
                        verify: spec.verify, at: _gdUndoNow() });
  _gdUndoShowToast(spec.label);
}

// Undo the most recent live entry. Resolves to true when the inverse landed.
async function gdUndoLast() {
  _gdUndoPrune();
  if (_gdUndoBusy) return false;
  const entry = _gdUndoJournal[_gdUndoJournal.length - 1];
  if (!entry) return false;
  _gdUndoBusy = true;
  _gdUndoHideToast();
  const toast = (msg, kind) => { if (typeof gdToast === 'function') gdToast(msg, kind); };
  try {
    // The gesture's own follow-up (an extend's reload + select) may still
    // be in flight — a chord lands fast. Let it settle first, so the
    // inverse's reload is the LAST word and a stale subtree fetch cannot
    // re-add the row the inverse just removed.
    for (let i = 0; i < 50 && typeof document !== 'undefined'
         && document.body?.classList?.contains('editor-busy'); i++) {
      await new Promise((r) => setTimeout(r, 100));
    }
    if (typeof entry.verify === 'function') {
      let stale = null;
      try { stale = await entry.verify(); } catch (_) { stale = null; }
      if (stale) {
        _gdUndoJournal.pop();
        toast('Not undone — ' + stale, 'error');
        return false;
      }
    }
    let res;
    try { res = await entry.undo(); }
    catch (e) { res = { ok: false, error: e?.message || String(e) }; }
    if (res?.ok) {
      _gdUndoJournal.pop();
      toast('Undone: ' + entry.label);
      return true;
    }
    // A refused inverse keeps its entry: the reader may clear the reason
    // (delete the dependant, leave the protected branch) and try again
    // within the window.
    toast('Could not undo (' + entry.label + '): '
          + (res?.error || 'the server refused'), 'error');
    return false;
  } finally {
    _gdUndoBusy = false;
  }
}

// --- toast ------------------------------------------------------------------

function _gdUndoEnsureToast() {
  if (_gdUndoToastEl) return _gdUndoToastEl;
  const el = document.createElement('div');
  el.id = 'gd-undo-toast';
  el.className = 'gd-undo-toast';
  el.setAttribute('role', 'status');
  el.setAttribute('aria-live', 'polite');
  const label = document.createElement('span');
  label.className = 'gd-undo-toast-label';
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'gd-undo-toast-btn';
  btn.textContent = 'Undo';
  btn.addEventListener('click', () => { gdUndoLast(); });
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'gd-undo-toast-close';
  close.textContent = '×';
  close.setAttribute('aria-label', 'Dismiss');
  close.addEventListener('click', () => { _gdUndoHideToast(); });
  el.appendChild(label);
  el.appendChild(btn);
  el.appendChild(close);
  // Hold while the reader is on it (pointer or keyboard focus); the clock
  // resumes with whatever was left when they leave.
  el.addEventListener('mouseenter', _gdUndoToastPause);
  el.addEventListener('focusin', _gdUndoToastPause);
  el.addEventListener('mouseleave', _gdUndoToastResume);
  el.addEventListener('focusout', (ev) => {
    if (!el.contains(ev.relatedTarget)) _gdUndoToastResume();
  });
  document.body.appendChild(el);
  _gdUndoToastEl = el;
  return el;
}

function _gdUndoToastArm(ms) {
  if (_gdUndoToastTimer) clearTimeout(_gdUndoToastTimer);
  _gdUndoToastShownAt = Date.now();
  _gdUndoToastLeft = ms;
  _gdUndoToastTimer = setTimeout(_gdUndoHideToast, ms);
}

function _gdUndoToastPause() {
  if (!_gdUndoToastTimer) return;
  clearTimeout(_gdUndoToastTimer);
  _gdUndoToastTimer = null;
  _gdUndoToastLeft = Math.max(1000, _gdUndoToastLeft - (Date.now() - _gdUndoToastShownAt));
}

function _gdUndoToastResume() {
  if (_gdUndoToastTimer || !_gdUndoToastEl
      || !_gdUndoToastEl.classList.contains('gd-undo-toast-visible')) return;
  _gdUndoToastArm(_gdUndoToastLeft || 1000);
}

function _gdUndoShowToast(label) {
  const el = _gdUndoEnsureToast();
  el.querySelector('.gd-undo-toast-label').textContent = label;
  el.querySelector('.gd-undo-toast-btn')
    .setAttribute('aria-label', 'Undo: ' + label);
  el.classList.add('gd-undo-toast-visible');
  _gdUndoToastArm(GD_UNDO_TOAST_MS);
}

function _gdUndoHideToast() {
  if (_gdUndoToastTimer) { clearTimeout(_gdUndoToastTimer); _gdUndoToastTimer = null; }
  if (_gdUndoToastEl) _gdUndoToastEl.classList.remove('gd-undo-toast-visible');
}

// --- inverse builders shared by the write sites -----------------------------

// The create endpoints answer without an id, so a created row is found
// again by name through the search endpoint (the lexical graph holds only
// the selected subtree). Namespace-qualified when the caller knows it.
async function gdUndoFindFnId(name, nsId) {
  try {
    const r = await authFetch(API.api_graph_entities
      + '?scope=search&q=' + encodeURIComponent(name));
    const payload = await r.json();
    const hits = (payload.fns || []).filter((f) => f.name === name
      && (nsId === undefined || (f['namespace-id'] || null) === (nsId || null)));
    return hits.length ? hits[0].id : null;
  } catch (_) { return null; }
}

async function gdUndoFindNsId(name, parentId) {
  try {
    const r = await authFetch(API.api_graph_entities + '?scope=tree');
    const payload = await r.json();
    const hit = (payload.namespaces || []).find((n) => n.name === name
      && (n['parent-id'] || null) === (parentId || null));
    return hit ? hit.id : null;
  } catch (_) { return null; }
}

// `authFetch` RESOLVES on 4xx — a refusal is a Response, not a throw.
async function _gdUndoResult(response) {
  if (response && response.status >= 200 && response.status < 300) return { ok: true };
  let error = 'the server refused';
  try {
    if (typeof extractResponseError === 'function') error = await extractResponseError(response);
  } catch (_) { /* keep the generic reason */ }
  return { ok: false, error };
}

// After a create is undone the editor must not sit on the deleted fn (a
// stale card over a dead selection — the state a plain reload leaves).
// Go back where the gesture started: the parent that was extended /
// wrapped (`backTo`, its qualified name), or, for a fn made from the
// Explorer, to no selection at all — the same move ⋯ → Delete makes.
async function _gdUndoLeaveDeleted(backTo) {
  if (backTo && typeof selectFnByName === 'function') {
    if (typeof initGraph === 'function') await initGraph();
    await selectFnByName(backTo);
    return;
  }
  if (typeof gdClearSelection === 'function') gdClearSelection();
  else { try { if (typeof window !== 'undefined' && window.location) window.location.hash = ''; } catch (_) { /* ignore */ } }
  if (typeof initGraph === 'function') await initGraph();
}

// A fresh fn (extend / wrap / new graph): undo = tombstone it. The server
// refuses while something references it (409), which is the right answer.
function gdUndoRecordCreatedFn(name, nsId, backTo) {
  gdUndoRecord({
    label: 'Created ' + name,
    undo: async () => {
      const id = await gdUndoFindFnId(name, nsId);
      if (!id) return { ok: false, error: 'the fn is already gone' };
      const r = await authMutate('DELETE', API.api_entities_type_id('fn', id));
      const res = await _gdUndoResult(r);
      if (res.ok) await _gdUndoLeaveDeleted(backTo);
      return res;
    },
  });
}

function gdUndoRecordCreatedNs(name, parentId) {
  gdUndoRecord({
    label: 'Created namespace ' + name,
    undo: async () => {
      const id = await gdUndoFindNsId(name, parentId);
      if (!id) return { ok: false, error: 'the namespace is already gone' };
      const r = await authMutate('DELETE', API.api_entities_type_id('ns', id));
      const res = await _gdUndoResult(r);
      if (res.ok && typeof initGraph === 'function') await initGraph();
      return res;
    },
  });
}

// A rename: undo = write the old name again, while the fn still carries
// the new one (someone else's rename in between is theirs to keep).
function gdUndoRecordRename(fnId, oldName, newName) {
  gdUndoRecord({
    label: 'Renamed ' + oldName + ' → ' + newName,
    verify: async () => {
      const cur = (typeof lookups !== 'undefined' && lookups?.fnMap)
        ? lookups.fnMap.get(fnId) : null;
      return (cur && cur.name !== newName)
        ? 'the fn was renamed again since (now ' + cur.name + ')' : null;
    },
    undo: async () => {
      const r = await authMutate('PUT', API.api_entities_type_id('fn', fnId),
                                 { name: oldName });
      const res = await _gdUndoResult(r);
      if (res.ok && typeof initGraph === 'function') await initGraph();
      return res;
    },
  });
}

// A namespace move: undo = move back (root spelled as the bare key, since
// authMutate's field form strips empty strings).
function gdUndoRecordNsMove(fnId, fnName, oldNsId, newNsId) {
  const pathOf = (id) => (id && typeof lookups !== 'undefined' && lookups?.nsPathMap)
    ? (lookups.nsPathMap.get(id) || '?') : '(root)';
  gdUndoRecord({
    label: 'Moved ' + fnName + ' to ' + pathOf(newNsId),
    verify: async () => {
      const cur = (typeof lookups !== 'undefined' && lookups?.fnMap)
        ? lookups.fnMap.get(fnId) : null;
      return (cur && (cur['namespace-id'] || null) !== (newNsId || null))
        ? 'the fn was moved again since' : null;
    },
    undo: async () => {
      const body = oldNsId ? { 'namespace-id': oldNsId } : 'namespace-id=';
      const r = await authMutate('PUT', API.api_entities_type_id('fn', fnId), body);
      const res = await _gdUndoResult(r);
      if (res.ok && typeof initGraph === 'function') await initGraph();
      return res;
    },
  });
}

// --- bindings and sequence items --------------------------------------------
//
// The write sites (editor-edit-modes.js `writeBindingFields` /
// `deleteUseSiteBinding`, editor-edit-modes-seq.js) call these with the
// PRE-IMAGE they read from `lookups` before writing. A binding's wire form
// is what the value form sends: `value=<JSON>`, `ref-fn-id=<uuid>`; an empty
// value clears the column (the same spelling the namespace move uses).

function _gdUndoSlotLabel(slotId) {
  const s = (typeof lookups !== 'undefined' && lookups?.slotMap) ? lookups.slotMap.get(slotId) : null;
  return s?.name ? ':' + s.name : 'the slot';
}

// Bindings and items change rows below the fn, not its structure — the
// lighter `loadGraphData` (index + subtree + rich-types) reflects them.
async function _gdUndoReloadBindings() {
  if (typeof loadGraphData === 'function') await loadGraphData();
  else if (typeof initGraph === 'function') await initGraph();
}

function _gdUndoWireValue(v) {
  return (v === undefined || v === null) ? '' : JSON.stringify(v);
}

// The current binding of `(fnId, slotId)` — read fresh, falling back to a
// subtree fetch when the lexical cache does not hold the fn.
async function _gdUndoBindingOf(fnId, slotId) {
  const cached = (typeof lookups !== 'undefined' && lookups?.bindingByFnSlot)
    ? lookups.bindingByFnSlot.get(fnId + '|' + slotId) : null;
  if (cached) return cached;
  try {
    const r = await authFetch(API.api_graph_entities
      + '?scope=subtree&root-id=' + encodeURIComponent(fnId));
    const payload = await r.json();
    return (payload.bindings || []).find((b) => b['fn-id'] === fnId && b['slot-id'] === slotId) || null;
  } catch (_) { return null; }
}

// A binding write: POST (no `prev`) → undo deletes the row; PUT → undo
// writes the pre-image of every column the write touched, plus value /
// ref (switching a literal to a ref clears the other column, so both are
// restored together).
function gdUndoRecordBindingWrite(arg, fields, prev) {
  const fnId = arg?.['fn-id'];
  const slotId = arg?.['slot-id'];
  if (!fnId || !slotId || !fields) return;
  const slot = _gdUndoSlotLabel(slotId);
  if (!prev) {
    gdUndoRecord({
      label: 'Bound ' + slot,
      undo: async () => {
        const b = await _gdUndoBindingOf(fnId, slotId);
        if (!b?.id) return { ok: false, error: 'the binding is already gone' };
        const r = await authMutate('DELETE', API.api_entities_type_id('binding', b.id));
        const res = await _gdUndoResult(r);
        if (res.ok) await _gdUndoReloadBindings();
        return res;
      },
    });
    return;
  }
  const keys = new Set(Object.keys(fields));
  if (keys.has('value') || keys.has('ref-fn-id')) { keys.add('value'); keys.add('ref-fn-id'); }
  const body = [...keys].map((k) => k + '=' + encodeURIComponent(
    k === 'value' ? _gdUndoWireValue(prev.value) : (prev[k] ?? ''))).join('&');
  gdUndoRecord({
    label: 'Changed ' + slot,
    verify: async () => {
      const cur = await _gdUndoBindingOf(fnId, slotId);
      if (!cur || cur.id !== prev.id) return 'the binding was replaced since';
      if ('value' in fields && _gdUndoWireValue(cur.value) !== String(fields.value)) return 'the value changed again since';
      if ('ref-fn-id' in fields && (cur['ref-fn-id'] || '') !== String(fields['ref-fn-id'] || '')) return 'the reference changed again since';
      return null;
    },
    undo: async () => {
      const r = await authMutate('PUT', API.api_entities_type_id('binding', prev.id), body);
      const res = await _gdUndoResult(r);
      if (res.ok) await _gdUndoReloadBindings();
      return res;
    },
  });
}

// A removed binding: undo re-creates it with its value / ref.
function gdUndoRecordBindingDeleted(prev) {
  if (!prev?.['fn-id'] || !prev['slot-id']) return;
  const slot = _gdUndoSlotLabel(prev['slot-id']);
  gdUndoRecord({
    label: 'Unbound ' + slot,
    undo: async () => {
      const parts = ['fn-id=' + encodeURIComponent(prev['fn-id']),
                     'slot-id=' + encodeURIComponent(prev['slot-id'])];
      if (prev['ref-fn-id']) parts.push('ref-fn-id=' + encodeURIComponent(prev['ref-fn-id']));
      else if (prev.value !== undefined && prev.value !== null) parts.push('value=' + encodeURIComponent(_gdUndoWireValue(prev.value)));
      const r = await authMutate('POST', API.api_entities_type('binding'), parts.join('&'));
      const res = await _gdUndoResult(r);
      if (res.ok) await _gdUndoReloadBindings();
      return res;
    },
  });
}

// The items of every sequence binding of `fnId`, position-sorted.
function _gdUndoSeqItems(fnId) {
  if (typeof lookups === 'undefined' || !lookups?.bindingsByFn) return [];
  const out = [];
  for (const b of (lookups.bindingsByFn.get(fnId) || [])) {
    for (const it of (lookups.itemsByBinding?.get(b.id) || [])) out.push(it);
  }
  return out.sort((a, b) => (a.position || 0) - (b.position || 0));
}

function _gdUndoSameItem(it, body) {
  if (body.ref) return it['ref-fn-id'] === body.ref;
  return _gdUndoWireValue(it.value) === _gdUndoWireValue(body.value);
}

// An appended (or inserted) item: undo deletes the item that carries what
// was appended, at the position it went to (the newest match otherwise).
function gdUndoRecordSeqAppend(fnId, body) {
  if (!fnId || !body) return;
  gdUndoRecord({
    label: 'Appended an item',
    undo: async () => {
      const items = _gdUndoSeqItems(fnId);
      const at = (typeof body.position === 'number') ? items[body.position] : null;
      const hit = (at && _gdUndoSameItem(at, body)) ? at
        : [...items].reverse().find((it) => _gdUndoSameItem(it, body));
      if (!hit?.id) return { ok: false, error: 'the list changed since' };
      const r = await authMutate('DELETE', API.api_sequence_item_item_id(hit.id));
      const res = await _gdUndoResult(r);
      if (res.ok) await _gdUndoReloadBindings();
      return res;
    },
  });
}

// A removed item: undo appends it back at its old position.
function gdUndoRecordSeqRemoved(item) {
  if (!item) return;
  const binding = (typeof lookups !== 'undefined' && lookups?.bindingMap)
    ? lookups.bindingMap.get(item['binding-id']) : null;
  const fnId = binding?.['fn-id'];
  if (!fnId) return;
  const body = item['ref-fn-id'] ? { ref: item['ref-fn-id'] } : { value: item.value };
  if (typeof item.position === 'number') body.position = item.position;
  gdUndoRecord({
    label: 'Removed an item',
    undo: async () => {
      const r = await authFetch(API.api_sequence_append_fn_id(fnId), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body) });
      const res = await _gdUndoResult(r);
      if (res.ok) await _gdUndoReloadBindings();
      return res;
    },
  });
}

// An item's value edit: undo writes the old value back.
function gdUndoRecordSeqValue(itemId, oldValue, newValue) {
  if (!itemId) return;
  gdUndoRecord({
    label: 'Changed an item',
    verify: async () => {
      const cur = (typeof lookups !== 'undefined' && lookups?.itemByItemId) ? lookups.itemByItemId.get(itemId) : null;
      return (cur && _gdUndoWireValue(cur.value) !== _gdUndoWireValue(newValue)) ? 'the item changed again since' : null;
    },
    undo: async () => {
      const r = await authFetch(API.api_sequence_item_item_id(itemId), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: oldValue }) });
      const res = await _gdUndoResult(r);
      if (res.ok) await _gdUndoReloadBindings();
      return res;
    },
  });
}

// A move: undo moves the item back the other way.
function gdUndoRecordSeqMove(itemId, direction) {
  if (!itemId || !direction) return;
  const back = direction === 'up' ? 'down' : 'up';
  gdUndoRecord({
    label: 'Moved an item ' + direction,
    undo: async () => {
      const r = await authFetch(API.api_sequence_move_item_id(itemId), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ direction: back }) });
      const res = await _gdUndoResult(r);
      if (res.ok) await _gdUndoReloadBindings();
      return res;
    },
  });
}

// --- keyboard ---------------------------------------------------------------

if (typeof registerShortcut === 'function') {
  registerShortcut({
    id: 'undo', keys: 'u', group: 'Edit',
    description: 'Undo the last change (within 30 s)',
    when: () => gdUndoAvailable(),
    run: () => { gdUndoLast(); },
  });
  // The chord everyone reaches for first. Bare (not behind the leader), and
  // the registry leaves it to the browser inside a text field.
  registerShortcut({
    id: 'undo-chord', keys: 'Mod+z', leader: false, group: 'Edit',
    description: 'Undo the last change (within 30 s)',
    when: () => gdUndoAvailable(),
    run: () => { gdUndoLast(); },
  });
}

window.gdUndoRecord = gdUndoRecord;
window.gdUndoLast = gdUndoLast;
window.gdUndoAvailable = gdUndoAvailable;
window.gdUndoLastLabel = gdUndoLastLabel;
window.gdUndoLeaveDeleted = _gdUndoLeaveDeleted;
window.gdUndoRecordCreatedFn = gdUndoRecordCreatedFn;
window.gdUndoRecordCreatedNs = gdUndoRecordCreatedNs;
window.gdUndoRecordRename = gdUndoRecordRename;
window.gdUndoRecordNsMove = gdUndoRecordNsMove;
window.gdUndoRecordBindingWrite = gdUndoRecordBindingWrite;
window.gdUndoRecordBindingDeleted = gdUndoRecordBindingDeleted;
window.gdUndoRecordSeqAppend = gdUndoRecordSeqAppend;
window.gdUndoRecordSeqRemoved = gdUndoRecordSeqRemoved;
window.gdUndoRecordSeqValue = gdUndoRecordSeqValue;
window.gdUndoRecordSeqMove = gdUndoRecordSeqMove;
