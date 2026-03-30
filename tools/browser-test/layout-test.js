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

describe('layoutGraph - splitting nodes must be adjacent', () => {
  const data = makeEditorRoutesStyle();
  const result = layout.layoutGraph(data);

  assert(result.validation.valid, 'Layout should be valid');

  const posCreate = result.gridPos.get('form-create-route');
  const posEdit = result.gridPos.get('form-edit-route');
  const posHandler = result.gridPos.get('form-handler');

  // Splitting nodes must be adjacent (|row difference| == 1)
  const rowDiff = Math.abs(posCreate.row - posEdit.row);
  assertEqual(rowDiff, 1, 'Splitting nodes must be adjacent (row diff = 1)');

  // Shared handler should be on horizontal line of lower branch
  // Lower branch is the one with shorter path to shared (form-edit-route in this case)
  // So form-handler should be on same row as form-edit-route
  const lowerRow = Math.max(posCreate.row, posEdit.row);
  assertEqual(posHandler.row, lowerRow, 'Shared handler should be on row of lower splitting node');

  // No other routes should be between splitting nodes
  const posEditorRoute = result.gridPos.get('editor-route');
  const posApiRoute = result.gridPos.get('api-route');
  const posHealthRoute = result.gridPos.get('health-route');

  const minSplitRow = Math.min(posCreate.row, posEdit.row);
  const maxSplitRow = Math.max(posCreate.row, posEdit.row);

  // Other routes should NOT be between splitting nodes
  assert(
    posEditorRoute.row < minSplitRow || posEditorRoute.row > maxSplitRow,
    'editor-route should not be between splitting nodes'
  );
  assert(
    posApiRoute.row < minSplitRow || posApiRoute.row > maxSplitRow,
    'api-route should not be between splitting nodes'
  );
  assert(
    posHealthRoute.row < minSplitRow || posHealthRoute.row > maxSplitRow,
    'health-route should not be between splitting nodes'
  );
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
