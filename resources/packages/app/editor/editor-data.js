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

// Get inheritance chain: [fnId, parentId, grandparentId, ...]
function getInheritanceChain(fnId) {
  const chain = [];
  let current = fnId;
  const visited = new Set();
  while (current && !visited.has(current)) {
    visited.add(current);
    chain.push(current);
    const fn = lookups.fnMap.get(current);
    current = fn ? fn['parent-id'] : null;
  }
  return chain;
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

// ============================================================================
// ANCESTOR ITEMS (for overlay display)
// ============================================================================

// Build ancestor items for display in node overlay
// Groups consecutive non-arg-setting ancestors with previous "real" ancestor
// A "real" ancestor is one that sets args (has value or ref-id)
// Empty ancestors (no args set) are grouped with the previous real one
function buildAncestorItems(chain) {
  const items = [];
  let currentGroupId = 0;       // Unique group identifier
  let currentGroupLevel = 0;    // Level of the "real" ancestor in this group

  chain.forEach((fnId, idx) => {
    const fn = lookups.fnMap.get(fnId);
    if (!fn) return;

    const name = fn.name || '(anonymous)';
    const setsArgs = fnSetsArgs(fnId);

    if (setsArgs) {
      // This is a "real" ancestor - start new group
      currentGroupId++;
      currentGroupLevel = idx;
    }
    // else: empty ancestor - stays in current group with previous real ancestor

    items.push({
      fnId,
      name,
      level: idx,
      groupId: currentGroupId,           // Which group this belongs to
      groupLevel: currentGroupLevel,     // Level of the "real" ancestor in this group
      setsArgs,
      isGroupStart: setsArgs             // Is this the start of a group (real ancestor)?
    });
  });

  return items;
}
