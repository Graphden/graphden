const puppeteer = require('puppeteer');

// Trace the routing decision for a specific edge

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Find and click on list-10-9
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('list-10-9'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Expand 5 levels
  for (let i = 0; i < 5; i++) {
    await page.evaluate(() => {
      const overlays = document.querySelectorAll('.node-overlay');
      for (const overlay of overlays) {
        if (overlay.style.display !== 'none') {
          const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
          for (const line of lines) {
            const level = parseInt(line.dataset.level) || 0;
            const isBold = line.style.fontWeight === 'bold';
            if (level > 0 && !isBold) {
              line.click();
              return;
            }
          }
        }
      }
    });
    await new Promise(r => setTimeout(r, 1000));
  }

  // Now simulate the routing decision for the problematic edge
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);
    const { gridPos, matrix } = layout;
    const { nodeGrid, vEdge } = matrix;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Find delete-entity-route and delete-entity-api-handler positions
    let deleteRouteId = null;
    let deleteHandlerId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('delete-entity-route') && !n.data.label?.includes('handler')) {
        deleteRouteId = n.data.id;
      }
      if (n.data.label?.includes('delete-entity-api-handler')) {
        deleteHandlerId = n.data.id;
      }
    });

    const routePos = deleteRouteId ? gridPos.get(deleteRouteId) : null;
    const handlerPos = deleteHandlerId ? gridPos.get(deleteHandlerId) : null;

    if (!routePos || !handlerPos) {
      return { error: 'Nodes not found', deleteRouteId, deleteHandlerId };
    }

    // Simulate the routing check
    const parentRow = routePos.row;
    const parentCol = routePos.col;
    const childRow = handlerPos.row;
    const childCol = handlerPos.col;

    // Check parentCol clear
    let parentColClear = true;
    const parentColBlockers = [];
    for (let r = parentRow + 1; r < childRow; r++) {
      const nodeId = nodeGrid[r] && nodeGrid[r][parentCol];
      if (nodeId) {
        parentColClear = false;
        parentColBlockers.push({
          row: r,
          label: nodeDataMap.get(nodeId)?.label?.split('\n')[0]
        });
      }
    }

    // Check childCol clear
    let childColClear = true;
    const childColBlockers = [];
    for (let r = parentRow + 1; r < childRow; r++) {
      const nodeId = nodeGrid[r] && nodeGrid[r][childCol];
      if (nodeId) {
        childColClear = false;
        childColBlockers.push({
          row: r,
          label: nodeDataMap.get(nodeId)?.label?.split('\n')[0]
        });
      }
    }

    // Check columns between
    const colsBetween = [];
    for (let tryCol = parentCol + 1; tryCol < childCol; tryCol++) {
      let colClear = true;
      const blockers = [];
      for (let r = parentRow + 1; r < childRow; r++) {
        const nodeId = nodeGrid[r] && nodeGrid[r][tryCol];
        if (nodeId) {
          colClear = false;
          blockers.push({
            row: r,
            label: nodeDataMap.get(nodeId)?.label?.split('\n')[0]
          });
        }
      }
      colsBetween.push({ col: tryCol, clear: colClear, blockers });
    }

    return {
      parentPos: { row: parentRow, col: parentCol },
      childPos: { row: childRow, col: childCol },
      parentColClear,
      parentColBlockers,
      childColClear,
      childColBlockers,
      colsBetween
    };
  });

  console.log('=== Routing Analysis for delete-entity-route -> delete-entity-api-handler ===');
  console.log(`Parent: (${result.parentPos?.row}, ${result.parentPos?.col})`);
  console.log(`Child: (${result.childPos?.row}, ${result.childPos?.col})`);
  console.log(`\nParent column (${result.parentPos?.col}) clear: ${result.parentColClear}`);
  if (result.parentColBlockers?.length > 0) {
    console.log('  Blockers:', result.parentColBlockers.map(b => `row ${b.row}: "${b.label}"`).join(', '));
  }
  console.log(`\nChild column (${result.childPos?.col}) clear: ${result.childColClear}`);
  if (result.childColBlockers?.length > 0) {
    console.log('  Blockers:', result.childColBlockers.map(b => `row ${b.row}: "${b.label}"`).join(', '));
  }
  console.log('\nColumns between:');
  result.colsBetween?.forEach(c => {
    console.log(`  Column ${c.col}: ${c.clear ? 'CLEAR' : 'BLOCKED'}`);
    if (c.blockers?.length > 0) {
      console.log('    Blockers:', c.blockers.map(b => `row ${b.row}: "${b.label}"`).join(', '));
    }
  });

  await browser.close();
})();
