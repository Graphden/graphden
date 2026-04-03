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

  // Get all route fnIds
  const routes = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('-route') && !lbl.includes('editor-routes')) {
        result.push({
          id: n.data('originalFnId'),
          label: lbl.substring(0, 30).replace(/\n/g, '|')
        });
      }
    });
    return result;
  });

  console.log(`Found ${routes.length} routes to test\n`);

  // Test each route at level 4 (near max)
  for (const route of routes) {
    console.log(`Testing: ${route.label}`);

    await page.evaluate((fnId) => {
      setExpansionLevel(fnId, 4);
    }, route.id);

    await new Promise(r => setTimeout(r, 1000));

    const result = await page.evaluate(() => {
      const rows = {};
      cy.nodes().forEach(n => {
        const y = Math.round(n.position().y / 10) * 10;
        if (!rows[y]) rows[y] = [];
        rows[y].push({
          label: (n.data('label') || '').substring(0, 25),
          x: Math.round(n.position().x)
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
              node2: rowNodes[i].label,
              gap
            });
          }
        }
      }

      return { overlaps };
    });

    if (result.overlaps.length > 0) {
      console.log(`  !!! ${result.overlaps.length} OVERLAPS`);
      result.overlaps.slice(0, 3).forEach(o => {
        console.log(`    Y=${o.y}: "${o.node1}" <-> "${o.node2}" gap=${o.gap}`);
      });
    } else {
      console.log(`  OK`);
    }

    // Reset
    await page.evaluate((fnId) => {
      setExpansionLevel(fnId, 0);
    }, route.id);
    await new Promise(r => setTimeout(r, 300));
  }

  await browser.close();
})();
