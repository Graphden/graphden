const puppeteer = require('puppeteer');

// This script debugs the sortChildrenByPriority function

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Test the sorting function directly
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    // Build nodeDataMap
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => {
      nodeDataMap.set(n.data.id, n.data);
    });

    // Get getNodeType function behavior
    const getNodeTypeResults = [];
    elements.nodes.slice(0, 10).forEach(n => {
      const data = nodeDataMap.get(n.data.id);
      let type = 'free';
      if (data) {
        if (data.isPlaceholder) type = 'free';
        else if (data.type === 'fn') type = 'fn';
        else if (data.type === 'arg') type = 'fixed';
      }
      getNodeTypeResults.push({
        nodeId: n.data.id,
        dataType: data?.type,
        isPlaceholder: data?.isPlaceholder,
        resultType: type
      });
    });

    // Get a node with multiple children and trace sorting
    const { children, parents, edgeArgNames } = buildAdjacency(elements.edges);

    const testCases = [];
    children.forEach((childList, parentId) => {
      if (childList.length > 1 && testCases.length < 3) {
        const beforeSort = [...childList];
        const childTypes = childList.map(cid => {
          const data = nodeDataMap.get(cid);
          if (!data) return 'unknown';
          if (data.isPlaceholder) return 'free';
          if (data.type === 'fn') return 'fn';
          if (data.type === 'arg') return 'fixed';
          return 'free';
        });

        // Call the sorting function
        const sharedInfo = analyzeSharedArguments(children, parents);
        const sorted = sortChildrenByPriority(childList, nodeDataMap, sharedInfo, parentId, edgeArgNames);
        const afterSortTypes = sorted.map(cid => {
          const data = nodeDataMap.get(cid);
          if (!data) return 'unknown';
          if (data.isPlaceholder) return 'free';
          if (data.type === 'fn') return 'fn';
          if (data.type === 'arg') return 'fixed';
          return 'free';
        });

        testCases.push({
          parentId,
          beforeSort,
          beforeTypes: childTypes,
          afterSort: sorted,
          afterTypes: afterSortTypes
        });
      }
    });

    return { getNodeTypeResults, testCases };
  });

  console.log('=== getNodeType Results ===');
  result.getNodeTypeResults.forEach(r => {
    console.log(`${r.nodeId}: dataType=${r.dataType}, isPlaceholder=${r.isPlaceholder} -> ${r.resultType}`);
  });

  console.log('\n=== Sorting Test Cases ===');
  result.testCases.forEach(tc => {
    console.log(`\nParent: ${tc.parentId}`);
    console.log('Before:', tc.beforeTypes.join(', '));
    console.log('After:', tc.afterTypes.join(', '));

    const changedOrder = JSON.stringify(tc.beforeTypes) !== JSON.stringify(tc.afterTypes);
    console.log('Changed:', changedOrder ? 'YES' : 'NO');
  });

  await browser.close();
})();
