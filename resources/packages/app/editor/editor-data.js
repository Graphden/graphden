// Editor Data - Data utilities, lookups, inheritance chain resolution
// Depends on: editor-state.js

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

function truncateLabel(label, maxLen) {
  if (label.length > maxLen) {
    return label.substring(0, maxLen - 1) + '…';
  }
  return label;
}


// Structural deep-equality for JSON-shaped values (primitives,
// arrays, plain objects). Used by clientSubtype in place of
// `JSON.stringify(a) === JSON.stringify(b)` — the stringify path
// allocated two strings AND walked the structure twice (once per
// side); deep refinement chains paid the cost in every recursion
// frame.
function structEq(a, b) {
  if (a === b) return true;
  if (a == null || b == null) return false;
  if (typeof a !== typeof b) return false;
  if (Array.isArray(a)) {
    if (!Array.isArray(b) || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!structEq(a[i], b[i])) return false;
    return true;
  }
  if (typeof a === 'object') {
    if (Array.isArray(b)) return false;
    const ak = Object.keys(a);
    if (ak.length !== Object.keys(b).length) return false;
    for (const k of ak) if (!structEq(a[k], b[k])) return false;
    return true;
  }
  return false;
}


// ============================================================================
// CLIENT-SIDE SUBTYPE CHECK
// ============================================================================
//
// Fast local approximation for the fn-picker's INITIAL paint only —
// primitives + numeric hierarchy (`primitiveSubtype`, defined in
// editor-literal-types.js; call-time resolution, so file order doesn't
// matter), unions, refinement erasure, covariant lists. Anything
// structural (records, maps, tuples, fn-types, refinement-constraint
// implication) answers false: the authoritative /api/types/candidates
// response upgrades those a moment later, and it only ever upgrades —
// a conservative miss self-heals, a false positive would stick. A full
// structural mirror of the backend `subtype?` used to live here; it
// was redundant with the server call and drifted from
// src/graphden/types/core.clj. Don't grow it back.
function clientSubtype(sub, sup) {
  // :sequence (storage primitive) ≡ [:list :any]
  if (sub === 'sequence') sub = ['list', 'any'];
  if (sup === 'sequence') sup = ['list', 'any'];
  if (sub == null || sup == null) return true;
  if (structEq(sub, sup)) return true;
  if (sup === 'any')  return true;
  if (sub === 'any')  return false;
  // Union LHS: every member must subtype.
  if (Array.isArray(sub) && sub[0] === 'union') {
    return sub.slice(1).every(m => clientSubtype(m, sup));
  }
  // Union RHS: at least one branch must accept.
  if (Array.isArray(sup) && sup[0] === 'union') {
    return sup.slice(1).some(m => clientSubtype(sub, m));
  }
  // Refinement erasure: [:refine B c] ⊆ B. Constraint implication
  // between two non-identical refinements is structural — server's call.
  if (Array.isArray(sub) && sub[0] === 'refine') {
    return clientSubtype(sub[1], sup);
  }
  if (Array.isArray(sup) && sup[0] === 'refine') return false;
  if (typeof sub === 'string' && typeof sup === 'string') {
    if (primitiveSubtype(sub, sup)) return true;
    if (sup === 'jsonb') return sub !== 'fn';   // fn is not a json value
    return false;
  }
  // List subtype: covariant elem.
  if (Array.isArray(sub) && Array.isArray(sup) && sub[0] === 'list' && sup[0] === 'list') {
    return clientSubtype(sub[1], sup[1]);
  }
  // Any remaining json-shaped value (list / map / tuple / record) IS
  // a valid :jsonb.
  if (sup === 'jsonb' && typeof sub !== 'string') return true;
  return false;
}

// ============================================================================
// LOOKUP MAPS
// ============================================================================

// Build lookup maps from raw graph data.
//
// Produces TWO parallel lookup sets:
//
// NEW (slot/binding model — direct from the rewrite tables):
//   slotMap            — id → slot row
//   fnSlotsByFn        — fn-id → [fn-slot junction] sorted by :position
//   slotByFnAndName    — [fn-id, slot-name-keyword] → slot row
//   bindingMap         — id → binding row
//   bindingsByFn       — fn-id → [bindings on it]
//   bindingByFnSlot    — [fn-id, slot-id] → binding row
//   itemsByBinding     — binding-id → [list-items] sorted by :position
//
// Plus shared maps:
//   fnMap, nsMap, nsPathMap
//   fnUsedAsParent, fnUsedAsRef, nsHasChildNs, nsHasChildFn, nsTypeErrors
function buildLookups(data) {
  const fnMap = new Map();
  const slotMap = new Map();
  const fnSlotsByFn = new Map();
  const slotByFnAndName = new Map();
  const bindingMap = new Map();
  const bindingsByFn = new Map();
  const bindingByFnSlot = new Map();
  const itemsByBinding = new Map();
  const nsMap = new Map();
  const nsPathMap = new Map();

  (data.fns || []).forEach(f => fnMap.set(f.id, f));

  // --- new model ---
  (data.slots || []).forEach(s => slotMap.set(s.id, s));
  const sortedFnSlots = (data['fn-slots'] || []).slice()
    .sort((a, b) => (a.position || 0) - (b.position || 0));
  // Phase 6c — index renamed-view slots by (fn-id, source-slot-id)
  // so getEffectiveSlotName can answer in O(1) without scanning
  // fn-slots. A renamed slot is an own slot (fn-slot row on F)
  // whose `:source-slot-id` FK points back at the slot it renames.
  const slotByFnSourceSlot = new Map();
  sortedFnSlots.forEach(fs => {
    const fnId = fs['fn-id'];
    if (!fnSlotsByFn.has(fnId)) fnSlotsByFn.set(fnId, []);
    fnSlotsByFn.get(fnId).push(fs);
    const slot = slotMap.get(fs['slot-id']);
    if (slot?.name) {
      slotByFnAndName.set(fnId + '|' + slot.name, slot);
    }
    if (slot?.['source-slot-id']) {
      slotByFnSourceSlot.set(fnId + '|' + slot['source-slot-id'], slot);
    }
  });
  (data.bindings || []).forEach(b => {
    bindingMap.set(b.id, b);
    const fnId = b['fn-id'];
    if (fnId) {
      if (!bindingsByFn.has(fnId)) bindingsByFn.set(fnId, []);
      bindingsByFn.get(fnId).push(b);
    }
    if (fnId && b['slot-id']) {
      bindingByFnSlot.set(fnId + '|' + b['slot-id'], b);
    }
  });
  // Flat {item-id -> item-row} index sits next to itemsByBinding so
  // argRowFromNode can resolve an arg by `itemId` in O(1) — the
  // previous code looped EVERY bindings' item array (O(N×M) per
  // arg-overlay render).
  const itemByItemId = new Map();
  const sortedItems = (data['list-items'] || []).slice()
    .sort((a, b) => (a.position || 0) - (b.position || 0));
  sortedItems.forEach(it => {
    const bid = it['binding-id'];
    if (!bid) return;
    if (!itemsByBinding.has(bid)) itemsByBinding.set(bid, []);
    itemsByBinding.get(bid).push(it);
    if (it.id) itemByItemId.set(it.id, it);
  });

  // Build namespace maps
  (data.namespaces || []).forEach(ns => nsMap.set(ns.id, ns));
  // Compute full paths for each ns (walk parent chain)
  nsMap.forEach((ns, id) => {
    const parts = [];
    let cur = ns;
    for (let i = 0; i < 20 && cur; i++) {
      parts.unshift(cur.name);
      cur = cur['parent-id'] ? nsMap.get(cur['parent-id']) : null;
    }
    nsPathMap.set(id, parts.join('.'));
  });

  // Deletability sets. The sidebar no longer holds a full-fns mirror to
  // count against, so the whole-graph reverse-ref tallies come from the
  // SERVER instead of a client scan:
  //   - fnUsedAsParent / fnUsedAsRef: read off each fn row's
  //     `:used-as-parent-count` / `:used-as-ref-count`, which the
  //     `:namespace` / `:search` / `:subtree` payloads compute over the
  //     whole graph (see crud/entities `reverse-ref-index`). Keyed by the
  //     REFERENCED fn's id — exactly what `fnDeleteBlockReason` looks up.
  //   - nsHasChildFn: from the `:tree` `:counts` payload (named fns per
  //     namespace), authoritative even though the leaves load lazily.
  //   - nsHasChildNs: still derived from the (complete) namespace list.
  const fnUsedAsParent = new Map();
  const fnUsedAsRef    = new Map();
  const nsHasChildNs   = new Map();
  const nsHasChildFn   = new Map();
  // Per-namespace recorded-type-diagnostic counts (Phase 3) — from the
  // `:tree` counts payload's additive `:type-error-count`; feeds the
  // sidebar's ⚠ chip on namespace rows.
  const nsTypeErrors   = new Map();
  const bump = (m, k) => { if (k) m.set(k, (m.get(k) || 0) + 1); };

  (data.fns || []).forEach(f => {
    const ap = f['used-as-parent-count'];
    const ar = f['used-as-ref-count'];
    if (ap) fnUsedAsParent.set(f.id, ap);
    if (ar) fnUsedAsRef.set(f.id, ar);
  });
  (data.counts || []).forEach(c => {
    if (c.count) nsHasChildFn.set(c['namespace-id'], c.count);
    if (c['type-error-count']) nsTypeErrors.set(c['namespace-id'], c['type-error-count']);
  });
  (data.namespaces || []).forEach(ns => bump(nsHasChildNs, ns['parent-id']));

  // Per-lookups cache for getInheritanceLevels — auto-invalidated
  // whenever buildLookups runs again (graph mutation refresh).
  const inheritanceLevelsCache = new Map();

  return { fnMap,
           slotMap, fnSlotsByFn, slotByFnAndName, slotByFnSourceSlot,
           bindingMap, bindingsByFn, bindingByFnSlot, itemsByBinding, itemByItemId,
           nsMap, nsPathMap,
           fnUsedAsParent, fnUsedAsRef, nsHasChildNs, nsHasChildFn,
           nsTypeErrors,
           inheritanceLevelsCache };
}


// === Slot/binding helpers (new model) =====================================
//
// Replace `arg.X` accessors / `lookups.argMap` walks with these so
// editor JS can migrate off the synth-arg shape one callsite at a time.


// Effective name of a slot at a particular fn — walks the
// inheritance chain (closest-first) looking for an own renamed-view
// slot whose `source-slot-id` FK matches; the renamed slot's name
// wins. Falls back to the source slot's own name.
//
// Phase 6c: switched from `binding.rename-to` (text) to
// `slot.source-slot-id` (FK). Both sources currently agree —
// parser + Phase 6b's ensure-rename-slot! emit them in lock-step.
function getEffectiveSlotName(fnId, slotId) {
  if (!lookups?.fnMap) return null;
  const visited = new Set();
  const queue = [fnId];
  while (queue.length) {
    const fid = queue.shift();
    if (visited.has(fid)) continue;
    visited.add(fid);
    const renamed = lookups.slotByFnSourceSlot?.get(fid + '|' + slotId);
    if (renamed) return renamed.name;
    const fn = lookups.fnMap.get(fid);
    if (fn && Array.isArray(fn['parent-ids'])) {
      for (const pid of fn['parent-ids']) {
        if (!visited.has(pid)) queue.push(pid);
      }
    }
  }
  const slot = lookups.slotMap?.get(slotId);
  return slot ? slot.name : null;
}

// Build an arg-shape row from a graph node's `data` plus the
// slot/binding lookups. Replaces `lookups.argMap.get(argId)` —
// pulls the same fields (fn-id, slot-id, binding-id, item-id,
// value, ref-id, type, name) directly from the layout-emitted
// `slotId` / `bindingId` / `itemId` / `fnId` / `argType` / `value`
// fields without needing the synth `:args` collection.
function argRowFromNode(nodeData) {
  if (!nodeData) return null;
  const data = (typeof nodeData.data === 'function') ? nodeData.data() : nodeData;
  if (!data) return null;
  // Node-data carries `argId`; edge-data carries `sourceArgId` for the
  // bound-arg side. Both name the same logical entity (the arg row /
  // synth row); accept either so the helper covers both sources.
  const argId = data.argId || data.sourceArgId;
  const slotId = data.slotId;
  const bindingId = data.bindingId;
  const itemId = data.itemId;
  const fnId = data.fnId;
  if (!argId && !slotId && !bindingId) return null;
  const slot = slotId && lookups?.slotMap?.get(slotId);
  const binding = bindingId && lookups?.bindingMap?.get(bindingId);
  const item = itemId && lookups?.itemByItemId?.get(itemId);
  const effName = fnId && slotId && (typeof getEffectiveSlotName === 'function')
                  ? getEffectiveSlotName(fnId, slotId) : null;
  // A `literal: true` row stores a keyword as a bare string (the colon
  // is stripped on the wire). Re-prefix it so the value materialises as
  // the keyword it represents — otherwise classifyLiteralJS reads it as
  // plain text and reports a false type mismatch.
  const rawValue = (item && 'value' in item) ? item.value
                 : (binding && 'value' in binding) ? binding.value
                 : (data.value !== undefined ? data.value : null);
  const isLiteralKw = (item?.literal === true || binding?.literal === true)
                      && typeof rawValue === 'string'
                      && rawValue.length > 0
                      && rawValue.charAt(0) !== ':';
  return {
    id: argId,
    'fn-id': fnId,
    'slot-id': slotId,
    'binding-id': bindingId,
    'item-id': itemId,
    name: effName || (slot?.name) || null,
    type: data.argType || (slot?.['type-fn-id']
                            ? lookups.fnMap.get(slot['type-fn-id'])?.name
                            : null),
    position: item ? item.position : null,
    value: isLiteralKw ? ':' + rawValue : rawValue,
    'ref-id': (item?.['ref-fn-id']) || (binding?.['ref-fn-id']) || null,
    description: (binding?.description) || (slot?.description) || null
  };
}


// Editability gate — mirror of the backend's "fn-in-use-reason" delete
// check: a fn can be edited inline only if it's not used as a parent
// of any other fn AND not referenced as ref-id by any arg. Same rule
// as "deletable", on purpose: if the fn is wired into other places,
// changing its shape would break those places, so we force the user
// to navigate to the dependents and detach them first.
function isFnEditable(fnId) {
  if (!fnId || !lookups) return false;
  const usedAsParent = (lookups.fnUsedAsParent?.get(fnId)) || 0;
  const usedAsRef    = (lookups.fnUsedAsRef?.get(fnId))    || 0;
  return usedAsParent === 0 && usedAsRef === 0;
}

// Human-readable explanation when `isFnEditable` returns false — used
// by disabled-state row-action icons so a click reveals the concrete
// reason ("used as :parent by 3 fn(s)…") instead of nothing happening.
// Returns null when the fn IS editable.
function getFnEditBlockReason(fnId) {
  if (!fnId || !lookups) return null;
  const asParent = (lookups.fnUsedAsParent?.get(fnId)) || 0;
  const asRef    = (lookups.fnUsedAsRef?.get(fnId))    || 0;
  if (asParent === 0 && asRef === 0) return null;
  const parts = [];
  if (asParent > 0) parts.push('extended by ' + asParent + ' fn' + (asParent === 1 ? '' : 's'));
  if (asRef    > 0) parts.push('referenced by ' + asRef    + ' arg' + (asRef    === 1 ? '' : 's'));
  return 'In use — ' + parts.join(' and ') + '. Detach those first.';
}

// Look up a fn's namespace as a dotted path (e.g. "core.collections")
// or null if the fn has no namespace assigned.
function getFnNamespace(fn) {
  if (!fn?.['namespace-id'] || !lookups?.nsPathMap) return null;
  return lookups.nsPathMap.get(fn['namespace-id']) || null;
}

// Get the qualified name for a fn (ns.path.name or just name if no ns)
function getQualifiedFnName(fn) {
  if (!fn) return '(anonymous)';
  const name = fn.name || '(anonymous)';
  const nsId = fn['namespace-id'];
  if (nsId && lookups?.nsPathMap) {
    const nsPath = lookups.nsPathMap.get(nsId);
    if (nsPath) return nsPath + '.' + name;
  }
  return name;
}


// Visible label for a fn-name in the editor — strips a single leading
// `_` that marks a fn as private (graphden-fn-design SKILL). The full
// name with prefix is still the canonical identifier used in
// `getQualifiedFnName`, URL hashes, EDN refs, and the i-tooltip's
// canonical-name line. This helper is purely for visual labels (sidebar
// rows, ancestor-overlay rows, fn-picker entries). Non-private names
// pass through unchanged.
function displayLabel(name) {
  if (!name || typeof name !== 'string') return name;
  return name.startsWith('_') ? name.slice(1) : name;
}

// ============================================================================
// INHERITANCE CHAIN
// ============================================================================

// Get inheritance as BFS layers: [[fnId], [parent1, parent2, ...], [gp1, gp2, ...], ...]
// Each layer holds all fns reachable in exactly N parent-hops, deduped so each
// fn appears only at its shallowest depth.
//
// Memoised in `lookups.inheritanceLevelsCache` for the lifetime of
// the current lookups object — fn-overlay rendering calls this many
// times for the same fn-id (once per render + once per anonymous
// ancestor in buildAncestorLevels). The cache turns repeated BFS
// walks into single Map.get() calls.
function getInheritanceLevels(fnId) {
  const cache = lookups?.inheritanceLevelsCache;
  if (cache?.has(fnId)) return cache.get(fnId);
  const levels = [];
  let currentLevel = [fnId];
  const visited = new Set([fnId]);
  while (currentLevel.length > 0) {
    levels.push(currentLevel);
    const nextLevel = [];
    for (const id of currentLevel) {
      const fn = lookups.fnMap.get(id);
      const parentIds = fn ? fn['parent-ids'] : null;
      if (!parentIds) continue;
      for (const pid of parentIds) {
        if (!visited.has(pid)) {
          visited.add(pid);
          nextLevel.push(pid);
        }
      }
    }
    currentLevel = nextLevel;
  }
  if (cache) cache.set(fnId, levels);
  return levels;
}

// Flat BFS-ordered list of all reachable ancestors (including fnId itself).
// Use getInheritanceLevels when you need the per-level structure.
function getInheritanceChain(fnId) {
  return getInheritanceLevels(fnId).flat();
}

// ============================================================================
// ARG RESOLUTION
// ============================================================================

// Check if fn sets any of its slots — a binding row with a value,
// a ref, or list-append. These are the bindings that make ancestor
// rows "interesting" enough to render as their own group on the
// fn-overlay.
function fnSetsArgs(fnId) {
  const bindings = (lookups.bindingsByFn?.get(fnId)) || [];
  return bindings.some(b => {
    const hasValue = b.value !== null && b.value !== undefined;
    const hasRef = !!b['ref-fn-id'];
    const hasItems = !!b['list-append'];
    return hasValue || hasRef || hasItems;
  });
}

// ============================================================================
// ANCESTOR LEVELS (for overlay display)
// ============================================================================

// Build ancestor levels for display in node overlay.
// Each BFS level is rendered as ONE visual line. Multi-fn levels (multiple
// inheritance) display every parent on the same line, separated by a
// vertical bar; each parent is individually clickable.
//
// Grouping: an "empty" level (whose fns set no args) joins the previous
// "real" level — no separator between them. Multi-fn levels (MI) ALWAYS
// start a new group, and the empty level immediately after an MI also
// starts a new group, so MI never visually merges with neighbours.
//
// Returns: [{
//   depth: number,
//   fns: [{fnId, name, setsArgs}],
//   isMI: bool,
//   anySets: bool,
//   groupId, groupMaxDepth
// }]
function buildAncestorLevels(levels) {
  const enriched = levels.map((fnIds, depth) => {
    const topLevel = depth === 0;
    const fns = fnIds.map(fnId => {
      const fn = lookups.fnMap.get(fnId);
      // Name rendering: at the top level (depth=0) an anonymous fn stays
      // empty so the black header bar visually shows "no own name" and the
      // REAL parent chain renders on the rows below (which the user can
      // then expand). For deeper levels (>=1) we still fall back to the
      // nearest named ancestor so each row shows something meaningful.
      let displayName = fn?.name;
      if (!displayName && fn && !topLevel) {
        const chain = getInheritanceChain(fnId);
        for (let i = 1; i < chain.length; i++) {
          const ancestor = lookups.fnMap.get(chain[i]);
          if (ancestor?.name) { displayName = ancestor.name; break; }
        }
      }
      return {
        fnId,
        name: displayName || (topLevel ? '' : '(anonymous)'),
        description: fn?.description,
        setsArgs: fnSetsArgs(fnId),
        isClickable: fnSetsArgs(fnId)  // has value or ref-id → produces new nodes on expand
      };
    });
    return {
      depth,
      fns,
      isMI: fns.length > 1,
      anySets: fns.some(f => f.setsArgs),
      anyClickable: fns.some(f => f.isClickable)
    };
  });

  // Dedup consecutive single-fn levels with identical names (e.g. an
  // anonymous fn whose resolved ancestor name matches the very next level).
  // At depth 0 we now keep the empty-name anonymous row distinct from the
  // named ancestor below, so dedup never collapses it.
  for (let i = enriched.length - 1; i > 0; i--) {
    const prev = enriched[i - 1];
    const cur = enriched[i];
    if (!cur.isMI && !prev.isMI
        && cur.fns.length === 1 && prev.fns.length === 1
        && cur.fns[0].name === prev.fns[0].name
        && cur.fns[0].name !== '') {
      enriched.splice(i - 1, 1); // remove the earlier duplicate
    }
  }

  // Grouping: a level starts a new visual group if it has at least one
  // CLICKABLE fn.  Non-clickable levels merge with the predecessor block
  // (they share its bg and have no separator).  MI always starts a new
  // group (individual parents will be styled per-cell inside the group).
  let groupId = -1;
  enriched.forEach((lv, idx) => {
    const prev = enriched[idx - 1];
    const startsNew =
      idx === 0 ||
      lv.anyClickable ||    // at least one clickable fn
      lv.isMI ||            // MI always starts its own group
      (prev?.isMI);  // level after MI also starts a new group
    if (startsNew) groupId++;
    lv.groupId = groupId;
  });

  const maxDepthByGroup = new Map();
  enriched.forEach(lv => {
    const cur = maxDepthByGroup.get(lv.groupId);
    if (cur === undefined || lv.depth > cur) maxDepthByGroup.set(lv.groupId, lv.depth);
  });
  enriched.forEach(lv => { lv.groupMaxDepth = maxDepthByGroup.get(lv.groupId); });

  // Compute visual block assignment and column-below-MI flag.
  //
  // blockIsRoot: true for levels that belong to the root's black-bg block.
  // followsMI: index of the MI level this non-clickable level sits below
  //            (for column-split background), or -1 if not applicable.
  let currentBlockIsRoot = true;
  let lastMIIdx = -1;
  enriched.forEach((lv, idx) => {
    if (idx === 0) {
      lv.blockIsRoot = true;
    } else {
      // Only a CLICKABLE level breaks out of the root block.
      // MI with all-non-clickable parents stays in root block.
      if (lv.anyClickable) {
        currentBlockIsRoot = false;
      }
      lv.blockIsRoot = currentBlockIsRoot;
    }
    // Track column-below-MI: a non-clickable, non-MI level immediately
    // after (or chaining from) an MI level gets column-split rendering.
    if (lv.isMI) {
      lastMIIdx = idx;
      lv.followsMI = -1;
    } else if (!lv.anyClickable && lastMIIdx >= 0) {
      lv.followsMI = lastMIIdx;  // index of the MI level to inherit columns from
    } else {
      lv.followsMI = -1;
      lastMIIdx = -1;  // clickable level breaks the MI column chain
    }
  });

  return enriched;
}
