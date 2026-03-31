const puppeteer = require('puppeteer');

async function checkConfig(page, expandSteps, configName) {
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 5000));

  const cyLoaded = await page.evaluate(() => typeof cy !== 'undefined' && cy !== null);
  if (!cyLoaded) {
    return { config: configName, error: 'Cytoscape not loaded' };
  }

  // Click root node
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 300));

  // Perform expand steps
  for (const step of expandSteps) {
    await page.evaluate((stepName) => {
      const overlays = document.querySelectorAll('.node-overlay');
      for (const overlay of overlays) {
        if (overlay.style.display !== 'none') {
          const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
          for (const line of lines) {
            if (line.textContent.includes(stepName)) {
              line.click();
              return true;
            }
          }
        }
      }
      return false;
    }, step);
    await new Promise(r => setTimeout(r, 800));
  }

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

    const sharedNodes = [];
    sharedInfo.sharedNodes.forEach(sharedId => {
      const sharedParents = parents.get(sharedId) || [];
      const pos = gridPos.get(sharedId);
      
      const parentData = sharedParents.map(pid => ({
        label: nodeDataMap.get(pid)?.label?.split('\n')[0],
        pos: gridPos.get(pid)
      }));
      
      const cols = parentData.map(p => p.pos?.col).filter(c => c !== undefined);
      const sameCol = cols.length > 0 && cols.every(c => c === cols[0]);
      
      const rows = parentData.map(p => p.pos?.row).filter(r => r !== undefined);
      const maxRow = rows.length > 0 ? Math.max(...rows) : -1;
      const onLowerRow = pos?.row === maxRow;
      
      // Check for nodes between parents
      const minRow = rows.length > 0 ? Math.min(...rows) : -1;
      let nodesBetween = 0;
      if (minRow >= 0 && maxRow >= 0 && cols.length > 0) {
        const targetCol = cols[0];
        gridPos.forEach((nodePos, nodeId) => {
          if (nodePos.row > minRow && nodePos.row < maxRow && 
              nodePos.col >= targetCol && nodePos.col <= (pos?.col || targetCol)) {
            nodesBetween++;
          }
        });
      }
      
      sharedNodes.push({
        label: nodeDataMap.get(sharedId)?.label?.split('\n')[0],
        pos,
        parents: parentData,
        sameCol,
        onLowerRow,
        nodesBetween
      });
    });

    return {
      totalNodes: elements.nodes.length,
      sharedNodes,
      valid: validation.valid,
      issues: validation.issues
    };
  });

  return { config: configName, ...result };
}

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);

  const configs = [
    { name: 'No expand', steps: [] },
    { name: 'list-10 expand', steps: ['list-10'] },
    { name: 'list-10 + list-10-9 expand', steps: ['list-10', 'list-10-9'] },
    { name: 'list-10 + list-10-9 + list-10-8 expand', steps: ['list-10', 'list-10-9', 'list-10-8'] },
  ];

  console.log('=== Checking all configurations ===\n');

  for (const config of configs) {
    const result = await checkConfig(page, config.steps, config.name);
    
    console.log('--- ' + result.config + ' ---');
    console.log('Nodes: ' + result.totalNodes + ', Valid: ' + (result.valid ? 'YES' : 'NO'));
    
    if (result.sharedNodes && result.sharedNodes.length > 0) {
      result.sharedNodes.forEach(s => {
        const status = [];
        if (!s.sameCol) status.push('PARENTS NOT SAME COL');
        if (!s.onLowerRow) status.push('NOT ON LOWER ROW');
        if (s.nodesBetween > 0) status.push(s.nodesBetween + ' NODES BETWEEN');
        
        const statusStr = status.length > 0 ? ' ❌ ' + status.join(', ') : ' ✓';
        console.log('  ' + s.label + ': row=' + s.pos?.row + ', col=' + s.pos?.col + statusStr);
        s.parents.forEach(p => {
          console.log('    parent: ' + p.label + ' row=' + p.pos?.row + ', col=' + p.pos?.col);
        });
      });
    } else {
      console.log('  No shared arguments');
    }
    console.log('');
  }

  await browser.close();
})();
