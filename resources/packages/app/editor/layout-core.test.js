// Layout Core Tests
// Run: node layout-core.test.js

const {
  buildAdjacency,
  findRootNode,
  createMatrixState,
  collectHorizontalBranch,
  buildMatrix,
  validateMatrix,
  formatMatrixASCII,
  layoutGraph
} = require('./layout-core.js');

let passed = 0;
let failed = 0;

function test(name, fn) {
  try {
    fn();
    console.log('✓', name);
    passed++;
  } catch (e) {
    console.log('✗', name);
    console.log('  Error:', e.message);
    failed++;
  }
}

function assertEqual(actual, expected, msg = '') {
  const actualStr = JSON.stringify(actual);
  const expectedStr = JSON.stringify(expected);
  if (actualStr !== expectedStr) {
    throw new Error(`${msg}\n  Expected: ${expectedStr}\n  Actual: ${actualStr}`);
  }
}

function assertNoCollisions(result, msg = '') {
  if (result.collisions.length > 0) {
    throw new Error(`${msg}\n  Collisions: ${JSON.stringify(result.collisions)}`);
  }
}

function assertValid(result, msg = '') {
  if (!result.validation.valid) {
    throw new Error(`${msg}\n  Issues: ${JSON.stringify(result.validation.issues)}`);
  }
}

// Helper to create simple graph
function makeGraph(nodeIds, edgeList) {
  const nodes = nodeIds.map(id => ({ data: { id } }));
  const edges = edgeList.map(([src, tgt, argName]) => ({
    data: { source: src, target: tgt, argName }
  }));
  return { nodes, edges };
}

console.log('\n=== Layout Core Tests ===\n');

// ============================================================================
// Basic Tests
// ============================================================================

test('empty graph', () => {
  const result = layoutGraph({ nodes: [], edges: [] });
  assertEqual(result.gridPos.size, 0);
  assertEqual(result.validation.valid, true);
});

test('single node', () => {
  const graph = makeGraph(['A'], []);
  const result = layoutGraph(graph);

  assertEqual(result.gridPos.get('A'), { row: 0, col: 0 });
  assertNoCollisions(result);
  assertValid(result);
});

test('linear chain: A -> B -> C', () => {
  const graph = makeGraph(['A', 'B', 'C'], [
    ['A', 'B', 'arg1'],
    ['B', 'C', 'arg2']
  ]);
  const result = layoutGraph(graph);

  // Should be horizontal: A at col 0, B at col 1, C at col 2
  assertEqual(result.gridPos.get('A'), { row: 0, col: 0 });
  assertEqual(result.gridPos.get('B'), { row: 0, col: 1 });
  assertEqual(result.gridPos.get('C'), { row: 0, col: 2 });
  assertNoCollisions(result);
  assertValid(result);

  console.log('  ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

test('simple fork: A -> B, A -> C', () => {
  const graph = makeGraph(['A', 'B', 'C'], [
    ['A', 'B', 'first'],
    ['A', 'C', 'second']
  ]);
  const result = layoutGraph(graph);

  // A at (0,0), B at (0,1) - first child on same row
  // C at (1,1) - second child below
  assertEqual(result.gridPos.get('A'), { row: 0, col: 0 });
  assertEqual(result.gridPos.get('B'), { row: 0, col: 1 });
  assertEqual(result.gridPos.get('C'), { row: 1, col: 1 });
  assertNoCollisions(result);

  console.log('  ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

// ============================================================================
// Complex Structures
// ============================================================================

test('deep tree with multiple branches', () => {
  // Structure:
  //   A -> B -> D
  //   A -> C -> E
  //             C -> F
  const graph = makeGraph(['A', 'B', 'C', 'D', 'E', 'F'], [
    ['A', 'B', 'b'],
    ['A', 'C', 'c'],
    ['B', 'D', 'd'],
    ['C', 'E', 'e'],
    ['C', 'F', 'f']
  ]);
  const result = layoutGraph(graph);

  assertNoCollisions(result);
  assertValid(result);

  // Verify no overlaps
  const positions = new Set();
  result.gridPos.forEach((pos, nodeId) => {
    const key = `${pos.row},${pos.col}`;
    if (positions.has(key)) {
      throw new Error(`Overlap at ${key}`);
    }
    positions.add(key);
  });

  console.log('  ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

test('editor-routes style: root with 10 children', () => {
  // Simulates editor-routes -> list-10 structure
  // Root has 10 children (item1-item10)
  const nodeIds = ['root', 'child1', 'child2', 'child3', 'child4', 'child5',
                   'child6', 'child7', 'child8', 'child9', 'child10'];
  const edges = [];
  for (let i = 1; i <= 10; i++) {
    edges.push(['root', 'child' + i, 'item' + i]);
  }

  const graph = makeGraph(nodeIds, edges);
  const result = layoutGraph(graph);

  assertNoCollisions(result);
  assertValid(result);

  // Root at (0,0), first child at (0,1), rest below
  assertEqual(result.gridPos.get('root'), { row: 0, col: 0 });
  assertEqual(result.gridPos.get('child1'), { row: 0, col: 1 });

  // Other children should be in rows 1-9
  for (let i = 2; i <= 10; i++) {
    const pos = result.gridPos.get('child' + i);
    if (pos.row === 0) {
      throw new Error(`child${i} should not be on row 0`);
    }
    if (pos.col !== 1) {
      throw new Error(`child${i} should be at col 1, got ${pos.col}`);
    }
  }

  console.log('  ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

test('chain with expansion: root -> parent -> grandparent', () => {
  // Simulates expanded view: editor-routes -> list-10 -> list-10-9
  // Each level has its own children
  const graph = makeGraph(
    ['root', 'parent', 'grandparent', 'item10', 'item9', 'coll_ref'],
    [
      ['root', 'parent', 'parent'],
      ['parent', 'grandparent', 'parent'],
      ['parent', 'item10', 'item10'],
      ['grandparent', 'item9', 'item9'],
      ['grandparent', 'coll_ref', 'coll']
    ]
  );
  const result = layoutGraph(graph);

  assertNoCollisions(result);
  assertValid(result);

  console.log('  ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

test('wide tree: root with children that have children', () => {
  // root -> a -> a1, a2
  // root -> b -> b1, b2
  const graph = makeGraph(
    ['root', 'a', 'b', 'a1', 'a2', 'b1', 'b2'],
    [
      ['root', 'a', 'a'],
      ['root', 'b', 'b'],
      ['a', 'a1', 'a1'],
      ['a', 'a2', 'a2'],
      ['b', 'b1', 'b1'],
      ['b', 'b2', 'b2']
    ]
  );
  const result = layoutGraph(graph);

  assertNoCollisions(result);
  assertValid(result);

  console.log('  ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

// ============================================================================
// Edge Cases
// ============================================================================

test('diamond structure: A -> B -> D, A -> C -> D', () => {
  // This creates two edges to D, should still work
  const graph = makeGraph(['A', 'B', 'C', 'D'], [
    ['A', 'B', 'b'],
    ['A', 'C', 'c'],
    ['B', 'D', 'bd'],
    ['C', 'D', 'cd']
  ]);
  const result = layoutGraph(graph);

  // D will only appear once (first edge wins)
  assertValid(result);

  console.log('  ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

test('duplicate edges are handled', () => {
  const graph = makeGraph(['A', 'B'], [
    ['A', 'B', 'arg1'],
    ['A', 'B', 'arg2']  // duplicate
  ]);
  const result = layoutGraph(graph);

  assertEqual(result.gridPos.size, 2);
  assertNoCollisions(result);
});

// ============================================================================
// Collision Detection Tests
// ============================================================================

test('buildMatrix tracks collisions', () => {
  const { children, edgeArgNames } = buildAdjacency([
    { data: { source: 'A', target: 'B' } }
  ]);

  const result = buildMatrix('A', children, edgeArgNames);
  assertEqual(result.collisions.length, 0);
});

// ============================================================================
// Matrix State Tests
// ============================================================================

test('collectHorizontalBranch follows first children', () => {
  const children = new Map([
    ['A', ['B', 'C']],
    ['B', ['D']],
    ['D', []]
  ]);

  const branch = collectHorizontalBranch('A', children);
  assertEqual(branch, ['A', 'B', 'D']);
});

test('horizontal edges are recorded', () => {
  const graph = makeGraph(['A', 'B', 'C'], [
    ['A', 'B', 'arg1'],
    ['B', 'C', 'arg2']
  ]);
  const result = layoutGraph(graph);

  // Check hEdge matrix
  const { hEdge } = result.matrix;
  assertEqual(hEdge[0][0], 'arg1');
  assertEqual(hEdge[0][1], 'arg2');
});

test('vertical edges are recorded for branches', () => {
  const graph = makeGraph(['A', 'B', 'C'], [
    ['A', 'B', 'first'],
    ['A', 'C', 'second']
  ]);
  const result = layoutGraph(graph);

  // C is below B, so there should be a vertical edge
  const { vEdge } = result.matrix;
  // vEdge[0][1] should be true (from row 0 to row 1 at col 1)
  assertEqual(vEdge[0][1], true);
});

// ============================================================================
// Real-world: editor-routes simulation
// ============================================================================

test('REAL: editor-routes collapsed (all 10 routes as children)', () => {
  // editor-routes has 10 children: favicon, editor, api-entities, etc.
  // Each route itself may have children (handler fns)
  const graph = makeGraph(
    ['editor-routes', 'favicon-route', 'editor-route', 'api-entities-route',
     'entity-details-route', 'entity-form-create-route', 'entity-form-edit-route',
     'create-entity-route', 'delete-entity-route', 'health-route', 'metrics-route',
     // Some routes have handlers
     'favicon-handler', 'editor-handler', 'health-handler'],
    [
      ['editor-routes', 'favicon-route', 'item1'],
      ['editor-routes', 'editor-route', 'item2'],
      ['editor-routes', 'api-entities-route', 'item3'],
      ['editor-routes', 'entity-details-route', 'item4'],
      ['editor-routes', 'entity-form-create-route', 'item5'],
      ['editor-routes', 'entity-form-edit-route', 'item6'],
      ['editor-routes', 'create-entity-route', 'item7'],
      ['editor-routes', 'delete-entity-route', 'item8'],
      ['editor-routes', 'health-route', 'item9'],
      ['editor-routes', 'metrics-route', 'item10'],
      // Handlers
      ['favicon-route', 'favicon-handler', 'handler'],
      ['editor-route', 'editor-handler', 'handler'],
      ['health-route', 'health-handler', 'handler']
    ]
  );
  const result = layoutGraph(graph);

  assertNoCollisions(result, 'editor-routes collapsed');
  assertValid(result);

  console.log('  editor-routes COLLAPSED:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

test('REAL: editor-routes expanded to list-10 (chain visible)', () => {
  // When expanded, we see:
  // editor-routes -> list-10 -> list-10-9 -> ... -> triple -> pair -> conj-empty
  // Each level has its own items attached
  const graph = makeGraph(
    ['editor-routes', 'list-10', 'list-10-9', 'list-10-8', 'list-10-7',
     // Items attached to correct levels
     'metrics-route', 'health-route', 'delete-entity-route', 'create-entity-route',
     // Handlers for some routes
     'metrics-handler', 'health-handler'],
    [
      // Chain: editor-routes -> list-10 -> list-10-9 -> ...
      ['editor-routes', 'list-10', 'parent'],
      ['list-10', 'list-10-9', 'coll'],
      ['list-10-9', 'list-10-8', 'coll'],
      ['list-10-8', 'list-10-7', 'coll'],
      // Items at their owners
      ['list-10', 'metrics-route', 'item10'],
      ['list-10-9', 'health-route', 'item9'],
      ['list-10-8', 'delete-entity-route', 'item8'],
      ['list-10-7', 'create-entity-route', 'item7'],
      // Handlers
      ['metrics-route', 'metrics-handler', 'handler'],
      ['health-route', 'health-handler', 'handler']
    ]
  );
  const result = layoutGraph(graph);

  assertNoCollisions(result, 'editor-routes expanded');
  assertValid(result);

  console.log('  editor-routes EXPANDED to list-10:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));

  // Verify structure: chain should be horizontal
  const editorPos = result.gridPos.get('editor-routes');
  const list10Pos = result.gridPos.get('list-10');
  const list10_9Pos = result.gridPos.get('list-10-9');

  // Chain should be on same row (row 0)
  assertEqual(editorPos.row, 0, 'editor-routes on row 0');
  assertEqual(list10Pos.row, 0, 'list-10 on row 0');
  assertEqual(list10_9Pos.row, 0, 'list-10-9 on row 0');

  // Chain should be consecutive columns
  assertEqual(editorPos.col, 0, 'editor-routes at col 0');
  assertEqual(list10Pos.col, 1, 'list-10 at col 1');
  assertEqual(list10_9Pos.col, 2, 'list-10-9 at col 2');
});

test('REAL: deeply nested structure with value nodes', () => {
  // Simulates: health-route -> get-route -> route -> pair -> ...
  // With actual values like "/health" attached
  const graph = makeGraph(
    ['health-route', 'get-route', 'route', 'method-map', 'assoc-handler',
     '/health', 'health-handler-fn'],
    [
      ['health-route', 'get-route', 'parent'],
      ['get-route', 'route', 'parent'],
      ['route', 'method-map', 'item2'],
      ['method-map', 'assoc-handler', 'value'],
      ['health-route', '/health', 'path'],
      ['health-route', 'health-handler-fn', 'handler']
    ]
  );
  const result = layoutGraph(graph);

  assertNoCollisions(result, 'deeply nested');
  assertValid(result);

  console.log('  health-route EXPANDED:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

// ============================================================================
// Summary
// ============================================================================

console.log('\n=== Summary ===');
console.log(`Passed: ${passed}`);
console.log(`Failed: ${failed}`);

if (failed > 0) {
  process.exit(1);
}
