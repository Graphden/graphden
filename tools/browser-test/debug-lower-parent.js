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

    // Find handler
    let handlerId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('entity-form-handler')) {
        handlerId = n.data.id;
      }
    });

    const handlerParents = parents.get(handlerId) || [];

    // Determine lower parent
    let lowerParent = null;
    let maxPathLen = -1;

    for (const parentId of handlerParents) {
      const pathKey = parentId + '->' + handlerId;
      const pathLen = sharedInfo.pathLengths.get(pathKey) || 1;
      if (pathLen >= maxPathLen) {
        maxPathLen = pathLen;
        lowerParent = parentId;
      }
    }

    return {
      handlerParents: handlerParents.map(pid => ({
        label: nodeDataMap.get(pid)?.label?.split('\n')[0],
        pathLen: sharedInfo.pathLengths.get(pid + '->' + handlerId)
      })),
      lowerParentLabel: nodeDataMap.get(lowerParent)?.label?.split('\n')[0]
    };
  });

  console.log('Handler parents:');
  result.handlerParents.forEach(p => {
    console.log('  "' + p.label + '" pathLen=' + p.pathLen);
  });
  console.log('\nLower parent:', result.lowerParentLabel);

  await browser.close();
})();
