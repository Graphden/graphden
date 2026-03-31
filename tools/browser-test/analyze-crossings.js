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

  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

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

  await page.screenshot({ path: '/tmp/list10-expand.png', fullPage: true });

  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);
    const { gridPos, matrix, validation } = layout;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    const { children, parents } = buildAdjacency(elements.edges);

    // Find the three nodes we care about
    let editRouteId = null, createRouteId = null, handlerId = null;
    elements.nodes.forEach(n => {
      const label = n.data.label || '';
      if (label.includes('entity-form-edit-route')) editRouteId = n.data.id;
      if (label.includes('entity-form-create-route')) createRouteId = n.data.id;
      if (label.includes('entity-form-handler')) handlerId = n.data.id;
    });

    const editPos = gridPos.get(editRouteId);
    const createPos = gridPos.get(createRouteId);
    const handlerPos = gridPos.get(handlerId);

    // Analyze what's between editRoute (row 8) and createRoute (row 13)
    // in the columns 6, 7, 8 (where the shared node path goes)
    const betweenNodes = [];
    const editRow = editPos?.row || 0;
    const createRow = createPos?.row || 0;
    const handlerCol = handlerPos?.col || 0;

    gridPos.forEach((pos, nodeId) => {
      // Nodes between edit-route and create-route rows, in relevant columns
      if (pos.row > editRow && pos.row < createRow && pos.col >= 6 && pos.col <= handlerCol + 2) {
        betweenNodes.push({
          label: nodeDataMap.get(nodeId)?.label?.split('\n')[0],
          row: pos.row,
          col: pos.col,
          nodeId
        });
      }
    });

    // Look for edges that cross the path from edit-route to handler
    // The edge from edit-route to handler goes from (row=8, col=6) to (row=13, col=8)
    // Check for vertical edges in columns 6-8 between rows 8 and 13
    const vEdgesInPath = [];
    for (let r = editRow; r <= createRow; r++) {
      for (let c = 6; c <= handlerCol; c++) {
        if (matrix.vEdge[r] && matrix.vEdge[r][c]) {
          vEdgesInPath.push({ row: r, col: c });
        }
      }
    }

    // Check for horizontal edges that cross vertical path
    const hEdgesInPath = [];
    for (let r = editRow; r <= createRow; r++) {
      for (let c = 6; c <= handlerCol; c++) {
        if (matrix.hEdge[r] && matrix.hEdge[r][c] !== null) {
          hEdgesInPath.push({ row: r, col: c, argName: matrix.hEdge[r][c] });
        }
      }
    }

    // Find all edges that pass through the area between the two parents
    // These could cause visual crossings
    const edgesInArea = [];
    elements.edges.forEach(e => {
      const srcPos = gridPos.get(e.data.source);
      const tgtPos = gridPos.get(e.data.target);
      if (!srcPos || !tgtPos) return;

      // Check if edge crosses through the area between edit-route and handler
      const minRow = Math.min(srcPos.row, tgtPos.row);
      const maxRow = Math.max(srcPos.row, tgtPos.row);
      const minCol = Math.min(srcPos.col, tgtPos.col);
      const maxCol = Math.max(srcPos.col, tgtPos.col);

      // Edge is in the problematic area if it spans rows between edit and create
      // and is in columns between their column and handler's column
      if (maxRow > editRow && minRow < createRow && 
          maxCol >= 6 && minCol <= handlerCol) {
        edgesInArea.push({
          srcLabel: nodeDataMap.get(e.data.source)?.label?.split('\n')[0],
          srcPos,
          tgtLabel: nodeDataMap.get(e.data.target)?.label?.split('\n')[0],
          tgtPos,
          argName: e.data.argName
        });
      }
    });

    return {
      editPos,
      createPos,
      handlerPos,
      betweenNodes: betweenNodes.sort((a, b) => a.row - b.row || a.col - b.col),
      vEdgesInPath,
      hEdgesInPath,
      edgesInArea,
      validation
    };
  });

  console.log('=== Позиции узлов ===');
  console.log('entity-form-edit-route: row=' + result.editPos?.row + ', col=' + result.editPos?.col);
  console.log('entity-form-create-route: row=' + result.createPos?.row + ', col=' + result.createPos?.col);
  console.log('entity-form-handler: row=' + result.handlerPos?.row + ', col=' + result.handlerPos?.col);

  console.log('\n=== Узлы между edit-route (row 8) и create-route (row 13), cols 6-' + result.handlerPos?.col + ' ===');
  result.betweenNodes.forEach(n => {
    console.log('  row=' + n.row + ', col=' + n.col + ': "' + n.label + '"');
  });

  console.log('\n=== Вертикальные рёбра в области (rows 8-13, cols 6-' + result.handlerPos?.col + ') ===');
  result.vEdgesInPath.forEach(e => {
    console.log('  row=' + e.row + ', col=' + e.col);
  });

  console.log('\n=== Горизонтальные рёбра в области ===');
  result.hEdgesInPath.forEach(e => {
    console.log('  row=' + e.row + ', col=' + e.col + (e.argName ? ' (arg: ' + e.argName + ')' : ''));
  });

  console.log('\n=== Рёбра, пересекающие область ===');
  result.edgesInArea.forEach(e => {
    console.log('  "' + e.srcLabel + '" (row=' + e.srcPos.row + ', col=' + e.srcPos.col + ')');
    console.log('    -> "' + e.tgtLabel + '" (row=' + e.tgtPos.row + ', col=' + e.tgtPos.col + ')');
  });

  console.log('\n=== Валидация лейаута ===');
  console.log('Valid:', result.validation.valid);
  if (result.validation.issues.length > 0) {
    console.log('Issues:');
    result.validation.issues.forEach(i => console.log('  ' + i.type + ': ' + i.message));
  }

  await browser.close();
})();
