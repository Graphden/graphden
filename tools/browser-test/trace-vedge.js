const puppeteer = require('puppeteer');

// Trace where vEdges come from

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

  // Find all edges that could create vEdges at column 4
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const { children, parents, edgeArgNames } = buildAdjacency(elements.edges);
    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Find all edges where the vertical portion would pass through column 4
    const edgesAffectingCol4 = [];

    elements.edges.forEach(e => {
      const srcId = e.data.source;
      const tgtId = e.data.target;
      const srcPos = gridPos.get(srcId);
      const tgtPos = gridPos.get(tgtId);

      if (!srcPos || !tgtPos) return;

      // Check if this edge creates vertical path through column 4
      // Edge from (srcRow, srcCol) to (tgtRow, tgtCol)
      // If tgtRow > srcRow and either srcCol or tgtCol is near 4
      if (tgtPos.row > srcPos.row) {
        const srcData = nodeDataMap.get(srcId);
        const tgtData = nodeDataMap.get(tgtId);

        // Check if vertical path through srcCol or tgtCol would pass column 4
        // For L-shaped edges: vertical goes through either srcCol or tgtCol
        const possibleVCols = [srcPos.col, tgtPos.col];

        if (possibleVCols.includes(4) ||
            (srcPos.col < 4 && tgtPos.col >= 4) ||
            (srcPos.col <= 4 && tgtPos.col > 4)) {
          edgesAffectingCol4.push({
            from: srcData?.label?.split('\n')[0],
            fromPos: srcPos,
            to: tgtData?.label?.split('\n')[0],
            toPos: tgtPos,
            argName: e.data.argName
          });
        }
      }
    });

    return edgesAffectingCol4;
  });

  console.log('=== Edges that might affect column 4 ===');
  result.forEach(e => {
    console.log(`"${e.from}" (${e.fromPos.row},${e.fromPos.col}) -> "${e.to}" (${e.toPos.row},${e.toPos.col}) [${e.argName}]`);
  });

  await browser.close();
})();
