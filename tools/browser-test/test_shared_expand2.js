const puppeteer = require('puppeteer');

// Find shared nodes and test expansion
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

  // Find entity-form-edit-route and its shared children
  const info = await page.evaluate(() => {
    let routeId = null;
    let routeNodeId = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('entity-form-edit-route')) {
        routeId = n.data('originalFnId');
        routeNodeId = n.id();
      }
    });

    // Find children of entity-form-edit-route
    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === routeNodeId) {
        const target = e.target();
        // Check if target has multiple parents
        let parentCount = 0;
        cy.edges().forEach(e2 => {
          if (e2.target().id() === target.id()) parentCount++;
        });
        children.push({
          id: target.id(),
          label: (target.data('label') || '').substring(0, 40).replace(/\n/g, '|'),
          argName: e.data('argName'),
          isShared: parentCount > 1,
          parentCount
        });
      }
    });

    return { routeId, routeNodeId, children };
  });

  console.log('entity-form-edit-route:', info.routeNodeId);
  console.log('\nChildren:');
  info.children.forEach(c => {
    console.log('  ', c.argName, ':', c.label, c.isShared ? `(SHARED - ${c.parentCount} parents)` : '');
  });

  // Find the shared child
  const sharedChild = info.children.find(c => c.isShared);
  if (sharedChild) {
    console.log('\n=== SHARED CHILD:', sharedChild.label, '===');
    
    // Show all parents of this shared child
    const parents = await page.evaluate((childId) => {
      const result = [];
      cy.edges().forEach(e => {
        if (e.target().id() === childId) {
          result.push({
            parentId: e.source().id(),
            parentLabel: (e.source().data('label') || '').substring(0, 40).replace(/\n/g, '|'),
            argName: e.data('argName'),
            edgeId: e.id()
          });
        }
      });
      return result;
    }, sharedChild.id);
    
    console.log('Parents:');
    parents.forEach(p => console.log('  ', p.parentLabel, '->', p.argName));
  }

  // Now expand entity-form-edit-route
  console.log('\n=== EXPAND entity-form-edit-route TO LEVEL 2 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, info.routeId);
  await new Promise(r => setTimeout(r, 1500));

  // Check children again
  const afterExpand = await page.evaluate((fnId) => {
    let routeNodeId = null;
    cy.nodes().forEach(n => {
      if (n.data('originalFnId') === fnId) {
        routeNodeId = n.id();
      }
    });

    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === routeNodeId) {
        const target = e.target();
        let parentCount = 0;
        cy.edges().forEach(e2 => {
          if (e2.target().id() === target.id()) parentCount++;
        });
        children.push({
          id: target.id(),
          label: (target.data('label') || '').substring(0, 40).replace(/\n/g, '|'),
          argName: e.data('argName'),
          isShared: parentCount > 1,
          parentCount
        });
      }
    });

    return { routeNodeId, children };
  }, info.routeId);

  console.log('entity-form-edit-route node:', afterExpand.routeNodeId);
  console.log('\nChildren after expand:');
  afterExpand.children.forEach(c => {
    console.log('  ', c.argName, ':', c.label, c.isShared ? `(SHARED - ${c.parentCount} parents)` : '');
  });

  // If there was a shared child before, check its parents now
  if (sharedChild) {
    // Find the same child by label
    const matchingChild = afterExpand.children.find(c => c.label === sharedChild.label);
    if (matchingChild) {
      const parentsAfter = await page.evaluate((childId) => {
        const result = [];
        cy.edges().forEach(e => {
          if (e.target().id() === childId) {
            result.push({
              parentId: e.source().id(),
              parentLabel: (e.source().data('label') || '').substring(0, 40).replace(/\n/g, '|'),
              argName: e.data('argName')
            });
          }
        });
        return result;
      }, matchingChild.id);
      
      console.log('\nParents of shared child after expand:');
      parentsAfter.forEach(p => console.log('  ', p.parentLabel, '->', p.argName));
    }
  }

  await browser.close();
})();
