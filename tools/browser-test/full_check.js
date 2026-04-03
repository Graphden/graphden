const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();
  const errors = [];
  page.on('console', msg => {
    if (msg.text().includes('invalid endpoints')) {
      errors.push(msg.text().substring(0, 100));
    }
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Get graph data without expansion
  console.log('=== WITHOUT EXPANSION ===');
  let data = await page.evaluate(() => {
    return { graphData, positions: Object.fromEntries(
      cy.nodes().map(n => [n.id(), { x: Math.round(n.position().x), y: Math.round(n.position().y) }])
    )};
  });

  // Find key nodes
  const findNode = (data, pattern) => {
    for (const n of (data.graphData?.nodes || [])) {
      if (n.data.label && n.data.label.includes(pattern)) {
        return { id: n.data.id, label: n.data.label.substring(0, 30).replace(/\n/g, '|') };
      }
    }
    return null;
  };

  const createRoute = findNode(data, 'entity-form-create-route');
  const editRoute = findNode(data, 'entity-form-edit-route');
  const handler = findNode(data, 'entity-form-handler');

  console.log('Create-route:', createRoute?.id, 'pos:', data.positions[createRoute?.id]);
  console.log('Edit-route:', editRoute?.id, 'pos:', data.positions[editRoute?.id]);
  console.log('Handler:', handler?.id, 'pos:', data.positions[handler?.id]);

  // Expand create-route
  console.log('\n=== AFTER EXPANSION TO LEVEL 2 ===');
  await page.evaluate(() => {
    setExpansionLevel('35ed3970-9143-4a2d-b322-351080ec31bc', 2);
  });
  await new Promise(r => setTimeout(r, 2000));

  data = await page.evaluate(() => {
    return { graphData, positions: Object.fromEntries(
      cy.nodes().map(n => [n.id(), { x: Math.round(n.position().x), y: Math.round(n.position().y) }])
    )};
  });

  console.log('Create-route:', data.positions[createRoute?.id]);
  console.log('Edit-route:', data.positions[editRoute?.id]);
  console.log('Handler:', data.positions[handler?.id]);

  // Show edges from create-route
  const edges = await page.evaluate((createId, editId) => {
    const result = [];
    cy.edges().forEach(e => {
      if (e.data('source') === createId || e.data('source') === editId) {
        const tgt = cy.getElementById(e.data('target'));
        result.push({
          from: e.data('source') === createId ? 'create' : 'edit',
          to: (tgt.data('label') || '').substring(0, 25).replace(/\n/g, '|'),
          toY: tgt.length ? Math.round(tgt.position().y) : null
        });
      }
    });
    return result;
  }, createRoute?.id, editRoute?.id);

  console.log('\nEdges:');
  edges.forEach(e => console.log(`  ${e.from} -> ${e.to} (y=${e.toY})`));

  if (errors.length > 0) {
    console.log('\nEDGE ERRORS:', errors);
  }

  await browser.close();
})();
