const puppeteer = require('puppeteer');

// Test delete-entity-route expand to pair level - check pair-1 position
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
  const routeData = await page.evaluate(() => {
    let routeId = null;
    let routeLabel = null;
    let maxLevel = 0;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('delete-entity-route')) {
        routeId = n.data('originalFnId');
        routeLabel = lbl;
        maxLevel = lbl.split('\n').length - 1;
      }
    });
    return { routeId, routeLabel, maxLevel };
  });

  console.log('Route:', routeData.routeLabel.split('\n')[0]);
  console.log('Max level:', routeData.maxLevel);

  // Get state BEFORE expansion
  console.log('\n=== BEFORE EXPANSION ===');
  let before = await page.evaluate(() => {
    const nodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('delete') || lbl.includes('pair') || lbl.includes('route')) {
        nodes.push({
          id: n.id(),
          label: lbl.substring(0, 40).replace(/\n/g, '|'),
          x: Math.round(n.position().x),
          y: Math.round(n.position().y),
          row: Math.round(n.position().y / 40),
          col: Math.round(n.position().x / 80)
        });
      }
    });
    return nodes.sort((a, b) => a.y - b.y);
  });

  before.forEach(n => {
    console.log(`  Row ${n.row}, Col ${n.col}: ${n.label.substring(0, 35)}`);
  });

  // Expand to MAX level (pair)
  console.log(`\n=== EXPAND TO LEVEL ${routeData.maxLevel} ===`);
  await page.evaluate((fnId, level) => {
    setExpansionLevel(fnId, level);
  }, routeData.routeId, routeData.maxLevel);
  await new Promise(r => setTimeout(r, 2000));

  // Get state AFTER expansion
  let after = await page.evaluate(() => {
    const nodes = [];
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      nodes.push({
        id: n.id(),
        label: lbl.substring(0, 40).replace(/\n/g, '|'),
        x: Math.round(n.position().x),
        y: Math.round(n.position().y),
        row: Math.round(n.position().y / 40),
        col: Math.round(n.position().x / 80)
      });
    });
    return nodes.sort((a, b) => a.y - b.y);
  });

  // Find pair nodes
  console.log('All nodes after expansion (sorted by row):');
  after.forEach(n => {
    const marker = n.label.includes('pair') ? ' <-- PAIR' :
                   n.label.includes('delete') ? ' <-- DELETE' : '';
    console.log(`  Row ${n.row}, Col ${n.col}: ${n.label.substring(0, 35)}${marker}`);
  });

  // Check for overlaps
  const rows = {};
  after.forEach(n => {
    if (!rows[n.row]) rows[n.row] = [];
    rows[n.row].push(n);
  });

  console.log('\n=== OVERLAP CHECK ===');
  let hasOverlaps = false;
  for (const [row, rowNodes] of Object.entries(rows)) {
    if (rowNodes.length < 2) continue;
    rowNodes.sort((a, b) => a.col - b.col);
    for (let i = 1; i < rowNodes.length; i++) {
      if (rowNodes[i].col === rowNodes[i-1].col) {
        hasOverlaps = true;
        console.log(`!!! Row ${row}: SAME CELL`);
        console.log(`    "${rowNodes[i-1].label.substring(0, 25)}" and "${rowNodes[i].label.substring(0, 25)}"`);
      }
    }
  }

  if (!hasOverlaps) {
    console.log('No cell collisions detected');
  }

  // Check grid positions from API
  console.log('\n=== GRID POSITIONS FROM API ===');
  const gridResult = await page.evaluate(async (fnId, level) => {
    const url = 'http://localhost:9002/api/graph/layout';
    const body = {
      'root-id': window.graphData.rootId,
      expansions: { [fnId]: level }
    };
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    return await resp.json();
  }, routeData.routeId, routeData.maxLevel);

  // Show grid positions
  const gridNodes = gridResult.nodes || [];
  const gridPos = gridResult['grid-pos'] || {};

  // Find pair nodes in grid
  gridNodes.forEach(n => {
    if (n.data.label && n.data.label.includes('pair')) {
      const pos = gridPos[n.data.id];
      console.log(`  ${n.data.id}: row=${pos?.row}, col=${pos?.col} label="${n.data.label.substring(0, 30).replace(/\n/g, '|')}"`);
    }
  });

  // Check validation
  console.log('\n=== VALIDATION ===');
  console.log(gridResult.validation);

  await browser.close();
})();
