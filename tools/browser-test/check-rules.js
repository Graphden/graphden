const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  const result = await page.evaluate(() => {
    if (!window.cy || !window.layoutGraph) return { error: 'no layoutGraph' };

    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);
    const violations = [];

    // Build parent->children map from edges
    const childrenMap = new Map();
    const parentsMap = new Map();
    cy.edges().forEach(e => {
      const src = e.data('source');
      const tgt = e.data('target');
      if (!childrenMap.has(src)) childrenMap.set(src, []);
      childrenMap.get(src).push(tgt);
      if (!parentsMap.has(tgt)) parentsMap.set(tgt, []);
      parentsMap.get(tgt).push(src);
    });

    // Helper to get label
    const getLabel = (id) => {
      const n = cy.getElementById(id);
      const label = n.data('label');
      return label ? label.split('\n')[0].substring(0, 20) : id.substring(0, 8);
    };

    // Check each parent-child relationship
    for (const [parentId, childIds] of childrenMap.entries()) {
      const parentPos = layout.gridPos.get(parentId);
      if (!parentPos) continue;

      const parentLabel = getLabel(parentId);

      childIds.forEach((childId, idx) => {
        const childPos = layout.gridPos.get(childId);
        if (!childPos) return;

        const childLabel = getLabel(childId);

        // Rule 1: Child must be to the right of parent
        if (childPos.col <= parentPos.col) {
          violations.push({
            rule: 'child-right-of-parent',
            parent: parentLabel,
            child: childLabel,
            detail: 'child.col=' + childPos.col + ' <= parent.col=' + parentPos.col
          });
        }

        // Rule 2: First child on same row
        // EXCEPTION: If a shared node among children is on same row, it became "first"
        if (idx === 0 && childPos.row !== parentPos.row) {
          // Check if any sibling (shared node) is on parent's row
          const hasSharedOnSameRow = childIds.some(sibId => {
            const sibParents = parentsMap.get(sibId) || [];
            const sibPos = layout.gridPos.get(sibId);
            return sibParents.length > 1 && sibPos && sibPos.row === parentPos.row;
          });
          if (!hasSharedOnSameRow) {
            violations.push({
              rule: 'first-child-same-row',
              parent: parentLabel,
              child: childLabel,
              detail: 'child.row=' + childPos.row + ' != parent.row=' + parentPos.row
            });
          }
        }

        // Rule 3: Other children strictly below (EXCEPTION: shared nodes stay on lower parent's row)
        const childParents = parentsMap.get(childId) || [];
        const isSharedNode = childParents.length > 1;
        if (idx > 0 && childPos.row <= parentPos.row && !isSharedNode) {
          violations.push({
            rule: 'other-children-below',
            parent: parentLabel,
            child: childLabel,
            detail: 'child[' + idx + '].row=' + childPos.row + ' <= parent.row=' + parentPos.row
          });
        }
      });

      // Rule 4: One child per row per parent (EXCEPTION: shared nodes allowed on same row)
      const rowCounts = {};
      const nonSharedOnRow = {};
      childIds.forEach(childId => {
        const childPos = layout.gridPos.get(childId);
        if (!childPos) return;
        const childParents = parentsMap.get(childId) || [];
        const isShared = childParents.length > 1;
        rowCounts[childPos.row] = (rowCounts[childPos.row] || 0) + 1;
        if (!isShared) {
          nonSharedOnRow[childPos.row] = (nonSharedOnRow[childPos.row] || 0) + 1;
        }
      });
      // Only violation if more than 1 NON-shared child on same row
      for (const [row, count] of Object.entries(nonSharedOnRow)) {
        if (count > 1) {
          violations.push({
            rule: 'one-child-per-row',
            parent: parentLabel,
            detail: count + ' non-shared children on row ' + row
          });
        }
      }
    }

    // Rule 6: Shared node should be in horizontal branch of lower parent
    for (const [nodeId, parentIds] of parentsMap.entries()) {
      if (parentIds.length < 2) continue;

      const nodePos = layout.gridPos.get(nodeId);
      if (!nodePos) continue;

      // Find lower parent (max row)
      let maxRow = -1;
      let lowerParentId = null;
      parentIds.forEach(pid => {
        const ppos = layout.gridPos.get(pid);
        if (ppos && ppos.row > maxRow) {
          maxRow = ppos.row;
          lowerParentId = pid;
        }
      });

      if (!lowerParentId) continue;

      const lowerParentPos = layout.gridPos.get(lowerParentId);
      const nodeLabel = getLabel(nodeId);
      const lowerParentLabel = getLabel(lowerParentId);

      // Shared node should be on same row as lower parent
      if (nodePos.row !== lowerParentPos.row) {
        violations.push({
          rule: 'shared-in-lower-horizontal',
          node: nodeLabel,
          lowerParent: lowerParentLabel,
          detail: 'shared.row=' + nodePos.row + ' != lowerParent.row=' + lowerParentPos.row
        });
      }
    }

    // Rule 7: No edge crossings through nodes
    for (let r = 0; r < layout.matrix.nodeGrid.length; r++) {
      for (let c = 0; c < (layout.matrix.nodeGrid[r] || []).length; c++) {
        const node = layout.matrix.nodeGrid[r] && layout.matrix.nodeGrid[r][c];
        const vEdge = layout.matrix.vEdge[r] && layout.matrix.vEdge[r][c];

        if (node && vEdge) {
          violations.push({
            rule: 'no-vedge-through-node',
            node: getLabel(node),
            detail: 'vEdge at row=' + r + ', col=' + c
          });
        }
      }
    }

    return { violations, nodeCount: layout.gridPos.size };
  });

  if (result.error) {
    console.log('Error:', result.error);
  } else {
    console.log('Nodes:', result.nodeCount);
    console.log('Violations:', result.violations.length);
    console.log('');

    // Group by rule
    const byRule = {};
    result.violations.forEach(v => {
      if (!byRule[v.rule]) byRule[v.rule] = [];
      byRule[v.rule].push(v);
    });

    for (const rule of Object.keys(byRule)) {
      const vs = byRule[rule];
      console.log('[' + rule + '] (' + vs.length + '):');
      vs.slice(0, 5).forEach(v => {
        if (v.parent && v.child) {
          console.log('  ' + v.parent + ' -> ' + v.child + ': ' + v.detail);
        } else if (v.node) {
          console.log('  ' + v.node + ': ' + v.detail);
        } else {
          console.log('  ' + (v.parent || '') + ': ' + v.detail);
        }
      });
      if (vs.length > 5) console.log('  ... and ' + (vs.length - 5) + ' more');
    }
  }

  await browser.close();
})();
