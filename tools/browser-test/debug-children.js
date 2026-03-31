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
  
  const clickPos = await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (!node) return null;
    const originalFnId = node.data('originalFnId');
    const overlay = document.querySelector(`.node-overlay[data-original-fn-id="${originalFnId}"]`);
    if (!overlay) return null;
    const lines = overlay.querySelectorAll('.ancestor-line');
    let targetLine = null;
    lines.forEach(line => { if (line.textContent === 'get-route') targetLine = line; });
    if (!targetLine) return null;
    const rect = targetLine.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  });
  
  if (clickPos) {
    await page.mouse.click(clickPos.x, clickPos.y);
    await new Promise(r => setTimeout(r, 1000));
  }
  
  const result = await page.evaluate(() => {
    const elements = { nodes: cy.nodes().map(n => ({ data: n.data() })), edges: cy.edges().map(e => ({ data: e.data() })) };
    const { children, parents } = buildAdjacency(elements.edges);
    const sharedInfo = analyzeSharedArguments(children, parents);
    
    const createRoute = elements.nodes.find(n => n.data.label?.includes('entity-form-create-route'));
    const editRoute = elements.nodes.find(n => n.data.label?.includes('entity-form-edit-route'));  
    const handler = elements.nodes.find(n => n.data.label?.includes('entity-form-handler'));
    
    const nodeMap = new Map();
    elements.nodes.forEach(n => nodeMap.set(n.data.id, n.data));
    
    // Get children order
    const createChildren = children.get(createRoute.data.id) || [];
    const editChildren = children.get(editRoute.data.id) || [];
    
    // Check path lengths
    const createToHandler = sharedInfo.pathLengths.get(createRoute.data.id + '->' + handler.data.id);
    const editToHandler = sharedInfo.pathLengths.get(editRoute.data.id + '->' + handler.data.id);
    
    return {
      handlerIsShared: sharedInfo.sharedNodes.has(handler.data.id),
      createChildren: createChildren.map(id => {
        const data = nodeMap.get(id);
        return { label: data?.label?.substring(0, 20), type: data?.type, isShared: sharedInfo.sharedNodes.has(id) };
      }),
      editChildren: editChildren.map(id => {
        const data = nodeMap.get(id);
        return { label: data?.label?.substring(0, 20), type: data?.type, isShared: sharedInfo.sharedNodes.has(id) };
      }),
      createToHandler,
      editToHandler
    };
  });
  
  console.log('Handler is shared:', result.handlerIsShared);
  console.log('Path lengths:');
  console.log('  create-route -> handler:', result.createToHandler);
  console.log('  edit-route -> handler:', result.editToHandler);
  console.log('\ncreate-route children (original order from adjacency):');
  result.createChildren.forEach((c, i) => console.log('  ' + i + ':', c.label, '(' + c.type + ')', c.isShared ? 'SHARED' : ''));
  console.log('\nedit-route children (original order from adjacency):');
  result.editChildren.forEach((c, i) => console.log('  ' + i + ':', c.label, '(' + c.type + ')', c.isShared ? 'SHARED' : ''));
  
  await browser.close();
})();
