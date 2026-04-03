const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Find metrics-route and expand to level 2
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

  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeId);
  await new Promise(r => setTimeout(r, 1500));

  // Show full tree
  const tree = await page.evaluate((fnId) => {
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
    
    return getChildren(nodeId, 0);
  }, routeId);

  function printTree(items, indent) {
    items.forEach(item => {
      console.log(indent + item.argName + ': ' + item.label);
      if (item.children.length > 0) {
        printTree(item.children, indent + '  ');
      }
    });
  }

  console.log('=== TREE AT LEVEL 2 ===');
  printTree(tree, '');

  // Check if metrics-handler-fn is anywhere
  const handlerInfo = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('metrics-handler') || lbl.includes('metrics-response')) {
        // Find parents
        const parents = [];
        cy.edges().forEach(e => {
          if (e.target().id() === n.id()) {
            parents.push({
              parentId: e.source().id(),
              parentLabel: (e.source().data('label') || '').substring(0, 30).replace(/\n/g, '|'),
              argName: e.data('argName')
            });
          }
        });
        result.push({
          id: n.id(),
          label: lbl.substring(0, 40).replace(/\n/g, '|'),
          parents
        });
      }
    });
    return result;
  });

  console.log('\n=== HANDLER/RESPONSE NODES ===');
  handlerInfo.forEach(n => {
    console.log(n.label, '(' + n.id + ')');
    n.parents.forEach(p => console.log('  <- ' + p.argName + ' from ' + p.parentLabel));
  });

  await browser.close();
})();
