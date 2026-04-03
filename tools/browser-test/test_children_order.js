const puppeteer = require('puppeteer');

// Check children order for delete-entity-route after expansion
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

  console.log('=== GRAPH STRUCTURE BEFORE EXPAND ===');
  let before = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('delete-entity-route')) {
        const children = [];
        cy.edges().forEach(e => {
          if (e.source().id() === n.id()) {
            children.push({
              target: e.target().id(),
              label: (e.target().data('label') || '').substring(0, 30).replace(/\n/g, '|'),
              argName: e.data('argName')
            });
          }
        });
        result.push({
          id: n.id(),
          label: lbl.substring(0, 30).replace(/\n/g, '|'),
          children
        });
      }
    });
    return result;
  });
  console.log(JSON.stringify(before, null, 2));

  // Expand to level 4
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 4);
  }, routeId);
  await new Promise(r => setTimeout(r, 2000));

  console.log('\n=== GRAPH STRUCTURE AFTER EXPAND ===');
  let after = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('delete-entity-route')) {
        const children = [];
        cy.edges().forEach(e => {
          if (e.source().id() === n.id()) {
            const targetNode = e.target();
            children.push({
              target: targetNode.id(),
              label: (targetNode.data('label') || '').substring(0, 30).replace(/\n/g, '|'),
              argName: e.data('argName'),
              type: targetNode.data('type'),
              isPlaceholder: targetNode.data('isPlaceholder')
            });
          }
        });
        result.push({
          id: n.id(),
          label: lbl.substring(0, 30).replace(/\n/g, '|'),
          children
        });
      }
    });
    return result;
  });
  console.log(JSON.stringify(after, null, 2));

  // Check what API returns for children order
  console.log('\n=== API RESPONSE ===');
  const apiResult = await page.evaluate(async (fnId) => {
    const rootFnId = await new Promise(resolve => {
      cy.nodes().forEach(n => {
        if (n.data('isRoot')) {
          resolve(n.data('originalFnId'));
        }
      });
    });

    const url = 'http://localhost:9002/api/graph/layout';
    const body = {
      'root-id': rootFnId,
      expansions: { [fnId]: 4 }
    };
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    return await resp.json();
  }, routeId);

  // Find delete-entity-route in API nodes
  const deleteNode = apiResult.nodes.find(n => n.data.label && n.data.label.includes('delete-entity-route'));
  console.log('Delete node ID:', deleteNode?.data.id);

  // Find its children from edges
  const deleteChildren = apiResult.edges
    .filter(e => e.data.source === deleteNode?.data.id)
    .map(e => {
      const targetNode = apiResult.nodes.find(n => n.data.id === e.data.target);
      const pos = apiResult['grid-pos'][e.data.target];
      return {
        argName: e.data.argName,
        target: e.data.target,
        label: (targetNode?.data.label || '').substring(0, 30).replace(/\n/g, '|'),
        row: pos?.row,
        col: pos?.col
      };
    });

  console.log('Children from API:');
  deleteChildren.forEach(c => {
    console.log(`  Arg "${c.argName}": ${c.label} -> Row ${c.row}, Col ${c.col}`);
  });

  // Show grid-pos for delete node
  const deletePos = apiResult['grid-pos'][deleteNode?.data.id];
  console.log(`\nDelete node position: Row ${deletePos?.row}, Col ${deletePos?.col}`);

  await browser.close();
})();
