const puppeteer = require('puppeteer');

// Debug delete-entity-route graph structure
(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  page.on('console', msg => {
    const text = msg.text();
    if (text.includes('Build:')) console.log('BROWSER:', text);
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Find delete-entity-route
  const routeId = await page.evaluate(() => {
    let id = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('delete-entity-route')) {
        id = n.data('originalFnId');
      }
    });
    return id;
  });

  // Expand to level 4
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 4);
  }, routeId);
  await new Promise(r => setTimeout(r, 2000));

  // Get graph structure (children relationships)
  const graph = await page.evaluate(() => {
    const nodes = {};
    const edges = [];

    cy.nodes().forEach(n => {
      nodes[n.id()] = {
        id: n.id(),
        label: (n.data('label') || '').substring(0, 35).replace(/\n/g, '|'),
        x: Math.round(n.position().x),
        y: Math.round(n.position().y),
        row: Math.round(n.position().y / 40),
        col: Math.round(n.position().x / 80)
      };
    });

    cy.edges().forEach(e => {
      edges.push({
        source: e.source().id(),
        target: e.target().id(),
        argName: e.data('argName')
      });
    });

    return { nodes, edges };
  });

  // Find delete-entity-route node
  let deleteNode = null;
  for (const n of Object.values(graph.nodes)) {
    if (n.label.includes('delete-entity-route')) {
      deleteNode = n;
      break;
    }
  }

  console.log('=== DELETE-ENTITY-ROUTE CHILDREN ===');
  console.log(`Parent: ${deleteNode.label} at Row ${deleteNode.row}, Col ${deleteNode.col}`);

  // Find children of delete-entity-route
  const children = graph.edges
    .filter(e => e.source === deleteNode.id)
    .map(e => {
      const child = graph.nodes[e.target];
      return { ...child, argName: e.argName };
    })
    .sort((a, b) => a.col - b.col);

  console.log('\nChildren:');
  children.forEach(c => {
    console.log(`  Arg "${c.argName}": ${c.label.substring(0, 30)} at Row ${c.row}, Col ${c.col}`);
  });

  // Find pair-1 specifically
  let pairNode = null;
  for (const n of Object.values(graph.nodes)) {
    if (n.label.includes('pair-1')) {
      pairNode = n;
      break;
    }
  }

  if (pairNode) {
    console.log(`\n=== PAIR-1 DETAILS ===`);
    console.log(`Position: Row ${pairNode.row}, Col ${pairNode.col}`);

    // Find pair-1's parent(s)
    const parents = graph.edges
      .filter(e => e.target === pairNode.id)
      .map(e => {
        const parent = graph.nodes[e.source];
        return { ...parent, argName: e.argName };
      });

    console.log('Parents of pair-1:');
    parents.forEach(p => {
      console.log(`  ${p.label.substring(0, 30)} (arg: ${p.argName}) at Row ${p.row}, Col ${p.col}`);
    });

    // Find pair-1's children
    const pairChildren = graph.edges
      .filter(e => e.source === pairNode.id)
      .map(e => {
        const child = graph.nodes[e.target];
        return { ...child, argName: e.argName };
      });

    console.log('Children of pair-1:');
    pairChildren.forEach(c => {
      console.log(`  Arg "${c.argName}": ${c.label.substring(0, 30)} at Row ${c.row}, Col ${c.col}`);
    });
  }

  // Show all nodes sorted by row
  console.log('\n=== ALL NODES BY ROW ===');
  const nodesByRow = {};
  for (const n of Object.values(graph.nodes)) {
    if (!nodesByRow[n.row]) nodesByRow[n.row] = [];
    nodesByRow[n.row].push(n);
  }

  for (const row of Object.keys(nodesByRow).sort((a, b) => parseInt(a) - parseInt(b))) {
    const nodesInRow = nodesByRow[row].sort((a, b) => a.col - b.col);
    console.log(`Row ${row}:`);
    nodesInRow.forEach(n => {
      const isPair = n.label.includes('pair') ? ' <PAIR>' : '';
      const isDelete = n.label.includes('delete') ? ' <DELETE>' : '';
      console.log(`  Col ${n.col}: ${n.label.substring(0, 35)}${isPair}${isDelete}`);
    });
  }

  await browser.close();
})();
