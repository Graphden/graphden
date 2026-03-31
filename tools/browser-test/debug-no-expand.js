const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  await page.screenshot({ path: '/tmp/no-expand.png', fullPage: true });

  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const { children, parents } = buildAdjacency(elements.edges);
    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Find nodes at rows 24-26, cols 1-3
    const nodesInArea = [];
    gridPos.forEach((pos, nodeId) => {
      if (pos.row >= 24 && pos.row <= 26 && pos.col >= 1 && pos.col <= 3) {
        nodesInArea.push({
          label: nodeDataMap.get(nodeId)?.label?.split('\n')[0],
          row: pos.row,
          col: pos.col
        });
      }
    });

    // Find handler and its parents
    let handlerId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('entity-form-handler')) {
        handlerId = n.data.id;
      }
    });

    const handlerParents = parents.get(handlerId) || [];
    const parentInfo = handlerParents.map(pid => ({
      label: nodeDataMap.get(pid)?.label?.split('\n')[0],
      pos: gridPos.get(pid),
      children: (children.get(pid) || []).map(cid => ({
        label: nodeDataMap.get(cid)?.label?.split('\n')[0],
        pos: gridPos.get(cid)
      }))
    }));

    return {
      nodesInArea: nodesInArea.sort((a, b) => a.row - b.row || a.col - b.col),
      handlerPos: gridPos.get(handlerId),
      parentInfo
    };
  });

  console.log('=== Handler position ===');
  console.log('row=' + result.handlerPos?.row + ', col=' + result.handlerPos?.col);

  console.log('\n=== Nodes in area (rows 24-26, cols 1-3) ===');
  result.nodesInArea.forEach(n => {
    console.log('  row=' + n.row + ', col=' + n.col + ': "' + n.label + '"');
  });

  console.log('\n=== Handler parents and their children ===');
  result.parentInfo.forEach(p => {
    console.log('\n"' + p.label + '" at row=' + p.pos?.row + ', col=' + p.pos?.col);
    console.log('  Children:');
    p.children.forEach(c => {
      console.log('    "' + c.label + '" at row=' + c.pos?.row + ', col=' + c.pos?.col);
    });
  });

  await browser.close();
})();
