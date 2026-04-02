const puppeteer = require('puppeteer');

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
  const browser = await puppeteer.launch({ 
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });
  const page = await browser.newPage();
  
  // Collect console messages
  const logs = [];
  page.on('console', msg => logs.push(msg.text()));
  
  console.log('Loading editor...');
  await page.goto('http://localhost:9002/#delete-entity-route', { waitUntil: 'networkidle0' });
  await sleep(1000);
  
  // Check if cytoscape loaded
  const cytoscapeLoaded = await page.evaluate(() => typeof cytoscape !== 'undefined');
  console.log('Cytoscape loaded:', cytoscapeLoaded);
  
  if (!cytoscapeLoaded) {
    console.log('ERROR: Cytoscape not loaded!');
    console.log('Console logs:', logs.join('\n'));
    await browser.close();
    process.exit(1);
  }
  
  // Check initial state
  const initialState = await page.evaluate(() => {
    return {
      nodes: cy ? cy.nodes().length : 0,
      edges: cy ? cy.edges().length : 0,
      expansionLevel: Object.fromEntries(expansionLevel)
    };
  });
  console.log('Initial state:', JSON.stringify(initialState));
  
  // Get edges from root at initial state
  const initialEdges = await page.evaluate(() => {
    const rootId = 'fn-' + selectedFnId;
    return cy.edges().filter(e => e.data('source') === rootId).map(e => ({
      id: e.id(),
      target: e.data('target'),
      argName: e.data('argName')
    }));
  });
  console.log('Initial edges from root:', JSON.stringify(initialEdges, null, 2));
  
  // Click on delete-route (level 1) in the overlay
  console.log('\nClicking on delete-route (level 1)...');
  await page.evaluate(() => {
    const rootNode = cy.nodes('[?isRoot]')[0];
    const rootFnId = rootNode.data('originalFnId');
    setExpansionLevel(rootFnId, 1);
  });
  await sleep(500);
  
  const afterLevel1 = await page.evaluate(() => {
    const rootId = 'fn-' + selectedFnId;
    return {
      nodes: cy.nodes().length,
      edges: cy.edges().length,
      expansionLevel: Object.fromEntries(expansionLevel),
      edgesFromRoot: cy.edges().filter(e => e.data('source') === rootId).map(e => ({
        id: e.id().substring(0, 40),
        argName: e.data('argName')
      }))
    };
  });
  console.log('After level 1:', JSON.stringify(afterLevel1, null, 2));
  
  // Click on route (level 2)
  console.log('\nClicking on route (level 2)...');
  await page.evaluate(() => {
    const rootNode = cy.nodes('[?isRoot]')[0];
    const rootFnId = rootNode.data('originalFnId');
    setExpansionLevel(rootFnId, 2);
  });
  await sleep(500);
  
  const afterLevel2 = await page.evaluate(() => {
    const rootId = 'fn-' + selectedFnId;
    return {
      nodes: cy.nodes().length,
      edges: cy.edges().length,
      expansionLevel: Object.fromEntries(expansionLevel),
      edgesFromRoot: cy.edges().filter(e => e.data('source') === rootId).map(e => ({
        id: e.id().substring(0, 40),
        argName: e.data('argName')
      }))
    };
  });
  console.log('After level 2 (sequential):', JSON.stringify(afterLevel2, null, 2));
  
  // Now test direct level 2
  console.log('\n--- Testing DIRECT level 2 ---');
  
  // Reset to level 0
  await page.evaluate(() => {
    expansionLevel.clear();
    renderGraph(true);
  });
  await sleep(500);
  
  // Directly to level 2
  console.log('Directly setting level 2...');
  await page.evaluate(() => {
    const rootNode = cy.nodes('[?isRoot]')[0];
    const rootFnId = rootNode.data('originalFnId');
    setExpansionLevel(rootFnId, 2);
  });
  await sleep(500);
  
  const directLevel2 = await page.evaluate(() => {
    const rootId = 'fn-' + selectedFnId;
    return {
      nodes: cy.nodes().length,
      edges: cy.edges().length,
      expansionLevel: Object.fromEntries(expansionLevel),
      edgesFromRoot: cy.edges().filter(e => e.data('source') === rootId).map(e => ({
        id: e.id().substring(0, 40),
        argName: e.data('argName')
      }))
    };
  });
  console.log('After level 2 (direct):', JSON.stringify(directLevel2, null, 2));
  
  // Compare
  console.log('\n=== COMPARISON ===');
  console.log('Sequential edges from root:', afterLevel2.edgesFromRoot.map(e => e.argName).sort().join(', '));
  console.log('Direct edges from root:', directLevel2.edgesFromRoot.map(e => e.argName).sort().join(', '));
  
  if (JSON.stringify(afterLevel2.edgesFromRoot.map(e => e.argName).sort()) === 
      JSON.stringify(directLevel2.edgesFromRoot.map(e => e.argName).sort())) {
    console.log('\n✓ PASS: Sequential and direct expansion produce same result!');
  } else {
    console.log('\n✗ FAIL: Results differ!');
  }
  
  await browser.close();
})();
