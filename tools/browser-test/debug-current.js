const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));
  
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));
  
  // Get layout WITHOUT expand
  const result = await page.evaluate(() => {
    const elements = { nodes: cy.nodes().map(n => ({ data: n.data() })), edges: cy.edges().map(e => ({ data: e.data() })) };
    const { children } = buildAdjacency(elements.edges);
    const layout = layoutGraph(elements);
    const { gridPos } = layout;
    
    // Find entity-form-create-route and its children
    const createRoute = elements.nodes.find(n => n.data.label?.includes('entity-form-create-route'));
    const editRoute = elements.nodes.find(n => n.data.label?.includes('entity-form-edit-route'));
    
    const nodeMap = new Map();
    elements.nodes.forEach(n => nodeMap.set(n.data.id, n.data));
    
    const createPos = gridPos.get(createRoute.data.id);
    const editPos = gridPos.get(editRoute.data.id);
    
    const createChildren = children.get(createRoute.data.id) || [];
    const editChildren = children.get(editRoute.data.id) || [];
    
    return {
      createRoute: { label: createRoute.data.label?.split('\n')[0], ...createPos },
      editRoute: { label: editRoute.data.label?.split('\n')[0], ...editPos },
      createChildren: createChildren.map(id => {
        const data = nodeMap.get(id);
        const pos = gridPos.get(id);
        return { label: data?.label?.substring(0, 25), ...pos };
      }),
      editChildren: editChildren.map(id => {
        const data = nodeMap.get(id);
        const pos = gridPos.get(id);
        return { label: data?.label?.substring(0, 25), ...pos };
      })
    };
  });
  
  console.log('=== WITHOUT EXPAND ===');
  console.log('\ncreate-route:', result.createRoute);
  console.log('Children:');
  result.createChildren.forEach(c => console.log('  ', c));
  
  console.log('\nedit-route:', result.editRoute);
  console.log('Children:');
  result.editChildren.forEach(c => console.log('  ', c));
  
  await browser.close();
})();
