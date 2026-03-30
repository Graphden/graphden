const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));
  
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };
    
    const layout = layoutGraph(elements);
    
    // Build children map
    const childrenMap = new Map();
    cy.edges().forEach(e => {
      const src = e.data('source');
      const tgt = e.data('target');
      if (!childrenMap.has(src)) childrenMap.set(src, []);
      childrenMap.get(src).push(tgt);
    });
    
    const getLabel = (id) => {
      const n = cy.getElementById(id);
      const label = n.data('label');
      return label ? label.split('\n')[0].substring(0, 25) : id.substring(0, 8);
    };
    
    // Find all parents with 2+ children on same row
    const violations = [];
    for (const [parentId, childIds] of childrenMap.entries()) {
      if (childIds.length < 2) continue;
      
      const parentPos = layout.gridPos.get(parentId);
      if (!parentPos) continue;
      
      // Group children by row
      const byRow = {};
      childIds.forEach(cid => {
        const cpos = layout.gridPos.get(cid);
        if (cpos) {
          if (!byRow[cpos.row]) byRow[cpos.row] = [];
          byRow[cpos.row].push({ id: cid, label: getLabel(cid), col: cpos.col });
        }
      });
      
      // Check for rows with 2+ children
      for (const [row, children] of Object.entries(byRow)) {
        if (children.length > 1) {
          violations.push({
            parent: getLabel(parentId),
            parentPos,
            row: parseInt(row),
            children: children.sort((a, b) => a.col - b.col)
          });
        }
      }
    }
    
    return violations;
  });
  
  console.log('Parents with 2+ children on same row:');
  if (result.length === 0) {
    console.log('  None');
  } else {
    result.forEach(v => {
      console.log('  ' + v.parent + ' (row=' + v.parentPos.row + ', col=' + v.parentPos.col + '):');
      console.log('    Row ' + v.row + ': ' + v.children.map(c => c.label + ' (col=' + c.col + ')').join(', '));
    });
  }
  
  await browser.close();
})();
