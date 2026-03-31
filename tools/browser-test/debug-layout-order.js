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

  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Sort by row then col
    const positions = [];
    gridPos.forEach((pos, nodeId) => {
      positions.push({
        label: nodeDataMap.get(nodeId)?.label?.split('\n')[0],
        row: pos.row,
        col: pos.col
      });
    });
    positions.sort((a, b) => a.row - b.row || a.col - b.col);

    // Filter to show rows 22-28
    return positions.filter(p => p.row >= 22 && p.row <= 28);
  });

  console.log('=== Nodes at rows 22-28 ===');
  result.forEach(n => {
    console.log('  row=' + n.row + ', col=' + n.col + ': "' + n.label + '"');
  });

  await browser.close();
})();
