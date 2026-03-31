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
  
  // Get edges TO handler
  const result = await page.evaluate(() => {
    const handler = cy.nodes().filter(n => n.data('label')?.includes('entity-form-handler'))[0];
    const edgesToHandler = cy.edges().filter(e => e.data('target') === handler.data('id'));
    
    return {
      handlerId: handler.data('id'),
      edgesToHandler: edgesToHandler.map(e => {
        const source = cy.nodes().filter(n => n.data('id') === e.data('source'))[0];
        return {
          sourceLabel: source?.data('label')?.split('\n')[0],
          argName: e.data('argName'),
          edgeId: e.data('id')
        };
      })
    };
  });
  
  console.log('Edges TO handler:');
  result.edgesToHandler.forEach(e => {
    console.log('  <- ' + e.sourceLabel + ' (edge: ' + e.edgeId + ', arg: ' + e.argName + ')');
  });
  
  await browser.close();
})();
