const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  page.on('console', msg => {
    const text = msg.text();
    if (text.includes('Build:')) console.log('BROWSER:', text);
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  const ids = await page.evaluate(() => {
    let createRouteId = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('entity-form-create-route')) createRouteId = n.data('originalFnId');
    });
    return { createRouteId };
  });

  console.log('Create-route ID:', ids.createRouteId);

  // Test different expansion levels
  for (let level = 1; level <= 5; level++) {
    console.log(`\n========== TESTING LEVEL ${level} ==========`);

    await page.evaluate((fnId, lvl) => {
      setExpansionLevel(fnId, lvl);
    }, ids.createRouteId, level);

    await new Promise(r => setTimeout(r, 1500));

    const result = await page.evaluate(() => {
      // Check for overlaps
      const rows = {};
      cy.nodes().forEach(n => {
        const y = Math.round(n.position().y);
        // Group by exact Y (within 5px)
        const rowKey = Math.round(y / 10) * 10;
        if (!rows[rowKey]) rows[rowKey] = [];
        rows[rowKey].push({
          id: n.id(),
          label: (n.data('label') || '').substring(0, 30).replace(/\n/g, '|'),
          x: Math.round(n.position().x),
          y: y
        });
      });

      const overlaps = [];
      for (const [y, rowNodes] of Object.entries(rows)) {
        if (rowNodes.length < 2) continue;
        rowNodes.sort((a, b) => a.x - b.x);
        for (let i = 1; i < rowNodes.length; i++) {
          const gap = rowNodes[i].x - rowNodes[i-1].x;
          if (gap < 100) {
            overlaps.push({
              y: parseInt(y),
              node1: rowNodes[i-1].label,
              x1: rowNodes[i-1].x,
              node2: rowNodes[i].label,
              x2: rowNodes[i].x,
              gap
            });
          }
        }
      }

      return {
        totalNodes: cy.nodes().length,
        overlaps
      };
    });

    console.log(`Nodes: ${result.totalNodes}`);

    if (result.overlaps.length > 0) {
      console.log('!!! OVERLAPS:');
      result.overlaps.slice(0, 5).forEach(o => {
        console.log(`  Y=${o.y}: "${o.node1}" (X=${o.x1}) <-> "${o.node2}" (X=${o.x2}) gap=${o.gap}`);
      });
      if (result.overlaps.length > 5) {
        console.log(`  ... and ${result.overlaps.length - 5} more`);
      }
    } else {
      console.log('No overlaps');
    }

    // Reset for next test
    await page.evaluate((fnId) => {
      setExpansionLevel(fnId, 0);
    }, ids.createRouteId);
    await new Promise(r => setTimeout(r, 500));
  }

  await browser.close();
})();
