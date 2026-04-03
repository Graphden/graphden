const puppeteer = require('puppeteer');

// Test MULTIPLE simultaneous expansions - different routes expanded at once
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
        const lines = lbl.split('\n');
        result.push({
          id: n.data('originalFnId'),
          label: lbl.substring(0, 30).replace(/\n/g, '|'),
          maxLevel: lines.length - 1
        });
      }
    });
    return result;
  });

  console.log(`Found ${routes.length} routes\n`);

  // Test 1: Expand two routes that share handler (create + edit)
  console.log('=== TEST 1: Expand create + edit routes (share handler) ===');
  const createRoute = routes.find(r => r.label.includes('entity-form-create'));
  const editRoute = routes.find(r => r.label.includes('entity-form-edit'));

  if (createRoute && editRoute) {
    await page.evaluate((id1, id2) => {
      setExpansionLevel(id1, 3);
      setExpansionLevel(id2, 3);
    }, createRoute.id, editRoute.id);
    await new Promise(r => setTimeout(r, 2000));

    let result = await page.evaluate(() => {
      const rows = {};
      cy.nodes().forEach(n => {
        const y = Math.round(n.position().y / 10) * 10;
        if (!rows[y]) rows[y] = [];
        rows[y].push({
          label: (n.data('label') || '').substring(0, 30).replace(/\n/g, '|'),
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
            overlaps.push({ y: parseInt(y), node1: rowNodes[i-1].label, node2: rowNodes[i].label, gap });
          }
        }
      }
      return { totalNodes: cy.nodes().length, overlaps };
    });

    console.log(`Nodes: ${result.totalNodes}`);
    if (result.overlaps.length > 0) {
      console.log('!!! OVERLAPS:');
      result.overlaps.slice(0, 5).forEach(o => console.log(`  Y=${o.y}: "${o.node1}" <-> "${o.node2}" gap=${o.gap}`));
    } else {
      console.log('OK - No overlaps');
    }

    // Reset
    await page.evaluate((id1, id2) => {
      setExpansionLevel(id1, 0);
      setExpansionLevel(id2, 0);
    }, createRoute.id, editRoute.id);
    await new Promise(r => setTimeout(r, 500));
  }

  // Test 2: Expand 3 routes at different levels
  console.log('\n=== TEST 2: Expand 3 routes at level 4 ===');
  const route1 = routes[0];
  const route2 = routes[1];
  const route3 = routes[2];

  await page.evaluate((id1, id2, id3) => {
    setExpansionLevel(id1, 4);
    setExpansionLevel(id2, 4);
    setExpansionLevel(id3, 4);
  }, route1.id, route2.id, route3.id);
  await new Promise(r => setTimeout(r, 2000));

  let result = await page.evaluate(() => {
    const rows = {};
    cy.nodes().forEach(n => {
      const y = Math.round(n.position().y / 10) * 10;
      if (!rows[y]) rows[y] = [];
      rows[y].push({
        label: (n.data('label') || '').substring(0, 30).replace(/\n/g, '|'),
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
          overlaps.push({ y: parseInt(y), node1: rowNodes[i-1].label, node2: rowNodes[i].label, gap });
        }
      }
    }
    return { totalNodes: cy.nodes().length, overlaps };
  });

  console.log(`Nodes: ${result.totalNodes}`);
  if (result.overlaps.length > 0) {
    console.log('!!! OVERLAPS:');
    result.overlaps.slice(0, 5).forEach(o => console.log(`  Y=${o.y}: "${o.node1}" <-> "${o.node2}" gap=${o.gap}`));
  } else {
    console.log('OK - No overlaps');
  }

  // Reset all
  await page.evaluate((id1, id2, id3) => {
    setExpansionLevel(id1, 0);
    setExpansionLevel(id2, 0);
    setExpansionLevel(id3, 0);
  }, route1.id, route2.id, route3.id);
  await new Promise(r => setTimeout(r, 500));

  // Test 3: Expand ALL routes at level 4
  console.log('\n=== TEST 3: Expand ALL routes at level 4 ===');
  for (const route of routes) {
    await page.evaluate((fnId) => {
      setExpansionLevel(fnId, 4);
    }, route.id);
  }
  await new Promise(r => setTimeout(r, 2000));

  result = await page.evaluate(() => {
    const rows = {};
    cy.nodes().forEach(n => {
      const y = Math.round(n.position().y / 10) * 10;
      if (!rows[y]) rows[y] = [];
      rows[y].push({
        label: (n.data('label') || '').substring(0, 30).replace(/\n/g, '|'),
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
          overlaps.push({ y: parseInt(y), node1: rowNodes[i-1].label, node2: rowNodes[i].label, gap });
        }
      }
    }
    return { totalNodes: cy.nodes().length, overlaps };
  });

  console.log(`Nodes: ${result.totalNodes}`);
  if (result.overlaps.length > 0) {
    console.log('!!! OVERLAPS:');
    result.overlaps.slice(0, 10).forEach(o => console.log(`  Y=${o.y}: "${o.node1}" <-> "${o.node2}" gap=${o.gap}`));
    if (result.overlaps.length > 10) console.log(`  ... and ${result.overlaps.length - 10} more`);
  } else {
    console.log('OK - No overlaps');
  }

  await browser.close();
})();
