const puppeteer = require('puppeteer');

// This script traces the sorting during layout

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  const result = await page.evaluate(() => {
    // Monkey-patch sortChildrenByPriority to trace calls
    const originalSort = window.sortChildrenByPriority || sortChildrenByPriority;
    const sortLog = [];

    // Override the global function
    sortChildrenByPriority = function(childIds, nodeDataMap, sharedInfo, currentNodeId, edgeArgNames) {
      const result = originalSort(childIds, nodeDataMap, sharedInfo, currentNodeId, edgeArgNames);

      // Log the call
      const parentLabel = nodeDataMap.get(currentNodeId)?.label?.split('\n')[0] || currentNodeId;
      const beforeTypes = childIds.map(cid => {
        const data = nodeDataMap.get(cid);
        if (!data) return 'unknown';
        if (data.isPlaceholder) return 'free';
        if (data.type === 'fn') return 'fn';
        if (data.type === 'arg') return 'fixed';
        return 'free';
      });
      const afterTypes = result.map(cid => {
        const data = nodeDataMap.get(cid);
        if (!data) return 'unknown';
        if (data.isPlaceholder) return 'free';
        if (data.type === 'fn') return 'fn';
        if (data.type === 'arg') return 'fixed';
        return 'free';
      });

      if (childIds.length > 1 && JSON.stringify(beforeTypes) !== JSON.stringify(afterTypes)) {
        sortLog.push({
          parent: parentLabel,
          before: beforeTypes,
          after: afterTypes
        });
      }

      return result;
    };

    // Run layout
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };
    const layout = layoutGraph(elements);

    return { sortLog, gridPosSize: layout.gridPos.size };
  });

  console.log('=== Sort Trace ===');
  console.log(`Grid positions: ${result.gridPosSize}`);
  console.log(`Sort calls that changed order: ${result.sortLog.length}`);

  result.sortLog.forEach(log => {
    console.log(`\n${log.parent}:`);
    console.log(`  Before: [${log.before.join(', ')}]`);
    console.log(`  After:  [${log.after.join(', ')}]`);
  });

  await browser.close();
})();
