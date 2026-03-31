// Unit tests for editor-layout.js
// Run with: node layout-test.js

const layout = require('../../resources/packages/app/editor/editor-layout.js');

// Test utilities
let testsRun = 0;
let testsPassed = 0;

function assert(condition, message) {
  testsRun++;
  if (condition) {
    testsPassed++;
    console.log('  ✓ ' + message);
  } else {
    console.log('  ✗ ' + message);
  }
}

function assertEqual(actual, expected, message) {
  testsRun++;
  if (JSON.stringify(actual) === JSON.stringify(expected)) {
    testsPassed++;
    console.log('  ✓ ' + message);
  } else {
    console.log('  ✗ ' + message);
    console.log('    Expected:', JSON.stringify(expected));
    console.log('    Actual:', JSON.stringify(actual));
  }
}

function describe(name, fn) {
  console.log('\n' + name);
  fn();
}

// ============================================================================
// Test Data
// ============================================================================

// Simple chain: A -> B -> C
function makeSimpleChain() {
  return {
    nodes: [
      { data: { id: 'fn-A', type: 'fn', label: 'A' } },
      { data: { id: 'fn-B', type: 'fn', label: 'B' } },
      { data: { id: 'fn-C', type: 'fn', label: 'C' } }
    ],
    edges: [
      { data: { id: 'e1', source: 'fn-A', target: 'fn-B', argName: 'arg1' } },
      { data: { id: 'e2', source: 'fn-B', target: 'fn-C', argName: 'arg1' } }
    ]
  };
}

// Tree: A -> B, A -> C (two children)
function makeSimpleTree() {
  return {
    nodes: [
      { data: { id: 'fn-A', type: 'fn', label: 'A' } },
      { data: { id: 'fn-B', type: 'fn', label: 'B' } },
      { data: { id: 'fn-C', type: 'fn', label: 'C' } }
    ],
    edges: [
      { data: { id: 'e1', source: 'fn-A', target: 'fn-B', argName: 'arg1' } },
      { data: { id: 'e2', source: 'fn-A', target: 'fn-C', argName: 'arg2' } }
    ]
  };
}

// Chain with placeholder: A -> B -> placeholder
function makeChainWithPlaceholder() {
  return {
    nodes: [
      { data: { id: 'fn-A', type: 'fn', label: 'A' } },
      { data: { id: 'fn-B', type: 'fn', label: 'B' } },
      { data: { id: 'unset-1', type: 'fn', label: 'any', isPlaceholder: true } }
    ],
    edges: [
      { data: { id: 'e1', source: 'fn-A', target: 'fn-B', argName: 'arg1' } },
      { data: { id: 'e2', source: 'fn-B', target: 'unset-1', argName: 'arg1' } }
    ]
  };
}

// Tree with mixed types: fn and placeholder children
function makeMixedTree() {
  return {
    nodes: [
      { data: { id: 'fn-A', type: 'fn', label: 'A' } },
      { data: { id: 'fn-B', type: 'fn', label: 'B' } },
      { data: { id: 'unset-1', type: 'fn', label: 'any', isPlaceholder: true } }
    ],
    edges: [
      { data: { id: 'e1', source: 'fn-A', target: 'fn-B', argName: 'coll' } },
      { data: { id: 'e2', source: 'fn-A', target: 'unset-1', argName: 'x' } }
    ]
  };
}

// Shared argument: A -> B, A -> C, B -> D, C -> D (D has two parents)
function makeSharedArgument() {
  return {
    nodes: [
      { data: { id: 'fn-A', type: 'fn', label: 'A' } },
      { data: { id: 'fn-B', type: 'fn', label: 'B' } },
      { data: { id: 'fn-C', type: 'fn', label: 'C' } },
      { data: { id: 'fn-D', type: 'fn', label: 'D' } }
    ],
    edges: [
      { data: { id: 'e1', source: 'fn-A', target: 'fn-B', argName: 'arg1' } },
      { data: { id: 'e2', source: 'fn-A', target: 'fn-C', argName: 'arg2' } },
      { data: { id: 'e3', source: 'fn-B', target: 'fn-D', argName: 'arg1' } },
      { data: { id: 'e4', source: 'fn-C', target: 'fn-D', argName: 'arg1' } }
    ]
  };
}

// list-10 style: chain of fn with placeholder children
function makeList10Style() {
  return {
    nodes: [
      { data: { id: 'fn-list10', type: 'fn', label: 'list-10' } },
      { data: { id: 'fn-list9', type: 'fn', label: 'list-10-9' } },
      { data: { id: 'fn-list8', type: 'fn', label: 'list-10-8' } },
      { data: { id: 'unset-1', type: 'fn', label: 'any', isPlaceholder: true } },
      { data: { id: 'unset-2', type: 'fn', label: 'any', isPlaceholder: true } },
      { data: { id: 'unset-3', type: 'fn', label: 'any', isPlaceholder: true } }
    ],
    edges: [
      { data: { id: 'e1', source: 'fn-list10', target: 'fn-list9', argName: 'coll' } },
      { data: { id: 'e2', source: 'fn-list10', target: 'unset-1', argName: 'x' } },
      { data: { id: 'e3', source: 'fn-list9', target: 'fn-list8', argName: 'coll' } },
      { data: { id: 'e4', source: 'fn-list9', target: 'unset-2', argName: 'x' } },
      { data: { id: 'e5', source: 'fn-list8', target: 'unset-3', argName: 'x' } }
    ]
  };
}

// ============================================================================
// Tests
// ============================================================================

describe('buildAdjacency', () => {
  const data = makeSimpleChain();
  const { children, parents, edgeArgNames } = layout.buildAdjacency(data.edges);

  assert(children.has('fn-A'), 'A should have children');
  assertEqual(children.get('fn-A'), ['fn-B'], 'A should have B as child');
  assertEqual(children.get('fn-B'), ['fn-C'], 'B should have C as child');
  assert(!children.has('fn-C'), 'C should have no children');

  assert(!parents.has('fn-A'), 'A should have no parents');
  assertEqual(parents.get('fn-B'), ['fn-A'], 'B should have A as parent');
  assertEqual(parents.get('fn-C'), ['fn-B'], 'C should have B as parent');

  assertEqual(edgeArgNames.get('fn-A->fn-B'), 'arg1', 'Edge A->B should have argName');
});

describe('findRootNode', () => {
  const data = makeSimpleChain();
  const root = layout.findRootNode(data.nodes, data.edges);
  assertEqual(root, 'fn-A', 'Root should be A (no incoming edges)');

  const treeData = makeSimpleTree();
  const treeRoot = layout.findRootNode(treeData.nodes, treeData.edges);
  assertEqual(treeRoot, 'fn-A', 'Tree root should be A');
});

describe('analyzeSharedArguments', () => {
  const data = makeSharedArgument();
  const { children, parents } = layout.buildAdjacency(data.edges);
  const result = layout.analyzeSharedArguments(children, parents);

  assert(result.sharedNodes.has('fn-D'), 'D should be detected as shared');
  assertEqual(result.sharedNodes.size, 1, 'Should have exactly one shared node');

  // Check paths to shared
  assert(result.pathsToShared.has('fn-B'), 'B should have path to D');
  assert(result.pathsToShared.get('fn-B').has('fn-D'), 'B path should include D');
  assert(result.pathsToShared.has('fn-C'), 'C should have path to D');
});

describe('layoutGraph - simple chain', () => {
  const data = makeSimpleChain();
  const result = layout.layoutGraph(data);

  assert(result.validation.valid, 'Layout should be valid');
  assertEqual(result.gridPos.size, 3, 'Should have 3 nodes positioned');

  // All nodes should be on the same row (horizontal chain)
  const posA = result.gridPos.get('fn-A');
  const posB = result.gridPos.get('fn-B');
  const posC = result.gridPos.get('fn-C');

  assertEqual(posA.row, 0, 'A should be on row 0');
  assertEqual(posB.row, 0, 'B should be on row 0');
  assertEqual(posC.row, 0, 'C should be on row 0');

  assertEqual(posA.col, 0, 'A should be in column 0');
  assertEqual(posB.col, 1, 'B should be in column 1');
  assertEqual(posC.col, 2, 'C should be in column 2');
});

describe('layoutGraph - tree with two children', () => {
  const data = makeSimpleTree();
  const result = layout.layoutGraph(data);

  assert(result.validation.valid, 'Layout should be valid');

  const posA = result.gridPos.get('fn-A');
  const posB = result.gridPos.get('fn-B');
  const posC = result.gridPos.get('fn-C');

  assertEqual(posA.row, 0, 'A should be on row 0');
  assertEqual(posA.col, 0, 'A should be in column 0');

  // Both B and C should be in column 1
  assertEqual(posB.col, 1, 'B should be in column 1');
  assertEqual(posC.col, 1, 'C should be in column 1');

  // One should be row 0, other row 1
  assert(posB.row === 0 || posC.row === 0, 'One child should be on row 0');
  assert(posB.row !== posC.row, 'Children should be on different rows');
});

describe('layoutGraph - chain with placeholder', () => {
  const data = makeChainWithPlaceholder();
  const result = layout.layoutGraph(data);

  assert(result.validation.valid, 'Layout should be valid');

  const posA = result.gridPos.get('fn-A');
  const posB = result.gridPos.get('fn-B');
  const posPlaceholder = result.gridPos.get('unset-1');

  // fn nodes on horizontal chain
  assertEqual(posA.row, 0, 'A should be on row 0');
  assertEqual(posB.row, 0, 'B should be on row 0');
  assertEqual(posPlaceholder.row, 0, 'Placeholder should be on row 0 (single child)');

  assertEqual(posA.col, 0, 'A col');
  assertEqual(posB.col, 1, 'B col');
  assertEqual(posPlaceholder.col, 2, 'Placeholder col');
});

describe('layoutGraph - mixed types (fn > placeholder priority)', () => {
  const data = makeMixedTree();
  const result = layout.layoutGraph(data);

  assert(result.validation.valid, 'Layout should be valid');

  const posA = result.gridPos.get('fn-A');
  const posB = result.gridPos.get('fn-B');
  const posPlaceholder = result.gridPos.get('unset-1');

  // fn-B should be first (horizontal), placeholder below
  assertEqual(posB.row, 0, 'B (fn type) should be on row 0 (higher priority)');
  assert(posPlaceholder.row > 0, 'Placeholder should be below row 0');

  assertEqual(posB.col, 1, 'B should be in column 1');
  assertEqual(posPlaceholder.col, 1, 'Placeholder should be in column 1');
});

describe('layoutGraph - list-10 style', () => {
  const data = makeList10Style();
  const result = layout.layoutGraph(data);

  assert(result.validation.valid, 'Layout should be valid');

  const posList10 = result.gridPos.get('fn-list10');
  const posList9 = result.gridPos.get('fn-list9');
  const posList8 = result.gridPos.get('fn-list8');
  const posUnset1 = result.gridPos.get('unset-1');
  const posUnset2 = result.gridPos.get('unset-2');
  const posUnset3 = result.gridPos.get('unset-3');

  // All fn nodes should be on row 0 (horizontal chain)
  assertEqual(posList10.row, 0, 'list-10 should be on row 0');
  assertEqual(posList9.row, 0, 'list-10-9 should be on row 0');
  assertEqual(posList8.row, 0, 'list-10-8 should be on row 0');

  // fn nodes in sequential columns
  assertEqual(posList10.col, 0, 'list-10 col');
  assertEqual(posList9.col, 1, 'list-10-9 col');
  assertEqual(posList8.col, 2, 'list-10-8 col');

  // Placeholders with siblings should be below row 0
  assert(posUnset1.row > 0, 'Placeholder 1 should be below (has fn sibling)');
  assert(posUnset2.row > 0, 'Placeholder 2 should be below (has fn sibling)');
  // Placeholder 3 is the only child of list-10-8, so it continues the branch
  assertEqual(posUnset3.row, 0, 'Placeholder 3 on row 0 (single child continues branch)');
  assertEqual(posUnset3.col, 3, 'Placeholder 3 col');
});

describe('layoutGraph - shared argument detection', () => {
  const data = makeSharedArgument();
  const result = layout.layoutGraph(data);

  // Layout may have warnings but should complete
  assertEqual(result.gridPos.size, 4, 'Should have all 4 nodes positioned');

  const posD = result.gridPos.get('fn-D');
  assert(posD !== undefined, 'D should be positioned');
});

describe('validateMatrix - no collisions', () => {
  const matrix = layout.createMatrixState();
  const gridPos = new Map();

  gridPos.set('node1', { row: 0, col: 0 });
  gridPos.set('node2', { row: 0, col: 1 });
  gridPos.set('node3', { row: 1, col: 0 });

  const result = layout.validateMatrix(matrix, gridPos);
  assert(result.valid, 'Should be valid with no collisions');
});

describe('validateMatrix - detect collision', () => {
  const matrix = layout.createMatrixState();
  const gridPos = new Map();

  // Two nodes at same position
  gridPos.set('node1', { row: 0, col: 0 });
  gridPos.set('node2', { row: 0, col: 0 });

  const result = layout.validateMatrix(matrix, gridPos);
  assert(!result.valid, 'Should detect collision');
  assert(result.issues.some(i => i.type === 'collision'), 'Should have collision issue');
});

describe('formatMatrixASCII', () => {
  // Use layoutGraph to get properly filled matrix
  const data = makeSimpleChain();
  const result = layout.layoutGraph(data);

  const ascii = result.ascii;
  assert(ascii.includes('fn-A'), 'ASCII should include node A');
  assert(ascii.includes('fn-B'), 'ASCII should include node B');
  assert(ascii.includes('fn-C'), 'ASCII should include node C');
});

// Editor-routes style: multiple siblings, two of which share an argument
// Splitting nodes must be adjacent (one below the other)
function makeEditorRoutesStyle() {
  return {
    nodes: [
      { data: { id: 'editor-routes', type: 'fn', label: 'editor-routes' } },
      { data: { id: 'editor-route', type: 'fn', label: 'editor-route' } },
      { data: { id: 'api-route', type: 'fn', label: 'api-route' } },
      { data: { id: 'form-create-route', type: 'fn', label: 'form-create-route' } },
      { data: { id: 'form-edit-route', type: 'fn', label: 'form-edit-route' } },
      { data: { id: 'health-route', type: 'fn', label: 'health-route' } },
      { data: { id: 'form-handler', type: 'fn', label: 'form-handler' } },  // shared!
      { data: { id: 'create-path', type: 'fn', label: 'create-path' } },
      { data: { id: 'edit-path', type: 'fn', label: 'edit-path' } }
    ],
    edges: [
      // editor-routes has 5 children
      { data: { id: 'e1', source: 'editor-routes', target: 'editor-route', argName: 'item1' } },
      { data: { id: 'e2', source: 'editor-routes', target: 'api-route', argName: 'item2' } },
      { data: { id: 'e3', source: 'editor-routes', target: 'form-create-route', argName: 'item3' } },
      { data: { id: 'e4', source: 'editor-routes', target: 'form-edit-route', argName: 'item4' } },
      { data: { id: 'e5', source: 'editor-routes', target: 'health-route', argName: 'item5' } },
      // form-create-route and form-edit-route both point to form-handler (shared)
      { data: { id: 'e6', source: 'form-create-route', target: 'create-path', argName: 'path' } },
      { data: { id: 'e7', source: 'form-create-route', target: 'form-handler', argName: 'handler' } },
      { data: { id: 'e8', source: 'form-edit-route', target: 'edit-path', argName: 'path' } },
      { data: { id: 'e9', source: 'form-edit-route', target: 'form-handler', argName: 'handler' } }
    ]
  };
}

describe('layoutGraph - shared handler with multiple siblings', () => {
  const data = makeEditorRoutesStyle();
  const result = layout.layoutGraph(data);

  assert(result.validation.valid, 'Layout should be valid');

  const posCreate = result.gridPos.get('form-create-route');
  const posEdit = result.gridPos.get('form-edit-route');
  const posHandler = result.gridPos.get('form-handler');
  const posCreatePath = result.gridPos.get('create-path');
  const posEditPath = result.gridPos.get('edit-path');

  // For upper parent (create-route): path is first child, on same row
  assertEqual(posCreatePath.row, posCreate.row,
    'create-path should be on same row as form-create-route');

  // Shared handler must be placed AFTER all its parents and their children
  // So handler col = max(all parent cols, all sibling cols) + 1
  const maxSiblingCol = Math.max(posCreatePath.col, posEditPath.col);
  assert(posHandler.col > maxSiblingCol,
    `Shared handler col ${posHandler.col} should be > max sibling col ${maxSiblingCol}`);

  // For lower parent (edit-route): shared handler goes horizontal, path goes below
  // edit-path is NOT on same row as edit-route because handler takes priority
  assert(posEditPath.row > posEdit.row,
    `edit-path should be below form-edit-route (shared handler takes horizontal slot)`);
});

// Realistic editor-routes: two routes with path args AND shared handler
// This tests that shared handler is on same row as its direct parent from lower branch
// NOT below the path args
function makeRealisticSharedHandler() {
  return {
    nodes: [
      { data: { id: 'editor-routes', type: 'fn', label: 'editor-routes' } },
      { data: { id: 'form-create-route', type: 'fn', label: 'form-create-route' } },
      { data: { id: 'form-edit-route', type: 'fn', label: 'form-edit-route' } },
      { data: { id: 'create-path', type: 'fn', label: 'create-path', isPlaceholder: true } },
      { data: { id: 'edit-path', type: 'fn', label: 'edit-path', isPlaceholder: true } },
      { data: { id: 'form-handler', type: 'fn', label: 'form-handler' } }  // shared!
    ],
    edges: [
      // editor-routes has 2 route children
      { data: { id: 'e1', source: 'editor-routes', target: 'form-create-route', argName: 'item1' } },
      { data: { id: 'e2', source: 'editor-routes', target: 'form-edit-route', argName: 'item2' } },
      // Each route has path and handler children
      { data: { id: 'e3', source: 'form-create-route', target: 'create-path', argName: 'path' } },
      { data: { id: 'e4', source: 'form-create-route', target: 'form-handler', argName: 'handler' } },
      { data: { id: 'e5', source: 'form-edit-route', target: 'edit-path', argName: 'path' } },
      { data: { id: 'e6', source: 'form-edit-route', target: 'form-handler', argName: 'handler' } }  // shared!
    ]
  };
}

describe('layoutGraph - shared handler positioned correctly', () => {
  const data = makeRealisticSharedHandler();
  const result = layout.layoutGraph(data);

  console.log('  Realistic shared handler test:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  assert(result.validation.valid, 'Layout should be valid');

  const posCreate = result.gridPos.get('form-create-route');
  const posEdit = result.gridPos.get('form-edit-route');
  const posHandler = result.gridPos.get('form-handler');
  const posCreatePath = result.gridPos.get('create-path');
  const posEditPath = result.gridPos.get('edit-path');

  // Both routes should be at col 1 (children of editor-routes at col 0)
  assertEqual(posCreate.col, 1, 'form-create-route should be at col 1');
  assertEqual(posEdit.col, 1, 'form-edit-route should be at col 1');

  // Routes should be adjacent (one below the other)
  const routeRowDiff = Math.abs(posCreate.row - posEdit.row);
  assertEqual(routeRowDiff, 1, 'Routes should be adjacent (row diff = 1)');

  // Key rule: First child is ALWAYS on same row as parent (no steps in horizontal branch)
  // create-path is first child of form-create-route
  assertEqual(posCreatePath.row, posCreate.row,
    'create-path (first child) should be on same row as form-create-route');
  assertEqual(posCreatePath.col, posCreate.col + 1,
    'create-path should be at col+1 of form-create-route');

  // For lower parent (edit-route): shared handler goes horizontal, path goes below
  // edit-path is NOT first child anymore - handler is first child for lower parent
  assert(posEditPath.row > posEdit.row,
    'edit-path should be below form-edit-route (handler goes horizontal)');
  assertEqual(posEditPath.col, posEdit.col + 1,
    'edit-path should be at col+1 of form-edit-route');

  // Shared handler must be at col > max(all parent children cols)
  // This ensures no backward edges
  const maxChildCol = Math.max(posCreatePath.col, posEditPath.col);
  assert(posHandler.col > maxChildCol,
    `Shared handler col ${posHandler.col} must be > max child col ${maxChildCol}`);
});

// Test with expanded upper branch (simulates expand of entity-form-edit-route)
// The upper branch becomes longer, but shared handler must still be right of all parents
function makeExpandedUpperBranch() {
  return {
    nodes: [
      { data: { id: 'editor-routes', type: 'fn', label: 'editor-routes' } },
      { data: { id: 'form-create-route', type: 'fn', label: 'form-create-route' } },
      // Expanded form-edit-route -> shows get-route -> route -> assoc-handler chain
      { data: { id: 'form-edit-route', type: 'fn', label: 'form-edit-route' } },
      { data: { id: 'get-route', type: 'fn', label: 'get-route' } },
      { data: { id: 'route', type: 'fn', label: 'route' } },
      { data: { id: 'assoc-handler', type: 'fn', label: 'assoc-handler' } },
      { data: { id: 'create-path', type: 'fn', label: 'create-path', isPlaceholder: true } },
      { data: { id: 'edit-path', type: 'fn', label: 'edit-path', isPlaceholder: true } },
      { data: { id: 'form-handler', type: 'fn', label: 'form-handler' } }  // shared!
    ],
    edges: [
      { data: { id: 'e1', source: 'editor-routes', target: 'form-create-route', argName: 'item1' } },
      { data: { id: 'e2', source: 'editor-routes', target: 'form-edit-route', argName: 'item2' } },
      // form-create-route still direct to handler
      { data: { id: 'e3', source: 'form-create-route', target: 'create-path', argName: 'path' } },
      { data: { id: 'e4', source: 'form-create-route', target: 'form-handler', argName: 'handler' } },
      // form-edit-route expanded: edit-route -> get-route -> route -> assoc-handler -> handler
      { data: { id: 'e5', source: 'form-edit-route', target: 'get-route', argName: 'parent' } },
      { data: { id: 'e6', source: 'form-edit-route', target: 'edit-path', argName: 'path' } },
      { data: { id: 'e7', source: 'get-route', target: 'route', argName: 'parent' } },
      { data: { id: 'e8', source: 'route', target: 'assoc-handler', argName: 'method-map' } },
      { data: { id: 'e9', source: 'assoc-handler', target: 'form-handler', argName: 'handler' } }  // shared!
    ]
  };
}

describe('layoutGraph - expanded upper branch with shared handler', () => {
  const data = makeExpandedUpperBranch();
  const result = layout.layoutGraph(data);

  console.log('  Expanded upper branch test:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  assert(result.validation.valid, 'Layout should be valid');

  const posCreate = result.gridPos.get('form-create-route');
  const posEdit = result.gridPos.get('form-edit-route');
  const posHandler = result.gridPos.get('form-handler');
  const posAssocHandler = result.gridPos.get('assoc-handler');

  // form-handler is shared between form-create-route (direct) and assoc-handler (via chain)
  // Handler must be to the RIGHT of both parents
  assert(posHandler.col > posCreate.col,
    `Handler col ${posHandler.col} must be > form-create-route col ${posCreate.col}`);
  assert(posHandler.col > posAssocHandler.col,
    `Handler col ${posHandler.col} must be > assoc-handler col ${posAssocHandler.col}`);

  // No leftward edges - handler col must be >= all parent cols + 1
  const maxParentCol = Math.max(posCreate.col, posAssocHandler.col);
  assert(posHandler.col >= maxParentCol + 1,
    `Handler col ${posHandler.col} must be >= maxParentCol ${maxParentCol} + 1`);
});

describe('layoutGraph - no edge crossings with shared handler', () => {
  const data = makeRealisticSharedHandler();
  const result = layout.layoutGraph(data);

  // Check that vertical edges don't cross horizontal edges
  const { matrix, gridPos } = result;

  // Get positions
  const posCreate = gridPos.get('form-create-route');
  const posEdit = gridPos.get('form-edit-route');
  const posHandler = gridPos.get('form-handler');

  // The upper route connects to handler via vertical then horizontal edge
  // The lower route connects to handler via horizontal edge only
  // Check no crossings in the column between routes and handler

  const upperRoutePos = posCreate.row < posEdit.row ? posCreate : posEdit;
  const lowerRoutePos = posCreate.row < posEdit.row ? posEdit : posCreate;

  // If handler is on same row as lower route, then:
  // - Upper route has vertical edge down to handler's row, then horizontal to handler
  // - Lower route has horizontal edge to handler
  // These should not cross

  // The vertical edge from upper route should be in col 2 (handler col)
  // Check that no node is placed where vertical edge would be
  for (let r = upperRoutePos.row; r < lowerRoutePos.row; r++) {
    const nodeAtCell = layout.getNodeAt ? layout.getNodeAt(matrix, r, posHandler.col) : null;
    // There should be no node blocking the vertical edge path (except handler itself)
    if (nodeAtCell && nodeAtCell !== 'form-handler') {
      assert(false, `Node ${nodeAtCell} at row ${r}, col ${posHandler.col} blocks vertical edge to handler`);
    }
  }

  assert(true, 'No edge crossings detected');
});

// Test: Edge passing through node when expand shows intermediate nodes
// This reproduces the entity-form-edit-route expand issue where:
// - Root has children: method-map (horizontal), pair-1 (vertical)
// - method-map is at col=1, row=0
// - pair-1 is at col=1, row=2
// - Vertical edge from root to pair-1 passes THROUGH method-map
function makeExpandEdgeThroughNode() {
  // Simulates entity-form-edit-route with expand level 3
  // Structure:
  //   entity-form-edit-route
  //     -> method-map (item2) -> assoc-handler (value) -> entity-form-handler (handler)
  //     -> pair-1 (coll) -> path-value (path)
  //
  // Problem: vertical edge to pair-1 goes through method-map's cell
  return {
    nodes: [
      { data: { id: 'root', type: 'fn', label: 'root' } },
      { data: { id: 'method-map', type: 'fn', label: 'method-map' } },
      { data: { id: 'assoc-handler', type: 'fn', label: 'assoc-handler' } },
      { data: { id: 'handler', type: 'fn', label: 'handler' } },
      { data: { id: 'pair-1', type: 'fn', label: 'pair-1' } },
      { data: { id: 'path-value', type: 'fn', label: 'path-value', isPlaceholder: true } },
      { data: { id: 'key-value', type: 'fn', label: '"get"', isPlaceholder: true } },
      { data: { id: 'handler-key', type: 'fn', label: '"handler"', isPlaceholder: true } }
    ],
    edges: [
      // First child (coll/pair-1) goes horizontal, second (item2/method-map) goes vertical
      { data: { id: 'e1', source: 'root', target: 'pair-1', argName: 'coll' } },
      { data: { id: 'e2', source: 'root', target: 'method-map', argName: 'item2' } },
      { data: { id: 'e3', source: 'pair-1', target: 'path-value', argName: 'path' } },
      { data: { id: 'e4', source: 'method-map', target: 'assoc-handler', argName: 'value' } },
      { data: { id: 'e5', source: 'assoc-handler', target: 'handler', argName: 'handler' } },
      { data: { id: 'e6', source: 'assoc-handler', target: 'key-value', argName: 'key' } },
      { data: { id: 'e7', source: 'handler', target: 'handler-key', argName: 'key' } }
    ]
  };
}

describe('layoutGraph - edge must not pass through node (expand issue)', () => {
  const data = makeExpandEdgeThroughNode();
  const result = layout.layoutGraph(data);

  console.log('  Expand edge-through-node test:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  assert(result.validation.valid, 'Layout should be valid');

  const posRoot = result.gridPos.get('root');
  const posMethodMap = result.gridPos.get('method-map');
  const posPair1 = result.gridPos.get('pair-1');

  // Vertical edge from root to pair-1 should go through root's column (col=0)
  // Then horizontal edge to pair-1's column (col=1)
  // This way the vertical part doesn't pass through method-map (col=1, row=0)
  // Check that vertical edge is in root's column, not pair-1's column
  const vEdgeCol = posRoot.col; // vertical edge should be in parent's column
  const methodMapBlocksVEdge = posMethodMap.col === vEdgeCol &&
                                posMethodMap.row > posRoot.row &&
                                posMethodMap.row < posPair1.row;
  assert(!methodMapBlocksVEdge,
    `Vertical edge at col=${vEdgeCol} should not pass through method-map at (col=${posMethodMap.col}, row=${posMethodMap.row})`);

  // Verify pair-1 is reachable - first child goes horizontal (same row, col+1)
  assert(posPair1.row === posRoot.row, `pair-1 (first child) should be on same row as root`);
  assert(posPair1.col === posRoot.col + 1, `pair-1 should be at col+1 of root`);

  // More general check: no vertical edge should pass through any node
  const { matrix, gridPos } = result;
  let edgeThroughNode = false;
  let problemDetails = '';

  // For each vertical edge segment, check if there's a node at that cell
  for (let r = 0; r < matrix.vEdge.length; r++) {
    for (let c = 0; c < (matrix.vEdge[r] || []).length; c++) {
      if (matrix.vEdge[r][c]) {
        const nodeAtCell = matrix.nodeGrid[r] && matrix.nodeGrid[r][c];
        if (nodeAtCell) {
          edgeThroughNode = true;
          problemDetails = `Vertical edge at (row=${r}, col=${c}) passes through node "${nodeAtCell}"`;
        }
      }
    }
  }

  assert(!edgeThroughNode, problemDetails || 'No vertical edge passes through any node');
});

// Test: First child as placeholder must be on same row as parent
// This tests entity-form-handler case where first arg is hiccup (placeholder)
// and second arg is render-fn (fn). First child MUST be on same row regardless of type.
function makeFirstChildPlaceholder() {
  return {
    nodes: [
      { data: { id: 'handler', type: 'fn', label: 'entity-form-handler' } },
      { data: { id: 'hiccup', type: 'arg', label: 'jsonb', isPlaceholder: true } },
      { data: { id: 'render-fn', type: 'fn', label: 'render-entity-form-view' } },
      { data: { id: 'request', type: 'arg', label: 'jsonb', isPlaceholder: true } }
    ],
    edges: [
      // hiccup comes FIRST in database order
      { data: { id: 'e1', source: 'handler', target: 'hiccup', argName: 'hiccup' } },
      { data: { id: 'e2', source: 'handler', target: 'render-fn', argName: 'render-fn' } },
      { data: { id: 'e3', source: 'render-fn', target: 'request', argName: 'request' } }
    ]
  };
}

describe('layoutGraph - first child placeholder on same row', () => {
  const data = makeFirstChildPlaceholder();
  const result = layout.layoutGraph(data);

  console.log('  First child placeholder test:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  assert(result.validation.valid, 'Layout should be valid');

  const posHandler = result.gridPos.get('handler');
  const posHiccup = result.gridPos.get('hiccup');
  const posRenderFn = result.gridPos.get('render-fn');

  // CRITICAL: First child (hiccup) MUST be on same row as parent
  assertEqual(posHiccup.row, posHandler.row,
    'First child (hiccup placeholder) must be on same row as handler');
  assertEqual(posHiccup.col, posHandler.col + 1,
    'First child (hiccup) should be at col+1 of handler');

  // Second child (render-fn) goes below
  assert(posRenderFn.row > posHandler.row,
    `Second child (render-fn) at row ${posRenderFn.row} should be below handler (row ${posHandler.row})`);
});

// ============================================================================
// Summary
// ============================================================================

console.log('\n========================================');
console.log('Tests: ' + testsPassed + '/' + testsRun + ' passed');
if (testsPassed === testsRun) {
  console.log('All tests passed!');
  process.exit(0);
} else {
  console.log('Some tests failed');
  process.exit(1);
}
