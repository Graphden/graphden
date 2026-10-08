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

function _tourFnForCheck(check) {
  if (!check['owned?']) return _tourFindFn(check.name);
  if (typeof _tourState === 'undefined' || !_tourPrincipalMatches(_tourState)) return null;
  const created = _tourState?.created?.find(
    row => row.type === 'fn' && row.name === check.name && row.id && row.receipt === 'created');
  return created && typeof lookups !== 'undefined' ? lookups?.fnMap?.get(created.id) : null;
}

// Rename views and their persisted source bindings share an identity even
// when their names differ. Missing or cyclic source chains fail closed.
function _tourCanonicalSlotId(id) {
  const seen = new Set();
  while (id && !seen.has(id)) {
    seen.add(id);
    const slot = lookups.slotMap?.get(id);
    if (!slot) return null;
    if (!slot['source-slot-id']) return id;
    id = slot['source-slot-id'];
  }
  return null;
}

function _tourSlotIdentities(name) {
  const targets = new Set();
  for (const slot of lookups.slotMap?.values() || []) {
    if (slot.name === name) {
      const id = _tourCanonicalSlotId(slot.id);
      if (id) targets.add(id);
    }
  }
  return targets;
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


function _tourCreatedGraphView(name) {
  if (typeof _tourState === 'undefined' || !_tourState
      || !_tourPrincipalMatches(_tourState) || _tourState.activeBranch !== _tourSessionBranch()
      || typeof gdSharedViewsCached !== 'function') return null;
  return (gdSharedViewsCached() || []).find(view => _tourState.created.some(row => row.id
    && row.type === 'fn' && row.name === name && view.id === row.id
    && view.name === row.name && view['namespace-id'] === row['namespace-id'])) || null;
}

function _tourCheckPasses(check) {
  if (!check || check.kind === 'manual') return false;
  try {
    switch (check.kind) {
      case 'service-state':
      case 'queue-state':
        return gdTourServiceCheck(check);
      case 'graph-view-filters': {
        const view = _tourCreatedGraphView(check.name);
        if (!view || view.unsupported?.length || !check.filters || typeof gdEmptyFilters !== 'function') return false;
        const expected = {...gdEmptyFilters(), ...check.filters};
        return Object.entries(expected).every(([axis, value]) => {
          const actual = view.filters[axis];
          return Array.isArray(value) ? Array.isArray(actual)
            && JSON.stringify([...actual].sort()) === JSON.stringify([...value].sort())
            : actual === value;
        });
      }
      case 'graph-view-run': {
        const view = _tourCreatedGraphView(check.name);
        if (!view || selectedFnId !== view.id || !_tourDomVisible('.execute-popover.visible .execute-result-pane')) return false;
        const raw = document.querySelector('.execute-popover.visible .execute-result-host .execute-result-raw pre');
        const result = raw && JSON.parse(raw.textContent);
        return Array.isArray(result?.fns) && result.fns.some(fn => fn.id === view.id)
          && Number.isInteger(result.total) && result.total >= result.fns.length;
      }
      case 'token-form-scopes':
        return _tourDomVisible('#gd-acct-mint-form') && _tourTokenScopes(
          [...document.querySelectorAll('#gd-acct-tok-scopes input:checked')]
            .map(input => input.value).join(' ')) === check.scopes;
      case 'token-created':
      case 'token-execute-denied':
      case 'token-revoked':
        return _tourTokenCheck(check);
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
        const fn = _tourFnForCheck(check);
        if (!fn) return false;
        const parents = fn['parent-ids'] || [];
        if (!parents.length) return false;
        // A missing lazy-cache row is not evidence of the requested parent.
        // The graph payload may still carry it; otherwise wait for it to load.
        return parents.some((pid) => {
          const p = (typeof lookups !== 'undefined' ? lookups?.fnMap?.get(pid) : null)
            || (typeof graphData !== 'undefined' ? graphData?.fns?.find((f) => f.id === pid) : null);
          return !!p && p.name === check.parent;
        });
      }
      case 'execution-trace': {
        const fn = _tourFindFn(check.name);
        if (!fn || typeof check.values !== 'boolean') return false;
        return [...document.querySelectorAll('.execute-show-path-btn')].some(button => {
          if (button.gdExecutionFnId !== fn.id) return false;
          const entries = button.gdPathTrace?.entries;
          if (!Array.isArray(entries) || !entries.some(entry => entry['fn-id'] === fn.id)) return false;
          const hasValues = entries.some(entry => Object.hasOwn(entry, 'value') || entry['value-truncated?']);
          const rect = button.getBoundingClientRect();
          return hasValues === check.values && rect.width > 0 && rect.height > 0;
        });
      }
      case 'fn-sibling-variation': {
        const target = _tourFindFn(check.name);
        const parentIds = target?.['parent-ids'] || [];
        if (parentIds.length !== 1) return false;
        const copy = lookups?.fnMap?.get(parentIds[0]);
        const created = typeof _tourState !== 'undefined'
          && _tourState?.created?.find(row => row.id === parentIds[0]);
        const verified = created?.['verified-source'];
        const source = _tourFindFn(check.source) || (verified?.id === created?.['source-fn-id']
          && verified?.name === check.source
          && created?.['verified-source-branch'] === _tourSessionBranch() ? verified : null);
        if (!source || parentIds[0] === source.id) return false;
        return !!copy && !!created && copy.name === check.variation
          && copy['namespace-id'] === target['namespace-id']
          && created['namespace-id'] === copy['namespace-id']
          && JSON.stringify(copy['parent-ids'] || []) === JSON.stringify(source['parent-ids'] || []);
      }
      case 'binding-bound': {
        // The slot row belongs to the PARENT (slots are inherited);
        // the binding row belongs to the checked fn — so walk the fn's
        // bindings and resolve each slot's name, never slotByFnAndName
        // (which is keyed by the slot-OWNING fn).
        const fn = _tourFnForCheck(check);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        // CRUD stores a renamed-view binding on its canonical source slot.
        // Compare slot identities, including chained renames, rather than
        // expecting the stored source name to retain the visible label.
        const targets = _tourSlotIdentities(check.slot);
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return list.some((b) => {
          if (!targets.has(_tourCanonicalSlotId(b['slot-id']))) return false;
          if (b.value != null || b['ref-fn-id']) return true;
          // Sequence slots: the binding row itself carries no value —
          // the content lives in binding-list-item rows.
          const items = lookups.itemsByBinding?.get(b.id) || [];
          return items.length > 0;
        });
      }
      case 'binding-flag': {
        if (typeof check.value !== 'boolean'
            || !['terminal', 'list-closed', 'required', 'list-append'].includes(check.field)) return false;
        const created = typeof _tourState !== 'undefined'
          && _tourState?.created?.find(row => row.id && row.name === check.name);
        const fn = created ? lookups?.fnMap?.get(created.id) : _tourFindFn(check.name);
        if (!fn || !lookups?.bindingsByFn?.has(fn.id)) return false;
        return lookups.bindingsByFn.get(fn.id).some(binding => {
          const slot = lookups.slotMap?.get(binding['slot-id']);
          return binding['fn-id'] === fn.id && slot?.name === check.slot
            && Boolean(binding[check.field]) === check.value;
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
        const fn = _tourFnForCheck(check);
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
        const fn = _tourFnForCheck(check);
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
        const fn = _tourFnForCheck(check);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const targets = _tourSlotIdentities(check.slot);
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return list.some((b) => {
          return !!(targets.has(_tourCanonicalSlotId(b['slot-id']))
                    && b.value != null
                    && String(b.value) === String(check.value));
        });
      }
      case 'ui-component': return window.gdTourUIComponentCheck?.(check) || false;
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
        const fn = _tourFnForCheck(check);
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
        const fn = _tourFnForCheck(check);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return list.some((b) => {
          const s = lookups.slotMap?.get(b['slot-id']);
          if (!s || s.name !== check.slot) return false;
          const items = lookups.itemsByBinding?.get(b.id) || [];
          return items.length > 0 && String(items[0].value) === String(check.value);
        });
      }
      case 'list-values': {
        // Exact local literal sequence: appending is not inserting, and a
        // commutative Run result cannot prove that an item moved correctly.
        const fn = _tourFnForCheck(check);
        if (!fn || typeof lookups === 'undefined' || !lookups || !Array.isArray(check.values)) return false;
        return (lookups.bindingsByFn?.get(fn.id) || []).some((b) => {
          const slot = lookups.slotMap?.get(b['slot-id']);
          if (!slot || slot.name !== check.slot) return false;
          const items = lookups.itemsByBinding?.get(b.id) || [];
          return items.length === check.values.length && items.every((item, i) =>
            !item['ref-fn-id'] && item.value != null && String(item.value) === String(check.values[i]));
        });
      }
      case 'fn-field': {
        // A field of the fn ROW equals `check.value` — the card strips
        // that write the row (λ lambda-params, 📍 branch-local). Compared
        // as JSON so `[]`, `["string"]` and `true` all pin exactly.
        const fn = _tourFnForCheck(check);
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
      case 'result-value': {
        // Inspect the current Run pane's value, using raw JSON for shaped
        // results and rendered text for scalars. Submitting clears the host, so an
        // earlier result cannot complete a step while the new run is pending.
        if (!_tourDomVisible('.execute-popover.visible .execute-result-pane')) return false;
        const raw = document.querySelector(
          '.execute-popover.visible .execute-result-host .execute-result-raw pre');
        if (!Object.hasOwn(check, 'value')) return false;
        if (raw) return JSON.stringify(JSON.parse(raw.textContent)) === JSON.stringify(check.value);
        // The production scalar pane has no Raw details; it renders the
        // primitive directly. This proves the displayed value, not its type:
        // numeric 2 and text "2" share that markup. Shaped results use raw JSON.
        if (!['number', 'string', 'boolean'].includes(typeof check.value)) return false;
        const scalar = document.querySelector(
          '.execute-popover.visible .execute-result-host .execute-result-scalar');
        return !!scalar && scalar.textContent === String(check.value);
      }
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
      case 'review-comment': {
        const fn = _tourFnForCheck(check);
        if (!fn?.id || typeof check.text !== 'string' || !check.text) return false;
        const selector = '.branch-diff-anchor-thread[data-anchor-name="fn"][data-anchor-id="'
          + fn.id + '"] .branch-comment';
        return Array.from(document.querySelectorAll(selector)).some((row) => {
          if (!row.dataset.commentId || row.querySelector('.branch-comment-body')?.textContent !== check.text) return false;
          const rect = row.getBoundingClientRect();
          return rect.width > 0 && rect.height > 0;
        });
      }
      case 'created-branch': {
        const created = typeof _tourState !== 'undefined' && _tourState?.created?.some(
          (row) => row.type === 'branch' && row.name === check.name && row.id && row['base-branch-id']
            && row.receipt !== 'pending');
        return !!created && _tourCurrentBranch() === check.name;
      }
      case 'owned-entity':
        return typeof _tourOwnedEntityPasses === 'function' && _tourOwnedEntityPasses(check);
      case 'app-route-created':
        return typeof gdTourAppCreationPasses === 'function' && gdTourAppCreationPasses(check);
      case 'package-published':
        return typeof _tourPackagePublishedPasses === 'function' && _tourPackagePublishedPasses(check);
      case 'package-pin':
        return typeof _tourPackagePinPasses === 'function' && _tourPackagePinPasses(check);
      case 'package-reference':
        return typeof _tourPackageReferencePasses === 'function' && _tourPackageReferencePasses(check);
      case 'package-run': {
        const owner = typeof _tourState === 'undefined' ? null : _tourState?.created?.find(
          row => row.type === 'fn' && row.name === check.name && row.id && row.receipt === 'created');
        const host = document.querySelector('.execute-popover.visible .execute-result-host');
        return !!owner && _tourPrincipalMatches(_tourState)
          && host?.gdExecutionFnId === owner.id
          && _tourCheckPasses({kind: 'result-value', value: check.value});
      }
      default:
        return false;
    }
  } catch (_) { return false; }
}
