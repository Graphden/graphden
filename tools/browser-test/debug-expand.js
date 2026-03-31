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

  // Click on list-10-9
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('list-10-9'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Before any expand - check edges to delete-entity-route
  const before = await page.evaluate(() => {
    // Find delete-entity-route node
    let targetId = null;
    cy.nodes().forEach(n => {
      if (n.data('label')?.includes('delete-entity-route')) {
        targetId = n.data('id');
      }
    });

    if (!targetId) return { found: false };

    const edges = cy.edges().filter(e => e.data('target') === targetId);
    return {
      found: true,
      targetId,
      edges: edges.map(e => ({
        id: e.data('id'),
        source: e.data('source'),
        argName: e.data('argName')
      }))
    };
  });

  console.log('=== Before expand ===');
  console.log('Target:', before.targetId);
  console.log('Edges:', JSON.stringify(before.edges, null, 2));

  // Expand level 1
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

  const after1 = await page.evaluate(() => {
    let targetId = null;
    cy.nodes().forEach(n => {
      if (n.data('label')?.includes('delete-entity-route')) {
        targetId = n.data('id');
      }
    });

    if (!targetId) return { found: false };

    const edges = cy.edges().filter(e => e.data('target') === targetId);
    return {
      found: true,
      targetId,
      edges: edges.map(e => ({
        id: e.data('id'),
        source: e.data('source'),
        argName: e.data('argName')
      }))
    };
  });

  console.log('\n=== After expand 1 ===');
  console.log('Target:', after1.targetId);
  console.log('Edges:', JSON.stringify(after1.edges, null, 2));

  // Expand level 2
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

  const after2 = await page.evaluate(() => {
    let targetId = null;
    cy.nodes().forEach(n => {
      if (n.data('label')?.includes('delete-entity-route')) {
        targetId = n.data('id');
      }
    });

    if (!targetId) return { found: false };

    const edges = cy.edges().filter(e => e.data('target') === targetId);

    // Also check what buildGraphElements returns
    const elements = buildGraphElements();
    const elemEdges = elements.edges.filter(e => e.data.target === targetId);

    return {
      found: true,
      targetId,
      cyEdges: edges.map(e => ({
        id: e.data('id'),
        source: e.data('source'),
        argName: e.data('argName')
      })),
      buildEdges: elemEdges.map(e => ({
        id: e.data.id,
        source: e.data.source,
        argName: e.data.argName
      }))
    };
  });

  console.log('\n=== After expand 2 ===');
  console.log('Target:', after2.targetId);
  console.log('Cytoscape edges:', JSON.stringify(after2.cyEdges, null, 2));
  console.log('buildGraphElements edges:', JSON.stringify(after2.buildEdges, null, 2));

  await browser.close();
})();
