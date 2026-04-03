const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  const ids = await page.evaluate(() => {
    let rootId = null;
    let healthRouteId = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('editor-routes')) rootId = n.data('originalFnId');
      if (lbl.includes('health-route')) healthRouteId = n.data('originalFnId');
    });
    return { rootId, healthRouteId };
  });

  console.log('Root ID:', ids.rootId);
  console.log('Health-route ID:', ids.healthRouteId);

  // Get layout WITHOUT expansion
  const layoutBefore = await page.evaluate(async (rootId) => {
    const resp = await fetch('/api/graph/layout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 'root-id': rootId, expansions: {} })
    });
    return resp.json();
  }, ids.rootId);

  // Get layout WITH expansion of health-route to level 2
  const layoutAfter = await page.evaluate(async (rootId, expandId) => {
    const resp = await fetch('/api/graph/layout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 'root-id': rootId, expansions: { [expandId]: 2 } })
    });
    return resp.json();
  }, ids.rootId, ids.healthRouteId);

  // Compare nodes
  const nodesBefore = new Set(layoutBefore.nodes.map(n => n.data.id));
  const nodesAfter = new Set(layoutAfter.nodes.map(n => n.data.id));

  const added = [...nodesAfter].filter(id => !nodesBefore.has(id));
  const removed = [...nodesBefore].filter(id => !nodesAfter.has(id));

  // Get labels for nodes
  const labelsBefore = {};
  layoutBefore.nodes.forEach(n => {
    labelsBefore[n.data.id] = (n.data.label || '').substring(0, 35).replace(/\n/g, '|');
  });
  const labelsAfter = {};
  layoutAfter.nodes.forEach(n => {
    labelsAfter[n.data.id] = (n.data.label || '').substring(0, 35).replace(/\n/g, '|');
  });

  console.log('\n=== NODES COMPARISON ===');
  console.log(`Before: ${nodesBefore.size} nodes`);
  console.log(`After: ${nodesAfter.size} nodes`);

  if (removed.length > 0) {
    console.log('\n!!! REMOVED NODES:');
    removed.forEach(id => {
      console.log(`  - ${labelsBefore[id]} (${id.substring(0, 30)})`);
    });
  }

  if (added.length > 0) {
    console.log('\n+++ ADDED NODES:');
    added.forEach(id => {
      console.log(`  + ${labelsAfter[id]} (${id.substring(0, 30)})`);
    });
  }

  // Compare grid positions for nodes that exist in both
  const gridBefore = layoutBefore['grid-pos'] || {};
  const gridAfter = layoutAfter['grid-pos'] || {};

  console.log('\n=== POSITION CHANGES ===');

  const common = [...nodesBefore].filter(id => nodesAfter.has(id));
  const moved = common.filter(id => {
    const before = gridBefore[id];
    const after = gridAfter[id];
    if (!before || !after) return false;
    return before.row !== after.row || before.col !== after.col;
  });

  if (moved.length > 0) {
    console.log('Nodes that moved:');
    moved.forEach(id => {
      const before = gridBefore[id];
      const after = gridAfter[id];
      console.log(`  ${labelsBefore[id]}`);
      console.log(`    Before: row=${before.row} col=${before.col}`);
      console.log(`    After:  row=${after.row} col=${after.col}`);
    });
  } else {
    console.log('No nodes moved');
  }

  // Check health-route specifically
  console.log('\n=== HEALTH-ROUTE DETAILS ===');

  const hrIdBefore = `fn-${ids.healthRouteId}`;
  const hrPosBefore = gridBefore[hrIdBefore];
  const hrPosAfter = gridAfter[hrIdBefore];

  console.log('health-route position:');
  console.log('  Before:', hrPosBefore);
  console.log('  After:', hrPosAfter);

  // Show health-route children in both
  const childrenBefore = {};
  layoutBefore.edges.forEach(e => {
    if (!childrenBefore[e.data.source]) childrenBefore[e.data.source] = [];
    childrenBefore[e.data.source].push(e.data.target);
  });
  const childrenAfter = {};
  layoutAfter.edges.forEach(e => {
    if (!childrenAfter[e.data.source]) childrenAfter[e.data.source] = [];
    childrenAfter[e.data.source].push(e.data.target);
  });

  console.log('\nhealth-route children BEFORE:');
  (childrenBefore[hrIdBefore] || []).forEach(cid => {
    const pos = gridBefore[cid];
    console.log(`  ${labelsBefore[cid]} at row=${pos?.row} col=${pos?.col}`);
  });

  console.log('\nhealth-route children AFTER:');
  (childrenAfter[hrIdBefore] || []).forEach(cid => {
    const pos = gridAfter[cid];
    console.log(`  ${labelsAfter[cid]} at row=${pos?.row} col=${pos?.col}`);
  });

  await browser.close();
})();
