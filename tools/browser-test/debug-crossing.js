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
    const layout = layoutGraph(elements);
    const { matrix, gridPos } = layout;
    
    // Check rows 19-23 around the crossing
    const rows = {};
    for (let r = 19; r <= 23; r++) {
      rows['row' + r] = [];
      for (let c = 0; c <= 5; c++) {
        const nodeId = matrix.nodeGrid[r] && matrix.nodeGrid[r][c];
        const hasV = matrix.vEdge[r] && matrix.vEdge[r][c];
        const hasH = matrix.hEdge[r] && matrix.hEdge[r][c];
        
        let content = '';
        if (nodeId) {
          const node = elements.nodes.find(n => n.data.id === nodeId);
          content = 'NODE:' + node?.data.label?.split('\n')[0]?.substring(0, 12);
        }
        if (hasV) content += (content ? '+' : '') + 'V';
        if (hasH) content += (content ? '+' : '') + 'H';
        
        if (content) {
          rows['row' + r].push({ col: c, content });
        }
      }
    }
    return rows;
  });
  
  for (const [row, items] of Object.entries(result)) {
    console.log(row + ':');
    items.forEach(item => {
      console.log('  col ' + item.col + ': ' + item.content);
    });
  }
  
  await browser.close();
})();
