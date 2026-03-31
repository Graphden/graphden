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
  
  // Check Cytoscape edges
  const result = await page.evaluate(() => {
    const createRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    const editRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-edit-route'))[0];
    const handler = cy.nodes().filter(n => n.data('label')?.includes('entity-form-handler'))[0];
    
    // Get edges from create-route
    const createEdges = cy.edges().filter(e => e.data('source') === createRoute.data('id'));
    
    // Get edges from edit-route
    const editEdges = cy.edges().filter(e => e.data('source') === editRoute.data('id'));
    
    return {
      createRouteEdges: createEdges.map(e => ({
        target: cy.nodes().filter(n => n.data('id') === e.data('target'))[0]?.data('label')?.split('\n')[0]?.substring(0, 25),
        argName: e.data('argName')
      })),
      editRouteEdges: editEdges.map(e => ({
        target: cy.nodes().filter(n => n.data('id') === e.data('target'))[0]?.data('label')?.split('\n')[0]?.substring(0, 25),
        argName: e.data('argName')
      }))
    };
  });
  
  console.log('Edges FROM create-route:');
  result.createRouteEdges.forEach(e => console.log('  -> ' + e.target + ' (' + e.argName + ')'));
  console.log('\nEdges FROM edit-route:');
  result.editRouteEdges.forEach(e => console.log('  -> ' + e.target + ' (' + e.argName + ')'));
  
  await browser.close();
})();
