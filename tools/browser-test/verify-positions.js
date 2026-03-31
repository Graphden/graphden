const puppeteer = require('puppeteer');

// Verify exact node positions at column 4 and 5

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Find and click on list-10-9
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('list-10-9'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Expand 5 levels
  for (let i = 0; i < 5; i++) {
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
  }

  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);
    const { gridPos, matrix } = layout;
    const { nodeGrid, vEdge } = matrix;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Get col 4 and 5 state for rows 0-16
    const grid = [];
    for (let r = 0; r < 17; r++) {
      const row = { r };
      for (let c = 3; c <= 6; c++) {
        const nodeId = nodeGrid[r] && nodeGrid[r][c];
        const hasV = vEdge[r] && vEdge[r][c];
        const label = nodeId ? nodeDataMap.get(nodeId)?.label?.split('\n')[0]?.substring(0, 15) : null;
        row['col' + c] = { node: label, vEdge: hasV };
      }
      grid.push(row);
    }

    return grid;
  });

  console.log('=== Grid State (columns 3-6, rows 0-16) ===');
  console.log('Row | Col3                   | Col4                   | Col5                   | Col6');
  console.log('----|------------------------|------------------------|------------------------|------------------------');
  result.forEach(row => {
    const cols = [3, 4, 5, 6].map(c => {
      const cell = row['col' + c];
      const nodeStr = cell.node ? cell.node.padEnd(15) : '               ';
      const vStr = cell.vEdge ? '|' : ' ';
      return `${nodeStr} ${vStr}`;
    });
    console.log(`${row.r.toString().padStart(3)} | ${cols.join(' | ')}`);
  });

  await browser.close();
})();
