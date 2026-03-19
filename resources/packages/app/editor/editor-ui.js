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
 */
function setExpansionLevel(originalFnId, level) {
  if (level === 0) {
    expansionLevel.delete(originalFnId);
  } else {
    expansionLevel.set(originalFnId, level);
  }
  previewLevel.delete(originalFnId);
  renderGraph(false);
}

/**
 * Set preview level (hover)
 */
function setPreviewLevel(originalFnId, level) {
  const oldLevel = previewLevel.get(originalFnId);

  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }

  if (level === null) {
    previewLevel.delete(originalFnId);
    if (oldLevel !== level) {
      renderGraph(false);
    }
  } else {
    previewDebounceTimer = setTimeout(() => {
      previewLevel.set(originalFnId, level);
      if (oldLevel !== level) {
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
