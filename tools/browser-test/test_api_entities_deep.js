const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Find api-entities-route and expand to level 2
  const routeId = await page.evaluate(() => {
    let id = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('api-entities-route')) {
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

  console.log('=== API-ENTITIES-ROUTE TREE AT LEVEL 2 ===');
  printTree(tree, '');

  await browser.close();
})();
