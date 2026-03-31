const puppeteer = require('puppeteer');

// This script debugs sorting for a specific node

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

    // Build children map
    const { children, parents, edgeArgNames } = buildAdjacency(elements.edges);

    // Build nodeDataMap
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => {
      nodeDataMap.set(n.data.id, n.data);
    });

    // Find create-entity-route
    let targetNodeId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('create-entity-route')) {
        targetNodeId = n.data.id;
      }
    });

    if (!targetNodeId) return { error: 'Node not found' };

    const nodeChildren = children.get(targetNodeId) || [];

    // Manually test getNodeType
    const childTypes = nodeChildren.map(cid => {
      const data = nodeDataMap.get(cid);
      if (!data) return { cid, type: 'unknown - no data' };

      if (data.isPlaceholder) return { cid, type: 'free', reason: 'isPlaceholder' };
      if (data.type === 'fn') return { cid, type: 'fn', reason: 'data.type=fn' };
      if (data.type === 'arg') return { cid, type: 'fixed', reason: 'data.type=arg' };
      return { cid, type: 'free', reason: 'default' };
    });

    // Call sortChildrenByPriority
    const sharedInfo = analyzeSharedArguments(children, parents);
    const sorted = sortChildrenByPriority(nodeChildren, nodeDataMap, sharedInfo, targetNodeId, edgeArgNames);

    return {
      original: nodeChildren,
      childTypes,
      sorted,
      changed: JSON.stringify(nodeChildren) !== JSON.stringify(sorted)
    };
  });

  console.log('=== Sorting Debug for create-entity-route ===');
  console.log('Original order:', result.original);
  console.log('\nChild type detection:');
  result.childTypes.forEach(ct => {
    console.log(`  ${ct.cid.substring(0, 25)}: ${ct.type} (${ct.reason})`);
  });
  console.log('\nSorted order:', result.sorted);
  console.log('Order changed:', result.changed);

  await browser.close();
})();
