const puppeteer = require('puppeteer');

// Test expand and take screenshots

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Take before screenshot
  await page.screenshot({ path: '/tmp/before-expand.png', fullPage: true });
  console.log('Before expand screenshot saved');

  // Click on entity-form-create-route
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Expand by clicking on an ancestor line
  await page.evaluate(() => {
    const overlays = document.querySelectorAll('.node-overlay');
    for (const overlay of overlays) {
      if (overlay.style.display !== 'none') {
        const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
        for (const line of lines) {
          const level = parseInt(line.dataset.level) || 0;
          const isBold = line.style.fontWeight === 'bold';
          if (level > 0 && !isBold) {
            line.click();
            return true;
          }
        }
      }
    }
    return false;
  });
  await new Promise(r => setTimeout(r, 1500));

  // Take after screenshot
  await page.screenshot({ path: '/tmp/after-expand.png', fullPage: true });
  console.log('After expand screenshot saved');

  // Check for crossings
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };
    const layout = layoutGraph(elements);
    const { gridPos, matrix } = layout;
    const { nodeGrid, hEdge, vEdge } = matrix;

    const nodeOnEdgeCrossings = [];

    // Check each cell for node-on-vEdge conflicts
    for (let r = 0; r < nodeGrid.length; r++) {
      const rowLen = Math.max(
        (nodeGrid[r] || []).length,
        (vEdge[r] || []).length
      );
      for (let c = 0; c < rowLen; c++) {
        const hasNode = nodeGrid[r] && nodeGrid[r][c];
        const hasV = vEdge[r] && vEdge[r][c];

        if (hasNode && hasV) {
          const nodeId = nodeGrid[r][c];
          const label = cy.$(`#${nodeId}`).data('label')?.split('\n')[0]?.substring(0, 30);
          nodeOnEdgeCrossings.push({
            row: r,
            col: c,
            nodeLabel: label
          });
        }
      }
    }

    return {
      totalNodes: gridPos.size,
      nodeOnEdgeCrossings
    };
  });

  console.log('\n=== After Expand Analysis ===');
  console.log(`Total nodes: ${result.totalNodes}`);
  if (result.nodeOnEdgeCrossings.length === 0) {
    console.log('✓ No node-on-edge crossings detected');
  } else {
    console.log(`⚠️ Found ${result.nodeOnEdgeCrossings.length} node-on-edge crossings:`);
    result.nodeOnEdgeCrossings.forEach(c => {
      console.log(`  "${c.nodeLabel}" at (${c.row}, ${c.col})`);
    });
  }

  await browser.close();
})();
