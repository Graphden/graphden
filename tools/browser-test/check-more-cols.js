const puppeteer = require('puppeteer');

// Check columns 6-10 for clear paths

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
    const { nodeGrid } = matrix;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Check columns 6-10 for nodes in rows 1-14
    const colAnalysis = [];
    for (let col = 6; col <= 12; col++) {
      const blockers = [];
      for (let r = 1; r < 15; r++) {
        const nodeId = nodeGrid[r] && nodeGrid[r][col];
        if (nodeId) {
          blockers.push({
            row: r,
            label: nodeDataMap.get(nodeId)?.label?.split('\n')[0]?.substring(0, 20)
          });
        }
      }
      colAnalysis.push({
        col,
        clear: blockers.length === 0,
        blockers
      });
    }

    return colAnalysis;
  });

  console.log('=== Columns 6-12 Analysis (checking rows 1-14) ===');
  result.forEach(c => {
    console.log(`Column ${c.col}: ${c.clear ? 'CLEAR' : 'BLOCKED'}`);
    if (c.blockers.length > 0) {
      c.blockers.forEach(b => {
        console.log(`  row ${b.row}: "${b.label}"`);
      });
    }
  });

  await browser.close();
})();
