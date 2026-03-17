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
// Edge cases
// ============================================================================

test('duplicate edges handled', () => {
  const graph = makeGraph(['A', 'B'], [
    ['A', 'B', 'arg1'],
    ['A', 'B', 'arg2']
  ]);
  const result = layoutGraph(graph);
  assertEqual(result.gridPos.size, 2);
  assertNoCrossings(result);
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
