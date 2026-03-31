const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  const cyLoaded = await page.evaluate(() => typeof cy !== 'undefined' && cy !== null);
  if (!cyLoaded) {
    console.log('Cytoscape not loaded');
    await browser.close();
    return;
  }

  // Кликаем на корневой узел (editor-routes)
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Экспандим list-10 (первый уровень)
  await page.evaluate(() => {
    const overlays = document.querySelectorAll('.node-overlay');
    for (const overlay of overlays) {
      if (overlay.style.display !== 'none') {
        const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
        for (const line of lines) {
          if (line.textContent.includes('list-10')) {
            line.click();
            return;
          }
        }
      }
    }
  });
  await new Promise(r => setTimeout(r, 1500));

  await page.screenshot({ path: '/tmp/list10-expand.png', fullPage: true });

  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    const { children, parents } = buildAdjacency(elements.edges);
    const layout = layoutGraph(elements);
    const { gridPos } = layout;
    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Найдём все три узла
    let editRouteId = null, createRouteId = null, handlerId = null;

    elements.nodes.forEach(n => {
      const label = n.data.label || '';
      if (label.includes('entity-form-edit-route')) editRouteId = n.data.id;
      if (label.includes('entity-form-create-route')) createRouteId = n.data.id;
      if (label.includes('entity-form-handler')) handlerId = n.data.id;
    });

    const getInfo = (id, name) => {
      if (!id) return { name, found: false };
      const pos = gridPos.get(id);
      return {
        name,
        found: true,
        row: pos?.row,
        col: pos?.col,
        id
      };
    };

    const editRoute = getInfo(editRouteId, 'entity-form-edit-route');
    const createRoute = getInfo(createRouteId, 'entity-form-create-route');
    const handler = getInfo(handlerId, 'entity-form-handler');

    // Проверим родителей handler
    let handlerParentLabels = [];
    if (handlerId) {
      const handlerParents = parents.get(handlerId) || [];
      handlerParentLabels = handlerParents.map(pid =>
        nodeDataMap.get(pid)?.label?.split('\n')[0]
      );
    }

    return {
      editRoute,
      createRoute,
      handler,
      handlerParents: handlerParentLabels,
      totalNodes: elements.nodes.length
    };
  });

  console.log('=== После экспанда list-10 ===');
  console.log('Всего узлов:', result.totalNodes);
  console.log('');

  if (result.editRoute.found) {
    console.log('entity-form-edit-route: row=' + result.editRoute.row + ', col=' + result.editRoute.col);
  } else {
    console.log('entity-form-edit-route: НЕ НАЙДЕН');
  }

  if (result.createRoute.found) {
    console.log('entity-form-create-route: row=' + result.createRoute.row + ', col=' + result.createRoute.col);
  } else {
    console.log('entity-form-create-route: НЕ НАЙДЕН');
  }

  if (result.handler.found) {
    console.log('entity-form-handler: row=' + result.handler.row + ', col=' + result.handler.col);
    console.log('  Родители handler:', result.handlerParents.join(', ') || 'нет');
  } else {
    console.log('entity-form-handler: НЕ НАЙДЕН');
  }

  console.log('');
  console.log('Скриншот: /tmp/list10-expand.png');

  await browser.close();
})();
