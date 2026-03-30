const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 900 });
  
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));
  
  // Expand a node with ancestors
  await page.evaluate(() => {
    const lines = document.querySelectorAll('.ancestor-line');
    for (const line of lines) {
      const level = parseInt(line.dataset.level);
      if (level > 0) {
        line.click();
        break;
      }
    }
  });
  
  await new Promise(r => setTimeout(r, 1000));
  
  // Run layoutGraph and check
  const result = await page.evaluate(() => {
    if (!window.cy || !window.layoutGraph) return { error: 'no layoutGraph' };
    
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };
    
    const layoutResult = layoutGraph(elements);
    
    // Check for edge-through-node crossings
    const crossings = [];
    for (let r = 0; r < layoutResult.matrix.nodeGrid.length; r++) {
      for (let c = 0; c < (layoutResult.matrix.nodeGrid[r] || []).length; c++) {
        const node = layoutResult.matrix.nodeGrid[r] && layoutResult.matrix.nodeGrid[r][c];
        const vEdge = layoutResult.matrix.vEdge[r] && layoutResult.matrix.vEdge[r][c];
        
        if (node && vEdge) {
          const n = cy.getElementById(node);
          const label = n.data('label') ? n.data('label').split('\n')[0].substring(0, 20) : node;
          crossings.push({ row: r, col: c, type: 'vEdge through ' + label });
        }
      }
    }
    
    // Check first-child-same-row rule using edges
    const violations = [];
    
    // Build children map from edges
    const childrenMap = new Map();
    cy.edges().forEach(e => {
      const src = e.data('source');
      const tgt = e.data('target');
      if (!childrenMap.has(src)) childrenMap.set(src, []);
      childrenMap.get(src).push(tgt);
    });
    
    // Build parents map for shared node detection
    const parentsMap = new Map();
    cy.edges().forEach(e => {
      const tgt = e.data('target');
      if (!parentsMap.has(tgt)) parentsMap.set(tgt, []);
      parentsMap.get(tgt).push(e.data('source'));
    });

    for (const [parentId, childIds] of childrenMap.entries()) {
      if (childIds.length === 0) continue;

      const parentPos = layoutResult.gridPos.get(parentId);
      if (!parentPos) continue;

      const firstChildId = childIds[0];
      const firstChildPos = layoutResult.gridPos.get(firstChildId);
      if (!firstChildPos) continue;

      if (firstChildPos.row !== parentPos.row) {
        // Check if any sibling (shared node) is on parent's row - this is OK
        const hasSharedOnSameRow = childIds.some(sibId => {
          const sibParents = parentsMap.get(sibId) || [];
          const sibPos = layoutResult.gridPos.get(sibId);
          return sibParents.length > 1 && sibPos && sibPos.row === parentPos.row;
        });
        if (hasSharedOnSameRow) continue; // Not a violation

        const parentNode = cy.getElementById(parentId);
        const childNode = cy.getElementById(firstChildId);
        const parentLabel = parentNode.data('label') ? parentNode.data('label').split('\n')[0].substring(0, 15) : parentId;
        const childLabel = childNode.data('label') ? childNode.data('label').split('\n')[0].substring(0, 15) : firstChildId;
        violations.push({
          parent: parentLabel,
          parentRow: parentPos.row,
          child: childLabel,
          childRow: firstChildPos.row
        });
      }
    }
    
    return {
      nodeCount: layoutResult.gridPos.size,
      crossings,
      firstChildViolations: violations
    };
  });
  
  if (result.error) {
    console.log('Error:', result.error);
    await browser.close();
    return;
  }
  
  console.log('Expanded graph - nodes:', result.nodeCount);
  console.log('');
  console.log('Edge-through-node crossings:', result.crossings.length);
  if (result.crossings.length > 0) {
    result.crossings.forEach(c => console.log('  ', c.type, 'at row=' + c.row + ', col=' + c.col));
  }
  
  console.log('');
  console.log('First-child-same-row violations:', result.firstChildViolations.length);
  if (result.firstChildViolations.length > 0) {
    result.firstChildViolations.forEach(v => {
      console.log('  ', v.parent, '(row=' + v.parentRow + ') -> first child', v.child, '(row=' + v.childRow + ')');
    });
  }
  
  await browser.close();
})();
