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
  
  const result = await page.evaluate(() => {
    const elements = { nodes: cy.nodes().map(n => ({ data: n.data() })), edges: cy.edges().map(e => ({ data: e.data() })) };
    const { children, parents } = buildAdjacency(elements.edges);
    const sharedInfo = analyzeSharedArguments(children, parents);
    
    const createRoute = elements.nodes.find(n => n.data.label?.includes('entity-form-create-route'));
    const editRoute = elements.nodes.find(n => n.data.label?.includes('entity-form-edit-route'));
    const handler = elements.nodes.find(n => n.data.label?.includes('entity-form-handler'));
    
    const nodeMap = new Map();
    elements.nodes.forEach(n => nodeMap.set(n.data.id, n.data));
    
    // Check who is "lower parent"
    const createPathLen = sharedInfo.pathLengths.get(createRoute.data.id + '->' + handler.data.id);
    const editPathLen = sharedInfo.pathLengths.get(editRoute.data.id + '->' + handler.data.id);
    
    return {
      createPathLen,
      editPathLen,
      createChildren: (children.get(createRoute.data.id) || []).map(id => {
        const data = nodeMap.get(id);
        return { label: data?.label?.substring(0, 20), isShared: sharedInfo.sharedNodes.has(id) };
      }),
      editChildren: (children.get(editRoute.data.id) || []).map(id => {
        const data = nodeMap.get(id);
        return { label: data?.label?.substring(0, 20), isShared: sharedInfo.sharedNodes.has(id) };
      })
    };
  });
  
  console.log('Path lengths to handler:');
  console.log('  create-route:', result.createPathLen);
  console.log('  edit-route:', result.editPathLen);
  console.log('  Lower parent:', result.createPathLen >= result.editPathLen ? 'create-route' : 'edit-route');
  
  console.log('\ncreate-route children (adjacency order):');
  result.createChildren.forEach((c, i) => console.log('  ' + i + ':', c.label, c.isShared ? 'SHARED' : ''));
  
  console.log('\nedit-route children (adjacency order):');
  result.editChildren.forEach((c, i) => console.log('  ' + i + ':', c.label, c.isShared ? 'SHARED' : ''));
  
  await browser.close();
})();
