// Editor Edit Modes — SEQUENCE items: add, insert, move, remove.
//
// Split out of editor-edit-modes.js (2026-09-13). `openLiteralVsRefChooser` is
// the two-way chooser a `+` opens (a literal value → `promptLiteralForAppend`
// with the type-aware form, or a fn reference → the fn picker, typed by the
// list's element type so a hiccup :children chain offers the component
// library as its Compatible section); `appendSequenceItem` /
// `postSequenceAppend` write the item (with an optional `position` for
// insert-before), `moveSequenceItem` ↑ / ↓, `removeSequenceItem` ×. All on top
// of the popover skeleton and the network helpers in editor-edit-modes.js;
// loads right after it (before the fn-level and type-level modes).

// --- free-arg binding (Phase 4) ---
//
// Click on a placeholder for a root-fn free-arg → tiny chooser:
//   - "literal" → text input → PUT value=<json>
//   - "fn-ref"  → fn-picker → PUT ref-id=<fn-id>
//
// Effective type comes from the slot row (override-fn-id wins);
// for `:fn` the chooser short-circuits straight to the picker
// since a literal `fn-id` makes no sense.

// Shared two-button "literal vs fn-ref" chooser popover — the same
// skeleton serves the free-arg binder and the sequence-append flow
// (different labels + follow-ups). Both buttons close the popover and
// hand off; the skeleton's Save is inert.
function openLiteralVsRefChooser({ anchorEl, ariaLabel, litLabel, refLabel,
                                   onLiteral, onRef, extraButtons }) {
  openInlineEditPopover({
    anchorEl,
    ariaLabel,
    noSave: true,
    makeControl(root) {
      const wrap = document.createElement('div');
      wrap.className = 'free-arg-bind-chooser';
      const mk = (label, handler) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
        btn.textContent = label;
        btn.addEventListener('click', (e) => {
          e.preventDefault();
          closeInlineEdit();
          handler();
        });
        wrap.appendChild(btn);
        return btn;
      };
      const litBtn = mk(litLabel, onLiteral);
      mk(refLabel, onRef);
      for (const b of extraButtons || []) mk(b.label, b.handler);
      root.insertBefore(wrap, root.firstChild);
      return litBtn;  // initial focus target
    },
    async doSave() { return false; }
  });
}




// --- sequence add/remove (Phase 5) ---
//
// Thin wrappers over the existing /api/sequence/append/:fn-id and
// /api/sequence/item/:item-id endpoints. The `+` button on a chain
// tail kicks off a small chooser (literal vs fn-ref) so the new
// item's binding is set in the same operation.

// `expectedType` (optional) — the type the appended item must have,
// as resolved by `appendNavType` for a nav-typed sequence (e.g. an
// `:update-in` `:path`). When it's a closed enum the literal prompt
// renders a <select>; undefined means an unconstrained append.
// `opts.position` (optional) turns the append into an INSERT — the
// new item takes that position, later items shift +1 (the backend's
// optional `:position` body field).
// `opts.elemType` (optional) — the sequence's declared element type
// (`slotRichType`'s `[:list T]` elem). It types the "Append fn-ref"
// picker, so e.g. a hiccup :children chain offers the component library
// as its Compatible section. A NEW instance of a component is made from
// there too: append the component itself, then ⋯ → Extend on its card
// puts a child in its place (extend in place, editor-edit-modes-fn.js) —
// which retired the chooser's separate "New from template…" button
// (2026-09-16): one path for "the fn this list needs does not exist yet".
// `opts.wholeSlotArg` (optional) — the synth arg of the slot itself,
// passed for an EMPTY list's first `+` only. It adds "Bind fn-ref (whole
// list)": the slot takes one fn's RESULT as the entire list — `:coll` of
// `:map` fed by a `:str-split`, the pipeline shape every fns.edn
// composes with `:coll :other-fn` and the canvas could not express at
// all (its `+` only ever appended items). Offered on an empty list only:
// once items exist the list IS the items.
async function appendSequenceItem(fnId, anchorEl, expectedType, opts) {
  if (!fnId) return;
  const position = (opts && typeof opts.position === 'number') ? opts.position : null;
  const elemType = (opts && opts.elemType !== undefined) ? opts.elemType : null;
  const wholeSlotArg = (opts && position === null) ? (opts.wholeSlotArg || null) : null;
  const verb = position === null ? 'Append' : 'Insert';
  closeInlineEdit();
  // Two-step UX mirroring free-arg binding: pick "Literal" / "Fn-ref",
  // then enter the value / pick the fn. The endpoint accepts the chosen
  // body in the same request, so we wait for the user's pick.
  openLiteralVsRefChooser({
    anchorEl: anchorEl || document.getElementById('graph-surface') || document.body,
    ariaLabel: verb + ' sequence item',
    litLabel: verb + ' literal',
    refLabel: verb + ' fn-ref',
    onLiteral: () => promptLiteralForAppend(fnId, anchorEl, expectedType, position),
    onRef: () => {
      if (typeof openFnPicker === 'function') {
        openFnPicker({
          anchorEl: anchorEl || document.body,
          excludeIds: [fnId],
          // The declared element type first (`[:list hiccup-node]` → the
          // component library); the nav-segment type is the fallback for a
          // list whose elements are not declared (`:update-in`'s :path).
          expectedType: (elemType || expectedType) || undefined,
          onPick: async (fn) => {
            const body = { ref: fn.id };
            if (position !== null) body.position = position;
            await postSequenceAppend(fnId, body);
          }
        });
      }
    },
    extraButtons: [
      ...(wholeSlotArg ? [{
        label: 'Bind fn-ref (whole list)',
        handler: () => {
          if (typeof openFnPicker !== 'function') return;
          openFnPicker({
            anchorEl: anchorEl || document.body,
            excludeIds: [fnId],
            expectedType: (typeof expectedSlotType === 'function')
              ? expectedSlotType(wholeSlotArg) : undefined,
            onPick: async (fn) => {
              if (typeof saveArgRef === 'function') await saveArgRef(wholeSlotArg, fn.id);
            }
          });
        }
      }] : [])]
  });
}

function promptLiteralForAppend(fnId, anchorEl, expectedType, position) {
  // Closed-enum target → <select> of valid values; otherwise free text.
  const enumInfo = (expectedType != null && typeof closedEnumOf === 'function')
                   ? closedEnumOf(expectedType) : null;
  openInlineEditPopover({
    anchorEl: anchorEl || document.body,
    ariaLabel: 'Enter literal value to append',
    makeControl(root) {
      if (expectedType != null && typeof formatTypeHint === 'function') {
        const hint = document.createElement('div');
        hint.className = 'arg-value-edit-hint';
        hint.textContent = 'Expected: ' + formatTypeHint(expectedType);
        root.insertBefore(hint, root.firstChild);
      }
      if (enumInfo) {
        const select = document.createElement('select');
        select.className = 'arg-value-edit-input';
        for (const m of enumInfo.members) {
          const opt = document.createElement('option');
          opt.value = m.value;
          opt.textContent = m.label;
          select.appendChild(opt);
        }
        root.insertBefore(select, root.firstChild);
        return select;
      }
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.placeholder =
        (typeof isKeywordType === 'function' && isKeywordType(expectedType))
          ? ':key-name'
          : 'JSON value (e.g. 42, "text", true)';
      root.insertBefore(input, root.firstChild);
      return input;
    },
    async doSave(control) {
      const trimmed = (control.value || '').trim();
      if (trimmed === '') return false;
      let value;
      if (typeof isKeywordType === 'function' && isKeywordType(expectedType)) {
        // Keyword-typed segment — the input names a keyword. Store it
        // colon-prefixed so the backend keeps it AS a keyword; a bare
        // string would persist as plain text.
        value = (trimmed.charAt(0) === ':') ? trimmed : ':' + trimmed;
      } else {
        try { value = JSON.parse(trimmed); }
        catch (_) { value = control.value; }
      }
      const body = { value: value };
      if (typeof position === 'number') body.position = position;
      return postSequenceAppend(fnId, body);
    }
    // No `onSaved` refresh — `postSequenceAppend` already fires the
    // lighter `loadGraphData` (index + subtree + rich-types) on success.
    // A second `initGraph` here double-pulled the full index + types +
    // value-kinds + services and re-rendered the graph for nothing (the
    // ref-append path has never done it). See `postSequenceAppend`.
  });
}

async function postSequenceAppend(fnId, body) {
  try {
    const r = await authFetch(API.api_sequence_append_fn_id(fnId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (r?.ok) {
      if (typeof gdUndoRecordSeqAppend === 'function') gdUndoRecordSeqAppend(fnId, body);
      // A ref appended on a CHILD card is drawn only while that card is
      // unfolded — unfold it before the render (see `saveArgRef`).
      if (body.ref && typeof unfoldNodeForBuild === 'function') unfoldNodeForBuild(fnId);
      // Sequence edits change binding-list-item rows, not fn structure/
      // value-kinds — the lighter `loadGraphData` (index + subtree +
      // rich-types) reflects them without the `initGraph` graph re-render.
      if (typeof loadGraphData === 'function') loadGraphData();
      return true;
    }
  } catch (_) {}
  return false;
}

// POST /api/sequence/move/:item-id with `{direction: "up"|"down"}` —
// swaps the item with its neighbour; an edge move is a server no-op.
async function moveSequenceItem(itemId, direction) {
  if (!itemId) return false;
  try {
    const r = await authFetch(API.api_sequence_move_item_id(itemId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ direction: direction })
    });
    if (r?.ok) {
      if (typeof gdUndoRecordSeqMove === 'function') gdUndoRecordSeqMove(itemId, direction);
      if (typeof loadGraphData === 'function') loadGraphData();
      return true;
    }
  } catch (_) {}
  return false;
}

async function removeSequenceItem(itemId) {
  if (!itemId) return false;
  const prev = lookups?.itemByItemId ? (lookups.itemByItemId.get(itemId) || null) : null;
  try {
    const r = await authMutate('DELETE',
                               API.api_sequence_item_item_id(itemId));
    if (r?.ok) {
      if (prev && typeof gdUndoRecordSeqRemoved === 'function') gdUndoRecordSeqRemoved(prev);
      if (typeof loadGraphData === 'function') loadGraphData();
      return true;
    }
  } catch (_) {}
  return false;
}
