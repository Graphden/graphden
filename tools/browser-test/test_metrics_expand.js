const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Find metrics-route
  const routeId = await page.evaluate(() => {
    let id = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('metrics-route') && !lbl.includes('|')) {
        id = n.data('originalFnId');
      }
    });
    return id;
  });

  console.log('metrics-route ID:', routeId);

  // Show chain
  const chain = await page.evaluate((fnId) => {
    return getInheritanceChain(fnId).map(id => {
      const fn = lookups.fnMap.get(id);
      return fn ? fn.name : '?';
    });
  }, routeId);
  console.log('Chain:', chain.join(' -> '));

  // Initial state
  console.log('\n=== INITIAL STATE ===');
  let state = await page.evaluate((fnId) => {
    const nodeId = 'fn-' + fnId;
    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === nodeId) {
        children.push({
          argName: e.data('argName'),
          label: (e.target().data('label') || '').substring(0, 40).replace(/\n/g, '|'),
          id: e.target().id()
        });
      }
    });
    return { children };
  }, routeId);
  console.log('Children:');
  state.children.forEach(c => console.log('  ', c.argName, ':', c.label));

  // Expand to level 1
  console.log('\n=== EXPAND TO LEVEL 1 (get-route) ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 1);
  }, routeId);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate((fnId) => {
    const nodeId = 'fn-' + fnId;
    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === nodeId) {
        children.push({
          argName: e.data('argName'),
          label: (e.target().data('label') || '').substring(0, 40).replace(/\n/g, '|'),
          id: e.target().id()
        });
      }
    });
    // Count handler nodes
    let handlerCount = 0;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('metrics-handler')) handlerCount++;
    });
    return { children, handlerCount };
  }, routeId);
  console.log('Children:');
  state.children.forEach(c => console.log('  ', c.argName, ':', c.label, '(', c.id, ')'));
  console.log('metrics-handler nodes:', state.handlerCount);

  // Expand to level 2
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
        const grandchildren = [];
        cy.edges().forEach(e2 => {
          if (e2.source().id() === target.id()) {
            grandchildren.push({
              argName: e2.data('argName'),
              label: (e2.target().data('label') || '').substring(0, 35).replace(/\n/g, '|')
            });
          }
        });
        children.push({
          argName: e.data('argName'),
          label: (target.data('label') || '').substring(0, 40).replace(/\n/g, '|'),
          id: target.id(),
          grandchildren
        });
      }
    });
    // Count handler nodes
    let handlerCount = 0;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('metrics-handler')) handlerCount++;
    });
    return { children, handlerCount };
  }, routeId);
  console.log('Children:');
  state.children.forEach(c => {
    console.log('  ', c.argName, ':', c.label, '(', c.id, ')');
    c.grandchildren.forEach(gc => console.log('      ', gc.argName, ':', gc.label));
  });
  console.log('metrics-handler nodes:', state.handlerCount);

  await browser.close();
})();
