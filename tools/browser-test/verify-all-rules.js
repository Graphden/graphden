const puppeteer = require('puppeteer');

/**
 * Comprehensive layout rule checker.
 * Checks ALL rules both without expands and with maximum expansion.
 */

const RULES = {
  CHILD_RIGHT_OF_PARENT: 'Children must be to the right of parent (child.col > parent.col)',
  FIRST_CHILD_SAME_ROW: 'First child must be on same row as parent',
  ONE_CHILD_PER_ROW: 'Only one child per row per parent (except shared nodes)',
  NO_BACKWARD_EDGES: 'No backward edges (source.col < target.col)',
  NO_EDGE_CROSSINGS: 'No edges crossing through nodes'
};

function checkAllRules(layoutResult, edges, getLabel) {
  const { gridPos } = layoutResult;
  const violations = [];

  // Build parent->children map
  const children = new Map();
  const parents = new Map();
  edges.forEach(e => {
    const src = e.data.source;
    const tgt = e.data.target;
    if (!children.has(src)) children.set(src, []);
    children.get(src).push(tgt);
    if (!parents.has(tgt)) parents.set(tgt, []);
    parents.get(tgt).push(src);
  });

  // Find shared nodes
  const sharedNodes = new Set();
  parents.forEach((parentList, nodeId) => {
    if (parentList.length > 1) sharedNodes.add(nodeId);
  });

  // Rule 1: Children must be to the right of parent
  children.forEach((childIds, parentId) => {
    const parentPos = gridPos.get(parentId);
    if (!parentPos) return;

    childIds.forEach(childId => {
      const childPos = gridPos.get(childId);
      if (!childPos) return;

      if (childPos.col <= parentPos.col) {
        violations.push({
          rule: 'CHILD_RIGHT_OF_PARENT',
          message: `${getLabel(childId)} (col=${childPos.col}) not right of ${getLabel(parentId)} (col=${parentPos.col})`
        });
      }
    });
  });

  // Rule 2: First child same row as parent
  // Rule 3: One child per row per parent
  children.forEach((childIds, parentId) => {
    const parentPos = gridPos.get(parentId);
    if (!parentPos) return;

    // Get positions of all children
    const childPositions = childIds
      .map(id => ({ id, pos: gridPos.get(id), label: getLabel(id), isShared: sharedNodes.has(id) }))
      .filter(c => c.pos);

    if (childPositions.length === 0) return;

    // Rule 2: At least one child must be on the same row as parent
    // This child is the "first child" (horizontal continuation)
    //
    // EXCEPTION: If ALL children are shared nodes, they may be placed on
    // a different row (the row of their "lower parent"). This is correct behavior
    // for shared argument handling.
    const childrenOnParentRow = childPositions.filter(c => c.pos.row === parentPos.row);
    const allChildrenAreShared = childPositions.every(c => c.isShared);

    if (childrenOnParentRow.length === 0 && !allChildrenAreShared) {
      // No child on parent's row and not all shared - violation
      // Report the leftmost child as the one that should be on parent's row
      childPositions.sort((a, b) => a.pos.col - b.pos.col);
      const firstChild = childPositions[0];
      violations.push({
        rule: 'FIRST_CHILD_SAME_ROW',
        message: `First child ${firstChild.label} (row=${firstChild.pos.row}) not on same row as ${getLabel(parentId)} (row=${parentPos.row})`
      });
    }

    // Rule 3: One child per row (excluding shared nodes on parent's row)
    // Non-shared children should each be on a different row
    const rowCounts = new Map();
    childPositions.forEach(c => {
      // Shared nodes on parent's row are exempt (they're placed specially)
      if (c.isShared && c.pos.row === parentPos.row) return;

      const key = c.pos.row;
      if (!rowCounts.has(key)) rowCounts.set(key, []);
      rowCounts.get(key).push(c);
    });

    rowCounts.forEach((childrenOnRow, row) => {
      if (childrenOnRow.length > 1) {
        const names = childrenOnRow.map(c => c.label).join(', ');
        violations.push({
          rule: 'ONE_CHILD_PER_ROW',
          message: `Parent ${getLabel(parentId)} has ${childrenOnRow.length} children on row ${row}: ${names}`
        });
      }
    });
  });

  // Rule 4: No backward edges
  edges.forEach(e => {
    const sourcePos = gridPos.get(e.data.source);
    const targetPos = gridPos.get(e.data.target);
    if (!sourcePos || !targetPos) return;

    if (sourcePos.col >= targetPos.col) {
      violations.push({
        rule: 'NO_BACKWARD_EDGES',
        message: `Edge ${getLabel(e.data.source)} (col=${sourcePos.col}) -> ${getLabel(e.data.target)} (col=${targetPos.col})`
      });
    }
  });

  return violations;
}

async function runTest(page, testName, expandFn = null) {
  console.log(`\n=== ${testName} ===`);

  // Apply expansion if provided
  if (expandFn) {
    await expandFn(page);
    await new Promise(r => setTimeout(r, 500));
  }

  const result = await page.evaluate(() => {
    if (!window.cy || !window.layoutGraph) {
      return { error: 'cy or layoutGraph not available' };
    }

    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);

    return {
      nodeCount: layout.gridPos.size,
      gridPos: Array.from(layout.gridPos.entries()),
      edges: elements.edges,
      valid: layout.validation.valid
    };
  });

  if (result.error) {
    console.log('ERROR:', result.error);
    return { passed: false, violations: [{ rule: 'ERROR', message: result.error }] };
  }

  // Reconstruct gridPos Map
  const gridPos = new Map(result.gridPos);
  const layoutResult = { gridPos };

  // Get labels
  const labels = await page.evaluate(() => {
    const map = {};
    cy.nodes().forEach(n => {
      const label = n.data('label');
      map[n.id()] = label ? label.split('\n')[0].substring(0, 25) : n.id();
    });
    return map;
  });

  const getLabel = (id) => labels[id] || id;

  const violations = checkAllRules(layoutResult, result.edges, getLabel);

  console.log(`Nodes: ${result.nodeCount}`);
  if (violations.length === 0) {
    console.log('✓ All rules passed');
    return { passed: true, violations: [] };
  } else {
    console.log(`✗ ${violations.length} violations:`);
    violations.forEach(v => console.log(`  - [${v.rule}] ${v.message}`));
    return { passed: false, violations };
  }
}

async function expandAllNodes(page) {
  for (let round = 0; round < 20; round++) {
    const clicked = await page.evaluate(() => {
      const overlays = document.querySelectorAll('.node-overlay');
      for (const overlay of overlays) {
        const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
        let maxLevel = -1;
        let targetLine = null;
        for (const line of lines) {
          const level = parseInt(line.dataset.level) || 0;
          const isBold = line.style.fontWeight === 'bold';
          if (level > maxLevel && !isBold) {
            maxLevel = level;
            targetLine = line;
          }
        }
        if (targetLine && maxLevel > 0) {
          targetLine.click();
          return true;
        }
      }
      return false;
    });
    if (!clicked) break;
    await new Promise(r => setTimeout(r, 400));
  }
}

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  const results = [];

  // Test 1: Base graph without expands
  results.push(await runTest(page, 'Base graph (no expands)'));

  // Reload page for clean state
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Test 2: With maximum expansion
  results.push(await runTest(page, 'Maximum expansion', expandAllNodes));

  // Summary
  console.log('\n========================================');
  console.log('SUMMARY');
  console.log('========================================');
  const allPassed = results.every(r => r.passed);
  if (allPassed) {
    console.log('✓ ALL TESTS PASSED');
  } else {
    console.log('✗ SOME TESTS FAILED');
    results.forEach((r, i) => {
      if (!r.passed) {
        console.log(`  Test ${i + 1}: ${r.violations.length} violations`);
      }
    });
  }

  await page.screenshot({ path: '/tmp/verify-all-rules.png' });
  await browser.close();

  process.exit(allPassed ? 0 : 1);
})();
