const puppeteer = require('puppeteer');

// Trace entity-form-create-route sorting

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

    // Find entity-form-create-route
    let targetNodeId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('entity-form-create-route')) {
        targetNodeId = n.data.id;
      }
    });

    if (!targetNodeId) return { error: 'Node not found' };

    const nodeChildren = children.get(targetNodeId) || [];

    // Check if children are shared nodes
    const childDetails = nodeChildren.map(cid => {
      const data = nodeDataMap.get(cid);
      const parentList = parents.get(cid) || [];
      const isShared = parentList.length > 1;
      let type = 'unknown';
      if (data) {
        if (data.isPlaceholder) type = 'free';
        else if (data.type === 'fn') type = 'fn';
        else if (data.type === 'arg') type = 'fixed';
      }
      return {
        cid: cid.substring(0, 30),
        label: data?.label?.split('\n')[0]?.substring(0, 30),
        type,
        isShared,
        numParents: parentList.length
      };
    });

    // Call sorting
    const sharedInfo = analyzeSharedArguments(children, parents);
    const sorted = sortChildrenByPriority(nodeChildren, nodeDataMap, sharedInfo, targetNodeId, edgeArgNames);

    const sortedDetails = sorted.map(cid => {
      const data = nodeDataMap.get(cid);
      let type = 'unknown';
      if (data) {
        if (data.isPlaceholder) type = 'free';
        else if (data.type === 'fn') type = 'fn';
        else if (data.type === 'arg') type = 'fixed';
      }
      return { cid: cid.substring(0, 30), type };
    });

    // Get layout positions
    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    const childPositions = nodeChildren.map(cid => {
      const pos = gridPos.get(cid);
      const data = nodeDataMap.get(cid);
      return {
        cid: cid.substring(0, 30),
        label: data?.label?.split('\n')[0]?.substring(0, 20),
        row: pos?.row,
        col: pos?.col
      };
    });

    return {
      childDetails,
      sortedDetails,
      childPositions
    };
  });

  console.log('=== entity-form-create-route Children ===');
  console.log('Original order:');
  result.childDetails.forEach((c, i) => {
    console.log(`  ${i}: [${c.type}] "${c.label}" (shared=${c.isShared}, parents=${c.numParents})`);
  });

  console.log('\nAfter sorting:');
  result.sortedDetails.forEach((c, i) => {
    console.log(`  ${i}: [${c.type}] ${c.cid}`);
  });

  console.log('\nLayout positions:');
  result.childPositions.forEach(p => {
    console.log(`  "${p.label}" -> row=${p.row}, col=${p.col}`);
  });

  await browser.close();
})();
