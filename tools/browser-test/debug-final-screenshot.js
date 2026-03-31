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
  
  // Fit to entity-form nodes WITHOUT expand
  await page.evaluate(() => {
    const createRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    const editRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-edit-route'))[0];
    const handler = cy.nodes().filter(n => n.data('label')?.includes('entity-form-handler'))[0];
    
    if (createRoute && editRoute && handler) {
      const collection = cy.collection([createRoute, editRoute, handler]);
      cy.fit(collection, 80);
    }
  });
  await new Promise(r => setTimeout(r, 500));
  
  await page.screenshot({ path: '/tmp/no-expand.png', fullPage: false });
  console.log('Screenshot saved');
  await browser.close();
})();
