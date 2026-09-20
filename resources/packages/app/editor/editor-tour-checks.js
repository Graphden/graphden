// editor-tour-checks.js — the tour's step-completion predicates.
//
// graph-first-exception: every predicate here reads LIVE client state — the
// lexical graph the editor rendered from, the current selection, the DOM, the
// branch in the URL. There is nothing for a server to render; the step's
// CONTENT (which check, with which argument) is graph data already.
//
// A lesson step carries a declarative `:check`; this file turns it into a
// predicate over the editor's own state. Split out of editor-tour.js so the
// vocabulary can be unit-tested without a browser (tools/runtime-test/
// tour-checks.test.js drives it in a node vm) — the engine that polls these
// bind to live DOM and cannot.
//
// graphData / lookups / selectedFnId are script-scope globals of the
// concatenated editor bundle, not window.*; the bare identifiers resolve
// because this file is part of that bundle.

// The branch context IS the `?branch=` param — editor-branches keeps its own
// getter module-private, and `switchToBranch` round-trips through the URL.
// It lives here because `on-branch` is a check like any other; the engine
// reads it too.
function _tourCurrentBranch() {
  try { return new URLSearchParams(window.location.search).get('branch'); }
  catch (_) { return null; }
}

// --- checks -----------------------------------------------------------------
// Declarative check → predicate over the editor's lexical graph state.
// graphData/lookups are script-scope globals (NOT window.*) — this module is
// concatenated into the same bundle, so the bare identifiers resolve.

function _tourFindFn(name) {
  if (typeof lookups !== 'undefined' && lookups && lookups.fnMap) {
    for (const f of lookups.fnMap.values()) if (f && f.name === name) return f;
  }
  if (typeof graphData !== 'undefined' && graphData && graphData.fns) {
    return graphData.fns.find((f) => f.name === name) || null;
  }
  return null;
}

// A `dom` check asks whether the reader can SEE the thing, not whether it is
// in the document: the editor keeps whole surfaces mounted and hidden (the
// Organization panels exist from boot), so `querySelector` alone completed
// "open the Organization surface" the moment the lesson started — the tour
// walked on while the reader was still looking at the canvas.
//
// Measured, not `offsetParent`: the popovers these steps point at are
// `position: fixed`, where `offsetParent` is null even when they are on
// screen. A `[hidden]` / `display: none` element measures 0x0.
function _tourDomVisible(selector) {
  if (!selector) return false;
  for (const el of document.querySelectorAll(selector)) {
    const r = el.getBoundingClientRect();
    if (r.width > 0 && r.height > 0) return true;
  }
  return false;
}


// Is `name`'s Explorer row present but hidden by the kind lens? (A row that
// is simply not rendered yet is NOT this case — that is the filter box's job,
// and the step tells the reader to type there.)
function _tourFnRowHidden(name) {
  const list = document.getElementById('entity-list');
  if (!list) return false;
  for (const el of list.querySelectorAll('.entity-item')) {
    const label = el.querySelector('.name');
    if (label && label.textContent.trim() === name) return el.hasAttribute('hidden');
  }
  return false;
}


function _tourCheckPasses(check) {
  if (!check || check.kind === 'manual') return false;
  try {
    switch (check.kind) {
      case 'ns-exists':
        // ROOT namespaces only: `name` is the SEGMENT, not the path, so a
        // nested ns elsewhere (the cloud's landing.tutorial lesson pages)
        // must not false-pass the "create a namespace" step.
        return typeof graphData !== 'undefined' && !!graphData
          && (graphData.namespaces || []).some(
            (n) => n.name === check.name && !n['parent-id']);
      case 'fn-exists':
        return !!_tourFindFn(check.name);
      case 'fn-parent': {
        const fn = _tourFindFn(check.name);
        if (!fn) return false;
        const parents = fn['parent-ids'] || [];
        if (!parents.length) return false;
        // If the parent row isn't in the lazy cache yet, accept any parent —
        // the lesson's instruction was followed structurally.
        return parents.some((pid) => {
          const p = lookups?.fnMap ? lookups.fnMap.get(pid) : null;
          return p ? p.name === check.parent : true;
        });
      }
      case 'binding-bound': {
        // The slot row belongs to the PARENT (slots are inherited);
        // the binding row belongs to the checked fn — so walk the fn's
        // bindings and resolve each slot's name, never slotByFnAndName
        // (which is keyed by the slot-OWNING fn).
        const fn = _tourFindFn(check.name);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return list.some((b) => {
          const s = lookups.slotMap?.get(b['slot-id']);
          if (!s || s.name !== check.slot) return false;
          if (b.value != null || b['ref-fn-id']) return true;
          // Sequence slots: the binding row itself carries no value —
          // the content lives in binding-list-item rows.
          const items = lookups.itemsByBinding?.get(b.id) || [];
          return items.length > 0;
        });
      }
      case 'selected': {
        if (typeof selectedFnId === 'undefined' || !selectedFnId) return false;
        const sel = lookups?.fnMap ? lookups.fnMap.get(selectedFnId) : null;
        return !!(sel && sel.name === check.name);
      }
      case 'on-branch': {
        // Branch context IS the URL param; switching reloads the page and
        // the tour resumes from localStorage, so this check re-evaluates on
        // the OTHER side of the reload — which is exactly what it asserts.
        // "main" also matches the no-param (default-branch) case.
        const cur = _tourCurrentBranch();
        return check.name === 'main' ? (!cur || cur === 'main')
                                     : cur === check.name;
      }
      case 'arg-named': {
        // "an arg on the canvas now carries THIS name" — the completion
        // signal for a rename. Neither `binding-bound` nor `bindings-count`
        // can see one: a rename-only binding has no value, no ref and no
        // items, and the client lookups don't carry it either — the new
        // name reaches the client as the layout's edge label. So read the
        // label: the check then passes exactly when the user can SEE the
        // rename, which is what the step asked for.
        return Array.from(document.querySelectorAll('.edge-label-overlay span'))
          .some((sp) => sp.textContent.trim() === check.arg);
      }
      case 'list-items': {
        // "the sequence slot `check.slot` holds at least `check.count`
        // items" — `binding-bound` is true after the FIRST append, so a
        // lesson that asks for a second number (`:add`'s :nums, 1 + 1)
        // needs to count the binding-list-item rows, not the binding.
        const fn = _tourFindFn(check.name);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return list.some((b) => {
          const s = lookups.slotMap?.get(b['slot-id']);
          if (!s || s.name !== check.slot) return false;
          const items = lookups.itemsByBinding?.get(b.id) || [];
          return items.length >= (check.count || 1);
        });
      }
      case 'bindings-count': {
        // "at least N of this fn's slots are bound" — order-independent,
        // which is what a step asking for two sibling slots needs: the
        // canvas decides which placeholder sits where, and a lesson must
        // not depend on that.
        const fn = _tourFindFn(check.name);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        const bound = list.filter((b) => {
          if (b.value != null || b['ref-fn-id']) return true;
          const items = lookups.itemsByBinding?.get(b.id) || [];
          return items.length > 0;
        });
        return bound.length >= (check.count || 1);
      }
      case 'binding-value': {
        // binding-bound, but the literal must equal `check.value`. Compared
        // as TEXT: a JSON literal round-trips through jsonb, so 42 can come
        // back as a number or a string depending on the slot's type.
        const fn = _tourFindFn(check.name);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return list.some((b) => {
          const s = lookups.slotMap?.get(b['slot-id']);
          return !!(s && s.name === check.slot
                    && b.value != null
                    && String(b.value) === String(check.value));
        });
      }
      case 'expanded': {
        // "the card of fn `name` is unfolded to at least `depth`" — read
        // from the COMMITTED expansion (`expansionState`), never the hover
        // preview: pointing at a parent row already renders the cards it
        // would reveal, so a `dom` check on the revealed card passed while
        // the reader was only reading the row, and the card folded away
        // under them the moment the cursor left. The overlay carries the
        // node id the expansion state is keyed by; several copies of the
        // fn may share a canvas, any unfolded one counts.
        if (typeof expansionState === 'undefined' || !expansionState) return false;
        const depth = (check.depth == null) ? 1 : check.depth;
        const sel = '.node-overlay[data-fn-name="' + check.name + '"]';
        const overlays = Array.from(document.querySelectorAll(sel));
        // `:depth 0` is the FOLDED card — "click the top row to fold it back"
        // done: the card is on the canvas and no copy of it keeps a
        // committed expansion (a hover preview never reaches this map).
        if (depth === 0) {
          return overlays.length > 0 && overlays.every((ov) => {
            const spec = ov.dataset?.nodeId ? expansionState.get(ov.dataset.nodeId) : null;
            return !spec || (!(spec.fullDepth > 0) && !(spec.partialFns?.size > 0));
          });
        }
        return overlays.some((ov) => {
          const spec = ov.dataset?.nodeId ? expansionState.get(ov.dataset.nodeId) : null;
          if (!spec) return false;
          const full = spec.fullDepth || 0;
          // A partial expansion at the next level counts for that level.
          return full >= depth
            || (full === depth - 1 && (spec.partialFns?.size || 0) > 0);
        });
      }
      case 'binding-absent': {
        // The inverse of `binding-bound` for one slot: the fn holds NO
        // binding that values, refs or fills `check.slot` — what a reader
        // sees after Delete on a bound literal (the `+` is back). A
        // flag-only binding (a seal, a rename) does not count as bound.
        const fn = _tourFindFn(check.name);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return !list.some((b) => {
          const s = lookups.slotMap?.get(b['slot-id']);
          if (!s || s.name !== check.slot) return false;
          if (b.value != null || b['ref-fn-id']) return true;
          return (lookups.itemsByBinding?.get(b.id) || []).length > 0;
        });
      }
      case 'list-first': {
        // The FIRST item of the sequence slot `check.slot` reads
        // `check.value` — how a lesson sees that ↑ / ↓ moved an item.
        // Items are position-sorted in `itemsByBinding`.
        const fn = _tourFindFn(check.name);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return list.some((b) => {
          const s = lookups.slotMap?.get(b['slot-id']);
          if (!s || s.name !== check.slot) return false;
          const items = lookups.itemsByBinding?.get(b.id) || [];
          return items.length > 0 && String(items[0].value) === String(check.value);
        });
      }
      case 'fn-field': {
        // A field of the fn ROW equals `check.value` — the card strips
        // that write the row (λ lambda-params, 📍 branch-local). Compared
        // as JSON so `[]`, `["string"]` and `true` all pin exactly.
        const fn = _tourFindFn(check.name);
        if (!fn) return false;
        return JSON.stringify(fn[check.field] ?? null) === JSON.stringify(check.value ?? null);
      }
      case 'dom':
        return _tourDomVisible(check.selector);
      case 'dom-absent':
        // The inverse of `dom` — completes when something DISAPPEARS (a
        // type-error badge cleared by the fixing edit), which for a reader
        // includes "is still in the DOM but hidden".
        return !_tourDomVisible(check.selector);
      case 'input-value': {
        // A form control's CURRENT value — what `dom` cannot see, because a
        // live `value` is a property, not an attribute a selector can match.
        // The one use so far: "clear the Explorer filter" as a step of its
        // own (`#search-input` reads ""), so the ring sits on the × the
        // reader is told to press instead of on whatever comes after it.
        const el = document.querySelector(check.selector);
        if (!el) return false;
        return String(el.value ?? '') === String(check.value ?? '');
      }
      default:
        return false;
    }
  } catch (_) { return false; }
}
