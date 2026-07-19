// Editor Edit Modes (type-level) — inline edit popovers around a
// slot's TYPE: the compatible-type select (server-rendered option
// list), the arg-type flip with its picker-chaining rollback, and the
// free-arg literal-vs-ref binder. Split out of editor-edit-modes.js.

// --- arg type flip (Phase 2) ---
//
// Type-chip click → small select of `value_kind` enum. On save we
// orchestrate two PUTs to /api/entities/arg/:id:
//
//   1. type=<new>&value=&ref-id=  — backend doesn't cascade type
//      changes, so we have to wipe both bindings explicitly. The
//      permissive parse honours empty form values now (Phase 2
//      backend change).
//   2. (only when new type is `:fn`) ref-id=<picked-fn-id> — opens
//      the fn-picker and writes the chosen ref-id, then refetches.
//
// Refetch is `initGraph()` because flipping type→fn restructures
// the canvas (arg-value node disappears, ref-edge appears).

// Populate `select` with every type-name T such that
// `T ⊆ expectedSlotType(arg)` per the server's alias-aware `subtype?`,
// so the dropdown only lists types the slot can legally narrow to —
// an fn-type slot like `:handler` never offers `:int`. One
// server-rendered option list (`/partials/compatible-type-options`,
// primitives included) — the per-name fan-out is gone.
async function populateCompatibleTypes(arg, select, cur, loadingOpt) {
  if (typeof expectedSlotType !== 'function') return;
  let expected = expectedSlotType(arg);
  // A null `expected` does NOT mean "nothing is compatible" — most of the time it
  // means the slots simply have not arrived yet. `initGraph()` rebuilds `lookups`
  // from `?scope=tree`, which carries namespaces + counts and NO slots; the slot /
  // binding rows land later, per view, through `ensureSubtreeFor()`. Open this
  // popover inside that window — the chip on screen is still the previous render's
  // — and `lookups.slotMap` is empty, so the slot is unknown and the picker used to
  // quietly drop its "loading…" placeholder and offer the current type alone.
  // Nothing re-ran it when the subtree landed, so the user was left with a type
  // picker that could not change the type, with no error to explain it.
  //
  // Measured, at the moment of the call rather than after the fact:
  //   {slotMapSize: 0, slotKnown: false, result: null}   <- the click
  //   {slotMapSize: 1, slotKnown: true,  result: "any"}  <- re-renders, too late
  //
  // So wait for the payload that carries slots, then ask again. `ensureSubtreeFor`
  // is idempotent and hands back the in-flight fetch, so this costs nothing when
  // the subtree is already there.
  if (!expected && typeof ensureSubtreeFor === 'function') {
    try {
      await ensureSubtreeFor(selectedFnId || arg?.['fn-id']);
    } catch (_) { /* fall through — reported as "no compatible types" below */ }
    expected = expectedSlotType(arg);
  }
  if (!expected) {
    if (loadingOpt) loadingOpt.remove();
    return;
  }
  await loadCompatibleTypeOptions(select, expected,
                                  { current: cur, includePrimitives: true });
  if (loadingOpt) loadingOpt.remove();
}
function enterArgTypeEditMode(arg, anchorEl) {
  if (!arg) return;
  // Capture pre-edit binding state so we can roll back if the user
  // changes type to `:fn`, then cancels the chained fn-picker. Without
  // this revert, the row is left as `:fn` with no ref — an orphaned
  // mismatch the user has to discover via the red ring. Reverting
  // makes "I changed my mind" a non-destructive operation.
  const preEdit = {
    typeOverrideFnId: arg['type-override-fn-id'] || '',
    valueJson: arg.value == null ? '' : JSON.stringify(arg.value),
    refFnId: arg['ref-id'] || arg['ref-fn-id'] || '',
    type: arg.type ? String(arg.type).replace(/^:/, '') : null,
  };
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Change arg type',
    makeControl(root) {
      const select = document.createElement('select');
      select.className = 'arg-value-edit-input';
      const cur = arg.type ? String(arg.type).replace(/^:/, '') : '';
      // Seed with the current type so the popover renders something
      // immediately; the rest of the compatible options stream in
      // asynchronously below (the subtype filter needs the server's
      // alias-aware `subtype?` predicate, no JS analogue handles
      // refinements / records / fn-types fully).
      if (cur) {
        // `selected=true` + the option being the very first item is
        // enough signal for the user; appending " (current)" was
        // redundant chrome the inline-expand panel already dropped.
        const o = document.createElement('option');
        o.value = cur;
        o.textContent = cur;
        o.selected = true;
        select.appendChild(o);
      }
      const loading = document.createElement('option');
      loading.value = '';
      loading.textContent = 'loading compatible types…';
      loading.disabled = true;
      select.appendChild(loading);
      root.insertBefore(select, root.firstChild);
      // Fire-and-forget async populate. Only narrows the picker — if
      // it fails, user keeps the current-only option (still valid).
      populateCompatibleTypes(arg, select, cur, loading);
      return select;
    },
    async doSave(select) {
      const newType = select.value;
      const curType = arg.type ? String(arg.type).replace(/^:/, '') : null;
      if (newType === curType) return true;  // no-op
      // Type override = binding's `:type-override-fn-id` pointing at
      // the type-row whose name matches the picker selection. The
      // picker offers PRIMITIVES (`:text`, `:int`, …) AND
      // REFINEMENTS (`:non-blank-text`, `:port`, …) — both are
      // valid override targets, the only constraint is
      // "parent-less" (rules out composed fn-defs that
      // happen to share a name). The earlier "primitive only"
      // filter (`!base-fn-id && !element-fn-id`) rejected
      // refinements which the picker happily listed, leaving the
      // user staring at a silent 400 from `writeBindingFields`.
      const overrideFnId = await (async () => {
        if (!newType || !graphData) return '';
        const parentLess = f => !f['parent-ids'] || f['parent-ids'].length === 0;
        // Fast path: an already-loaded parent-less type-fn.
        let fn = (graphData.fns || []).find(f => f.name === newType && parentLess(f));
        // Slow path: the type-fn (primitive / refinement) may not be loaded —
        // it lives in the root bucket. Resolve it by name via the server.
        if (!fn && typeof resolveFnByName === 'function') {
          const resolved = await resolveFnByName(newType);
          if (resolved && parentLess(resolved)) fn = resolved;
        }
        return fn ? fn.id : '';
      })();
      if (!(await writeBindingFields(arg, {
        'type-override-fn-id': overrideFnId,
        value: '',
        'ref-fn-id': ''
      })).ok) return false;
      // Local arg-shape mirror — used by onSaved below to decide
      // whether to chain into the fn-picker. The next initGraph()
      // refetches authoritative state, so this only needs to live
      // until then.
      arg.type = newType;
      arg.value = null;
      arg['ref-id'] = null;
      return true;
    },
    onSaved() {
      const newType = arg.type ? String(arg.type).replace(/^:/, '') : null;
      if (newType === 'fn' && typeof openFnPicker === 'function') {
        // Allow self-reference but exclude only the fn the arg is
        // attached to to keep things sane. Re-parent (Phase 3) will
        // need the descendants exclusion.
        const fnId = arg['fn-id'];
        openFnPicker({
          anchorEl: document.getElementById('graph-surface') || document.body,
          excludeIds: fnId ? [fnId] : [],
          expectedType: expectedSlotType(arg),
          onPick: async (fn) => { await saveArgRef(arg, fn.id); },
          onCancel: async () => {
            // Roll back the type change so the row doesn't end up
            // as `:fn` with no value/ref. We re-issue a write
            // restoring the pre-edit binding fields, then refresh.
            // If the rollback itself fails the row is left in the
            // pending state and the mismatch indicator + explainer
            // will surface it — better to fail loud than silently
            // half-revert.
            await writeBindingFields(arg, {
              'type-override-fn-id': preEdit.typeOverrideFnId,
              value: preEdit.valueJson,
              'ref-fn-id': preEdit.refFnId,
            });
            if (typeof initGraph === 'function') initGraph();
          }
        });
      } else if (typeof initGraph === 'function') {
        initGraph();
      }
    }
  });
}
function enterFreeArgBindEditMode(arg, anchorEl) {
  if (!arg) return;
  closeInlineEdit();

  // Slot-level type drives the default action.
  const effType = arg.type ? String(arg.type).replace(/^:/, '') : null;

  // For `:fn` slots the only sensible binding is a fn-ref; jump straight
  // into the picker.
  if (effType === 'fn') {
    if (typeof openFnPicker === 'function') {
      openFnPicker({
        anchorEl,
        excludeIds: arg['fn-id'] ? [arg['fn-id']] : [],
        // The slot's structural type from /api/types — picker uses
        // it to highlight type-compatible fns.
        expectedType: expectedSlotType(arg),
        onPick: async (fn) => { await saveArgRef(arg, fn.id); }
      });
    }
    return;
  }

  // Otherwise show a literal-vs-ref chooser, then descend into the
  // appropriate input.
  openLiteralVsRefChooser({
    anchorEl,
    ariaLabel: 'Bind free arg',
    litLabel: 'Bind literal',
    refLabel: 'Bind fn-ref',
    // Fall through into the existing arg-value editor.
    onLiteral: () => enterArgValueEditMode(arg, anchorEl),
    onRef: () => {
      if (typeof openFnPicker === 'function') {
        openFnPicker({
          anchorEl,
          excludeIds: arg['fn-id'] ? [arg['fn-id']] : [],
          expectedType: expectedSlotType(arg),
          onPick: async (fn) => { await saveArgRef(arg, fn.id); }
        });
      }
    }
  });
}
