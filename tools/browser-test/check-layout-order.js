const puppeteer = require('puppeteer');

// This script checks the ACTUAL LAYOUT order (by row position) not edge order

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Get layout positions and check order
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    // Build nodeDataMap
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => {
      nodeDataMap.set(n.data.id, n.data);
    });

    // Build children map
    const children = new Map();
    elements.edges.forEach(e => {
      const src = e.data.source;
      const tgt = e.data.target;
      if (!children.has(src)) children.set(src, []);
      children.get(src).push(tgt);
    });

    const layoutOrders = [];
    children.forEach((childList, parentId) => {
      if (childList.length < 2) return;

      const parentData = nodeDataMap.get(parentId);
      if (!parentData || parentData.type !== 'fn') return;

      // Get children with their layout row positions
      const childrenWithPos = childList.map(childId => {
        const data = nodeDataMap.get(childId);
        const pos = gridPos.get(childId);
        let type = 'free';
        if (data) {
          if (data.isPlaceholder) type = 'free';
          else if (data.type === 'fn') type = 'fn';
          else if (data.type === 'arg') type = 'fixed';
        }
        return {
          childId,
          type,
          row: pos ? pos.row : 999,
          label: data?.label?.split('\n')[0]?.substring(0, 20) || childId
        };
      });

      // Sort by row
      childrenWithPos.sort((a, b) => a.row - b.row);

      layoutOrders.push({
        parentLabel: parentData.label?.split('\n')[0] || parentId,
        children: childrenWithPos
      });
    });

    return layoutOrders;
  });

  console.log('=== Layout Order Analysis (by row) ===');
  let issueCount = 0;
  result.forEach(item => {
    // Check if fn types come before fixed/free by row
    const fnRows = item.children.filter(c => c.type === 'fn').map(c => c.row);
    const fixedRows = item.children.filter(c => c.type === 'fixed').map(c => c.row);
    const freeRows = item.children.filter(c => c.type === 'free').map(c => c.row);

    const maxFnRow = Math.max(...fnRows, -1);
    const minFixedRow = Math.min(...fixedRows, Infinity);
    const minFreeRow = Math.min(...freeRows, Infinity);

    const hasIssue = (maxFnRow >= 0 && minFixedRow < Infinity && maxFnRow > minFixedRow) ||
                     (maxFnRow >= 0 && minFreeRow < Infinity && maxFnRow > minFreeRow);

    if (hasIssue) {
      issueCount++;
      console.log(`\n${item.parentLabel}:`);
      item.children.forEach((child, idx) => {
        console.log(`  row ${child.row}: [${child.type}] -> "${child.label}"`);
      });
      console.log(`  ⚠️ ORDER ISSUE`);
    }
  });

  console.log(`\n=== Summary ===`);
  console.log(`Found ${issueCount} nodes with incorrect layout order`);
  console.log(`Total nodes checked: ${result.length}`);

  await browser.close();
})();
