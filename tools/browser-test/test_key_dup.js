const puppeteer = require('puppeteer');

// Test key duplication when expanding deeper
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

  // Find api-entities-route
  const routeId = await page.evaluate(() => {
    let id = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('api-entities-route')) {
        id = n.data('originalFnId');
      }
    });
    return id;
  });

  console.log('api-entities-route ID:', routeId);

  // Show inheritance chain
  const chain = await page.evaluate((fnId) => {
    return getInheritanceChain(fnId).map(id => {
      const fn = lookups.fnMap.get(id);
      return fn?.name || '?';
    });
  }, routeId);
  console.log('Chain:', chain.join(' -> '));

  // Expand to level 1 (get-route)
  console.log('\n=== EXPAND TO LEVEL 1 (get-route) ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 1);
  }, routeId);
  await new Promise(r => setTimeout(r, 1500));

  let state = await page.evaluate((fnId) => {
    const nodeId = 'fn-' + fnId;
    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === nodeId) {
        const target = e.target();
        children.push({
          argName: e.data('argName'),
          label: (target.data('label') || '').substring(0, 30).replace(/\n/g, '|'),
          id: target.id()
        });
      }
    });
    return { children };
  }, routeId);

  console.log('Children:');
  state.children.forEach(c => console.log('  ', c.argName, ':', c.label));

  // Expand to level 2 (route)
  console.log('\n=== EXPAND TO LEVEL 2 (route) ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeId);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate((fnId) => {
    const nodeId = 'fn-' + fnId;
    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === nodeId) {
        const target = e.target();
        // Also get grandchildren
        const grandchildren = [];
        cy.edges().forEach(e2 => {
          if (e2.source().id() === target.id()) {
            grandchildren.push({
              argName: e2.data('argName'),
              label: (e2.target().data('label') || '').substring(0, 30).replace(/\n/g, '|')
            });
          }
        });
        children.push({
          argName: e.data('argName'),
          label: (target.data('label') || '').substring(0, 30).replace(/\n/g, '|'),
          id: target.id(),
          grandchildren
        });
      }
    });
    return { children };
  }, routeId);

  console.log('Children:');
  state.children.forEach(c => {
    console.log('  ', c.argName, ':', c.label);
    if (c.grandchildren.length > 0) {
      c.grandchildren.forEach(gc => {
        console.log('      ', gc.argName, ':', gc.label);
      });
    }
  });

  // Count "get" nodes
  const getCount = await page.evaluate(() => {
    let count = 0;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl === '"get"') count++;
    });
    return count;
  });
  console.log('\nTotal "get" value nodes:', getCount);

  await browser.close();
})();
