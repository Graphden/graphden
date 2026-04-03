const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

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

  // Expand to level 2
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeId);
  await new Promise(r => setTimeout(r, 1500));

  // Show full tree and find entity-form-handler
  const info = await page.evaluate((fnId) => {
    const nodeId = 'fn-' + fnId;
    
    function getChildren(nid, depth) {
      if (depth > 5) return [];
      const children = [];
      cy.edges().forEach(e => {
        if (e.source().id() === nid) {
          const target = e.target();
          children.push({
            argName: e.data('argName'),
            label: (target.data('label') || '').substring(0, 35).replace(/\n/g, '|'),
            id: target.id(),
            children: getChildren(target.id(), depth + 1)
          });
        }
      });
      return children;
    }
    
    const tree = getChildren(nodeId, 0);
    
    // Find entity-form-handler
    let handlerInfo = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('entity-form-handler')) {
        const parents = [];
        cy.edges().forEach(e => {
          if (e.target().id() === n.id()) {
            parents.push({
              parentLabel: (e.source().data('label') || '').substring(0, 35).replace(/\n/g, '|'),
              argName: e.data('argName')
            });
          }
        });
        handlerInfo = {
          id: n.id(),
          label: lbl.substring(0, 40),
          parents
        };
      }
    });
    
    return { tree, handlerInfo };
  }, routeId);

  function printTree(items, indent) {
    items.forEach(item => {
      console.log(indent + item.argName + ': ' + item.label);
      if (item.children.length > 0) {
        printTree(item.children, indent + '  ');
      }
    });
  }

  console.log('=== ENTITY-FORM-EDIT-ROUTE TREE AT LEVEL 2 ===');
  printTree(info.tree, '');

  console.log('\n=== ENTITY-FORM-HANDLER INFO ===');
  if (info.handlerInfo) {
    console.log('Node:', info.handlerInfo.label);
    console.log('ID:', info.handlerInfo.id);
    console.log('Parents:');
    info.handlerInfo.parents.forEach(p => console.log('  <- ' + p.argName + ' from ' + p.parentLabel));
  } else {
    console.log('NOT FOUND');
  }

  await browser.close();
})();
