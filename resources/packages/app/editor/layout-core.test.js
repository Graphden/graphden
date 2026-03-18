// Layout Core Tests - with crossing detection
// Run: node layout-core.test.js

const {
  buildAdjacency,
  findRootNode,
  buildMatrix,
  detectCrossings,
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

function assertNoCrossings(result, msg = '') {
  if (!result.validation.valid) {
    const crossingIssues = result.validation.issues.filter(i => i.type === 'crossing');
    if (crossingIssues.length > 0) {
      throw new Error(`${msg}\n  CROSSINGS DETECTED:\n${crossingIssues.map(i => '    ' + i.message).join('\n')}\n  ASCII:\n${result.ascii.split('\n').map(l => '    ' + l).join('\n')}`);
    }
  }
}

function assertNoCollisions(result, msg = '') {
  const collisionIssues = result.validation.issues.filter(i => i.type === 'collision');
  if (collisionIssues.length > 0) {
    throw new Error(`${msg}\n  Collisions: ${JSON.stringify(collisionIssues)}`);
  }
}

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
  assertNoCrossings(result);
});

test('linear chain: A -> B -> C', () => {
  const graph = makeGraph(['A', 'B', 'C'], [
    ['A', 'B', 'arg1'],
    ['B', 'C', 'arg2']
  ]);
  const result = layoutGraph(graph);

  assertEqual(result.gridPos.get('A'), { row: 0, col: 0 });
  assertEqual(result.gridPos.get('B'), { row: 0, col: 1 });
  assertEqual(result.gridPos.get('C'), { row: 0, col: 2 });
  assertNoCrossings(result);
  assertNoCollisions(result);

  console.log('  ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

test('simple fork: A -> B, A -> C', () => {
  const graph = makeGraph(['A', 'B', 'C'], [
    ['A', 'B', 'first'],
    ['A', 'C', 'second']
  ]);
  const result = layoutGraph(graph);

  assertEqual(result.gridPos.get('A'), { row: 0, col: 0 });
  assertEqual(result.gridPos.get('B'), { row: 0, col: 1 });
  assertEqual(result.gridPos.get('C'), { row: 1, col: 1 });
  assertNoCrossings(result);

  console.log('  ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
});

// ============================================================================
// Diamond structure - THE KEY TEST
// ============================================================================

test('diamond: A -> B -> D, A -> C -> D (shared node)', () => {
  const graph = makeGraph(['A', 'B', 'C', 'D'], [
    ['A', 'B', 'b'],
    ['A', 'C', 'c'],
    ['B', 'D', 'bd'],
    ['C', 'D', 'cd']
  ]);
  const result = layoutGraph(graph);

  console.log('  Diamond ASCII:\n' + result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));
  console.log('  Validation:', result.validation);

  assertNoCollisions(result);
  assertNoCrossings(result, 'Diamond should have no crossings');
});

test('diamond with shared handler - real case', () => {
  // This is the actual structure that causes problems:
  // list-10-6 -> list-10-5 -> list-10-4
  //    |            |
  //  item6        item5
  //    |            |
  // entity-edit  entity-create
  //    |            |
  //  handler      handler
  //    +---> shared-handler <---+

  const graph = makeGraph(
    ['list-10-6', 'list-10-5', 'list-10-4', 'entity-form-create', 'entity-form-edit', 'shared-handler'],
    [
      ['list-10-6', 'list-10-5', 'coll'],
      ['list-10-5', 'list-10-4', 'coll'],
      ['list-10-5', 'entity-form-create', 'item5'],
      ['list-10-6', 'entity-form-edit', 'item6'],
      ['entity-form-create', 'shared-handler', 'handler'],
      ['entity-form-edit', 'shared-handler', 'handler']
    ]
  );
  const result = layoutGraph(graph);

  console.log('  Real diamond with shared handler:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));
  console.log('  Validation:', result.validation);

  assertNoCollisions(result);
  assertNoCrossings(result, 'Real diamond should have no crossings');
});

test('three branches with shared leaf', () => {
  // A -> B -> D
  // A -> C -> D
  // A -> E -> D
  const graph = makeGraph(['A', 'B', 'C', 'D', 'E'], [
    ['A', 'B', 'b'],
    ['A', 'C', 'c'],
    ['A', 'E', 'e'],
    ['B', 'D', 'bd'],
    ['C', 'D', 'cd'],
    ['E', 'D', 'ed']
  ]);
  const result = layoutGraph(graph);

  console.log('  Three branches shared:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Validation:', result.validation);

  assertNoCollisions(result);
  assertNoCrossings(result, 'Three branches should have no crossings');
});

// ============================================================================
// Complex structures
// ============================================================================

test('deep tree with branches', () => {
  const graph = makeGraph(['A', 'B', 'C', 'D', 'E', 'F'], [
    ['A', 'B', 'b'],
    ['A', 'C', 'c'],
    ['B', 'D', 'd'],
    ['C', 'E', 'e'],
    ['C', 'F', 'f']
  ]);
  const result = layoutGraph(graph);

  console.log('  Deep tree:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));

  assertNoCollisions(result);
  assertNoCrossings(result);
});

test('root with 10 children', () => {
  const nodeIds = ['root'];
  const edges = [];
  for (let i = 1; i <= 10; i++) {
    nodeIds.push('child' + i);
    edges.push(['root', 'child' + i, 'item' + i]);
  }

  const graph = makeGraph(nodeIds, edges);
  const result = layoutGraph(graph);

  console.log('  10 children:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));

  assertNoCollisions(result);
  assertNoCrossings(result);
});

test('wide tree with grandchildren', () => {
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

  console.log('  Wide tree:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));

  assertNoCollisions(result);
  assertNoCrossings(result);
});

// ============================================================================
// More complex diamond cases
// ============================================================================

test('deep diamond - shared at depth 3', () => {
  // A -> B -> C -> shared
  // A -> D -> E -> shared
  const graph = makeGraph(['A', 'B', 'C', 'D', 'E', 'shared'], [
    ['A', 'B', 'b'],
    ['A', 'D', 'd'],
    ['B', 'C', 'c'],
    ['D', 'E', 'e'],
    ['C', 'shared', 'x'],
    ['E', 'shared', 'y']
  ]);
  const result = layoutGraph(graph);

  console.log('  Deep diamond:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Validation:', result.validation);

  assertNoCollisions(result);
  assertNoCrossings(result, 'Deep diamond');
});

test('nested branches with diamond at end', () => {
  // root -> a -> a1 -> shared
  //      -> b -> b1 -> shared
  //           -> b2
  const graph = makeGraph(['root', 'a', 'a1', 'b', 'b1', 'b2', 'shared'], [
    ['root', 'a', 'a'],
    ['root', 'b', 'b'],
    ['a', 'a1', 'a1'],
    ['b', 'b1', 'b1'],
    ['b', 'b2', 'b2'],
    ['a1', 'shared', 'x'],
    ['b1', 'shared', 'y']
  ]);
  const result = layoutGraph(graph);

  console.log('  Nested with diamond:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Validation:', result.validation);

  assertNoCollisions(result);
  assertNoCrossings(result, 'Nested with diamond');
});

test('short branch should fit in its column only', () => {
  // root -> a -> a1 -> a2 -> a3 (long horizontal chain)
  //      -> b (short, only occupies col 1)
  // Each node in chain has a hanging child going down
  // a1 -> x1, a2 -> x2, a3 -> x3
  //
  // b should be at row 1, NOT pushed down by x1, x2, x3 which are in cols 2, 3, 4
  const graph = makeGraph(['root', 'a', 'a1', 'a2', 'a3', 'x1', 'x2', 'x3', 'b'], [
    ['root', 'a', 'a'],
    ['root', 'b', 'b'],
    ['a', 'a1', 'a1'],
    ['a1', 'a2', 'a2'],
    ['a2', 'a3', 'a3'],
    ['a1', 'x1', 'x1'],
    ['a2', 'x2', 'x2'],
    ['a3', 'x3', 'x3']
  ]);
  const result = layoutGraph(graph);

  console.log('  Short branch positioning:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  // b is a branch of length 1 at col 1
  // It should only check col 1, so b should be at row 1
  const bPos = result.gridPos.get('b');
  if (bPos.row > 1) {
    throw new Error(`b should be at row 1, but is at row ${bPos.row}. It was pushed down by nodes in other columns.`);
  }

  assertNoCollisions(result);
  assertNoCrossings(result);
});

// ============================================================================
// Argument must be strictly to the right of fn
// ============================================================================

function assertArgRightOfFn(result, edges, msg = '') {
  // For each edge (fn -> arg), arg.col must be > fn.col
  const gridPos = result.gridPos;
  for (const edge of edges) {
    const [src, tgt] = edge;
    const srcPos = gridPos.get(src);
    const tgtPos = gridPos.get(tgt);
    if (srcPos && tgtPos && tgtPos.col <= srcPos.col) {
      throw new Error(`${msg}\n  Argument ${tgt} (col=${tgtPos.col}) is NOT to the right of fn ${src} (col=${srcPos.col})\n  ASCII:\n${result.ascii.split('\n').map(l => '    ' + l).join('\n')}`);
    }
  }
}

test('editor-routes expanded - args must be right of fn', () => {
  // Real editor-routes structure (simplified)
  // editor-routes has multiple route children, two of which share entity-form-handler:
  // - entity-form-create-route -> entity-form-handler
  // - entity-form-edit-route -> entity-form-handler

  const graph = makeGraph(
    [
      'editor-routes',
      'editor-route', 'editor-path', 'editor-handler',
      'entity-form-create-route', 'create-path', 'entity-form-handler',
      'entity-form-edit-route', 'edit-path'
    ],
    [
      ['editor-routes', 'editor-route', 'route1'],
      ['editor-routes', 'entity-form-create-route', 'route2'],
      ['editor-routes', 'entity-form-edit-route', 'route3'],
      ['editor-route', 'editor-path', 'path'],
      ['editor-route', 'editor-handler', 'handler'],
      ['entity-form-create-route', 'create-path', 'path'],
      ['entity-form-create-route', 'entity-form-handler', 'handler'],
      ['entity-form-edit-route', 'edit-path', 'path'],
      ['entity-form-edit-route', 'entity-form-handler', 'handler']  // shared!
    ]
  );
  const result = layoutGraph(graph);

  console.log('  Editor-routes with shared handler:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));
  console.log('  Validation:', result.validation);

  // Key constraint: every argument must be to the right of its fn
  const edges = [
    ['editor-routes', 'editor-route'],
    ['editor-routes', 'entity-form-create-route'],
    ['editor-routes', 'entity-form-edit-route'],
    ['editor-route', 'editor-path'],
    ['editor-route', 'editor-handler'],
    ['entity-form-create-route', 'create-path'],
    ['entity-form-create-route', 'entity-form-handler'],
    ['entity-form-edit-route', 'edit-path'],
    ['entity-form-edit-route', 'entity-form-handler']
  ];

  assertArgRightOfFn(result, edges, 'Editor-routes expanded');
  assertNoCollisions(result);
  assertNoCrossings(result, 'Editor-routes expanded should have no crossings');
});

test('fn and arg in same column is invalid', () => {
  // This should NOT happen: fn at col 1, arg at col 1
  // A -> B -> C
  //      |
  //      D (D should be at col 2, not col 1)
  const graph = makeGraph(['A', 'B', 'C', 'D'], [
    ['A', 'B', 'b'],
    ['B', 'C', 'c'],
    ['B', 'D', 'd']
  ]);
  const result = layoutGraph(graph);

  console.log('  Fn-arg column test:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));

  const bPos = result.gridPos.get('B');
  const dPos = result.gridPos.get('D');

  // D must be to the right of B
  if (dPos.col <= bPos.col) {
    throw new Error(`D (arg of B) is at col ${dPos.col}, but B is at col ${bPos.col}. Arg must be strictly to the right!`);
  }
});

test('shared node must be right of ALL parents', () => {
  // A -> B -> shared
  //      |
  //      C -> shared
  // shared must be to the right of BOTH B and C
  const graph = makeGraph(['A', 'B', 'C', 'shared'], [
    ['A', 'B', 'b'],
    ['B', 'shared', 'x'],
    ['B', 'C', 'c'],
    ['C', 'shared', 'y']
  ]);
  const result = layoutGraph(graph);

  console.log('  Shared node right of all parents:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  const bPos = result.gridPos.get('B');
  const cPos = result.gridPos.get('C');
  const sharedPos = result.gridPos.get('shared');

  // shared must be to the right of B
  if (sharedPos.col <= bPos.col) {
    throw new Error(`shared (col=${sharedPos.col}) must be to the right of B (col=${bPos.col})`);
  }
  // shared must be to the right of C
  if (sharedPos.col <= cPos.col) {
    throw new Error(`shared (col=${sharedPos.col}) must be to the right of C (col=${cPos.col})`);
  }

  assertNoCollisions(result);
  assertNoCrossings(result);
});

test('shift should not cause collisions', () => {
  // A -> B -> shared -> X
  //      |
  //      C -> shared
  // When shared shifts right, X should shift too (no collision)
  const graph = makeGraph(['A', 'B', 'C', 'shared', 'X'], [
    ['A', 'B', 'b'],
    ['B', 'shared', 'x'],
    ['shared', 'X', 'child'],
    ['B', 'C', 'c'],
    ['C', 'shared', 'y']
  ]);
  const result = layoutGraph(graph);

  console.log('  Shift with child:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  assertNoCollisions(result);
  assertNoCrossings(result);

  // X must be to the right of shared
  const sharedPos = result.gridPos.get('shared');
  const xPos = result.gridPos.get('X');
  if (xPos.col <= sharedPos.col) {
    throw new Error(`X (col=${xPos.col}) must be to the right of shared (col=${sharedPos.col})`);
  }
});

test('shift should not cause crossings with other branches', () => {
  // Complex case: shifting shared might cross edges from other branches
  // A -> B -> shared
  //      |
  //      C -> shared
  //      |
  //      D -> E
  // When C->shared shifts shared right, vertical edge to D/E might cross
  const graph = makeGraph(['A', 'B', 'C', 'D', 'E', 'shared'], [
    ['A', 'B', 'b'],
    ['B', 'shared', 'x'],
    ['B', 'C', 'c'],
    ['C', 'shared', 'y'],
    ['C', 'D', 'd'],
    ['D', 'E', 'e']
  ]);
  const result = layoutGraph(graph);

  console.log('  Shift with parallel branches:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));
  console.log('  Validation:', result.validation);

  assertNoCollisions(result);
  assertNoCrossings(result, 'Shift should not cause crossings');
});

test('shift into occupied column should cascade', () => {
  // A -> B -> shared
  //      |      |
  //      C -> shared
  //      |
  //      D -> X (X at col 3, same as where shared wants to go)
  // When shared shifts to col=3, it might collide with X
  const graph = makeGraph(['A', 'B', 'C', 'D', 'X', 'shared'], [
    ['A', 'B', 'b'],
    ['B', 'shared', 'x'],
    ['B', 'C', 'c'],
    ['C', 'shared', 'y'],
    ['C', 'D', 'd'],
    ['D', 'X', 'xx']
  ]);
  const result = layoutGraph(graph);

  console.log('  Shift into occupied:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  assertNoCollisions(result);
  assertNoCrossings(result);
});

test('shift should not collide with unrelated node in same row', () => {
  // A -> B -> shared -> Y
  //      |
  //      C -> shared
  //      |
  //      D -> X -> Z
  // shared at row=0, X at row=2. No collision.
  // But what if X is at same row as shared after shift?
  // Actually let's make it so shared and Z are at same row after processing:
  // A -> B -> shared
  //           |
  //      C -> shared
  //      |
  //   -> D -> X -> Y (Y ends up at col=4)
  // shared shifts to col=3. If there's something at (0,3) we have collision.

  // Simpler case: shared ends up in same cell as another node
  const graph = makeGraph(['A', 'B', 'C', 'shared', 'X', 'Y'], [
    ['A', 'B', 'b'],
    ['A', 'X', 'x'],
    ['B', 'shared', 's'],
    ['B', 'C', 'c'],
    ['C', 'shared', 'cs'],
    ['X', 'Y', 'y']
  ]);
  const result = layoutGraph(graph);

  console.log('  Shift collision test:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  assertNoCollisions(result);
  assertNoCrossings(result);
});

test('shift cascades to avoid collision in same row', () => {
  // Force a collision scenario:
  // Row 0: A -> B -> shared -> sharedChild
  // B also has child C which references shared
  // C is at col 2, shared originally at col 2 (same col as C!)
  // shared must shift to col 3
  // But what if there's already something at col 3 row 0?
  //
  // Let's create:
  // A -> B -> shared
  //      |
  //      C -> shared
  // A -> X -> Y -> Z (all on row 0, Z at col 3)
  //
  // Initially: A(0,0) B(0,1) shared(0,2) X(1,1) Y(1,2) Z(1,3)
  // When C at (1,2) references shared at (0,2) - they're in same column!
  // No wait, C references shared means shared must be RIGHT of C
  // C at col 2, shared at col 2 -> shift shared to col 3
  // But that only happens if C is placed before shared references it

  // Actually the scenario is:
  // 1. A->B->shared placed at row 0: A(0,0), B(0,1), shared(0,2)
  // 2. B->C placed below: C goes to (1,2)
  // 3. C->shared: shared is at col 2, C is at col 2 -> same column! Shift shared to col 3
  // 4. If there's something at (0,3), collision!

  // Create that exact scenario with another branch
  const graph = makeGraph(['A', 'B', 'C', 'shared', 'D'], [
    ['A', 'B', 'b'],
    ['B', 'shared', 's1'],
    ['shared', 'D', 'd'],  // D will be at col 3
    ['B', 'C', 'c'],
    ['C', 'shared', 's2']
  ]);
  const result = layoutGraph(graph);

  console.log('  Cascade collision test:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));

  // shared must be right of both B (col 1) and C
  const bPos = result.gridPos.get('B');
  const cPos = result.gridPos.get('C');
  const sharedPos = result.gridPos.get('shared');
  const dPos = result.gridPos.get('D');

  if (sharedPos.col <= bPos.col) {
    throw new Error(`shared must be right of B`);
  }
  if (sharedPos.col <= cPos.col) {
    throw new Error(`shared must be right of C`);
  }
  // D must be right of shared
  if (dPos.col <= sharedPos.col) {
    throw new Error(`D must be right of shared`);
  }

  assertNoCollisions(result);
  assertNoCrossings(result);
});

// ============================================================================
// Edge cases
// ============================================================================

test('real expand case - shared handler referenced from different depths', () => {
  // Real structure from editor-routes expand:
  // list-10-6 -> list-10-5 -> list-10-4 -> list-10-3 (coll chain)
  //                |           |
  //              item5       item4
  //                |           |
  //         entity-form-5  entity-form-4
  //                |           |
  //              handler     handler
  //                +--> shared-handler <--+
  //
  // The problem: entity-form-5 is at deeper row than entity-form-4
  // but both reference shared-handler
  // shared-handler gets placed when processing entity-form-4 (first)
  // then entity-form-5 references it from below

  const graph = makeGraph(
    ['list-6', 'list-5', 'list-4',
     'entity-5', 'entity-4', 'shared-handler',
     'path-5', 'path-4'],
    [
      ['list-6', 'list-5', 'coll'],
      ['list-5', 'list-4', 'coll'],
      ['list-6', 'entity-4', 'item4'],  // list-6 references entity-4
      ['list-5', 'entity-5', 'item5'],  // list-5 references entity-5
      ['entity-4', 'shared-handler', 'handler'],
      ['entity-5', 'shared-handler', 'handler'],
      ['entity-4', 'path-4', 'path'],
      ['entity-5', 'path-5', 'path']
    ]
  );
  const result = layoutGraph(graph);

  console.log('  Real expand case:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));
  console.log('  Validation:', result.validation);

  // shared-handler must be to the right of BOTH entity-4 and entity-5
  const e4Pos = result.gridPos.get('entity-4');
  const e5Pos = result.gridPos.get('entity-5');
  const sharedPos = result.gridPos.get('shared-handler');

  if (sharedPos.col <= e4Pos.col) {
    throw new Error(`shared-handler (col=${sharedPos.col}) must be right of entity-4 (col=${e4Pos.col})`);
  }
  if (sharedPos.col <= e5Pos.col) {
    throw new Error(`shared-handler (col=${sharedPos.col}) must be right of entity-5 (col=${e5Pos.col})`);
  }

  assertNoCollisions(result);
  assertNoCrossings(result, 'Real expand case');
});

test('duplicate edges handled', () => {
  const graph = makeGraph(['A', 'B'], [
    ['A', 'B', 'arg1'],
    ['A', 'B', 'arg2']
  ]);
  const result = layoutGraph(graph);
  assertEqual(result.gridPos.size, 2);
  assertNoCrossings(result);
});

test('child with placed descendant should go first in horizontal branch', () => {
  // This is the KEY test for the issue:
  // When list-5 has children [list-4, entity-5], and entity-5 has a child shared-handler
  // that is already placed (because entity-4 was processed first and placed shared-handler),
  // then entity-5 should go first in horizontal branch (not list-4)
  //
  // list-6 -> list-5 -> list-4
  //    |         |
  //  entity-4  entity-5
  //    |         |
  // shared-handler (SHARED!)
  //
  // Expected processing order:
  // 1. list-6 branch: list-6 -> entity-4 -> shared-handler (shared goes to horizontal)
  // 2. list-6's other child: list-5 branch
  //    - list-5 has children: list-4 and entity-5
  //    - entity-5 has child shared-handler which IS ALREADY PLACED
  //    - Therefore entity-5 should go first in horizontal branch: list-5 -> entity-5 -> (connect to shared)
  //    - Then list-4 goes below
  //
  // This avoids the vertical edge from list-5 crossing anything

  const graph = makeGraph(
    ['list-6', 'list-5', 'list-4', 'entity-4', 'entity-5', 'shared-handler'],
    [
      ['list-6', 'entity-4', 'item'],  // entity-4 first, will place shared-handler
      ['list-6', 'list-5', 'coll'],
      ['list-5', 'list-4', 'coll'],
      ['list-5', 'entity-5', 'item'],
      ['entity-4', 'shared-handler', 'handler'],
      ['entity-5', 'shared-handler', 'handler']
    ]
  );
  const result = layoutGraph(graph);

  console.log('  Child with placed descendant first:');
  console.log(result.ascii.split('\n').map(l => '    ' + l).join('\n'));
  console.log('  Positions:');
  result.gridPos.forEach((p, id) => console.log(`    ${id}: row=${p.row}, col=${p.col}`));
  console.log('  Validation:', result.validation);

  // Key assertions:
  // 1. No crossings
  assertNoCrossings(result, 'Child with placed descendant should prevent crossings');
  assertNoCollisions(result);

  // 2. shared-handler must be right of BOTH entity-4 and entity-5
  const e4Pos = result.gridPos.get('entity-4');
  const e5Pos = result.gridPos.get('entity-5');
  const sharedPos = result.gridPos.get('shared-handler');

  if (sharedPos.col <= e4Pos.col) {
    throw new Error(`shared-handler (col=${sharedPos.col}) must be right of entity-4 (col=${e4Pos.col})`);
  }
  if (sharedPos.col <= e5Pos.col) {
    throw new Error(`shared-handler (col=${sharedPos.col}) must be right of entity-5 (col=${e5Pos.col})`);
  }
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
