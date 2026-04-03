const puppeteer = require('puppeteer');

// Test deep expansions (last ancestors) - looking for intersections
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

  // Get all route fnIds and their max expansion level
  const routes = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('-route') && !lbl.includes('editor-routes')) {
        // Get ancestors count from label (count lines)
        const lines = lbl.split('\n');
        result.push({
          id: n.data('originalFnId'),
          label: lbl.substring(0, 30).replace(/\n/g, '|'),
          maxLevel: lines.length - 1 // ancestors count
        });
      }
    });
    return result;
  });

  console.log(`Found ${routes.length} routes\n`);

  // Test each route at its MAX level (last ancestor)
  for (const route of routes) {
    const testLevel = route.maxLevel;
    console.log(`\n=== ${route.label} at level ${testLevel} (MAX) ===`);

    await page.evaluate((fnId, level) => {
      setExpansionLevel(fnId, level);
    }, route.id, testLevel);

    await new Promise(r => setTimeout(r, 1500));

    const result = await page.evaluate(() => {
      // Check for overlaps with tight tolerance
      const rows = {};
      cy.nodes().forEach(n => {
        const y = Math.round(n.position().y / 10) * 10; // 10px tolerance
        if (!rows[y]) rows[y] = [];
        rows[y].push({
          id: n.id(),
          label: (n.data('label') || '').substring(0, 30).replace(/\n/g, '|'),
          x: Math.round(n.position().x),
          y: Math.round(n.position().y)
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
      console.log('OK - No overlaps');
    }

    // Reset
    await page.evaluate((fnId) => {
      setExpansionLevel(fnId, 0);
    }, route.id);
    await new Promise(r => setTimeout(r, 300));
  }

  await browser.close();
})();
