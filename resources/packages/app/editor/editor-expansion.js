// Editor Expansion Control — the spec→state→preview machine that
// drives ancestor row click/hover behaviour.
//
// Vocabulary:
//   spec      — {fullDepth, partialFns}: which ancestor levels are
//                expanded for a given fn-overlay node
//   committed — what the graph currently renders (expansionState)
//   preview   — what hovering would render if the user clicked
//                (previewState; debounced)
//
// Globals consumed: expansionState, previewState, anchorNodeId,
// previewDebounceTimer, savedUserPositions, suppressPreviewOnClick,
// renderGraph, PREVIEW_DEBOUNCE_MS.

// ============================================================================
// EXPANSION CONTROL
// ============================================================================

/**
 * Get the current spec for a node, defaulting to empty (no expansion).
 */
function getSpec(nodeId) {
  return expansionState.get(nodeId) || { fullDepth: 0, partialFns: new Set() };
}

/**
 * Apply a click/hover on an ancestor row.
 *
 * SINGLE-FN at depth L → spec = {fullDepth: L, partial: empty}
 *   "expand exactly to L" — pure SET, no toggle. Clicking a row already
 *   inside the expansion is a no-op; to collapse a level the user clicks
 *   a SHALLOWER row (which sets fullDepth to that shallower level, hiding
 *   anything deeper). The visual model treats a grouped block (e.g.
 *   merge-in + assoc-in joined into one cell) as a single unit — hovering
 *   inside an already-expanded block must NOT preview an asymmetric
 *   half-collapse like "merge-in stays, assoc-in disappears".
 *
 * MULTI-FN parent (MI) → toggle membership in partial:
 *   - Currently NOT in expansion: ADD this fn (cascade through shallower
 *     levels first if needed). If after adding, partial covers ALL MI fns
 *     at that depth, auto-promote to fullDepth = depth (clear partial).
 *   - Currently IS in expansion: REMOVE this fn. If was fully expanded,
 *     unpromote to {fullDepth: depth - 1, partial: (other MI fns)}.
 *     Also collapses anything deeper than this depth (since deeper required
 *     this fn as part of its cascade).
 *
 * This lets the user select ONE OR SEVERAL MI parents but not all.
 * Selecting a deeper non-MI level cascades (auto-includes all MI).
 *
 * Depth 0 → null (collapse all).
 */
function computeSpecAfterClick(currentSpec, depth, fnId, allFnsAtDepth) {
  if (depth <= 0) return null;
  const isMI = allFnsAtDepth && allFnsAtDepth.length > 1;
  const fullDepth = currentSpec.fullDepth || 0;

  if (!isMI) {
    // Pure SET: expand to exactly this depth. If already there or deeper,
    // the spec is unchanged (no asymmetric collapse). To shrink, the user
    // clicks a shallower row.
    return { fullDepth: depth, partialFns: new Set() };
  }

  const partial = new Set(currentSpec.partialFns || []);

  // Is this fn already part of the committed expansion?
  const fullyExpandedHere = depth <= fullDepth;
  const inPartialHere = depth === fullDepth + 1 && partial.has(fnId);
  const currentlyExpanded = fullyExpandedHere || inPartialHere;

  if (currentlyExpanded) {
    // TOGGLE OFF: remove this fn from the expansion.
    if (fullyExpandedHere) {
      // Was fully expanded at this depth — keep all OTHER MI fns at this
      // depth as partial; collapse anything deeper than this depth.
      const others = allFnsAtDepth.filter(f => f !== fnId);
      const newFull = depth - 1;
      if (newFull <= 0 && others.length === 0) return null;
      return { fullDepth: newFull, partialFns: new Set(others) };
    }
    // depth === fullDepth + 1 and fnId in partial: just remove from partial
    partial.delete(fnId);
    if (partial.size === 0 && fullDepth === 0) return null;
    return { fullDepth, partialFns: partial };
  }

  // TOGGLE ON: add this fn.
  if (depth > fullDepth + 1) {
    // Cascade: fully expand intermediate levels, then add this MI fn
    return { fullDepth: depth - 1, partialFns: new Set([fnId]) };
  }
  // depth === fullDepth + 1: add to existing partial
  partial.add(fnId);
  // Auto-promote when all MI fns at this depth are now selected
  if (allFnsAtDepth.every(f => partial.has(f))) {
    return { fullDepth: depth, partialFns: new Set() };
  }
  return { fullDepth, partialFns: partial };
}

/**
 * True when two specs (or absence-of-spec) describe the same expansion.
 * `null` and a missing entry both behave like {fullDepth: 0, partialFns: ∅}.
 */
function specsEqual(a, b) {
  const aFull = a ? a.fullDepth : 0;
  const bFull = b ? b.fullDepth : 0;
  if (aFull !== bFull) return false;
  const aPartial = (a?.partialFns) || new Set();
  const bPartial = (b?.partialFns) || new Set();
  if (aPartial.size !== bPartial.size) return false;
  for (const f of aPartial) if (!bPartial.has(f)) return false;
  return true;
}


/**
 * Apply spec change for click on a fn at a depth.
 */
function applyClickSpec(nodeId, depth, fnId, allFnsAtDepth) {
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }
  anchorNodeId = nodeId;

  const currentSpec = expansionState.get(nodeId);
  const newSpec = computeSpecAfterClick(getSpec(nodeId), depth, fnId, allFnsAtDepth);
  // No-op if the click would leave the expansion unchanged. Important now
  // that non-MI clicks are pure SET — re-clicking an already-expanded row
  // would otherwise clear savedUserPositions and trigger a needless re-render.
  if (specsEqual(currentSpec, newSpec)) {
    suppressPreviewOnClick();
    previewState.delete(nodeId);
    anchorNodeId = null;
    return;
  }
  if (newSpec === null) {
    expansionState.delete(nodeId);
  } else {
    expansionState.set(nodeId, newSpec);
  }
  previewState.delete(nodeId);
  // Suppress preview until cursor leaves the element. This prevents the
  // "ghost preview" where committed state is immediately reversed.
  suppressPreviewOnClick();
  // Commit clears saved user positions — nodes that disappeared in the
  // committed state lose their manual position.
  savedUserPositions.clear();
  // Did this click GROW the expansion? Only then auto-fit — newly
  // revealed nodes must not land off-screen; a collapse always fits
  // within the previous view, and refitting there would yank the
  // user's pan/zoom for nothing.
  const grew = newSpec !== null
    && (!currentSpec
        || (newSpec.fullDepth || 0) > (currentSpec.fullDepth || 0)
        || (newSpec.partialFns?.size || 0)
           > (currentSpec.partialFns?.size || 0));
  const rendered = renderGraph(false);
  if (grew && typeof fitGraphIfOverflowing === 'function') {
    // Fit once the render has LANDED. `renderGraph` awaits the backend
    // layout, so a fit scheduled on the next frames measured the OLD graph
    // and found it fitting — the card the click had just revealed sat
    // off-screen under the inspector, exactly the dead end this guard is
    // for (lesson 18's unfolded hop). New nodes jump straight
    // to their place; the survivors tween for ANIM_DURATION, so measure
    // after the tween.
    Promise.resolve(rendered).then(() => {
      setTimeout(fitGraphIfOverflowing, ANIM_DURATION + 30);
    });
  }
  anchorNodeId = null;
}

/**
 * Unfold the card of `fnId` so what hangs off it is drawn — the STATE half
 * of a click on its first parent row, without the render: the caller is
 * about to trigger one anyway (a write → `loadGraphData`), and the
 * expansion must be in place before it so that single render shows the
 * new hop. Used when a ref is bound or appended on a child card: the
 * reader is building here, so the canvas shows what they just wired
 * instead of leaving it folded inside a closed card (reopening the fn
 * later shows closed cards again — names are boundaries). A nav-root card
 * draws its own refs already — nothing to do (null). Returns a token for
 * `refoldNodeForBuild` when the state changed.
 */
function unfoldNodeForBuild(fnId) {
  if (!fnId) return null;
  const overlay = document.querySelector('.node-overlay[data-original-fn-id="' + fnId + '"]');
  const nodeId = overlay?.dataset?.nodeId;
  if (!nodeId || nodeId === 'fn-' + fnId) return null;
  const current = expansionState.get(nodeId);
  if ((current?.fullDepth || 0) >= 1) return null;
  expansionState.set(nodeId, { fullDepth: 1, partialFns: new Set() });
  previewState.delete(nodeId);
  savedUserPositions.clear();
  return { nodeId, previous: current || null };
}

// Put back what `unfoldNodeForBuild` changed — for a write that then failed.
function refoldNodeForBuild(token) {
  if (!token?.nodeId) return;
  if (token.previous) expansionState.set(token.nodeId, token.previous);
  else expansionState.delete(token.nodeId);
}

/**
 * Set preview spec (hover). Uses debouncing to avoid flicker.
 *
 * IMPORTANT: clicks are bound to `mousedown` (not `click`) so that the
 * click action fires BEFORE any pending hover render can shift the layout.
 * This keeps clicks reliable even with hover preview active.
 */
function applyHoverSpec(nodeId, depth, fnId, allFnsAtDepth) {
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }
  const newSpec = computeSpecAfterClick(getSpec(nodeId), depth, fnId, allFnsAtDepth);
  const committed = expansionState.get(nodeId);
  const oldPreview = previewState.get(nodeId);

  // What the layout actually needs to render under this hover. If hover
  // reproduces the committed state, no preview is needed — clear it (or
  // do nothing if there was none). This avoids hammering the backend
  // with no-op layouts for hovers that wouldn't change anything.
  const matchesCommitted = specsEqual(committed, newSpec);
  const effectiveSpec = matchesCommitted
    ? null
    : (newSpec || { fullDepth: 0, partialFns: new Set() });

  // Already in the desired preview state? Skip.
  if (effectiveSpec === null && !previewState.has(nodeId)) return;
  if (effectiveSpec && oldPreview && specsEqual(oldPreview, effectiveSpec)) return;

  previewDebounceTimer = setTimeout(() => {
    anchorNodeId = nodeId;
    if (effectiveSpec === null) {
      previewState.delete(nodeId);
    } else {
      previewState.set(nodeId, effectiveSpec);
    }
    renderGraph(false);
    anchorNodeId = null;
  }, PREVIEW_DEBOUNCE_MS);
}

function clearPreview(nodeId) {
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }
  if (!previewState.has(nodeId)) return;
  previewDebounceTimer = setTimeout(() => {
    anchorNodeId = nodeId;
    previewState.delete(nodeId);
    renderGraph(false);
    anchorNodeId = null;
  }, PREVIEW_DEBOUNCE_MS);
}


