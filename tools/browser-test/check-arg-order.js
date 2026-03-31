const puppeteer = require('puppeteer');

// This script checks the order of arguments for expanded nodes

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Click on entity-form-create-route and expand
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

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

  // Get argument order for all fn nodes
  const result = await page.evaluate(() => {
    const argOrders = [];

    // For each fn node, get the edges (children) and their types
    cy.nodes().forEach(node => {
      const nodeId = node.id();
      const nodeLabel = node.data('label')?.split('\n')[0] || nodeId;
      const nodeType = node.data('type');

      if (nodeType !== 'fn') return;

      const outEdges = node.outgoers('edge');
      if (outEdges.length === 0) return;

      const children = [];
      outEdges.forEach(edge => {
        const targetNode = edge.target();
        const targetType = targetNode.data('type');
        const isPlaceholder = targetNode.data('isPlaceholder');
        const argName = edge.data('argName') || '?';
        const targetLabel = targetNode.data('label')?.split('\n')[0]?.substring(0, 20) || targetNode.id();

        let childType = 'unknown';
        if (targetType === 'fn' && !isPlaceholder) childType = 'fn';
        else if (isPlaceholder) childType = 'free';
        else if (targetType === 'arg') childType = 'fixed';

        children.push({
          argName,
          childType,
          targetLabel
        });
      });

      if (children.length > 1) {
        argOrders.push({
          nodeLabel,
          children
        });
      }
    });

    return argOrders;
  });

  console.log('=== Argument Order Analysis ===');
  result.forEach(item => {
    console.log(`\n${item.nodeLabel}:`);
    item.children.forEach((child, idx) => {
      console.log(`  ${idx + 1}. [${child.childType}] ${child.argName} -> "${child.targetLabel}"`);
    });

    // Check if order is correct: fn should come before fixed/free
    const fnIndices = item.children.map((c, i) => c.childType === 'fn' ? i : -1).filter(i => i >= 0);
    const fixedIndices = item.children.map((c, i) => c.childType === 'fixed' ? i : -1).filter(i => i >= 0);
    const freeIndices = item.children.map((c, i) => c.childType === 'free' ? i : -1).filter(i => i >= 0);

    const maxFnIdx = Math.max(...fnIndices, -1);
    const minFixedIdx = Math.min(...fixedIndices, Infinity);
    const minFreeIdx = Math.min(...freeIndices, Infinity);

    if (maxFnIdx >= 0 && minFixedIdx < Infinity && maxFnIdx > minFixedIdx) {
      console.log(`  ⚠️ ORDER ISSUE: fn at ${maxFnIdx} comes after fixed at ${minFixedIdx}`);
    }
    if (maxFnIdx >= 0 && minFreeIdx < Infinity && maxFnIdx > minFreeIdx) {
      console.log(`  ⚠️ ORDER ISSUE: fn at ${maxFnIdx} comes after free at ${minFreeIdx}`);
    }
  });

  await browser.close();
})();
