const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const { children, parents } = buildAdjacency(elements.edges);
    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Find edit-route and its parent
    let editRouteId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('entity-form-edit-route')) {
        editRouteId = n.data.id;
      }
    });

    const editRouteParents = parents.get(editRouteId) || [];
    const editRouteParent = editRouteParents[0];
    const editRouteParentChildren = children.get(editRouteParent) || [];

    return {
      editRoutePos: gridPos.get(editRouteId),
      editRouteParentLabel: nodeDataMap.get(editRouteParent)?.label?.split('\n')[0],
      editRouteParentPos: gridPos.get(editRouteParent),
      editRouteParentChildren: editRouteParentChildren.map(cid => ({
        label: nodeDataMap.get(cid)?.label?.split('\n')[0],
        pos: gridPos.get(cid)
      }))
    };
  });

  console.log('=== edit-route ===');
  console.log('Position: row=' + result.editRoutePos?.row + ', col=' + result.editRoutePos?.col);
  console.log('\nParent: "' + result.editRouteParentLabel + '" at row=' + result.editRouteParentPos?.row + ', col=' + result.editRouteParentPos?.col);
  console.log('\nParent children:');
  result.editRouteParentChildren.forEach(c => {
    console.log('  "' + c.label + '" at row=' + c.pos?.row + ', col=' + c.pos?.col);
  });

  await browser.close();
})();
