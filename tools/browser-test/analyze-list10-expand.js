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

    const { children, parents } = buildAdjacency(elements.edges);
    const sharedInfo = analyzeSharedArguments(children, parents);
    const layout = layoutGraph(elements);
    const { gridPos } = layout;
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Найдём entity-form-handler и его родителей
    let handlerId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('entity-form-handler')) {
        handlerId = n.data.id;
      }
    });

    if (!handlerId) return { error: 'handler not found' };

    const handlerParents = parents.get(handlerId) || [];

    // Trace path from each parent back to find splitting node
    function getAncestors(nodeId, maxDepth = 20) {
      const ancestors = [];
      let current = nodeId;
      const visited = new Set();
      while (current && ancestors.length < maxDepth && !visited.has(current)) {
        visited.add(current);
        ancestors.push(current);
        const nodeParents = parents.get(current) || [];
        current = nodeParents[0] || null;
      }
      return ancestors;
    }

    const ancestorSets = handlerParents.map(pid => getAncestors(pid));

    // Find common ancestor (splitting node)
    let splittingNode = null;
    if (ancestorSets.length >= 2) {
      for (const anc of ancestorSets[0]) {
        if (ancestorSets[1].includes(anc)) {
          splittingNode = anc;
          break;
        }
      }
    }

    // Get path details
    const paths = handlerParents.map((pid, idx) => {
      const ancestors = ancestorSets[idx];
      return ancestors.map(anc => ({
        label: nodeDataMap.get(anc)?.label?.split('\n')[0]?.substring(0, 30),
        pos: gridPos.get(anc)
      }));
    });

    // Find splitting node children that lead to handler
    const splittingChildren = splittingNode ? (children.get(splittingNode) || []) : [];
    const childrenLeadingToHandler = splittingChildren.filter(childId => {
      const paths = sharedInfo.pathsToShared.get(childId);
      return paths && paths.has(handlerId);
    });

    return {
      handlerPos: gridPos.get(handlerId),
      parentPositions: handlerParents.map(pid => ({
        label: nodeDataMap.get(pid)?.label?.split('\n')[0],
        pos: gridPos.get(pid)
      })),
      splittingNode: splittingNode ? {
        label: nodeDataMap.get(splittingNode)?.label?.split('\n')[0],
        pos: gridPos.get(splittingNode)
      } : null,
      paths,
      childrenLeadingToHandler: childrenLeadingToHandler.map(cid => ({
        label: nodeDataMap.get(cid)?.label?.split('\n')[0],
        pathLen: sharedInfo.pathLengths.get(cid + '->' + handlerId)
      }))
    };
  });

  console.log('=== Анализ entity-form-handler при экспанде list-10 ===\n');

  console.log('Handler позиция:', result.handlerPos);

  console.log('\nРодители handler:');
  result.parentPositions.forEach(p => {
    console.log('  "' + p.label + '" at row=' + p.pos?.row + ', col=' + p.pos?.col);
  });

  console.log('\nSplitting node:', result.splittingNode?.label, 'at', result.splittingNode?.pos);

  console.log('\nДети splitting node, ведущие к handler:');
  result.childrenLeadingToHandler.forEach(c => {
    console.log('  "' + c.label + '" pathLen=' + c.pathLen);
  });

  console.log('\nПуть от родителя 1 к splitting:');
  result.paths[0]?.forEach((n, i) => {
    console.log('  ' + i + ': "' + n.label + '" at row=' + n.pos?.row + ', col=' + n.pos?.col);
  });

  console.log('\nПуть от родителя 2 к splitting:');
  result.paths[1]?.forEach((n, i) => {
    console.log('  ' + i + ': "' + n.label + '" at row=' + n.pos?.row + ', col=' + n.pos?.col);
  });

  await browser.close();
})();
