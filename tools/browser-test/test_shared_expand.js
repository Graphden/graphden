const puppeteer = require('puppeteer');

// Test shared node behavior when one is expanded
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

  // Find entity-form-edit-route
  const routeId = await page.evaluate(() => {
    let id = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('entity-form-edit-route')) {
        id = n.data('originalFnId');
      }
    });
    return id;
  });

  console.log('entity-form-edit-route ID:', routeId);

  // Before expand: show node ID and incoming edges
  console.log('\n=== BEFORE EXPAND ===');
  let state = await page.evaluate((fnId) => {
    const nodeId = 'fn-' + fnId;
    const node = cy.getElementById(nodeId);
    const incomingEdges = [];
    cy.edges().forEach(e => {
      if (e.target().id() === nodeId) {
        incomingEdges.push({
          edgeId: e.id(),
          source: e.source().id(),
          sourceLabel: (e.source().data('label') || '').substring(0, 30).replace(/\n/g, '|'),
          argName: e.data('argName')
        });
      }
    });
    return {
      nodeExists: node.length > 0,
      nodeId: nodeId,
      incomingEdges
    };
  }, routeId);
  
  console.log('Node ID:', state.nodeId, 'exists:', state.nodeExists);
  console.log('Incoming edges:', state.incomingEdges.length);
  state.incomingEdges.forEach(e => console.log('  ', e.sourceLabel, '->', e.argName, '(edge:', e.edgeId, ')'));

  // Expand to level 2 (route)
  console.log('\n=== EXPAND TO LEVEL 2 (route) ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeId);
  await new Promise(r => setTimeout(r, 1500));

  // After expand: show node ID and incoming edges
  state = await page.evaluate((fnId) => {
    // Try both possible IDs
    const oldNodeId = 'fn-' + fnId;
    const oldNode = cy.getElementById(oldNodeId);
    
    // Find any node with this originalFnId
    let newNodeId = null;
    let newNode = null;
    cy.nodes().forEach(n => {
      if (n.data('originalFnId') === fnId) {
        newNodeId = n.id();
        newNode = n;
      }
    });

    const incomingEdges = [];
    if (newNodeId) {
      cy.edges().forEach(e => {
        if (e.target().id() === newNodeId) {
          incomingEdges.push({
            edgeId: e.id(),
            source: e.source().id(),
            sourceLabel: (e.source().data('label') || '').substring(0, 30).replace(/\n/g, '|'),
            argName: e.data('argName')
          });
        }
      });
    }

    // Also check if there are orphaned edges pointing to old ID
    const orphanedEdges = [];
    cy.edges().forEach(e => {
      if (e.data('target') === oldNodeId && e.target().length === 0) {
        orphanedEdges.push({
          edgeId: e.id(),
          source: e.source().id(),
          targetId: e.data('target')
        });
      }
    });

    return {
      oldNodeExists: oldNode.length > 0,
      oldNodeId: oldNodeId,
      newNodeId: newNodeId,
      newNodeExists: newNode !== null,
      incomingEdges,
      orphanedEdges
    };
  }, routeId);
  
  console.log('Old node ID:', state.oldNodeId, 'exists:', state.oldNodeExists);
  console.log('New node ID:', state.newNodeId, 'exists:', state.newNodeExists);
  console.log('Incoming edges to new node:', state.incomingEdges.length);
  state.incomingEdges.forEach(e => console.log('  ', e.sourceLabel, '->', e.argName, '(edge:', e.edgeId, ')'));
  
  if (state.orphanedEdges.length > 0) {
    console.log('ORPHANED edges (pointing to non-existent node):');
    state.orphanedEdges.forEach(e => console.log('  ', e.edgeId, 'target:', e.targetId));
  }

  await browser.close();
})();
