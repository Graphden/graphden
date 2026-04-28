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

// ============================================================================
// LOOKUP MAPS
// ============================================================================

// Build lookup maps from raw graph data
function buildLookups(data) {
  const fnMap = new Map();
  const argMap = new Map();
  const argsByFn = new Map();
  const nsMap = new Map();    // ns-id → ns entity
  const nsPathMap = new Map(); // ns-id → full dotted path (e.g. "core.arithmetic")

  (data.fns || []).forEach(f => fnMap.set(f.id, f));
  (data.args || []).forEach(a => {
    argMap.set(a.id, a);
    const fnId = a['fn-id'];
    if (fnId) {
      if (!argsByFn.has(fnId)) argsByFn.set(fnId, []);
      argsByFn.get(fnId).push(a);
    }
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

  // Deletability sets — used by the sidebar's ✕ button to grey out
  // entities that can't be safely removed and surface the reason in
  // the button's tooltip. The backend enforces these same rules
  // (`process-delete-entity` returns 409 + an explanation), so the
  // frontend computation is just for ahead-of-time UX feedback.
  const fnUsedAsParent = new Map();   // fn-id → count
  const fnUsedAsRef    = new Map();   // fn-id → count
  const nsHasChildNs   = new Map();   // ns-id → count
  const nsHasChildFn   = new Map();   // ns-id → count
  const bump = (m, k) => { if (k) m.set(k, (m.get(k) || 0) + 1); };

  (data.fns || []).forEach(f => {
    (f['parent-ids'] || []).forEach(pid => bump(fnUsedAsParent, pid));
    bump(nsHasChildFn, f['namespace-id']);
  });
  (data.args || []).forEach(a => bump(fnUsedAsRef, a['ref-id']));
  (data.namespaces || []).forEach(ns => bump(nsHasChildNs, ns['parent-id']));

  return { fnMap, argMap, argsByFn, nsMap, nsPathMap,
           fnUsedAsParent, fnUsedAsRef, nsHasChildNs, nsHasChildFn };
}

// Mirror of the backend's `value_kind` enum
// (src/graphden/schema/graph/schema.clj). Used by the in-graph
// type-pickers (return-type chip on fn cards, arg type-chip in
// Phase 2). Order matches the schema's declaration order, which is
// also the order users will see in dropdowns.
const VALUE_KINDS = ['null', 'uuid', 'text', 'int', 'bool', 'numeric',
                     'timestamptz', 'jsonb', 'bytes', 'any', 'fn', 'sequence'];

// Editability gate — mirror of the backend's "fn-in-use-reason" delete
// check: a fn can be edited inline only if it's not used as a parent
// of any other fn AND not referenced as ref-id by any arg. Same rule
// as "deletable", on purpose: if the fn is wired into other places,
// changing its shape would break those places, so we force the user
// to navigate to the dependents and detach them first.
function isFnEditable(fnId) {
  if (!fnId || !lookups) return false;
  const usedAsParent = (lookups.fnUsedAsParent && lookups.fnUsedAsParent.get(fnId)) || 0;
  const usedAsRef    = (lookups.fnUsedAsRef    && lookups.fnUsedAsRef.get(fnId))    || 0;
  return usedAsParent === 0 && usedAsRef === 0;
}

// Look up a fn's namespace as a dotted path (e.g. "core.collections")
// or null if the fn has no namespace assigned.
function getFnNamespace(fn) {
  if (!fn || !fn['namespace-id'] || !lookups || !lookups.nsPathMap) return null;
  return lookups.nsPathMap.get(fn['namespace-id']) || null;
}

// Get the qualified name for a fn (ns.path.name or just name if no ns)
function getQualifiedFnName(fn) {
  if (!fn) return '(anonymous)';
  const name = fn.name || '(anonymous)';
  const nsId = fn['namespace-id'];
  if (nsId && lookups && lookups.nsPathMap) {
    const nsPath = lookups.nsPathMap.get(nsId);
    if (nsPath) return nsPath + '.' + name;
  }
  return name;
}

// ============================================================================
// INHERITANCE CHAIN
// ============================================================================

// Get inheritance as BFS layers: [[fnId], [parent1, parent2, ...], [gp1, gp2, ...], ...]
// Each layer holds all fns reachable in exactly N parent-hops, deduped so each
// fn appears only at its shallowest depth.
function getInheritanceLevels(fnId) {
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

// Resolve arg name by following source chain
function resolveArgName(arg) {
  let current = arg;
  for (let i = 0; i < 100; i++) {
    if (current.name) return current.name;
    if (!current['source-id']) return null;
    current = lookups.argMap.get(current['source-id']);
    if (!current) return null;
  }
  return null;
}

// Check if fn sets any args (has value or ref-id)
function fnSetsArgs(fnId) {
  const args = lookups.argsByFn.get(fnId) || [];
  return args.some(arg => {
    const hasValue = arg.value !== null && arg.value !== undefined;
    const hasRef = !!arg['ref-id'];
    return hasValue || hasRef;
  });
}

// Check if fn has any ref-id args (references to other fns).
// A fn with refs is "clickable" — expanding it produces new graph nodes.
// A fn with only value/free args is "non-clickable" — its values are
// discoverable via inheritance without expanding.
function fnHasRefArgs(fnId) {
  const args = lookups.argsByFn.get(fnId) || [];
  return args.some(arg => !!arg['ref-id']);
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
      let displayName = fn && fn.name;
      if (!displayName && fn && !topLevel) {
        const chain = getInheritanceChain(fnId);
        for (let i = 1; i < chain.length; i++) {
          const ancestor = lookups.fnMap.get(chain[i]);
          if (ancestor && ancestor.name) { displayName = ancestor.name; break; }
        }
      }
      return {
        fnId,
        name: displayName || (topLevel ? '' : '(anonymous)'),
        description: fn && fn.description,
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
      (prev && prev.isMI);  // level after MI also starts a new group
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
