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

  (data.fns || []).forEach(f => fnMap.set(f.id, f));
  (data.args || []).forEach(a => {
    argMap.set(a.id, a);
    const fnId = a['fn-id'];
    if (fnId) {
      if (!argsByFn.has(fnId)) argsByFn.set(fnId, []);
      argsByFn.get(fnId).push(a);
    }
  });

  return { fnMap, argMap, argsByFn };
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
    const fns = fnIds.map(fnId => {
      const fn = lookups.fnMap.get(fnId);
      return {
        fnId,
        name: (fn && fn.name) || '(anonymous)',
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
