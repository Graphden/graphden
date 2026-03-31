const puppeteer = require('puppeteer');

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

  // Expand all
  await expandAllNodes(page);
  await new Promise(r => setTimeout(r, 500));

  await page.screenshot({ path: '/tmp/expanded-graph.png' });
  console.log('Saved: /tmp/expanded-graph.png');

  // Check positions
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };
    const layout = layoutGraph(elements);
    
    return {
      nodeCount: layout.gridPos.size,
      positions: Array.from(layout.gridPos.entries()).map(([id, pos]) => ({
        label: cy.$(`#${id}`).data('label')?.split('\n')[0]?.substring(0, 25),
        row: pos.row,
        col: pos.col
      })).sort((a, b) => a.col - b.col || a.row - b.row)
    };
  });

  console.log(`\nExpanded graph: ${result.nodeCount} nodes`);
  console.log('Positions:');
  result.positions.forEach(p => {
    console.log(`  ${p.label}: row=${p.row}, col=${p.col}`);
  });

  await browser.close();
})();
