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
// "Created foo · Undo · ×", role=status, focus untouched) and the leader
// key `Space u` (registered here; the cheatsheet and the Space menu render
// it while an entry is live). Recorders live at the write sites:
// editor-edit-modes-fn.js (extend / wrap / rename / namespace move) and
// editor-create.js (new graph / new namespace) call `gdUndoRecord`.

const GD_UNDO_WINDOW_MS = 30000;

// { label, undo: async () => {ok, error}, verify?: async () => string|null,
//   at: ms }  — newest LAST.
const _gdUndoJournal = [];
let _gdUndoToastEl = null;
let _gdUndoToastTimer = null;
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
  document.body.appendChild(el);
  _gdUndoToastEl = el;
  return el;
}

function _gdUndoShowToast(label) {
  const el = _gdUndoEnsureToast();
  el.querySelector('.gd-undo-toast-label').textContent = label;
  el.querySelector('.gd-undo-toast-btn')
    .setAttribute('aria-label', 'Undo: ' + label);
  el.classList.add('gd-undo-toast-visible');
  if (_gdUndoToastTimer) clearTimeout(_gdUndoToastTimer);
  _gdUndoToastTimer = setTimeout(_gdUndoHideToast, GD_UNDO_WINDOW_MS);
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
  try { if (typeof window !== 'undefined' && window.location) window.location.hash = ''; } catch (_) { /* ignore */ }
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

// --- keyboard ---------------------------------------------------------------

if (typeof registerShortcut === 'function') {
  registerShortcut({
    id: 'undo', keys: 'u', group: 'Edit',
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
