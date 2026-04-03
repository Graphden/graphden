const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  page.on('console', msg => {
    const text = msg.text();
    if (text.includes('Build:') || text.includes('ERROR')) {
      console.log('BROWSER:', text);
    }
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 3000));

  // Find health-route and editor-route IDs
  const routeIds = await page.evaluate(() => {
    const result = {};
    cy.nodes().forEach(n => {
      const label = n.data('label') || '';
      if (label.includes('health-route')) {
        result.healthRoute = n.data('originalFnId');
      }
      if (label.includes('editor-route')) {
        result.editorRoute = n.data('originalFnId');
      }
    });
    return result;
  });

  console.log('Found routes:', routeIds);

  // First, expand health-route to level where "get" appears (get-route level)
  console.log('\n=== EXPANDING HEALTH-ROUTE TO LEVEL 2 ===');
  await page.evaluate((id) => {
    setExpansionLevel(id, 2);
  }, routeIds.healthRoute);
  await new Promise(r => setTimeout(r, 2000));

  // Find all "get" nodes and their parents
  let getNodes = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const label = n.data('label') || '';
      if (label === '"get"') {
        // Find parent
        const edges = cy.edges().filter(e => e.data('target') === n.id());
        const parents = edges.map(e => {
          const parent = cy.getElementById(e.data('source'));
          return (parent.data('label') || '').substring(0, 30).replace(/\n/g, '|');
        });
        result.push({
          id: n.id(),
          parents: parents
        });
      }
    });
    return result;
  });

  console.log('"get" nodes after health-route expansion:', getNodes.length);
  getNodes.forEach(n => console.log(`  ${n.id} -> parents: [${n.parents.join(', ')}]`));

  // Now expand editor-route to level 2 as well
  console.log('\n=== EXPANDING EDITOR-ROUTE TO LEVEL 2 ===');
  await page.evaluate((id) => {
    setExpansionLevel(id, 2);
  }, routeIds.editorRoute);
  await new Promise(r => setTimeout(r, 2000));

  // Find all "get" nodes again
  getNodes = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const label = n.data('label') || '';
      if (label === '"get"') {
        const edges = cy.edges().filter(e => e.data('target') === n.id());
        const parents = edges.map(e => {
          const parent = cy.getElementById(e.data('source'));
          return (parent.data('label') || '').substring(0, 30).replace(/\n/g, '|');
        });
        result.push({
          id: n.id(),
          parents: parents
        });
      }
    });
    return result;
  });

  console.log('"get" nodes after BOTH expansions:', getNodes.length);
  getNodes.forEach(n => console.log(`  ${n.id} -> parents: [${n.parents.join(', ')}]`));

  // Verify: should have 2 separate "get" nodes, one for each route
  if (getNodes.length >= 2) {
    console.log('\n✓ SUCCESS: Each expanded route has its own "get" argument node');
  } else {
    console.log('\n✗ FAILURE: Expected at least 2 "get" nodes, got', getNodes.length);
  }

  await browser.close();
})();
