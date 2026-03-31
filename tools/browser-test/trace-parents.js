const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

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

    // Find the nodes
    let editRouteId = null, createRouteId = null, handlerId = null;
    let list10_4 = null, list10_5 = null, list10_6 = null;

    elements.nodes.forEach(n => {
      const label = n.data.label || '';
      if (label.includes('entity-form-edit-route')) editRouteId = n.data.id;
      if (label.includes('entity-form-create-route')) createRouteId = n.data.id;
      if (label.includes('entity-form-handler')) handlerId = n.data.id;
      if (label.startsWith('list-10-4')) list10_4 = n.data.id;
      if (label.startsWith('list-10-5')) list10_5 = n.data.id;
      if (label.startsWith('list-10-6')) list10_6 = n.data.id;
    });

    // Trace the parent chain for edit-route and create-route
    function getParentChain(nodeId, maxDepth = 10) {
      const chain = [];
      let current = nodeId;
      while (current && chain.length < maxDepth) {
        const pos = gridPos.get(current);
        chain.push({
          label: nodeDataMap.get(current)?.label?.split('\n')[0],
          row: pos?.row,
          col: pos?.col
        });
        const parentList = parents.get(current) || [];
        current = parentList[0] || null;
      }
      return chain;
    }

    // Get children of list-10-5 and list-10-6
    const list10_5_children = (children.get(list10_5) || []).map(cid => ({
      label: nodeDataMap.get(cid)?.label?.split('\n')[0],
      pos: gridPos.get(cid)
    }));

    const list10_6_children = (children.get(list10_6) || []).map(cid => ({
      label: nodeDataMap.get(cid)?.label?.split('\n')[0],
      pos: gridPos.get(cid)
    }));

    return {
      editRouteChain: getParentChain(editRouteId),
      createRouteChain: getParentChain(createRouteId),
      list10_5_children,
      list10_6_children,
      list10_5_pos: gridPos.get(list10_5),
      list10_6_pos: gridPos.get(list10_6)
    };
  });

  console.log('=== Parent chain for entity-form-edit-route ===');
  result.editRouteChain.forEach((n, i) => {
    console.log('  ' + i + ': ' + n.label + ' (row=' + n.row + ', col=' + n.col + ')');
  });

  console.log('\n=== Parent chain for entity-form-create-route ===');
  result.createRouteChain.forEach((n, i) => {
    console.log('  ' + i + ': ' + n.label + ' (row=' + n.row + ', col=' + n.col + ')');
  });

  console.log('\n=== list-10-5 children (pos: row=' + result.list10_5_pos?.row + ', col=' + result.list10_5_pos?.col + ') ===');
  result.list10_5_children.forEach(c => {
    console.log('  ' + c.label + ' (row=' + c.pos?.row + ', col=' + c.pos?.col + ')');
  });

  console.log('\n=== list-10-6 children (pos: row=' + result.list10_6_pos?.row + ', col=' + result.list10_6_pos?.col + ') ===');
  result.list10_6_children.forEach(c => {
    console.log('  ' + c.label + ' (row=' + c.pos?.row + ', col=' + c.pos?.col + ')');
  });

  await browser.close();
})();
