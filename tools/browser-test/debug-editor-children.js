const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));
  
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));
  
  // EXPAND
  const clickPos = await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (!node) return null;
    const originalFnId = node.data('originalFnId');
    const overlay = document.querySelector(`.node-overlay[data-original-fn-id="${originalFnId}"]`);
    if (!overlay) return null;
    const lines = overlay.querySelectorAll('.ancestor-line');
    let targetLine = null;
    lines.forEach(line => { if (line.textContent === 'get-route') targetLine = line; });
    if (!targetLine) return null;
    const rect = targetLine.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  });
  
  if (clickPos) {
    await page.mouse.click(clickPos.x, clickPos.y);
    await new Promise(r => setTimeout(r, 1000));
  }
  
  const result = await page.evaluate(() => {
    const elements = { nodes: cy.nodes().map(n => ({ data: n.data() })), edges: cy.edges().map(e => ({ data: e.data() })) };
    const { children, parents } = buildAdjacency(elements.edges);
    const sharedInfo = analyzeSharedArguments(children, parents);
    const layout = layoutGraph(elements);
    const { gridPos } = layout;
    
    const root = findRootNode(elements.nodes, elements.edges);
    const rootChildren = children.get(root) || [];
    
    const nodeMap = new Map();
    elements.nodes.forEach(n => nodeMap.set(n.data.id, n.data));
    
    // Filter to last few children (where entity-form nodes are)
    const last10 = rootChildren.slice(-10);
    
    return last10.map(id => {
      const data = nodeMap.get(id);
      const pos = gridPos.get(id);
      const label = data?.label?.split('\n')[0]?.substring(0, 25);
      const paths = sharedInfo.pathsToShared.get(id);
      const leadsToShared = paths && paths.size > 0;
      return { label, row: pos?.row, col: pos?.col, leadsToShared };
    });
  });
  
  console.log('Last 10 children of editor-routes:');
  result.forEach((c, i) => {
    const marker = c.label?.includes('entity-form') ? ' <--' : '';
    const shared = c.leadsToShared ? ' [leads to shared]' : '';
    console.log('  ' + i + ': ' + c.label + ' row=' + c.row + ' col=' + c.col + shared + marker);
  });
  
  await browser.close();
})();
