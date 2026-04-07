const { chromium } = require('playwright');

// Test with api-entities-route instead of metrics-route

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1400, height: 900 });

  console.log('Loading api-entities-route...');
  await page.goto('http://localhost:9002/#api-entities-route');
  await page.waitForTimeout(2000);

  // Step 1: Expand to level 2 (shows method-map and assoc-handler)
  console.log('\n=== Step 1: Expand api-entities-route to level 2 ===');
  await page.evaluate(() => {
    const rootId = cy.nodes().filter(n => n.data('isRoot')).first().data('originalFnId');
    setExpansionLevel(rootId, 2);
  });
  await page.waitForTimeout(1500);

  let edges = await page.evaluate(() => {
    const result = [];
    cy.edges().forEach(e => {
      if (e.data('argName') === 'key') {
        const src = e.source();
        const tgt = e.target();
        result.push({
          source: src.data('label')?.split('\n')[0] || src.id(),
          target: tgt.data('label') || tgt.id()
        });
      }
    });
    return result;
  });
  console.log('key edges:', edges.map(e => `${e.source} --[key]--> ${e.target}`).join(', '));

  // Find method-map node
  const methodMapInfo = await page.evaluate(() => {
    let result = null;
    cy.nodes().forEach(n => {
      const label = n.data('label') || '';
      if (label.startsWith('method-map')) {
        result = {
          id: n.id(),
          originalFnId: n.data('originalFnId'),
          label: label.split('\n')[0]
        };
      }
    });
    return result;
  });

  if (!methodMapInfo) {
    console.log('ERROR: method-map node not found');
    await page.screenshot({ path: '/tmp/editor-screenshot.png' });
    await browser.close();
    return;
  }
  console.log('Found method-map:', methodMapInfo.id);

  // Step 2: Expand method-map to level 1
  console.log('\n=== Step 2: Expand method-map to level 1 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 1);
  }, methodMapInfo.originalFnId);
  await page.waitForTimeout(1500);

  edges = await page.evaluate(() => {
    const result = [];
    cy.edges().forEach(e => {
      if (e.data('argName') === 'key') {
        const src = e.source();
        const tgt = e.target();
        result.push({
          source: src.data('label')?.split('\n')[0] || src.id(),
          target: tgt.data('label') || tgt.id()
        });
      }
    });
    return result;
  });
  console.log('key edges:', edges.map(e => `${e.source} --[key]--> ${e.target}`).join(', '));

  // Verification
  console.log('\n=== VERIFICATION ===');
  const methodMapKeyEdges = edges.filter(e => e.source === 'method-map');
  const assocHandlerKeyEdges = edges.filter(e => e.source === 'assoc-handler');

  if (methodMapKeyEdges.length === 0 && assocHandlerKeyEdges.length === 1) {
    console.log('PASS: key shows ONLY on assoc-handler');
  } else if (methodMapKeyEdges.length > 0 && assocHandlerKeyEdges.length > 0) {
    console.log('FAIL: key shows on BOTH method-map AND assoc-handler (DUPLICATE!)');
  } else if (methodMapKeyEdges.length > 0) {
    console.log('FAIL: key shows on method-map but NOT on assoc-handler');
  } else {
    console.log('INFO: Unexpected state - edges:', JSON.stringify(edges));
  }

  await page.screenshot({ path: '/tmp/editor-screenshot.png' });
  console.log('\nScreenshot saved: /tmp/editor-screenshot.png');

  await browser.close();
})();
