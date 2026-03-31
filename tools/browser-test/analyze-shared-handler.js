const puppeteer = require('puppeteer');

// Analyze entity-form-handler shared node positioning

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

    // Find entity-form-handler
    let handlerId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('entity-form-handler')) {
        handlerId = n.data.id;
      }
    });

    if (!handlerId) return { error: 'Handler not found' };

    const handlerPos = gridPos.get(handlerId);
    const handlerParents = parents.get(handlerId) || [];

    // Get parent positions and details
    const parentDetails = handlerParents.map(pid => {
      const pos = gridPos.get(pid);
      const data = nodeDataMap.get(pid);
      return {
        id: pid,
        label: data?.label?.split('\n')[0],
        pos
      };
    });

    // Find the splitting node (common ancestor of both parents)
    // Trace up from each parent to find where they diverge
    function getAncestors(nodeId) {
      const ancestors = [nodeId];
      let current = nodeId;
      while (true) {
        const nodeParents = parents.get(current) || [];
        if (nodeParents.length === 0) break;
        current = nodeParents[0]; // Follow first parent
        ancestors.push(current);
      }
      return ancestors;
    }

    const parent1Ancestors = getAncestors(handlerParents[0]);
    const parent2Ancestors = getAncestors(handlerParents[1]);

    // Find common ancestor
    let splittingNode = null;
    for (const anc of parent1Ancestors) {
      if (parent2Ancestors.includes(anc)) {
        splittingNode = anc;
        break;
      }
    }

    const splittingPos = splittingNode ? gridPos.get(splittingNode) : null;
    const splittingLabel = splittingNode ? nodeDataMap.get(splittingNode)?.label?.split('\n')[0] : null;

    // Determine which parent is "lower" based on path length
    const sharedInfo = analyzeSharedArguments(children, parents);
    let lowerParent = null;
    let maxPathLen = -1;
    for (const pid of handlerParents) {
      const pathKey = pid + '->' + handlerId;
      const pathLen = sharedInfo.pathLengths.get(pathKey) || 1;
      if (pathLen >= maxPathLen) {
        maxPathLen = pathLen;
        lowerParent = pid;
      }
    }

    const lowerParentPos = lowerParent ? gridPos.get(lowerParent) : null;
    const lowerParentLabel = lowerParent ? nodeDataMap.get(lowerParent)?.label?.split('\n')[0] : null;

    return {
      handler: {
        id: handlerId,
        pos: handlerPos
      },
      parents: parentDetails,
      splittingNode: {
        id: splittingNode,
        label: splittingLabel,
        pos: splittingPos
      },
      lowerParent: {
        id: lowerParent,
        label: lowerParentLabel,
        pos: lowerParentPos
      }
    };
  });

  console.log('=== entity-form-handler Analysis ===\n');
  console.log('Handler position:', result.handler?.pos);
  console.log('\nParents:');
  result.parents?.forEach(p => {
    console.log(`  "${p.label}" at (${p.pos?.row}, ${p.pos?.col})`);
  });
  console.log('\nSplitting node:', result.splittingNode?.label, 'at', result.splittingNode?.pos);
  console.log('Lower parent:', result.lowerParent?.label, 'at', result.lowerParent?.pos);

  console.log('\n=== Expected Behavior ===');
  console.log('1. Both parents should be in the SAME COLUMN');
  console.log('2. Handler should be on the SAME ROW as lower parent');
  console.log('3. Handler should be at column = max(parent columns) + 1');

  const parent1Col = result.parents?.[0]?.pos?.col;
  const parent2Col = result.parents?.[1]?.pos?.col;
  const handlerRow = result.handler?.pos?.row;
  const lowerParentRow = result.lowerParent?.pos?.row;

  console.log('\n=== Issues ===');
  if (parent1Col !== parent2Col) {
    console.log(`❌ Parents NOT in same column: ${parent1Col} vs ${parent2Col}`);
  } else {
    console.log('✓ Parents in same column');
  }

  if (handlerRow !== lowerParentRow) {
    console.log(`❌ Handler NOT on same row as lower parent: ${handlerRow} vs ${lowerParentRow}`);
  } else {
    console.log('✓ Handler on same row as lower parent');
  }

  await browser.close();
})();
