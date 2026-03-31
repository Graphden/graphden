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
    const sharedInfo = analyzeSharedArguments(children, parents);

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Find editor-routes
    const hasIncoming = new Set();
    elements.edges.forEach(e => hasIncoming.add(e.data.target));
    const root = elements.nodes.find(n => !hasIncoming.has(n.data.id));
    const rootId = root ? root.data.id : null;

    // Get children of editor-routes
    const rootChildren = children.get(rootId) || [];

    // Find edit-route and create-route
    let editRouteId = null, createRouteId = null, handlerId = null;
    elements.nodes.forEach(n => {
      const label = n.data.label || '';
      if (label.includes('entity-form-edit-route')) editRouteId = n.data.id;
      if (label.includes('entity-form-create-route')) createRouteId = n.data.id;
      if (label.includes('entity-form-handler')) handlerId = n.data.id;
    });

    // Check if both lead to handler
    const editPaths = sharedInfo.pathsToShared.get(editRouteId);
    const createPaths = sharedInfo.pathsToShared.get(createRouteId);

    return {
      rootLabel: nodeDataMap.get(rootId)?.label?.split('\n')[0],
      childrenOrder: rootChildren.map(cid => nodeDataMap.get(cid)?.label?.split('\n')[0]),
      editRouteLeadsToHandler: editPaths && editPaths.has(handlerId),
      createRouteLeadsToHandler: createPaths && createPaths.has(handlerId),
      editRoutePath: sharedInfo.pathLengths.get(editRouteId + '->' + handlerId),
      createRoutePath: sharedInfo.pathLengths.get(createRouteId + '->' + handlerId),
      editRouteIdx: rootChildren.indexOf(editRouteId),
      createRouteIdx: rootChildren.indexOf(createRouteId)
    };
  });

  console.log('Root:', result.rootLabel);
  console.log('\nChildren order:');
  result.childrenOrder.forEach((c, i) => {
    const marker = c?.includes('entity-form') ? ' <--' : '';
    console.log('  ' + i + ': ' + c + marker);
  });

  console.log('\nedit-route leads to handler:', result.editRouteLeadsToHandler, 'path:', result.editRoutePath);
  console.log('create-route leads to handler:', result.createRouteLeadsToHandler, 'path:', result.createRoutePath);
  console.log('\nedit-route index:', result.editRouteIdx);
  console.log('create-route index:', result.createRouteIdx);

  await browser.close();
})();
