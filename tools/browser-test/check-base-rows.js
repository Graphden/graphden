const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  const result = await page.evaluate(() => {
    if (!window.cy || !window.layoutGraph) {
      return { error: 'cy or layoutGraph not available' };
    }

    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    // Build parent->children map
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

    // Check for multiple children on same row per parent
    const violations = [];
    children.forEach((childIds, parentId) => {
      const parentPos = gridPos.get(parentId);
      if (!parentPos) return;

      const childPositions = childIds
        .map(id => ({ 
          id, 
          pos: gridPos.get(id), 
          label: cy.$(`#${id}`).data('label')?.split('\n')[0]?.substring(0, 30) || id,
          isShared: sharedNodes.has(id)
        }))
        .filter(c => c.pos);

      if (childPositions.length < 2) return;

      // Check rows
      const rowCounts = new Map();
      childPositions.forEach(c => {
        // Shared nodes on parent's row are exempt
        if (c.isShared && c.pos.row === parentPos.row) return;
        
        const key = c.pos.row;
        if (!rowCounts.has(key)) rowCounts.set(key, []);
        rowCounts.get(key).push(c);
      });

      rowCounts.forEach((childrenOnRow, row) => {
        if (childrenOnRow.length > 1) {
          const parentLabel = cy.$(`#${parentId}`).data('label')?.split('\n')[0]?.substring(0, 30) || parentId;
          violations.push({
            parent: parentLabel,
            parentRow: parentPos.row,
            row: row,
            children: childrenOnRow.map(c => ({ label: c.label, col: c.pos.col, isShared: c.isShared }))
          });
        }
      });
    });

    // Also get entity-form-edit-route and its children
    const editRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-edit-route'))[0];
    let editRouteInfo = null;
    if (editRoute) {
      const editId = editRoute.id();
      const editPos = gridPos.get(editId);
      const editChildren = children.get(editId) || [];
      editRouteInfo = {
        id: editId,
        pos: editPos,
        children: editChildren.map(cid => {
          const cpos = gridPos.get(cid);
          const clabel = cy.$(`#${cid}`).data('label')?.split('\n')[0]?.substring(0, 30);
          return { id: cid, label: clabel, pos: cpos, isShared: sharedNodes.has(cid) };
        })
      };
    }

    return { violations, editRouteInfo, sharedNodes: Array.from(sharedNodes) };
  });

  console.log('=== Checking for two-children-on-same-row in base graph ===');
  console.log('\nShared nodes:', result.sharedNodes?.length || 0);
  
  if (result.editRouteInfo) {
    console.log('\n=== entity-form-edit-route ===');
    console.log('Position:', result.editRouteInfo.pos);
    console.log('Children:');
    result.editRouteInfo.children.forEach(c => {
      console.log(`  - ${c.label}: row=${c.pos?.row}, col=${c.pos?.col}, shared=${c.isShared}`);
    });
  }
  
  if (result.violations && result.violations.length > 0) {
    console.log('\n=== VIOLATIONS ===');
    result.violations.forEach(v => {
      console.log(`Parent ${v.parent} (row ${v.parentRow}) has ${v.children.length} children on row ${v.row}:`);
      v.children.forEach(c => console.log(`  - ${c.label} (col=${c.col}, shared=${c.isShared})`));
    });
  } else {
    console.log('\nNo two-children-on-same-row violations found in base graph');
  }

  await browser.close();
})();
