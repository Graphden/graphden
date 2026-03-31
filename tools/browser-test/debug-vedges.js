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

  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);
    const { gridPos, matrix } = layout;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Find nodes at col 7
    const nodesAtCol7 = [];
    gridPos.forEach((pos, nodeId) => {
      if (pos.col === 7) {
        nodesAtCol7.push({
          row: pos.row,
          label: nodeDataMap.get(nodeId)?.label?.split('\n')[0],
          nodeId
        });
      }
    });
    nodesAtCol7.sort((a, b) => a.row - b.row);

    // Find vertical edges at col 7
    const vEdgesCol7 = [];
    for (let r = 0; r < matrix.vEdge.length; r++) {
      if (matrix.vEdge[r] && matrix.vEdge[r][7]) {
        vEdgesCol7.push(r);
      }
    }

    // Find edges where target is at col 8 (to understand what causes vEdges at col 7)
    const { children, parents } = buildAdjacency(elements.edges);
    const edgesToCol8 = [];
    gridPos.forEach((pos, nodeId) => {
      if (pos.col === 8) {
        const nodeParents = parents.get(nodeId) || [];
        nodeParents.forEach(pid => {
          const parentPos = gridPos.get(pid);
          edgesToCol8.push({
            parentLabel: nodeDataMap.get(pid)?.label?.split('\n')[0],
            parentPos,
            childLabel: nodeDataMap.get(nodeId)?.label?.split('\n')[0],
            childPos: pos
          });
        });
      }
    });

    // Check the vertical edge at row 13, col 7 - trace where it comes from
    // A vertical edge at (r, c) means an edge goes down through that cell
    // Need to find which parent-child pair causes this

    return {
      nodesAtCol7,
      vEdgesCol7,
      edgesToCol8
    };
  });

  console.log('Nodes at col 7:');
  result.nodesAtCol7.forEach(n => console.log('  row ' + n.row + ': ' + n.label));

  console.log('\nVertical edges at col 7 (rows):');
  console.log('  ' + result.vEdgesCol7.join(', '));

  console.log('\nEdges going to col 8 nodes:');
  result.edgesToCol8.forEach(e => {
    console.log('  ' + e.parentLabel + ' (row=' + e.parentPos?.row + ', col=' + e.parentPos?.col + ')');
    console.log('    -> ' + e.childLabel + ' (row=' + e.childPos?.row + ', col=' + e.childPos?.col + ')');
  });

  await browser.close();
})();
