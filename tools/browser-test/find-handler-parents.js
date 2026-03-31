const puppeteer = require('puppeteer');

// Find all parents of entity-form-handler

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const { children, parents, edgeArgNames } = buildAdjacency(elements.edges);
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Find entity-form-handler
    let handlerId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('entity-form-handler')) {
        handlerId = n.data.id;
      }
    });

    if (!handlerId) return { error: 'Node not found' };

    const parentList = parents.get(handlerId) || [];
    const parentDetails = parentList.map(pid => {
      const data = nodeDataMap.get(pid);
      return {
        pid: pid.substring(0, 30),
        label: data?.label?.split('\n')[0]
      };
    });

    // Get layout
    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    const positions = parentList.map(pid => {
      const pos = gridPos.get(pid);
      const data = nodeDataMap.get(pid);
      return {
        label: data?.label?.split('\n')[0]?.substring(0, 25),
        row: pos?.row,
        col: pos?.col
      };
    });

    const handlerPos = gridPos.get(handlerId);

    return {
      parentDetails,
      positions,
      handlerPos
    };
  });

  console.log('=== entity-form-handler Parents ===');
  result.parentDetails.forEach((p, i) => {
    console.log(`  ${i}: "${p.label}"`);
  });

  console.log('\nParent positions:');
  result.positions.forEach(p => {
    console.log(`  "${p.label}" -> row=${p.row}, col=${p.col}`);
  });

  console.log('\nHandler position:', result.handlerPos);

  await browser.close();
})();
