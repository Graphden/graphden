const puppeteer = require('puppeteer');

// Show node labels
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

  // Find routes
  const routeIds = await page.evaluate(() => {
    const result = {};
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('api-entities-route')) {
        result.apiEntities = n.data('originalFnId');
      }
      if (lbl.includes('entity-form-create-route')) {
        result.entityFormCreate = n.data('originalFnId');
      }
    });
    return result;
  });

  // Expand api-entities-route to level 2 (route)
  console.log('=== EXPAND API-ENTITIES-ROUTE TO LEVEL 2 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeIds.apiEntities);
  await new Promise(r => setTimeout(r, 1500));

  // Show all new child nodes of api-entities-route
  let state = await page.evaluate((fnId) => {
    const routeNodeId = 'fn-' + fnId;
    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === routeNodeId) {
        const target = e.target();
        children.push({
          id: target.id(),
          label: (target.data('label') || '').substring(0, 40).replace(/\n/g, '|'),
          argName: e.data('argName')
        });
      }
    });
    return children;
  }, routeIds.apiEntities);
  
  console.log('Children of api-entities-route:');
  state.forEach(c => console.log('  ', c.argName, ':', c.label, '(', c.id, ')'));

  // Now expand entity-form-create-route to level 2 (route)
  console.log('\n=== EXPAND ENTITY-FORM-CREATE-ROUTE TO LEVEL 2 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeIds.entityFormCreate);
  await new Promise(r => setTimeout(r, 1500));

  // Show children of entity-form-create-route
  state = await page.evaluate((fnId) => {
    const routeNodeId = 'fn-' + fnId;
    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === routeNodeId) {
        const target = e.target();
        children.push({
          id: target.id(),
          label: (target.data('label') || '').substring(0, 40).replace(/\n/g, '|'),
          argName: e.data('argName')
        });
      }
    });
    return children;
  }, routeIds.entityFormCreate);
  
  console.log('Children of entity-form-create-route:');
  state.forEach(c => console.log('  ', c.argName, ':', c.label, '(', c.id, ')'));

  // Also show children of api-entities-route again
  state = await page.evaluate((fnId) => {
    const routeNodeId = 'fn-' + fnId;
    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === routeNodeId) {
        const target = e.target();
        children.push({
          id: target.id(),
          label: (target.data('label') || '').substring(0, 40).replace(/\n/g, '|'),
          argName: e.data('argName')
        });
      }
    });
    return children;
  }, routeIds.apiEntities);
  
  console.log('\nChildren of api-entities-route (after second expand):');
  state.forEach(c => console.log('  ', c.argName, ':', c.label, '(', c.id, ')'));

  await browser.close();
})();
