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
    await new Promise(r => setTimeout(r, 1500));
  }
  
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (node) {
      cy.zoom(1.5);
      cy.center(node);
    }
  });
  await new Promise(r => setTimeout(r, 500));
  
  await page.screenshot({ path: '/tmp/expand-zoomed.png', fullPage: false });
  console.log('Screenshot saved');
  await browser.close();
})();
