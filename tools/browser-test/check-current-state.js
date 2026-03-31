const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Check BUILD_TIMESTAMP
  const buildTimestamp = await page.evaluate(() => {
    return window.BUILD_TIMESTAMP || 'not found';
  });
  console.log('BUILD_TIMESTAMP in browser:', buildTimestamp);

  // Find entity-form-edit-route and check its children positions
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    // Build children map
    const children = new Map();
    const parents = new Map();
    elements.edges.forEach(e => {
      const src = e.data.source;
      const tgt = e.data.target;
      if (!children.has(src)) children.set(src, []);
      children.get(src).push(tgt);
      if (!parents.has(tgt)) parents.set(tgt, []);
      parents.get(tgt).push(src);
    });

    // Find shared nodes
    const sharedNodes = new Set();
    parents.forEach((parentList, nodeId) => {
      if (parentList.length > 1) sharedNodes.add(nodeId);
    });

    // Find entity-form-edit-route
    const editRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-edit-route'))[0];
    if (!editRoute) return { error: 'entity-form-edit-route not found' };

    const editId = editRoute.id();
    const editChildren = children.get(editId) || [];
    
    // Get actual pixel positions from cytoscape
    const editPos = cy.$(`#${editId}`).position();
    
    const childrenInfo = editChildren.map(cid => {
      const node = cy.$(`#${cid}`);
      const pos = node.position();
      const label = node.data('label')?.split('\n')[0]?.substring(0, 30);
      return {
        id: cid,
        label,
        x: pos.x,
        y: pos.y,
        isShared: sharedNodes.has(cid)
      };
    });

    return {
      editRoute: {
        id: editId,
        x: editPos.x,
        y: editPos.y,
        children: childrenInfo
      }
    };
  });

  console.log('\nentity-form-edit-route position:', result.editRoute?.x, result.editRoute?.y);
  console.log('Children (actual pixel positions):');
  result.editRoute?.children?.forEach(c => {
    console.log(`  - ${c.label}: x=${Math.round(c.x)}, y=${Math.round(c.y)}, shared=${c.isShared}`);
  });

  // Check if children are on same Y (same row visually)
  if (result.editRoute?.children?.length >= 2) {
    const c1 = result.editRoute.children[0];
    const c2 = result.editRoute.children[1];
    const yDiff = Math.abs(c1.y - c2.y);
    console.log(`\nY difference between children: ${yDiff}px`);
    if (yDiff < 10) {
      console.log('WARNING: Both children appear to be on the SAME row!');
    } else {
      console.log('OK: Children are on DIFFERENT rows');
    }
  }

  await page.screenshot({ path: '/tmp/current-state.png' });
  await browser.close();
})();
