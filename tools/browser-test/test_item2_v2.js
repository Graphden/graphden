const puppeteer = require('puppeteer');

// Test item2 node behavior - show all nodes
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

  // Initial nodes
  console.log('=== INITIAL STATE ===');
  let initialNodes = await page.evaluate(() => {
    const nodes = [];
    cy.nodes().forEach(n => nodes.push(n.id()));
    return nodes;
  });
  console.log('Node count:', initialNodes.length);

  // Expand api-entities-route to level 2 (route)
  console.log('\n=== EXPAND API-ENTITIES-ROUTE TO LEVEL 2 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeIds.apiEntities);
  await new Promise(r => setTimeout(r, 1500));

  let afterFirst = await page.evaluate(() => {
    const nodes = [];
    cy.nodes().forEach(n => nodes.push(n.id()));
    return nodes;
  });
  
  // New nodes
  const newAfterFirst = afterFirst.filter(n => !initialNodes.includes(n));
  console.log('Node count:', afterFirst.length);
  console.log('NEW NODES:');
  newAfterFirst.forEach(id => {
    console.log('  ', id);
  });

  // Expand entity-form-create-route to level 2 (route)
  console.log('\n=== EXPAND ENTITY-FORM-CREATE-ROUTE TO LEVEL 2 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeIds.entityFormCreate);
  await new Promise(r => setTimeout(r, 1500));

  let afterSecond = await page.evaluate(() => {
    const nodes = [];
    cy.nodes().forEach(n => nodes.push(n.id()));
    return nodes;
  });
  
  // New nodes after second expand
  const newAfterSecond = afterSecond.filter(n => !afterFirst.includes(n));
  console.log('Node count:', afterSecond.length);
  console.log('NEW NODES:');
  newAfterSecond.forEach(id => {
    console.log('  ', id);
  });

  // Check if any nodes from first expand are missing
  const missingFromFirst = afterFirst.filter(n => !afterSecond.includes(n));
  if (missingFromFirst.length > 0) {
    console.log('\n!!! MISSING NODES (were in first expand, gone after second):');
    missingFromFirst.forEach(id => console.log('  ', id));
  }

  await browser.close();
})();
