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

  // Before expand
  const result1 = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    let targetId = null;
    elements.nodes.forEach(n => {
      if (n.data.label && n.data.label.includes('delete-entity-route')) {
        targetId = n.data.id;
      }
    });

    if (!targetId) {
      return { found: false, message: 'not in initial graph' };
    }

    const incomingEdges = elements.edges.filter(e => e.data.target === targetId);
    return {
      found: true,
      incomingEdgeCount: incomingEdges.length,
      parents: incomingEdges.map(e => nodeDataMap.get(e.data.source)?.label?.split('\n')[0])
    };
  });

  console.log('=== Before expand ===');
  console.log(JSON.stringify(result1, null, 2));

  // Click and expand
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('list-10-9'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Expand 1 level
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

  const result2 = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    let targetId = null;
    elements.nodes.forEach(n => {
      if (n.data.label && n.data.label.includes('delete-entity-route')) {
        targetId = n.data.id;
      }
    });

    if (!targetId) {
      return { found: false };
    }

    const incomingEdges = elements.edges.filter(e => e.data.target === targetId);
    return {
      found: true,
      incomingEdgeCount: incomingEdges.length,
      parents: incomingEdges.map(e => nodeDataMap.get(e.data.source)?.label?.split('\n')[0])
    };
  });

  console.log('\n=== After 1 expand ===');
  console.log(JSON.stringify(result2, null, 2));

  await browser.close();
})();
