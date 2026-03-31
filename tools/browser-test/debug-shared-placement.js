const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  const cyLoaded = await page.evaluate(() => typeof cy !== 'undefined' && cy !== null);
  if (!cyLoaded) {
    console.log('Cytoscape not loaded');
    await browser.close();
    return;
  }

  // Кликаем на корневой узел (editor-routes)
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Экспандим list-10
  await page.evaluate(() => {
    const overlays = document.querySelectorAll('.node-overlay');
    for (const overlay of overlays) {
      if (overlay.style.display !== 'none') {
        const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
        for (const line of lines) {
          if (line.textContent.includes('list-10')) {
            line.click();
            return;
          }
        }
      }
    }
  });
  await new Promise(r => setTimeout(r, 1500));

  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);
    const { gridPos, matrix } = layout;

    // Find handler
    let handlerId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('entity-form-handler')) {
        handlerId = n.data.id;
      }
    });

    const { children, parents } = buildAdjacency(elements.edges);
    const handlerParents = parents.get(handlerId) || [];
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Check handler's column
    const handlerPos = gridPos.get(handlerId);
    const handlerCol = handlerPos?.col;

    // Check what's at column 7 and 8
    const col7Nodes = [];
    const col8Nodes = [];
    gridPos.forEach((pos, nodeId) => {
      if (pos.col === 7) {
        col7Nodes.push({
          row: pos.row,
          label: nodeDataMap.get(nodeId)?.label?.split('\n')[0]
        });
      }
      if (pos.col === 8) {
        col8Nodes.push({
          row: pos.row,
          label: nodeDataMap.get(nodeId)?.label?.split('\n')[0]
        });
      }
    });

    // Check what's at row 13
    const row13Nodes = [];
    gridPos.forEach((pos, nodeId) => {
      if (pos.row === 13) {
        row13Nodes.push({
          col: pos.col,
          label: nodeDataMap.get(nodeId)?.label?.split('\n')[0]
        });
      }
    });

    // Check cells around where handler should be (row 13, cols 7-10)
    const cellsInfo = [];
    for (let col = 6; col <= 10; col++) {
      const nodeAt = matrix.nodeGrid[13] && matrix.nodeGrid[13][col];
      const hEdgeAt = matrix.hEdge[13] && matrix.hEdge[13][col];
      const vEdgeAt = matrix.vEdge[13] && matrix.vEdge[13][col];
      cellsInfo.push({
        col,
        hasNode: nodeAt !== null && nodeAt !== undefined,
        nodeId: nodeAt,
        hasHEdge: hEdgeAt !== null && hEdgeAt !== undefined,
        hasVEdge: vEdgeAt === true
      });
    }

    return {
      handlerPos,
      handlerParents: handlerParents.map(pid => ({
        id: pid,
        label: nodeDataMap.get(pid)?.label?.split('\n')[0],
        pos: gridPos.get(pid)
      })),
      col7Nodes: col7Nodes.sort((a, b) => a.row - b.row),
      col8Nodes: col8Nodes.sort((a, b) => a.row - b.row),
      row13Nodes: row13Nodes.sort((a, b) => a.col - b.col),
      cellsAtRow13: cellsInfo
    };
  });

  console.log('Handler position:', result.handlerPos);
  console.log('\nHandler parents:');
  result.handlerParents.forEach(p => {
    console.log('  ' + p.label + ' at row=' + p.pos?.row + ', col=' + p.pos?.col);
  });

  console.log('\nNodes at col 7:');
  result.col7Nodes.forEach(n => console.log('  row ' + n.row + ': ' + n.label));

  console.log('\nNodes at col 8:');
  result.col8Nodes.forEach(n => console.log('  row ' + n.row + ': ' + n.label));

  console.log('\nNodes at row 13:');
  result.row13Nodes.forEach(n => console.log('  col ' + n.col + ': ' + n.label));

  console.log('\nCells at row 13, cols 6-10:');
  result.cellsAtRow13.forEach(c => {
    console.log('  col ' + c.col + ': node=' + c.hasNode + ', hEdge=' + c.hasHEdge + ', vEdge=' + c.hasVEdge);
    if (c.nodeId) console.log('         nodeId: ' + c.nodeId);
  });

  await browser.close();
})();
