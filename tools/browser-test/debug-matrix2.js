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
  
  const result = await page.evaluate(() => {
    const elements = { nodes: cy.nodes().map(n => ({ data: n.data() })), edges: cy.edges().map(e => ({ data: e.data() })) };
    const layout = layoutGraph(elements);
    const { matrix, gridPos, validation } = layout;
    
    // Check rows 18-22
    const rows = {};
    for (let r = 18; r <= 22; r++) {
      rows['row' + r] = [];
      for (let c = 0; c <= 4; c++) {
        const nodeId = matrix.nodeGrid[r] && matrix.nodeGrid[r][c];
        const hasV = matrix.vEdge[r] && matrix.vEdge[r][c];
        const hasH = matrix.hEdge[r] && matrix.hEdge[r][c];
        
        if (nodeId) {
          const node = elements.nodes.find(n => n.data.id === nodeId);
          rows['row' + r].push({ col: c, type: 'NODE', label: node?.data.label?.split('\n')[0]?.substring(0, 20) });
        } else if (hasV && hasH) {
          rows['row' + r].push({ col: c, type: 'V+H' });
        } else if (hasV) {
          rows['row' + r].push({ col: c, type: 'vEdge' });
        } else if (hasH) {
          rows['row' + r].push({ col: c, type: 'hEdge' });
        }
      }
    }
    return { rows, valid: validation.valid, issues: validation.issues };
  });
  
  console.log('Valid:', result.valid);
  if (result.issues.length) console.log('Issues:', result.issues);
  
  for (const [row, items] of Object.entries(result.rows)) {
    console.log(row + ':');
    items.forEach(item => {
      if (item.type === 'NODE') {
        console.log('  col ' + item.col + ': NODE "' + item.label + '"');
      } else {
        console.log('  col ' + item.col + ': ' + item.type);
      }
    });
  }
  
  await browser.close();
})();
