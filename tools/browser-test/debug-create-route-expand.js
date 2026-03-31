const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  // Tap on editor-routes to select it
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  console.log('=== BEFORE EXPAND ===');

  // Get initial layout info
  const beforeExpand = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data(), position: n.position() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    // Find entity-form-create-route and related nodes
    const createRoute = elements.nodes.find(n => n.data.label?.includes('entity-form-create-route'));
    const editRoute = elements.nodes.find(n => n.data.label?.includes('entity-form-edit-route'));
    const handler = elements.nodes.find(n => n.data.label?.includes('entity-form-handler'));

    return {
      nodeCount: elements.nodes.length,
      createRoute: createRoute ? { id: createRoute.data.id, pos: createRoute.position } : null,
      editRoute: editRoute ? { id: editRoute.data.id, pos: editRoute.position } : null,
      handler: handler ? { id: handler.data.id, pos: handler.position } : null
    };
  });

  console.log('Node count:', beforeExpand.nodeCount);
  console.log('create-route:', beforeExpand.createRoute?.pos);
  console.log('edit-route:', beforeExpand.editRoute?.pos);
  console.log('handler:', beforeExpand.handler?.pos);

  // Click on entity-form-create-route overlay to expand to level 1 (get-route)
  const clickPos = await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (!node) return null;

    const originalFnId = node.data('originalFnId');
    const overlay = document.querySelector(`.node-overlay[data-original-fn-id="${originalFnId}"]`);
    if (!overlay) return null;

    // Find get-route line (level 1)
    const lines = overlay.querySelectorAll('.ancestor-line');
    let targetLine = null;
    lines.forEach(line => {
      if (line.textContent === 'get-route') {
        targetLine = line;
      }
    });
    if (!targetLine) return null;

    const rect = targetLine.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  });

  if (!clickPos) {
    console.log('Could not find get-route line');
    await browser.close();
    return;
  }

  console.log('\n=== CLICKING get-route to expand ===');
  await page.mouse.click(clickPos.x, clickPos.y);
  await new Promise(r => setTimeout(r, 1000));

  // Get layout after expand
  const afterExpand = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data(), position: n.position() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    // Build layout
    const layout = layoutGraph(elements);
    const { gridPos, validation } = layout;

    // Get positions for key nodes
    const result = {
      nodeCount: elements.nodes.length,
      valid: validation.valid,
      issues: validation.issues,
      nodes: []
    };

    elements.nodes.forEach(n => {
      const gp = gridPos.get(n.data.id);
      if (gp) {
        const label = n.data.label?.split('\n')[0] || n.data.id;
        result.nodes.push({
          label: label.substring(0, 30),
          row: gp.row,
          col: gp.col,
          x: Math.round(n.position.x),
          y: Math.round(n.position.y)
        });
      }
    });

    // Sort by row then col
    result.nodes.sort((a, b) => a.row - b.row || a.col - b.col);

    return result;
  });

  console.log('\n=== AFTER EXPAND ===');
  console.log('Node count:', afterExpand.nodeCount);
  console.log('Valid:', afterExpand.valid);
  if (afterExpand.issues.length > 0) {
    console.log('Issues:', afterExpand.issues);
  }

  // Show nodes around entity-form area (rows with form-related nodes)
  console.log('\nNodes layout:');
  afterExpand.nodes.forEach(n => {
    const marker = n.label.includes('entity-form') || n.label.includes('get-route') || n.label.includes('route') ? ' <--' : '';
    console.log(`  row=${n.row}, col=${n.col}: "${n.label}"${marker}`);
  });

  await page.screenshot({ path: '/tmp/after-expand.png', fullPage: false });
  console.log('\nScreenshot saved to /tmp/after-expand.png');

  await browser.close();
})();
