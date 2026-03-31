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

  // BEFORE expand 2: call buildGraphElements manually
  const before2 = await page.evaluate(() => {
    const elements = buildGraphElements();

    // Find delete-entity-route node
    let targetId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('delete-entity-route')) {
        targetId = n.data.id;
      }
    });

    const edges = elements.edges.filter(e => e.data.target === targetId);
    return {
      targetId,
      edgeCount: edges.length,
      edges: edges.map(e => ({ id: e.data.id, source: e.data.source }))
    };
  });

  console.log('=== buildGraphElements BEFORE expand 2 ===');
  console.log('Target:', before2.targetId);
  console.log('Edges:', before2.edgeCount);
  before2.edges.forEach(e => console.log('  ' + e.id));

  // Now simulate what happens during expand 2
  // Get expansion state before clicking
  const expansionBefore = await page.evaluate(() => {
    return {
      expansionLevel: Array.from(expansionLevel.entries()),
      previewLevel: Array.from(previewLevel.entries())
    };
  });
  console.log('\nExpansion state before expand 2:', expansionBefore);

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
            console.log('Clicking on level', level, 'line');
            line.click();
            return;
          }
        }
      }
    }
  });
  await new Promise(r => setTimeout(r, 100)); // Short wait to let state update

  // Check expansion state after click, before render completes
  const expansionAfter = await page.evaluate(() => {
    return {
      expansionLevel: Array.from(expansionLevel.entries()),
      previewLevel: Array.from(previewLevel.entries())
    };
  });
  console.log('\nExpansion state after expand 2:', expansionAfter);

  // Call buildGraphElements again
  const after2 = await page.evaluate(() => {
    const elements = buildGraphElements();

    let targetId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('delete-entity-route')) {
        targetId = n.data.id;
      }
    });

    const edges = elements.edges.filter(e => e.data.target === targetId);
    return {
      targetId,
      edgeCount: edges.length,
      edges: edges.map(e => ({ id: e.data.id, source: e.data.source }))
    };
  });

  console.log('\n=== buildGraphElements AFTER expand 2 ===');
  console.log('Target:', after2.targetId);
  console.log('Edges:', after2.edgeCount);
  after2.edges.forEach(e => console.log('  ' + e.id));

  await browser.close();
})();
