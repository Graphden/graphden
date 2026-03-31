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

  // After expand 1 - check nodes
  const after1 = await page.evaluate(() => {
    const nodeDataMap = new Map();
    cy.nodes().forEach(n => nodeDataMap.set(n.data('id'), n.data()));

    // Find all fn nodes with their labels
    const fnNodes = [];
    cy.nodes().forEach(n => {
      if (n.data('type') === 'fn' && !n.data('isPlaceholder')) {
        fnNodes.push({
          id: n.data('id'),
          label: n.data('label')?.split('\n')[0],
          originalFnId: n.data('originalFnId')
        });
      }
    });

    return fnNodes;
  });

  console.log('=== After expand 1 - fn nodes ===');
  after1.forEach(n => console.log('  ' + n.id + ' -> ' + n.label));

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
    const fnNodes = [];
    cy.nodes().forEach(n => {
      if (n.data('type') === 'fn' && !n.data('isPlaceholder')) {
        fnNodes.push({
          id: n.data('id'),
          label: n.data('label')?.split('\n')[0]
        });
      }
    });
    return fnNodes;
  });

  console.log('\n=== After expand 2 - fn nodes ===');
  after2.forEach(n => console.log('  ' + n.id + ' -> ' + n.label));

  // Check if list-10-8 appears as a separate node
  const list108 = after2.find(n => n.label && n.label.includes('list-10-8'));
  console.log('\nlist-10-8 node:', list108);

  await browser.close();
})();
