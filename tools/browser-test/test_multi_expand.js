const puppeteer = require('puppeteer');

// Test multiple expansions of different fns with same ancestor
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

  // Find health-route and metrics-route (both have get-route ancestor)
  const routeIds = await page.evaluate(() => {
    const result = {};
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('health-route')) {
        result.health = n.data('originalFnId');
      }
      if (lbl.includes('metrics-route')) {
        result.metrics = n.data('originalFnId');
      }
    });
    return result;
  });

  console.log('Health route ID:', routeIds.health);
  console.log('Metrics route ID:', routeIds.metrics);

  // Initial state
  console.log('\n=== INITIAL STATE ===');
  let state = await page.evaluate(() => {
    let getNodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('"get"') || lbl === 'get') {
        getNodes.push({ id: n.id(), label: lbl });
      }
    });
    return { nodeCount: cy.nodes().length, getNodes };
  });
  console.log('Nodes:', state.nodeCount, 'Get nodes:', state.getNodes.length);

  // Expand health-route to level 1 (get-route)
  console.log('\n=== EXPAND HEALTH-ROUTE TO LEVEL 1 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 1);
  }, routeIds.health);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate(() => {
    let getNodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('"get"') || lbl === 'get') {
        getNodes.push({ id: n.id(), label: lbl });
      }
    });
    return { nodeCount: cy.nodes().length, getNodes };
  });
  console.log('Nodes:', state.nodeCount, 'Get nodes:', state.getNodes.length);
  state.getNodes.forEach(n => console.log('  -', n.id, ':', n.label));

  // Now expand metrics-route to level 1 (get-route)
  console.log('\n=== EXPAND METRICS-ROUTE TO LEVEL 1 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 1);
  }, routeIds.metrics);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate(() => {
    let getNodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('"get"') || lbl === 'get') {
        getNodes.push({ id: n.id(), label: lbl });
      }
    });
    return { nodeCount: cy.nodes().length, getNodes };
  });
  console.log('Nodes:', state.nodeCount, 'Get nodes:', state.getNodes.length);
  state.getNodes.forEach(n => console.log('  -', n.id, ':', n.label));

  console.log('\n=== VERIFICATION ===');
  if (state.getNodes.length >= 2) {
    console.log('SUCCESS: Both expansions have their own "get" nodes');
  } else {
    console.log('FAIL: Expected at least 2 "get" nodes, got', state.getNodes.length);
  }

  await browser.close();
})();
