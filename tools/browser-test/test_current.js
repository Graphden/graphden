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
    let rootId = null;
    let healthRouteId = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('editor-routes')) rootId = n.data('originalFnId');
      if (lbl.includes('health-route')) healthRouteId = n.data('originalFnId');
    });
    return { rootId, healthRouteId };
  });

  console.log('Testing health-route expand to level 2...');

  // Set expansion
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, ids.healthRouteId);

  await new Promise(r => setTimeout(r, 2000));

  // Check for overlaps
  const result = await page.evaluate(() => {
    const nodes = [];
    cy.nodes().forEach(n => {
      nodes.push({
        id: n.id(),
        label: (n.data('label') || '').substring(0, 30).replace(/\n/g, '|'),
        x: Math.round(n.position().x),
        y: Math.round(n.position().y)
      });
    });

    // Group by Y (within 20px tolerance)
    const rows = {};
    nodes.forEach(n => {
      const rowKey = Math.round(n.y / 50) * 50;
      if (!rows[rowKey]) rows[rowKey] = [];
      rows[rowKey].push(n);
    });

    // Check for X overlaps in each row
    const overlaps = [];
    for (const [y, rowNodes] of Object.entries(rows)) {
      rowNodes.sort((a, b) => a.x - b.x);
      for (let i = 1; i < rowNodes.length; i++) {
        const gap = rowNodes[i].x - rowNodes[i-1].x;
        if (gap < 80) {
          overlaps.push({
            y: parseInt(y),
            node1: rowNodes[i-1].label,
            node2: rowNodes[i].label,
            gap
          });
        }
      }
    }

    return { nodeCount: nodes.length, overlaps };
  });

  console.log(`Total nodes: ${result.nodeCount}`);

  if (result.overlaps.length > 0) {
    console.log('\n!!! OVERLAPS FOUND:');
    result.overlaps.forEach(o => {
      console.log(`  Y~${o.y}: "${o.node1}" <-> "${o.node2}" (gap=${o.gap}px)`);
    });
  } else {
    console.log('No overlaps found');
  }

  await browser.close();
})();
