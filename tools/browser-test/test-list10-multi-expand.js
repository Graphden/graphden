const puppeteer = require('puppeteer');

// Test multiple expand levels of list-10

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Find and click on list-10-9 (which is part of the list chain)
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('list-10-9'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Function to expand to next level
  async function expandNextLevel() {
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
    await new Promise(r => setTimeout(r, 1000));
    return expanded;
  }

  // Expand multiple levels
  for (let i = 0; i < 5; i++) {
    const expanded = await expandNextLevel();
    if (!expanded) {
      console.log(`Level ${i}: No more levels to expand`);
      break;
    }
    console.log(`Level ${i + 1}: Expanded "${expanded}"`);
  }

  // Take screenshot
  await page.screenshot({ path: '/tmp/list10-multi-expand.png', fullPage: true });
  console.log('\nScreenshot saved to /tmp/list10-multi-expand.png');

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

    // Check layout order issues
    const { children, parents } = buildAdjacency(elements.edges);
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    const orderIssues = [];
    children.forEach((childList, parentId) => {
      if (childList.length < 2) return;
      const parentData = nodeDataMap.get(parentId);
      if (!parentData || parentData.type !== 'fn') return;

      const childrenWithPos = childList.map(childId => {
        const data = nodeDataMap.get(childId);
        const pos = gridPos.get(childId);
        let type = 'free';
        if (data) {
          if (data.isPlaceholder) type = 'free';
          else if (data.type === 'fn') type = 'fn';
          else if (data.type === 'arg') type = 'fixed';
        }
        return { childId, type, row: pos ? pos.row : 999 };
      });

      childrenWithPos.sort((a, b) => a.row - b.row);

      const fnRows = childrenWithPos.filter(c => c.type === 'fn').map(c => c.row);
      const fixedRows = childrenWithPos.filter(c => c.type === 'fixed').map(c => c.row);
      const freeRows = childrenWithPos.filter(c => c.type === 'free').map(c => c.row);

      const maxFnRow = Math.max(...fnRows, -1);
      const minFixedRow = Math.min(...fixedRows, Infinity);
      const minFreeRow = Math.min(...freeRows, Infinity);

      if ((maxFnRow >= 0 && minFixedRow < Infinity && maxFnRow > minFixedRow) ||
          (maxFnRow >= 0 && minFreeRow < Infinity && maxFnRow > minFreeRow)) {
        orderIssues.push({
          parent: parentData.label?.split('\n')[0],
          children: childrenWithPos.map(c => ({ type: c.type, row: c.row }))
        });
      }
    });

    return {
      totalNodes: gridPos.size,
      nodeOnEdgeCrossings,
      orderIssues
    };
  });

  console.log('\n=== After Multi-Expand Analysis ===');
  console.log(`Total nodes: ${result.totalNodes}`);

  if (result.nodeOnEdgeCrossings.length === 0) {
    console.log('✓ No node-on-edge crossings');
  } else {
    console.log(`⚠️ Found ${result.nodeOnEdgeCrossings.length} node-on-edge crossings:`);
    result.nodeOnEdgeCrossings.forEach(c => {
      console.log(`  "${c.nodeLabel}" at (${c.row}, ${c.col})`);
    });
  }

  if (result.orderIssues.length === 0) {
    console.log('✓ No argument order issues');
  } else {
    console.log(`⚠️ Found ${result.orderIssues.length} argument order issues:`);
    result.orderIssues.forEach(issue => {
      console.log(`  ${issue.parent}: ${issue.children.map(c => `[${c.type}@${c.row}]`).join(', ')}`);
    });
  }

  await browser.close();
})();
