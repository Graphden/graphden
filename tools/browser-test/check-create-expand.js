const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Find and expand entity-form-create-route
  const expanded = await page.evaluate(() => {
    const overlays = document.querySelectorAll('.node-overlay');
    for (const overlay of overlays) {
      const nameEl = overlay.querySelector('.overlay-fn-name');
      if (nameEl && nameEl.textContent.includes('entity-form-create-route')) {
        const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
        for (const line of lines) {
          const level = parseInt(line.dataset.level) || 0;
          const isBold = line.style.fontWeight === 'bold';
          if (level > 0 && !isBold) {
            line.click();
            return 'clicked';
          }
        }
      }
    }
    return 'not found';
  });

  console.log('Expand result:', expanded);
  await new Promise(r => setTimeout(r, 1000));

  // Get positions
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };
    const layout = layoutGraph(elements);
    
    // Find relevant nodes
    const positions = [];
    layout.gridPos.forEach((pos, id) => {
      const label = cy.$(`#${id}`).data('label')?.split('\n')[0]?.substring(0, 30);
      if (label && (
        label.includes('entity-form') || 
        label.includes('method-map') || 
        label.includes('get') ||
        label.includes('handler')
      )) {
        positions.push({ id, label, row: pos.row, col: pos.col });
      }
    });
    
    return {
      nodeCount: layout.gridPos.size,
      positions: positions.sort((a, b) => a.row - b.row || a.col - b.col)
    };
  });

  console.log(`\nNodes: ${result.nodeCount}`);
  console.log('Relevant positions:');
  result.positions.forEach(p => {
    console.log(`  ${p.label}: row=${p.row}, col=${p.col}`);
  });

  await page.screenshot({ path: '/tmp/create-expand.png' });
  console.log('\nSaved: /tmp/create-expand.png');

  await browser.close();
})();
