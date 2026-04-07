const { chromium } = require('playwright');

// User's exact scenario:
// 1. Select editor-routes (which contains both metrics-route and api-entities-route)
// 2. Expand editor-routes to see both routes
// 3. Expand route on BOTH metrics-route AND api-entities-route
// 4. Expand assoc-empty on ONE of the method-map nodes
// 5. BUG: both method-maps merge into one!

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1600, height: 900 });

  console.log('Loading editor-routes...');
  await page.goto('http://localhost:9002/#editor-routes');
  await page.waitForTimeout(2000);

  // Step 1: Expand editor-routes to see children (metrics-route, api-entities-route, etc)
  console.log('\n=== Step 1: Expand editor-routes to level 1 ===');
  await page.evaluate(() => {
    const rootId = cy.nodes().filter(n => n.data('isRoot')).first().data('originalFnId');
    setExpansionLevel(rootId, 1);
  });
  await page.waitForTimeout(1500);

  // Find metrics-route and api-entities-route nodes
  const routeIds = await page.evaluate(() => {
    const result = {};
    cy.nodes().forEach(n => {
      const label = n.data('label') || '';
      if (label.startsWith('metrics-route')) {
        result.metrics = n.data('originalFnId');
      }
      if (label.startsWith('api-entities-route')) {
        result.apiEntities = n.data('originalFnId');
      }
    });
    return result;
  });
  console.log('Found routes:', routeIds);

  // Step 2: Expand BOTH routes to level 2 (shows their method-map nodes)
  console.log('\n=== Step 2: Expand BOTH routes to level 2 ===');
  await page.evaluate((ids) => {
    setExpansionLevel(ids.metrics, 2);
    setExpansionLevel(ids.apiEntities, 2);
  }, routeIds);
  await page.waitForTimeout(1500);

  // Check method-map nodes
  let methodMapNodes = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const label = n.data('label') || '';
      if (label.startsWith('method-map')) {
        result.push({
          id: n.id(),
          originalFnId: n.data('originalFnId')
        });
      }
    });
    return result;
  });
  console.log('method-map nodes BEFORE expanding assoc-empty:', methodMapNodes.length);
  methodMapNodes.forEach(n => console.log('  ' + n.id));

  // Check key edges
  let keyEdges = await page.evaluate(() => {
    const result = [];
    cy.edges().forEach(e => {
      if (e.data('argName') === 'key') {
        result.push({
          source: e.source().data('label')?.split('\n')[0] || e.source().id(),
          target: e.target().data('label') || e.target().id()
        });
      }
    });
    return result;
  });
  console.log('key edges:', keyEdges.map(e => `${e.source} --[key]--> ${e.target}`).join(', '));

  await page.screenshot({ path: '/tmp/before-expand-assoc-empty.png' });

  // Step 3: Expand assoc-empty on ONE method-map (use the first one's originalFnId)
  console.log('\n=== Step 3: Expand assoc-empty on first method-map ===');
  const methodMapFnId = methodMapNodes[0]?.originalFnId;
  if (!methodMapFnId) {
    console.log('ERROR: No method-map found');
    await browser.close();
    return;
  }
  console.log('Expanding method-map:', methodMapFnId);

  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 1);
  }, methodMapFnId);
  await page.waitForTimeout(1500);

  // Check method-map nodes AFTER
  methodMapNodes = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const label = n.data('label') || '';
      if (label.startsWith('method-map')) {
        result.push({
          id: n.id(),
          originalFnId: n.data('originalFnId')
        });
      }
    });
    return result;
  });
  console.log('\nmethod-map nodes AFTER expanding assoc-empty:', methodMapNodes.length);
  methodMapNodes.forEach(n => console.log('  ' + n.id));

  // Check assoc-handler nodes
  const assocHandlerNodes = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const label = n.data('label') || '';
      if (label.startsWith('assoc-handler')) {
        result.push({
          id: n.id(),
          originalFnId: n.data('originalFnId')
        });
      }
    });
    return result;
  });
  console.log('assoc-handler nodes:', assocHandlerNodes.length);
  assocHandlerNodes.forEach(n => console.log('  ' + n.id));

  // Check key edges after
  keyEdges = await page.evaluate(() => {
    const result = [];
    cy.edges().forEach(e => {
      if (e.data('argName') === 'key') {
        result.push({
          source: e.source().data('label')?.split('\n')[0] || e.source().id(),
          target: e.target().data('label') || e.target().id()
        });
      }
    });
    return result;
  });
  console.log('key edges:', keyEdges.map(e => `${e.source} --[key]--> ${e.target}`).join(', '));

  await page.screenshot({ path: '/tmp/editor-screenshot.png' });
  console.log('\nScreenshot saved: /tmp/editor-screenshot.png');

  // Verification
  console.log('\n=== VERIFICATION ===');
  if (methodMapNodes.length === 2) {
    console.log('PASS: Still have 2 separate method-map nodes');
  } else if (methodMapNodes.length === 1) {
    console.log('FAIL: method-map nodes MERGED into 1!');
  } else {
    console.log('INFO: Unexpected number of method-map nodes:', methodMapNodes.length);
  }

  await browser.close();
})();
