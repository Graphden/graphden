const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  // Tap on editor-routes to select it
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Click to expand entity-form-create-route to get-route
  const clickPos = await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (!node) return null;

    const originalFnId = node.data('originalFnId');
    const overlay = document.querySelector(`.node-overlay[data-original-fn-id="${originalFnId}"]`);
    if (!overlay) return null;

    const lines = overlay.querySelectorAll('.ancestor-line');
    let targetLine = null;
    lines.forEach(line => {
      if (line.textContent === 'get-route') targetLine = line;
    });
    if (!targetLine) return null;

    const rect = targetLine.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  });

  if (clickPos) {
    await page.mouse.click(clickPos.x, clickPos.y);
    await new Promise(r => setTimeout(r, 1000));
  }

  // Detailed analysis of shared argument handling
  const analysis = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const { children, parents } = buildAdjacency(elements.edges);
    const sharedInfo = analyzeSharedArguments(children, parents);

    const nodeMap = new Map();
    elements.nodes.forEach(n => nodeMap.set(n.data.id, n.data));

    // Find handler
    const handlerNode = elements.nodes.find(n =>
      n.data.label?.includes('entity-form-handler')
    );

    if (!handlerNode) return { error: 'handler not found' };

    const handlerId = handlerNode.data.id;
    const handlerParents = parents.get(handlerId) || [];

    // Get path lengths to handler from parents
    const parentInfo = handlerParents.map(pid => {
      const data = nodeMap.get(pid);
      const pathLen = sharedInfo.pathLengths.get(pid + '->' + handlerId);
      const parentChildren = children.get(pid) || [];

      return {
        label: data?.label?.split('\n')[0],
        pathLen,
        childCount: parentChildren.length,
        children: parentChildren.map(cid => {
          const cdata = nodeMap.get(cid);
          return {
            label: cdata?.label?.substring(0, 25),
            type: cdata?.type,
            isHandler: cid === handlerId
          };
        })
      };
    });

    // Find editor-routes (root)
    const rootNode = elements.nodes.find(n =>
      n.data.label?.includes('editor-routes') && !n.data.label?.includes('\n')
    );

    let rootChildrenOrder = [];
    if (rootNode) {
      const rootChildren = children.get(rootNode.data.id) || [];
      rootChildrenOrder = rootChildren.map(cid => {
        const cdata = nodeMap.get(cid);
        return cdata?.label?.split('\n')[0];
      });
    }

    return {
      handlerId,
      isShared: sharedInfo.sharedNodes.has(handlerId),
      parentInfo,
      rootChildrenOrder
    };
  });

  console.log('=== SHARED ARGUMENT ANALYSIS ===');
  console.log('\nHandler is shared:', analysis.isShared);

  console.log('\nParents of handler:');
  analysis.parentInfo.forEach(p => {
    console.log(`\n  ${p.label} (pathLen=${p.pathLen}, ${p.childCount} children):`);
    p.children.forEach(c => {
      const marker = c.isHandler ? ' <-- HANDLER' : '';
      console.log(`    - ${c.label} (${c.type})${marker}`);
    });
  });

  console.log('\nRoot children order (first 10):');
  analysis.rootChildrenOrder.slice(0, 10).forEach((c, i) => {
    const marker = c?.includes('entity-form') ? ' <--' : '';
    console.log(`  ${i}: ${c}${marker}`);
  });

  await browser.close();
})();
