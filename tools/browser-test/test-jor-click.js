const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1600, height: 1000 } })).newPage();
  await page.goto('http://localhost:9002/#json-ok-response', { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);

  const before = await page.evaluate(() => ({
    nodes: cy.nodes().length,
    edges: cy.edges().length,
    state: Array.from(expansionState.entries()).map(([k, v]) =>
      ({ k, full: v.fullDepth, partial: Array.from(v.partialFns) }))
  }));
  console.log('BEFORE click:', JSON.stringify(before));

  // Click json-content-type
  await page.evaluate(() => {
    const overlays = document.querySelectorAll('.node-overlay[data-original-fn-id]');
    for (const o of overlays) {
      const firstLine = o.querySelector('.ancestor-line');
      if (firstLine && firstLine.textContent.trim() === 'json-ok-response') {
        const lines = o.querySelectorAll('.ancestor-line');
        for (const line of lines) {
          const spans = line.querySelectorAll('span');
          for (const s of spans) {
            if (s.textContent.trim() === 'json-content-type') {
              s.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
              return;
            }
          }
        }
      }
    }
  });
  await page.waitForTimeout(800);

  const after = await page.evaluate(() => ({
    nodes: cy.nodes().length,
    edges: cy.edges().length,
    state: Array.from(expansionState.entries()).map(([k, v]) =>
      ({ k, full: v.fullDepth, partial: Array.from(v.partialFns) }))
  }));
  console.log('AFTER click:', JSON.stringify(after));

  console.log('\nDelta nodes:', after.nodes - before.nodes, 'edges:', after.edges - before.edges);
  await browser.close();
})();
