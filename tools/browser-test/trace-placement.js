const puppeteer = require('puppeteer');

// Trace placement order to understand when vEdge and nodes conflict

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Find and click on list-10-9
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('list-10-9'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Expand 5 levels
  for (let i = 0; i < 5; i++) {
    await page.evaluate(() => {
      const overlays = document.querySelectorAll('.node-overlay');
      for (const overlay of overlays) {
        if (overlay.style.display !== 'none') {
          const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
          for (const line of lines) {
            const level = parseInt(line.dataset.level) || 0;
            const isBold = line.style.fontWeight === 'bold';
            if (level > 0 && !isBold) {
              line.click();
              return;
            }
          }
        }
      }
    });
    await new Promise(r => setTimeout(r, 1000));
  }

  // Find the specific issue: where does the vEdge at column 4 come from?
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };

    // Find edges that connect to nodes at column 4
    const { children, parents, edgeArgNames } = buildAdjacency(elements.edges);
    const layout = layoutGraph(elements);
    const { gridPos } = layout;

    const nodeDataMap = new Map();
    elements.nodes.forEach(n => nodeDataMap.set(n.data.id, n.data));

    // Find all nodes at column 4
    const col4Nodes = [];
    gridPos.forEach((pos, nodeId) => {
      if (pos.col === 4) {
        const data = nodeDataMap.get(nodeId);
        col4Nodes.push({
          nodeId,
          label: data?.label?.split('\n')[0],
          row: pos.row,
          col: pos.col
        });
      }
    });
    col4Nodes.sort((a, b) => a.row - b.row);

    // Find edges connecting these nodes
    const edgesInCol4 = [];
    col4Nodes.forEach(node => {
      const nodeParents = parents.get(node.nodeId) || [];
      nodeParents.forEach(parentId => {
        const parentPos = gridPos.get(parentId);
        const parentData = nodeDataMap.get(parentId);
        if (parentPos) {
          edgesInCol4.push({
            from: parentData?.label?.split('\n')[0],
            fromPos: parentPos,
            to: node.label,
            toPos: { row: node.row, col: node.col },
            argName: edgeArgNames.get(parentId + '->' + node.nodeId)
          });
        }
      });
    });

    // Check where the long vertical edge from row 0 to row 13 comes from
    // This would be from editor-routes to something
    let editorRoutesId = null;
    elements.nodes.forEach(n => {
      if (n.data.label?.includes('editor-routes')) {
        editorRoutesId = n.data.id;
      }
    });

    let editorRoutesInfo = null;
    if (editorRoutesId) {
      const pos = gridPos.get(editorRoutesId);
      const childIds = children.get(editorRoutesId) || [];
      const childInfo = childIds.map(cid => {
        const cpos = gridPos.get(cid);
        const cdata = nodeDataMap.get(cid);
        return {
          label: cdata?.label?.split('\n')[0],
          pos: cpos,
          argName: edgeArgNames.get(editorRoutesId + '->' + cid)
        };
      });
      editorRoutesInfo = {
        pos,
        children: childInfo
      };
    }

    return { col4Nodes, edgesInCol4, editorRoutesInfo };
  });

  console.log('=== Nodes at Column 4 ===');
  result.col4Nodes.forEach(n => {
    console.log(`Row ${n.row}: "${n.label}"`);
  });

  console.log('\n=== Edges involving Column 4 Nodes ===');
  result.edgesInCol4.forEach(e => {
    console.log(`"${e.from}" (${e.fromPos.row},${e.fromPos.col}) -> "${e.to}" (${e.toPos.row},${e.toPos.col}) [${e.argName}]`);
  });

  if (result.editorRoutesInfo) {
    console.log('\n=== editor-routes Info ===');
    console.log(`Position: (${result.editorRoutesInfo.pos.row}, ${result.editorRoutesInfo.pos.col})`);
    console.log('Children:');
    result.editorRoutesInfo.children.forEach(c => {
      console.log(`  "${c.label}" at (${c.pos?.row}, ${c.pos?.col}) [${c.argName}]`);
    });
  }

  await browser.close();
})();
