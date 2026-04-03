const puppeteer = require('puppeteer');

// Test item2 node behavior with multiple expansions
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

  // Find api-entities-route and entity-form-create-route
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

  console.log('api-entities-route ID:', routeIds.apiEntities);
  console.log('entity-form-create-route ID:', routeIds.entityFormCreate);

  // Get inheritance chains
  const chains = await page.evaluate((ids) => {
    return {
      apiEntities: getInheritanceChain(ids.apiEntities).map(id => {
        const fn = lookups.fnMap.get(id);
        return fn?.name || '?';
      }),
      entityFormCreate: getInheritanceChain(ids.entityFormCreate).map(id => {
        const fn = lookups.fnMap.get(id);
        return fn?.name || '?';
      })
    };
  }, routeIds);

  console.log('\napi-entities-route chain:', chains.apiEntities.join(' -> '));
  console.log('entity-form-create-route chain:', chains.entityFormCreate.join(' -> '));

  // Initial state
  console.log('\n=== INITIAL STATE ===');
  let state = await page.evaluate(() => {
    let item2Nodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('item2') || lbl.includes('conj-empty') || lbl.includes('pair-1')) {
        item2Nodes.push({ id: n.id(), label: lbl.substring(0, 40).replace(/\n/g, '|') });
      }
    });
    return { nodeCount: cy.nodes().length, item2Nodes };
  });
  console.log('Nodes:', state.nodeCount, 'item2/conj-empty nodes:', state.item2Nodes.length);

  // Expand api-entities-route to level 2 (route)
  console.log('\n=== EXPAND API-ENTITIES-ROUTE TO LEVEL 2 (route) ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeIds.apiEntities);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate(() => {
    let item2Nodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('item2') || lbl.includes('conj-empty') || lbl.includes('pair-1')) {
        item2Nodes.push({ id: n.id(), label: lbl.substring(0, 40).replace(/\n/g, '|') });
      }
    });
    return { nodeCount: cy.nodes().length, item2Nodes };
  });
  console.log('Nodes:', state.nodeCount, 'item2/conj-empty nodes:', state.item2Nodes.length);
  state.item2Nodes.forEach(n => console.log('  -', n.id));

  // Check edges to item2 nodes
  const edges1 = await page.evaluate(() => {
    let result = [];
    cy.edges().forEach(e => {
      const targetLabel = e.target().data('label') || '';
      if (targetLabel.includes('item2') || targetLabel.includes('conj-empty') || targetLabel.includes('pair-1')) {
        result.push({
          source: (e.source().data('label') || '').substring(0, 30).replace(/\n/g, '|'),
          target: e.target().id(),
          argName: e.data('argName')
        });
      }
    });
    return result;
  });
  console.log('Edges to item2/conj-empty:', edges1.length);
  edges1.forEach(e => console.log('  ', e.source, '->', e.target, '(' + e.argName + ')'));

  // Now expand entity-form-create-route to level 2 (route)
  console.log('\n=== EXPAND ENTITY-FORM-CREATE-ROUTE TO LEVEL 2 (route) ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeIds.entityFormCreate);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate(() => {
    let item2Nodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('item2') || lbl.includes('conj-empty') || lbl.includes('pair-1')) {
        item2Nodes.push({ id: n.id(), label: lbl.substring(0, 40).replace(/\n/g, '|') });
      }
    });
    return { nodeCount: cy.nodes().length, item2Nodes };
  });
  console.log('Nodes:', state.nodeCount, 'item2/conj-empty nodes:', state.item2Nodes.length);
  state.item2Nodes.forEach(n => console.log('  -', n.id));

  // Check edges again
  const edges2 = await page.evaluate(() => {
    let result = [];
    cy.edges().forEach(e => {
      const targetLabel = e.target().data('label') || '';
      if (targetLabel.includes('item2') || targetLabel.includes('conj-empty') || targetLabel.includes('pair-1')) {
        result.push({
          source: (e.source().data('label') || '').substring(0, 30).replace(/\n/g, '|'),
          target: e.target().id(),
          argName: e.data('argName')
        });
      }
    });
    return result;
  });
  console.log('Edges to item2/conj-empty:', edges2.length);
  edges2.forEach(e => console.log('  ', e.source, '->', e.target, '(' + e.argName + ')'));

  await browser.close();
})();
