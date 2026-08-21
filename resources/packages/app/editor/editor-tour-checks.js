// editor-tour-checks.js — the tour's step-completion predicates.
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
      case 'dom':
        return !!document.querySelector(check.selector);
      case 'dom-absent':
        // The inverse of `dom` — completes when something DISAPPEARS (a
        // type-error badge cleared by the fixing edit).
        return !document.querySelector(check.selector);
      default:
        return false;
    }
  } catch (_) { return false; }
}

// --- overlay elements --------------------------------------------------------
