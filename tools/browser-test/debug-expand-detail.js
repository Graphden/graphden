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

  // Click to expand entity-form-create-route to get-route
  const clickPos = await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (!node) return null;

    const originalFnId = node.data('originalFnId');
    const overlay = document.querySelector(`.node-overlay[data-original-fn-id="${originalFnId}"]`);
    if (!overlay) return null;

    const lines = overlay.querySelectorAll('.ancestor-line');
    let targetLine = null;
    lines.forEach(line => {
      if (line.textContent === 'get-route') targetLine = line;
    });
    if (!targetLine) return null;

    const rect = targetLine.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  });

  if (clickPos) {
    await page.mouse.click(clickPos.x, clickPos.y);
    await new Promise(r => setTimeout(r, 1000));
  }

  // Analyze the graph structure after expand
  const analysis = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const { children, parents } = buildAdjacency(elements.edges);

    // Find entity-form-create-route node
    const createRouteNode = elements.nodes.find(n =>
      n.data.label?.includes('entity-form-create-route')
    );

    if (!createRouteNode) return { error: 'create-route not found' };

    const createRouteId = createRouteNode.data.id;
    const createRouteChildren = children.get(createRouteId) || [];

    // Get info about children
    const nodeMap = new Map();
    elements.nodes.forEach(n => nodeMap.set(n.data.id, n.data));

    const childrenInfo = createRouteChildren.map(childId => {
      const data = nodeMap.get(childId);
      return {
        id: childId,
        label: data?.label?.substring(0, 40),
        type: data?.type
      };
    });

    // Find entity-form-handler
    const handlerNode = elements.nodes.find(n =>
      n.data.label?.includes('entity-form-handler')
    );

    let handlerParentsInfo = [];
    if (handlerNode) {
      const handlerParents = parents.get(handlerNode.data.id) || [];
      handlerParentsInfo = handlerParents.map(pid => {
        const data = nodeMap.get(pid);
        return {
          id: pid,
          label: data?.label?.split('\n')[0]?.substring(0, 40)
        };
      });
    }

    // Get layout
    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    // Get grid positions for relevant nodes
    const positions = {};

    if (createRouteNode) {
      const gp = gridPos.get(createRouteId);
      positions.createRoute = gp ? { row: gp.row, col: gp.col } : null;
    }

    createRouteChildren.forEach(childId => {
      const gp = gridPos.get(childId);
      const data = nodeMap.get(childId);
      const label = data?.label?.substring(0, 20) || childId;
      positions['child_' + label] = gp ? { row: gp.row, col: gp.col } : null;
    });

    if (handlerNode) {
      const gp = gridPos.get(handlerNode.data.id);
      positions.handler = gp ? { row: gp.row, col: gp.col } : null;
    }

    // Find edit-route
    const editRouteNode = elements.nodes.find(n =>
      n.data.label?.includes('entity-form-edit-route')
    );
    if (editRouteNode) {
      const gp = gridPos.get(editRouteNode.data.id);
      positions.editRoute = gp ? { row: gp.row, col: gp.col } : null;
    }

    return {
      createRouteId,
      createRouteLabel: createRouteNode.data.label,
      childrenInfo,
      handlerParentsInfo,
      positions
    };
  });

  console.log('=== ANALYSIS AFTER EXPAND ===');
  console.log('\nentity-form-create-route label:');
  console.log(analysis.createRouteLabel);

  console.log('\nChildren of entity-form-create-route:');
  analysis.childrenInfo.forEach(c => {
    console.log(`  - ${c.label} (${c.type})`);
  });

  console.log('\nParents of entity-form-handler:');
  analysis.handlerParentsInfo.forEach(p => {
    console.log(`  - ${p.label}`);
  });

  console.log('\nGrid positions:');
  Object.entries(analysis.positions).forEach(([key, pos]) => {
    console.log(`  ${key}: row=${pos?.row}, col=${pos?.col}`);
  });

  await browser.close();
})();
