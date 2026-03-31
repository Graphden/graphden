const puppeteer = require('puppeteer');

// This script traces the branch building during layout

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
    const children = new Map();
    const edgeArgNames = new Map();
    elements.edges.forEach(e => {
      const src = e.data.source;
      const tgt = e.data.target;
      const key = src + '->' + tgt;
      if (!children.has(src)) children.set(src, []);
      children.get(src).push(tgt);
      if (e.data.argName) {
        edgeArgNames.set(key, e.data.argName);
      }
    });

    // Build nodeDataMap
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => {
      nodeDataMap.set(n.data.id, n.data);
    });

    // Find create-entity-route
    let targetNode = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('create-entity-route')) {
        targetNode = n.data;
      }
    });

    if (!targetNode) return { error: 'Node not found' };

    const nodeId = targetNode.id;
    const nodeChildren = children.get(nodeId) || [];

    // Get child details
    const childDetails = nodeChildren.map(cid => {
      const data = nodeDataMap.get(cid);
      const label = data?.label?.split('\n')[0] || cid;
      let type = 'unknown';
      if (data) {
        if (data.isPlaceholder) type = 'free';
        else if (data.type === 'fn') type = 'fn';
        else if (data.type === 'arg') type = 'fixed';
      }
      const argName = edgeArgNames.get(nodeId + '->' + cid) || '?';
      return { cid, label, type, argName };
    });

    // Run layout and get positions
    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    const parentPos = gridPos.get(nodeId);
    const childPositions = nodeChildren.map(cid => {
      const pos = gridPos.get(cid);
      return { cid, row: pos?.row, col: pos?.col };
    });

    return {
      parentLabel: targetNode.label?.split('\n')[0],
      parentPos,
      childDetails,
      childPositions
    };
  });

  console.log('=== Branch Trace for create-entity-route ===');
  console.log('Parent:', result.parentLabel);
  console.log('Parent pos:', result.parentPos);
  console.log('\nChildren (original order):');
  result.childDetails.forEach((c, i) => {
    console.log(`  ${i}: [${c.type}] ${c.argName} -> "${c.label}"`);
  });
  console.log('\nChild positions:');
  result.childPositions.forEach(p => {
    console.log(`  ${p.cid.substring(0, 20)}: row=${p.row}, col=${p.col}`);
  });

  await browser.close();
})();
