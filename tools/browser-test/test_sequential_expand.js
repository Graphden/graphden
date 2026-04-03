const puppeteer = require('puppeteer');

// Test sequential expansion of delete-entity-route
(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  page.on('console', msg => {
    const text = msg.text();
    if (text.includes('Build:') || text.includes('expansion')) {
      console.log('BROWSER:', text);
    }
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Find delete-entity-route
  const routeId = await page.evaluate(() => {
    let id = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('delete-entity-route')) {
        id = n.data('originalFnId');
      }
    });
    return id;
  });

  console.log('Delete-entity-route ID:', routeId);

  // Get ancestry chain info
  const chainInfo = await page.evaluate((fnId) => {
    const chain = getInheritanceChain(fnId);
    return chain.map((id, idx) => {
      const fn = lookups.fnMap.get(id);
      return { level: idx, id, name: fn?.name || '?' };
    });
  }, routeId);

  console.log('\n=== INHERITANCE CHAIN ===');
  chainInfo.forEach(item => {
    console.log(`  Level ${item.level}: ${item.name}`);
  });

  // Initial state
  console.log('\n=== INITIAL STATE (no expansion) ===');
  let state = await page.evaluate((fnId) => {
    return {
      expansionLevel: expansionLevel.get(fnId),
      nodeCount: cy.nodes().length
    };
  }, routeId);
  console.log(`expansionLevel: ${state.expansionLevel}, nodes: ${state.nodeCount}`);

  // Step 1: Expand to level 1 (delete-route)
  console.log('\n=== STEP 1: Expand to level 1 (delete-route) ===');
  await page.evaluate((fnId) => {
    console.log('Setting expansion level to 1');
    setExpansionLevel(fnId, 1);
  }, routeId);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate((fnId) => {
    const nodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('delete') || lbl.includes('route')) {
        nodes.push(lbl.substring(0, 30).replace(/\n/g, '|'));
      }
    });
    return {
      expansionLevel: expansionLevel.get(fnId),
      nodeCount: cy.nodes().length,
      relevantNodes: nodes
    };
  }, routeId);
  console.log(`expansionLevel: ${state.expansionLevel}, nodes: ${state.nodeCount}`);
  console.log('Relevant nodes:', state.relevantNodes.slice(0, 5));

  // Step 2: Expand to level 4 (route -> pair)
  console.log('\n=== STEP 2: Expand to level 4 (pair) ===');
  await page.evaluate((fnId) => {
    console.log('Setting expansion level to 4');
    setExpansionLevel(fnId, 4);
  }, routeId);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate((fnId) => {
    const nodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('delete') || lbl.includes('route') || lbl.includes('pair')) {
        nodes.push(lbl.substring(0, 30).replace(/\n/g, '|'));
      }
    });
    return {
      expansionLevel: expansionLevel.get(fnId),
      nodeCount: cy.nodes().length,
      relevantNodes: nodes
    };
  }, routeId);
  console.log(`expansionLevel: ${state.expansionLevel}, nodes: ${state.nodeCount}`);
  console.log('Relevant nodes:', state.relevantNodes.slice(0, 10));

  // Now try direct expansion to level 4
  console.log('\n=== RESET AND DIRECT EXPAND TO LEVEL 4 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 0);
  }, routeId);
  await new Promise(r => setTimeout(r, 1000));

  await page.evaluate((fnId) => {
    console.log('Direct expansion to level 4');
    setExpansionLevel(fnId, 4);
  }, routeId);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate((fnId) => {
    const nodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('delete') || lbl.includes('route') || lbl.includes('pair')) {
        nodes.push(lbl.substring(0, 30).replace(/\n/g, '|'));
      }
    });
    return {
      expansionLevel: expansionLevel.get(fnId),
      nodeCount: cy.nodes().length,
      relevantNodes: nodes
    };
  }, routeId);
  console.log(`expansionLevel: ${state.expansionLevel}, nodes: ${state.nodeCount}`);
  console.log('Relevant nodes:', state.relevantNodes.slice(0, 10));

  await browser.close();
})();
