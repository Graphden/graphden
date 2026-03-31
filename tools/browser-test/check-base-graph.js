const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  await page.screenshot({ path: '/tmp/base-graph.png' });
  
  // Also check entity-form-edit-route children layout
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };
    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    // Find entity-form-edit-route
    const editRoute = cy.nodes().filter(n => n.data('label')?.includes('entity-form-edit-route'))[0];
    if (!editRoute) return { error: 'entity-form-edit-route not found' };
    
    // Build children map
    const children = new Map();
    elements.edges.forEach(e => {
      const src = e.data.source;
      const tgt = e.data.target;
      if (!children.has(src)) children.set(src, []);
      children.get(src).push(tgt);
    });

    const editId = editRoute.id();
    const editPos = gridPos.get(editId);
    const editChildren = children.get(editId) || [];
    
    return {
      editRoute: {
        pos: editPos,
        children: editChildren.map(cid => ({
          label: cy.$(`#${cid}`).data('label')?.split('\n')[0]?.substring(0, 30),
          pos: gridPos.get(cid)
        }))
      }
    };
  });

  console.log('entity-form-edit-route:', result.editRoute?.pos);
  console.log('Children:');
  result.editRoute?.children?.forEach(c => {
    console.log(`  - ${c.label}: row=${c.pos?.row}, col=${c.pos?.col}`);
  });

  await browser.close();
})();
