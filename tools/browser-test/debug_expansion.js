const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  // Capture console messages
  page.on('console', msg => {
    const text = msg.text();
    if (text.includes('DEBUG') || text.includes('ERROR')) {
      console.log('BROWSER:', text);
    }
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Get initial state - find create-route and its children
  console.log('=== INITIAL STATE (no expansion) ===');
  let state = await page.evaluate(() => {
    const result = {
      nodes: [],
      edges: [],
      shared: []
    };

    cy.nodes().forEach(n => {
      const label = (n.data('label') || '').substring(0, 40).replace(/\n/g, '|');
      const pos = n.position();
      if (label.includes('entity-form') || label.includes('assoc-handler') || label.includes('method-map')) {
        result.nodes.push({
          id: n.id(),
          label: label,
          x: Math.round(pos.x),
          y: Math.round(pos.y),
          type: n.data('type')
        });
      }
    });

    // Find shared nodes (nodes with multiple incoming edges)
    const incomingCount = {};
    cy.edges().forEach(e => {
      const target = e.data('target');
      incomingCount[target] = (incomingCount[target] || 0) + 1;
    });
    for (const [nodeId, count] of Object.entries(incomingCount)) {
      if (count > 1) {
        const node = cy.getElementById(nodeId);
        if (node.length) {
          result.shared.push({
            id: nodeId,
            label: (node.data('label') || '').substring(0, 30),
            parentCount: count
          });
        }
      }
    }

    return result;
  });

  console.log('Nodes:');
  state.nodes.sort((a, b) => a.y - b.y || a.x - b.x).forEach(n => {
    console.log(`  [${n.y}, ${n.x}] ${n.label} (${n.type})`);
  });
  console.log('Shared nodes:');
  state.shared.forEach(s => {
    console.log(`  ${s.label} (${s.parentCount} parents)`);
  });

  // Expand create-route to level 2
  console.log('\n=== AFTER EXPANSION TO LEVEL 2 ===');
  await page.evaluate(() => {
    setExpansionLevel('35ed3970-9143-4a2d-b322-351080ec31bc', 2);
  });
  await new Promise(r => setTimeout(r, 2000));

  state = await page.evaluate(() => {
    const result = {
      nodes: [],
      shared: [],
      childrenOf: {}
    };

    cy.nodes().forEach(n => {
      const label = (n.data('label') || '').substring(0, 40).replace(/\n/g, '|');
      const pos = n.position();
      if (label.includes('entity-form') || label.includes('assoc-handler') ||
          label.includes('method-map') || label.includes('route|pair') ||
          label.includes('get-route')) {
        result.nodes.push({
          id: n.id(),
          label: label,
          x: Math.round(pos.x),
          y: Math.round(pos.y),
          type: n.data('type')
        });
      }
    });

    // Find shared nodes (nodes with multiple incoming edges)
    const incomingCount = {};
    cy.edges().forEach(e => {
      const target = e.data('target');
      incomingCount[target] = (incomingCount[target] || 0) + 1;
    });
    for (const [nodeId, count] of Object.entries(incomingCount)) {
      if (count > 1) {
        const node = cy.getElementById(nodeId);
        if (node.length) {
          const label = (node.data('label') || '').substring(0, 30);
          result.shared.push({
            id: nodeId,
            label: label,
            parentCount: count
          });

          // Find parents of this shared node
          const parents = [];
          cy.edges().forEach(e => {
            if (e.data('target') === nodeId) {
              const src = cy.getElementById(e.data('source'));
              if (src.length) {
                parents.push((src.data('label') || '').substring(0, 25).replace(/\n/g, '|'));
              }
            }
          });
          result.childrenOf[label] = parents;
        }
      }
    }

    // Get children of create-route and edit-route
    const createRoute = cy.nodes().filter(n => (n.data('label') || '').includes('entity-form-create-route'))[0];
    const editRoute = cy.nodes().filter(n => (n.data('label') || '').includes('entity-form-edit-route'))[0];

    if (createRoute) {
      const children = [];
      cy.edges().forEach(e => {
        if (e.data('source') === createRoute.id()) {
          const tgt = cy.getElementById(e.data('target'));
          if (tgt.length) {
            children.push({
              argName: e.data('argName') || 'N/A',
              label: (tgt.data('label') || '').substring(0, 25).replace(/\n/g, '|'),
              type: tgt.data('type'),
              y: Math.round(tgt.position().y)
            });
          }
        }
      });
      result.childrenOf['create-route'] = children.sort((a, b) => a.y - b.y);
    }

    if (editRoute) {
      const children = [];
      cy.edges().forEach(e => {
        if (e.data('source') === editRoute.id()) {
          const tgt = cy.getElementById(e.data('target'));
          if (tgt.length) {
            children.push({
              argName: e.data('argName') || 'N/A',
              label: (tgt.data('label') || '').substring(0, 25).replace(/\n/g, '|'),
              type: tgt.data('type'),
              y: Math.round(tgt.position().y)
            });
          }
        }
      });
      result.childrenOf['edit-route'] = children.sort((a, b) => a.y - b.y);
    }

    return result;
  });

  console.log('All relevant nodes (sorted by Y):');
  state.nodes.sort((a, b) => a.y - b.y || a.x - b.x).forEach(n => {
    console.log(`  [${n.y}, ${n.x}] ${n.label} (${n.type})`);
  });

  console.log('\nShared nodes:');
  state.shared.forEach(s => {
    console.log(`  ${s.label} - parents: ${(state.childrenOf[s.label] || []).join(', ')}`);
  });

  console.log('\nChildren of create-route:');
  (state.childrenOf['create-route'] || []).forEach(c => {
    console.log(`  [y=${c.y}] ${c.argName}: ${c.label} (${c.type})`);
  });

  console.log('\nChildren of edit-route:');
  (state.childrenOf['edit-route'] || []).forEach(c => {
    console.log(`  [y=${c.y}] ${c.argName}: ${c.label} (${c.type})`);
  });

  await browser.close();
})();
