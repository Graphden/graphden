// Editor Edit Modes — SEQUENCE items: add, insert, move, remove.
//
// Split out of editor-edit-modes.js (2026-09-13). `openLiteralVsRefChooser` is
// the two-way chooser a `+` opens (a literal value → `promptLiteralForAppend`
// with the type-aware form, or a fn reference → the fn picker; a template
// parent offers "new instance" — `promptTemplateInstanceName` +
// `createTemplateInstanceAndAppend`); `appendSequenceItem` /
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
// (`slotRichType`'s `[:list T]` elem). It types the "New from
// template…" picker so e.g. a hiccup :children chain offers the
// component library.
async function appendSequenceItem(fnId, anchorEl, expectedType, opts) {
  if (!fnId) return;
  const position = (opts && typeof opts.position === 'number') ? opts.position : null;
  const elemType = (opts && opts.elemType !== undefined) ? opts.elemType : null;
  const verb = position === null ? 'Append' : 'Insert';
  closeInlineEdit();
  // Two-step UX mirroring free-arg binding: pick "Literal" / "Fn-ref" /
  // "New from template…", then enter the value / pick the fn. The
  // endpoint accepts the chosen body in the same request, so we wait
  // for the user's pick.
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
          onPick: async (fn) => {
            const body = { ref: fn.id };
            if (position !== null) body.position = position;
            await postSequenceAppend(fnId, body);
          }
        });
      }
    },
    extraButtons: [{
      label: 'New from template…',
      handler: () => promptTemplateInstanceInsert(fnId, anchorEl,
                                                  expectedType || elemType,
                                                  position)
    }]
  });
}

// "New from template…" — pick a type-compatible fn as the PARENT of a
// fresh named instance, create it, and append a ref to it. This is how
// a component drops into a page: pick :button from the (type-filtered)
// palette, name the instance, then bind its free args on the canvas.
function promptTemplateInstanceInsert(fnId, anchorEl, expectedType, position) {
  if (typeof openFnPicker !== 'function') return;
  openFnPicker({
    anchorEl: anchorEl || document.body,
    excludeIds: [fnId],
    expectedType: expectedType || undefined,
    onPick: (template) => {
      if (!template?.id) return;
      promptTemplateInstanceName(fnId, anchorEl, template, position);
    }
  });
}

function promptTemplateInstanceName(fnId, anchorEl, template, position) {
  const owner = lookups?.fnMap?.get(fnId);
  const suggested = (owner?.name ? '_' + owner.name + '-' : 'my-')
                  + (template.name || 'instance');
  openInlineEditPopover({
    anchorEl: anchorEl || document.body,
    ariaLabel: 'Name the new ' + (template.name || 'instance'),
    makeControl(root) {
      const hint = document.createElement('div');
      hint.className = 'arg-value-edit-hint';
      hint.textContent = 'New ' + (template.name || 'fn') + ' — instance name';
      root.insertBefore(hint, root.firstChild);
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.value = suggested;
      root.insertBefore(input, root.firstChild.nextSibling);
      return input;
    },
    async doSave(input) {
      const name = (input.value || '').trim();
      if (!name) return false;
      return await createTemplateInstanceAndAppend(fnId, template, name, position);
    }
  });
}

async function createTemplateInstanceAndAppend(fnId, template, name, position) {
  const owner = lookups?.fnMap?.get(fnId);
  try {
    const fields = { name: name,
                     'namespace-id': owner?.['namespace-id'] || '',
                     'parent-ids': template.id };
    const r = await authMutate('POST', API.api_entities_type('fn'),
                               new URLSearchParams(fields).toString());
    if (!r?.ok) return { ok: false, error: await responseError(r) };
    // The create response is a plain confirmation (no id) — resolve the
    // new row by (namespace-qualified) name, the same path deep links use.
    const nsPath = owner?.['namespace-id'] != null
                 ? lookups?.nsPathMap?.get(owner['namespace-id']) : null;
    const created = (typeof resolveFnByName === 'function')
                  ? await resolveFnByName(nsPath ? nsPath + '/' + name : name)
                  : null;
    if (!created?.id) return { ok: false, error: 'Created, but could not resolve the new fn.' };
    const body = { ref: created.id };
    if (typeof position === 'number') body.position = position;
    const appended = await postSequenceAppend(fnId, body);
    return appended ? { ok: true }
                    : { ok: false, error: 'Instance created, but appending the ref failed.' };
  } catch (err) {
    console.error('template-instance create threw', err);
    return { ok: false, error: 'Create failed — network error.' };
  }
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
      if (typeof loadGraphData === 'function') loadGraphData();
      return true;
    }
  } catch (_) {}
  return false;
}

async function removeSequenceItem(itemId) {
  if (!itemId) return false;
  try {
    const r = await authMutate('DELETE',
                               API.api_sequence_item_item_id(itemId));
    if (r?.ok) {
      if (typeof loadGraphData === 'function') loadGraphData();
      return true;
    }
  } catch (_) {}
  return false;
}
