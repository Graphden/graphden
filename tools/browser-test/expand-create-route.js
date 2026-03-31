const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Click on entity-form-create-route node to select it
  const clicked = await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (node) {
      node.emit('tap');
      return node.id();
    }
    return null;
  });
  console.log('Clicked node:', clicked);
  await new Promise(r => setTimeout(r, 500));

  // Now try to expand via overlay
  const expandResult = await page.evaluate(() => {
    const overlays = document.querySelectorAll('.node-overlay');
    for (const overlay of overlays) {
      if (overlay.style.display !== 'none') {
        const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
        for (const line of lines) {
          const level = parseInt(line.dataset.level) || 0;
          const isBold = line.style.fontWeight === 'bold';
          if (level > 0 && !isBold) {
            const text = line.textContent;
            line.click();
            return `expanded: ${text}`;
          }
        }
        return 'no expandable lines';
      }
    }
    return 'no visible overlay';
  });
  console.log('Expand:', expandResult);
  await new Promise(r => setTimeout(r, 1000));

  // Get positions after expand
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };
    const layout = layoutGraph(elements);
    
    const positions = [];
    layout.gridPos.forEach((pos, id) => {
      const label = cy.$(`#${id}`).data('label')?.split('\n')[0]?.substring(0, 30);
      positions.push({ label, row: pos.row, col: pos.col });
    });
    
    return {
      nodeCount: layout.gridPos.size,
      positions: positions.sort((a, b) => a.row - b.row || a.col - b.col)
    };
  });

  console.log(`\nNodes after expand: ${result.nodeCount}`);
  console.log('All positions:');
  result.positions.forEach(p => {
    console.log(`  row=${p.row}, col=${p.col}: ${p.label}`);
  });

  await page.screenshot({ path: '/tmp/create-expand.png' });
  await browser.close();
})();
