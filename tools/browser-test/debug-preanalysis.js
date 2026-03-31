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

  // Кликаем на корневой узел (editor-routes)
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Экспандим list-10
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

    const { children, parents, edgeArgNames } = buildAdjacency(elements.edges);
    const sharedInfo = analyzeSharedArguments(children, parents);

    // Debug pre-analysis
    const splittingDecisions = new Map();
    const parentTargetCol = new Map();
    const sharedParentTargetCol = new Map();

    function findSplittingInfoLocal(nodeId, childIds, sharedId, pathsToShared, pathLengths) {
      const leadingChildren = childIds.filter(childId => {
        const paths = pathsToShared.get(childId);
        return paths && paths.has(sharedId);
      });

      if (leadingChildren.length < 2) return null;

      let maxPathLen = -1;
      let lowerChildIdx = -1;

      leadingChildren.forEach((childId, idx) => {
        const dist = pathLengths.get(childId + '->' + sharedId) || 0;
        if (dist >= maxPathLen) {
          maxPathLen = dist;
          lowerChildIdx = idx;
        }
      });

      const lowerChild = leadingChildren[lowerChildIdx];
      const upperChildren = leadingChildren.filter((_, idx) => idx !== lowerChildIdx);

      return {
        sharedId,
        lowerChild,
        upperChildren
      };
    }

    function preAnalyzeTree(nodeId, currentCol, visited) {
      if (visited.has(nodeId)) return;
      visited.add(nodeId);

      const nodeChildren = children.get(nodeId) || [];
      if (nodeChildren.length === 0) return;

      sharedInfo.sharedNodes.forEach(sharedId => {
        const info = findSplittingInfoLocal(nodeId, nodeChildren, sharedId, sharedInfo.pathsToShared, sharedInfo.pathLengths);
        if (info && !splittingDecisions.has(info.sharedId)) {
          splittingDecisions.set(info.sharedId, info);

          const sharedParents = parents.get(sharedId) || [];
          let maxDistToParent = 0;

          for (const childId of nodeChildren) {
            const paths = sharedInfo.pathsToShared.get(childId);
            if (paths && paths.has(sharedId)) {
              const distToShared = sharedInfo.pathLengths.get(childId + '->' + sharedId) || 0;
              const distToParent = Math.max(0, distToShared - 1);
              maxDistToParent = Math.max(maxDistToParent, distToParent);
            }
          }

          const targetCol = currentCol + 1 + maxDistToParent;
          sharedParentTargetCol.set(sharedId, {
            targetCol,
            splittingNodeId: nodeId,
            splittingCol: currentCol
          });

          for (const parentId of sharedParents) {
            if (!parentTargetCol.has(parentId)) {
              parentTargetCol.set(parentId, targetCol);
            }
          }
        }
      });

      let childCol = currentCol + 1;
      for (const childId of nodeChildren) {
        const targetCol = parentTargetCol.get(childId);
        const actualCol = (targetCol !== undefined && targetCol > childCol) ? targetCol : childCol;
        preAnalyzeTree(childId, actualCol, visited);
      }
    }

    // Find root
    const hasIncoming = new Set();
    elements.edges.forEach(e => hasIncoming.add(e.data.target));
    const root = elements.nodes.find(n => !hasIncoming.has(n.data.id));
    const rootId = root ? root.data.id : null;

    if (rootId) {
      preAnalyzeTree(rootId, 0, new Set());
    }

    // Get node labels for IDs
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Output results
    const splittingResults = [];
    splittingDecisions.forEach((info, sharedId) => {
      splittingResults.push({
        sharedLabel: nodeDataMap.get(sharedId)?.label?.split('\n')[0],
        splittingNodeId: info.splittingNodeId,
        splittingLabel: nodeDataMap.get(sharedParentTargetCol.get(sharedId)?.splittingNodeId)?.label?.split('\n')[0],
        lowerChildLabel: nodeDataMap.get(info.lowerChild)?.label?.split('\n')[0],
        upperChildrenLabels: info.upperChildren.map(c => nodeDataMap.get(c)?.label?.split('\n')[0]),
        targetCol: sharedParentTargetCol.get(sharedId)?.targetCol
      });
    });

    const parentTargets = [];
    parentTargetCol.forEach((col, nodeId) => {
      parentTargets.push({
        label: nodeDataMap.get(nodeId)?.label?.split('\n')[0],
        targetCol: col
      });
    });

    return {
      rootId,
      rootLabel: nodeDataMap.get(rootId)?.label?.split('\n')[0],
      sharedNodesCount: sharedInfo.sharedNodes.size,
      splittingResults,
      parentTargets
    };
  });

  console.log('Root:', result.rootLabel);
  console.log('Shared nodes count:', result.sharedNodesCount);
  console.log('\nSplitting decisions:');
  result.splittingResults.forEach(s => {
    console.log('  Shared:', s.sharedLabel);
    console.log('    Splitting at:', s.splittingLabel, '(col assumed:', s.targetCol - 1, ')');
    console.log('    Lower child:', s.lowerChildLabel);
    console.log('    Upper children:', s.upperChildrenLabels.join(', '));
    console.log('    Parents target col:', s.targetCol);
  });
  console.log('\nParent target columns:');
  result.parentTargets.forEach(p => {
    console.log('  "' + p.label + '" -> col=' + p.targetCol);
  });

  await browser.close();
})();
