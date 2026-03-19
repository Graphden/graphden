// Editor Graph - Building graph elements (nodes, edges) from data
// Depends on: editor-state.js, editor-data.js

// ============================================================================
// BUILD GRAPH ELEMENTS
// ============================================================================

/**
 * Build graph elements (nodes, edges) from selected function
 * Handles expansion levels and binding resolution
 */
function buildGraphElements() {
  const nodes = [];
  const edges = [];
  const addedNodeIds = new Set();

  if (!selectedFnId || !lookups.fnMap.has(selectedFnId)) {
    return { nodes: [], edges: [] };
  }

  // ============================================================================
  // HELPER FUNCTIONS
  // ============================================================================

  function getEffectiveLevel(originalFnId) {
    if (previewLevel.has(originalFnId)) {
      return previewLevel.get(originalFnId);
    }
    return expansionLevel.get(originalFnId) || 0;
  }

  function getSourceChain(argId) {
    const chain = [argId];
    let current = lookups.argMap.get(argId);
    while (current && current['source-id']) {
      chain.push(current['source-id']);
      current = lookups.argMap.get(current['source-id']);
    }
    return chain;
  }

  // ============================================================================
  // BINDINGS
  // ============================================================================

  function addBindingsFromFn(fnId, bindings) {
    const args = lookups.argsByFn.get(fnId) || [];

    args.forEach(arg => {
      const hasValue = arg.value !== null && arg.value !== undefined;
      const hasRef = !!arg['ref-id'];

      if ((hasValue || hasRef) && arg['source-id']) {
        let sourceId = arg['source-id'];
        while (sourceId) {
          bindings.set(sourceId, {
            argName: resolveArgName(arg),
            value: arg.value,
            refId: arg['ref-id'],
            argId: arg.id
          });
          const sourceArg = lookups.argMap.get(sourceId);
          sourceId = sourceArg ? sourceArg['source-id'] : null;
        }
      }
    });
  }

  function buildArgBindings(fnId) {
    const bindings = new Map();
    addBindingsFromFn(fnId, bindings);
    return bindings;
  }

  function buildChainBindings(chain, targetLevel) {
    const bindings = new Map();
    for (let i = Math.min(targetLevel, chain.length) - 1; i >= 0; i--) {
      addBindingsFromFn(chain[i], bindings);
    }
    return bindings;
  }

  // ============================================================================
  // ARG COLLECTION
  // ============================================================================

  function collectFnArgs(fnId, bindings) {
    const args = lookups.argsByFn.get(fnId) || [];
    const refs = [];
    const values = [];
    const unset = [];

    args.forEach(arg => {
      const argName = resolveArgName(arg);
      const hasValue = arg.value !== null && arg.value !== undefined;
      const hasRef = !!arg['ref-id'];

      const binding = bindings.get(arg.id);

      if (binding) {
        if (binding.refId) {
          refs.push({ argName: binding.argName, refId: binding.refId, argId: binding.argId });
        } else if (binding.value !== null && binding.value !== undefined) {
          values.push({ argName: binding.argName, value: binding.value, argId: binding.argId });
        }
      } else if (hasRef) {
        refs.push({ argName, refId: arg['ref-id'], argId: arg.id });
      } else if (hasValue) {
        values.push({ argName, value: arg.value, argId: arg.id });
      } else {
        unset.push({ argName, type: arg.type || 'any', argId: arg.id });
      }
    });

    return { refs, values, unset };
  }

  // ============================================================================
  // NODE CREATION
  // ============================================================================

  function addFnNode(originalFnId, isRoot) {
    const nodeId = 'fn-' + originalFnId;
    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    const chain = getInheritanceChain(originalFnId);
    const items = buildAncestorItems(chain);
    const visibleItems = items.slice(0, MAX_VISIBLE_ANCESTORS + 1);

    const labelLines = visibleItems.map(item => item.name);
    if (items.length > MAX_VISIBLE_ANCESTORS + 1) {
      labelLines.push('...');
    }

    const label = labelLines.join('\n');

    nodes.push({
      data: {
        id: nodeId,
        label: label,
        type: 'fn',
        isRoot: isRoot,
        originalFnId: originalFnId
      }
    });

    return nodeId;
  }

  function addArgValueNode(argName, value, argId, sourceNodeId) {
    const nodeId = 'arg-' + argId;

    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    const displayValue = truncateLabel(JSON.stringify(value), 20);

    nodes.push({
      data: {
        id: nodeId,
        label: displayValue,
        type: 'arg'
      }
    });

    edges.push({
      data: {
        id: 'e-val-' + argId,
        source: sourceNodeId,
        target: nodeId,
        argName: argName
      }
    });

    return nodeId;
  }

  function addUnsetArgNode(argName, argType, argId, sourceNodeId) {
    const nodeId = 'unset-' + argId;

    if (addedNodeIds.has(nodeId)) return;
    addedNodeIds.add(nodeId);

    nodes.push({
      data: {
        id: nodeId,
        label: argType || 'any',
        type: 'fn',
        isPlaceholder: true
      }
    });

    edges.push({
      data: {
        id: 'e-unset-' + argId,
        source: sourceNodeId,
        target: nodeId,
        argName: argName,
        isUnset: true
      }
    });
  }

  // ============================================================================
  // FN PROCESSING
  // ============================================================================

  function processFn(originalFnId, displayFnId, bindings, sourceNodeId, edgeArgName, isRoot) {
    const nodeId = addFnNode(originalFnId, isRoot);

    if (sourceNodeId && edgeArgName !== null) {
      const edgeId = 'e-ref-' + sourceNodeId + '-' + originalFnId;
      if (!addedNodeIds.has(edgeId)) {
        addedNodeIds.add(edgeId);
        edges.push({
          data: {
            id: edgeId,
            source: sourceNodeId,
            target: nodeId,
            argName: edgeArgName
          }
        });
      }
    }

    const { refs, values, unset } = collectFnArgs(displayFnId, bindings);

    refs.forEach(({ argName, refId, argId }) => {
      processAnyFn(refId, nodeId, argName, false, bindings);
    });

    values.forEach(({ argName, value, argId }) => {
      addArgValueNode(argName, value, argId, nodeId);
    });

    unset.forEach(({ argName, type, argId }) => {
      addUnsetArgNode(argName, type, argId, nodeId);
    });

    return nodeId;
  }

  function processExpandedFn(originalFnId, level, sourceNodeId, edgeArgName, isRoot) {
    const chain = getInheritanceChain(originalFnId);
    const displayFnId = chain[Math.min(level, chain.length - 1)];
    const bindings = buildChainBindings(chain, level);
    return processFn(originalFnId, displayFnId, bindings, sourceNodeId, edgeArgName, isRoot);
  }

  function processAnyFn(fnId, sourceNodeId, edgeArgName, isRoot, parentBindings) {
    const level = getEffectiveLevel(fnId);

    if (level > 0) {
      return processExpandedFn(fnId, level, sourceNodeId, edgeArgName, isRoot);
    } else {
      const bindings = buildArgBindings(fnId);
      if (parentBindings) {
        parentBindings.forEach((v, k) => {
          if (!bindings.has(k)) {
            bindings.set(k, v);
          }
        });
      }
      return processFn(fnId, fnId, bindings, sourceNodeId, edgeArgName, isRoot);
    }
  }

  // ============================================================================
  // MAIN
  // ============================================================================

  processAnyFn(selectedFnId, null, null, true, null);

  return { nodes, edges };
}
