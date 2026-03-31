const puppeteer = require('puppeteer');

// This script checks for ACTUAL edge-node crossings by examining the matrix
// It looks for cases where a node is placed on a vEdge or where hEdge and vEdge cross

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Click on entity-form-create-route and expand
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

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
            return;
          }
        }
      }
    }
  });
  await new Promise(r => setTimeout(r, 1000));

  // Check for ACTUAL edge-node crossings in the matrix
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

        // Node on vEdge is a crossing (bad)
        if (hasNode && hasV) {
          const nodeId = nodeGrid[r][c];
          const label = cy.$(`#${nodeId}`).data('label')?.split('\n')[0]?.substring(0, 30);
          nodeOnEdgeCrossings.push({
            type: 'node-on-vedge',
            row: r,
            col: c,
            nodeLabel: label
          });
        }

        // hEdge crossing vEdge
        if (hasH && hasV) {
          edgeEdgeCrossings.push({
            type: 'hedge-vedge',
            row: r,
            col: c
          });
        }
      }
    }

    // Get specific info about edit-route to handler path
    const editRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-edit-route'))[0];
    const handler = cy.nodes().filter(n => n.data('label')?.includes('entity-form-handler'))[0];

    let specificInfo = null;
    if (editRoute && handler) {
      const editPos = gridPos.get(editRoute.id());
      const handlerPos = gridPos.get(handler.id());
      specificInfo = {
        editRoute: editPos,
        handler: handlerPos
      };
    }

    return {
      nodeOnEdgeCrossings,
      edgeEdgeCrossings: edgeEdgeCrossings.length,
      specificInfo,
      totalNodes: gridPos.size
    };
  });

  console.log('=== Matrix Analysis After Expand ===');
  console.log(`Total nodes: ${result.totalNodes}`);

  console.log('\n=== Node-on-Edge Crossings (BAD) ===');
  if (result.nodeOnEdgeCrossings.length === 0) {
    console.log('✓ No nodes placed on edge paths');
  } else {
    console.log(`Found ${result.nodeOnEdgeCrossings.length} node-on-edge crossings:`);
    result.nodeOnEdgeCrossings.forEach(c => {
      console.log(`  "${c.nodeLabel}" at (${c.row}, ${c.col})`);
    });
  }

  console.log(`\n=== Edge-Edge Crossings ===`);
  console.log(`Found ${result.edgeEdgeCrossings} horizontal-vertical edge crossings`);
  console.log('(These are acceptable in dense graphs)');

  if (result.specificInfo) {
    console.log('\n=== entity-form-edit-route -> entity-form-handler ===');
    console.log('edit-route position:', result.specificInfo.editRoute);
    console.log('handler position:', result.specificInfo.handler);
  }

  await browser.close();
})();
