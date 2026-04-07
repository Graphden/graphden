// Editor UI - Sidebar, selection, expansion controls
// Depends on: editor-state.js, editor-data.js, editor-cytoscape.js

// ============================================================================
// DEBOUNCE
// ============================================================================

let previewDebounceTimer = null;
const PREVIEW_DEBOUNCE_MS = 100;

// ============================================================================
// SIDEBAR / ENTITY LIST
// ============================================================================

/**
 * Update the entity list in sidebar
 */
function updateEntityList(data) {
  const list = document.getElementById('entity-list');
  list.innerHTML = '';

  (data.fns || []).forEach(fn => {
    const li = document.createElement('li');
    li.className = 'entity-item';
    if (fn.id === selectedFnId) li.className += ' selected';
    li.dataset.fnId = fn.id;
    li.innerHTML = '<div class="name">' + fn.name + '</div>';
    li.onclick = () => selectFn(fn.id);
    list.appendChild(li);
  });

  if (list.children.length === 0) {
    list.innerHTML = '<li class="loading">No functions found</li>';
  }
}

// ============================================================================
// SELECTION
// ============================================================================

/**
 * Select a function by ID
 */
function selectFn(fnId, updateHistory = true) {
  selectedFnId = fnId;
  expansionLevel.clear();
  previewLevel.clear();
  userMovedNodes.clear();

  document.querySelectorAll('.entity-item').forEach(el => el.classList.remove('selected'));
  const item = document.querySelector('[data-fn-id="' + fnId + '"]');
  if (item) item.classList.add('selected');

  const fn = lookups.fnMap.get(fnId);
  if (fn && updateHistory) {
    window.history.pushState(null, '', '#' + fn.name);
  }

  renderGraph(true);
}

/**
 * Select a function by name
 */
function selectFnByName(name, updateHistory = true) {
  const fn = (graphData.fns || []).find(f => f.name === name);
  if (fn) selectFn(fn.id, updateHistory);
}

// ============================================================================
// EXPANSION CONTROL
// ============================================================================

/**
 * Set expansion level for a node (click)
 * @param {string} nodeId - The Cytoscape node ID (e.g., "fn-uuid" or "fn-uuid1_uuid2")
 * @param {number} level - Expansion level
 */
function setExpansionLevel(nodeId, level) {
  // Clear any pending preview debounce timer to prevent race condition:
  // If user clicks while preview debounce is pending, the debounced callback
  // would fire AFTER this render, causing the old overlay to be preserved
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }

  // Extract originalFnId from nodeId for anchor (last UUID in the node ID)
  // Node IDs are either "fn-{uuid}" or "fn-{uuid1}_{uuid2}"
  const parts = nodeId.replace('fn-', '').split('_');
  const originalFnId = parts[parts.length - 1];

  // Set this node as anchor so it stays stationary during layout
  anchorFnId = originalFnId;

  if (level === 0) {
    expansionLevel.delete(nodeId);
  } else {
    expansionLevel.set(nodeId, level);
  }
  previewLevel.delete(nodeId);
  renderGraph(false);

  // Clear anchor after render
  anchorFnId = null;
}

/**
 * Set preview level (hover)
 * @param {string} nodeId - The Cytoscape node ID (e.g., "fn-uuid" or "fn-uuid1_uuid2")
 * @param {number|null} level - Preview level or null to clear
 *
 * Uses debouncing to prevent flickering when:
 * 1. Mouse moves between overlay lines (level changes within same overlay)
 * 2. Overlays are rebuilt (mouseleave fires on removed overlays)
 */
function setPreviewLevel(nodeId, level) {
  const oldLevel = previewLevel.get(nodeId);

  // Clear any pending debounce timer
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }

  if (level === null) {
    // Only process if there was actually a preview set for this nodeId
    if (oldLevel === undefined) {
      return; // No preview was set for this nodeId
    }

    // Debounce the clear to allow mouseenter on new overlay to cancel it
    previewDebounceTimer = setTimeout(() => {
      // Re-check: if preview level changed (mouse entered another overlay), don't clear
      const currentLevel = previewLevel.get(nodeId);
      if (currentLevel === oldLevel) {
        previewLevel.delete(nodeId);
        renderGraph(false);
      }
    }, PREVIEW_DEBOUNCE_MS);
  } else {
    // Check if this is the same level as currently set - skip entirely
    if (oldLevel === level) {
      return; // No change needed
    }

    // Debounce the preview change
    previewDebounceTimer = setTimeout(() => {
      // Re-check in case state changed during debounce
      const currentLevel = previewLevel.get(nodeId);
      if (currentLevel !== level) {
        previewLevel.set(nodeId, level);
        renderGraph(false);
      }
    }, PREVIEW_DEBOUNCE_MS);
  }
}

/**
 * Clear all preview state
 */
function clearPreviewState() {
  if (previewLevel.size > 0) {
    previewLevel.clear();
    renderGraph(false);
  }
}
