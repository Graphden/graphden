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

  // Кликаем на корневой узел (editor-routes) БЕЗ экспанда
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
    const sharedInfo = analyzeSharedArguments(children, parents);
    const layout = layoutGraph(elements);
    const { gridPos, validation } = layout;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Find all shared nodes
    const sharedNodes = [];
    sharedInfo.sharedNodes.forEach(sharedId => {
      const sharedParents = parents.get(sharedId) || [];
      const pos = gridPos.get(sharedId);
      sharedNodes.push({
        label: nodeDataMap.get(sharedId)?.label?.split('\n')[0],
        pos,
        parents: sharedParents.map(pid => ({
          label: nodeDataMap.get(pid)?.label?.split('\n')[0],
          pos: gridPos.get(pid)
        }))
      });
    });

    return {
      totalNodes: elements.nodes.length,
      sharedNodes,
      validation
    };
  });

  console.log('=== БЕЗ экспанда ===');
  console.log('Всего узлов:', result.totalNodes);
  console.log('Валидация:', result.validation.valid ? 'OK' : 'FAILED');
  if (!result.validation.valid) {
    result.validation.issues.forEach(i => console.log('  ' + i.type + ': ' + i.message));
  }

  console.log('\n=== Shared arguments ===');
  result.sharedNodes.forEach(s => {
    console.log('\n"' + s.label + '" at row=' + s.pos?.row + ', col=' + s.pos?.col);
    console.log('  Parents:');
    s.parents.forEach(p => {
      console.log('    "' + p.label + '" at row=' + p.pos?.row + ', col=' + p.pos?.col);
    });
    
    // Check if parents are in same column
    const cols = s.parents.map(p => p.pos?.col).filter(c => c !== undefined);
    const sameCol = cols.length > 0 && cols.every(c => c === cols[0]);
    console.log('  Parents same column:', sameCol ? 'YES ✓' : 'NO ✗');
    
    // Check if shared node is on lower parent's row
    const rows = s.parents.map(p => p.pos?.row).filter(r => r !== undefined);
    const maxRow = Math.max(...rows);
    const onLowerRow = s.pos?.row === maxRow;
    console.log('  Shared on lower parent row:', onLowerRow ? 'YES ✓' : 'NO ✗ (should be ' + maxRow + ')');
  });

  await browser.close();
})();
