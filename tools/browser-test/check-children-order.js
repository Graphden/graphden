const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  const result = await page.evaluate(() => {
    // Find entity-form-edit-route
    const editRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-edit-route'))[0];
    if (!editRoute) return { error: 'entity-form-edit-route not found' };
    const editId = editRoute.id();

    // Get edges FROM entity-form-edit-route in ORDER they appear
    const outEdges = cy.edges().filter(e => e.data('source') === editId);
    const edgesInfo = outEdges.map(e => ({
      target: e.data('target'),
      targetLabel: cy.$(`#${e.data('target')}`).data('label')?.split('\n')[0]?.substring(0, 30),
      argName: e.data('argName')
    }));

    // Also find entity-form-create-route for comparison
    const createRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    let createEdgesInfo = [];
    if (createRoute) {
      const createId = createRoute.id();
      const createOutEdges = cy.edges().filter(e => e.data('source') === createId);
      createEdgesInfo = createOutEdges.map(e => ({
        target: e.data('target'),
        targetLabel: cy.$(`#${e.data('target')}`).data('label')?.split('\n')[0]?.substring(0, 30),
        argName: e.data('argName')
      }));
    }

    // Check parents of entity-form-handler
    const handlerNode = cy.nodes().filter(n => n.data('label')?.includes('entity-form-handler'))[0];
    let handlerParents = [];
    if (handlerNode) {
      const handlerId = handlerNode.id();
      const inEdges = cy.edges().filter(e => e.data('target') === handlerId);
      handlerParents = inEdges.map(e => ({
        source: e.data('source'),
        sourceLabel: cy.$(`#${e.data('source')}`).data('label')?.split('\n')[0]?.substring(0, 30)
      }));
    }

    return {
      editRouteEdges: edgesInfo,
      createRouteEdges: createEdgesInfo,
      handlerParents
    };
  });

  console.log('entity-form-edit-route edges (in order):');
  result.editRouteEdges?.forEach((e, i) => {
    console.log(`  ${i}: -> ${e.targetLabel} (argName: ${e.argName})`);
  });

  console.log('\nentity-form-create-route edges (in order):');
  result.createRouteEdges?.forEach((e, i) => {
    console.log(`  ${i}: -> ${e.targetLabel} (argName: ${e.argName})`);
  });

  console.log('\nentity-form-handler parents:');
  result.handlerParents?.forEach(p => {
    console.log(`  <- ${p.sourceLabel}`);
  });

  await browser.close();
})();
