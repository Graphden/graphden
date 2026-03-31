const puppeteer = require('puppeteer');

// Test expand of list-10 and check for issues

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Take before screenshot
  await page.screenshot({ path: '/tmp/list10-before.png', fullPage: true });
  console.log('Before expand screenshot saved');

  // Click on list-10 node (it's in the editor-routes graph)
  const found = await page.evaluate(() => {
    const node = cy.nodes().filter(n => {
      const label = n.data('label');
      return label && label.includes('list-10') && !label.includes('list-10-');
    })[0];
    if (node) {
      node.emit('tap');
      return node.data('label');
    }
    return null;
  });

  if (!found) {
    console.log('list-10 node not found, trying list-10-9...');
    await page.evaluate(() => {
      const node = cy.nodes().filter(n => n.data('label')?.includes('list-10-9'))[0];
      if (node) node.emit('tap');
    });
  }

  await new Promise(r => setTimeout(r, 500));

  // Expand by clicking on first non-bold ancestor line
  const expanded = await page.evaluate(() => {
    const overlays = document.querySelectorAll('.node-overlay');
    for (const overlay of overlays) {
      if (overlay.style.display !== 'none') {
        const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
        for (const line of lines) {
          const level = parseInt(line.dataset.level) || 0;
          const isBold = line.style.fontWeight === 'bold';
          if (level > 0 && !isBold) {
            line.click();
            return line.textContent;
          }
        }
      }
    }
    return null;
  });

  console.log('Expanded:', expanded);
  await new Promise(r => setTimeout(r, 1500));

  // Take after screenshot
  await page.screenshot({ path: '/tmp/list10-after.png', fullPage: true });
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
    const edgeEdgeCrossings = [];

    // Check each cell for conflicts
    for (let r = 0; r < nodeGrid.length; r++) {
      const rowLen = Math.max(
        (nodeGrid[r] || []).length,
        (hEdge[r] || []).length,
        (vEdge[r] || []).length
      );
      for (let c = 0; c < rowLen; c++) {
        const hasNode = nodeGrid[r] && nodeGrid[r][c];
        const hasH = hEdge[r] && hEdge[r][c] !== null && hEdge[r][c] !== undefined;
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

        if (hasH && hasV) {
          edgeEdgeCrossings.push({ row: r, col: c });
        }
      }
    }

    return {
      totalNodes: gridPos.size,
      nodeOnEdgeCrossings,
      edgeEdgeCrossings: edgeEdgeCrossings.length
    };
  });

  console.log('\n=== After list-10 Expand Analysis ===');
  console.log(`Total nodes: ${result.totalNodes}`);

  if (result.nodeOnEdgeCrossings.length === 0) {
    console.log('✓ No node-on-edge crossings detected');
  } else {
    console.log(`⚠️ Found ${result.nodeOnEdgeCrossings.length} node-on-edge crossings:`);
    result.nodeOnEdgeCrossings.forEach(c => {
      console.log(`  "${c.nodeLabel}" at (${c.row}, ${c.col})`);
    });
  }

  console.log(`Edge-edge crossings: ${result.edgeEdgeCrossings}`);

  await browser.close();
})();
