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
  console.log('\nTesting entity-form-create-route expand to level 2...');

  // Set expansion
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, ids.createRouteId);

  await new Promise(r => setTimeout(r, 2000));

  // Check state
  const result = await page.evaluate(() => {
    const nodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('entity-form') || lbl.includes('method-map') ||
          lbl.includes('assoc-handler') || lbl.includes('handler')) {
        nodes.push({
          id: n.id(),
          label: lbl.substring(0, 35).replace(/\n/g, '|'),
          x: Math.round(n.position().x),
          y: Math.round(n.position().y)
        });
      }
    });

    // Sort by Y
    nodes.sort((a, b) => a.y - b.y);

    // Check for overlaps
    const rows = {};
    cy.nodes().forEach(n => {
      const y = Math.round(n.position().y / 50) * 50;
      if (!rows[y]) rows[y] = [];
      rows[y].push({
        label: (n.data('label') || '').substring(0, 25),
        x: Math.round(n.position().x)
      });
    });

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

    return { nodes, overlaps, totalNodes: cy.nodes().length };
  });

  console.log(`Total nodes: ${result.totalNodes}`);
  console.log('\nRelevant nodes (entity-form, handler, etc.):');
  result.nodes.forEach(n => {
    console.log(`  Y=${n.y} X=${n.x}: ${n.label}`);
  });

  if (result.overlaps.length > 0) {
    console.log('\n!!! OVERLAPS FOUND:');
    result.overlaps.forEach(o => {
      console.log(`  Y~${o.y}: "${o.node1}" <-> "${o.node2}" (gap=${o.gap}px)`);
    });
  } else {
    console.log('\nNo overlaps found');
  }

  // Check handler node count
  const handlerCount = await page.evaluate(() => {
    let count = 0;
    cy.nodes().forEach(n => {
      if ((n.data('label') || '').includes('entity-form-handler')) count++;
    });
    return count;
  });

  console.log(`\nentity-form-handler node count: ${handlerCount}`);

  await browser.close();
})();
