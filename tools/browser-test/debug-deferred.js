const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));
  
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('editor-routes'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));
  
  // EXPAND
  const clickPos = await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('entity-form-create-route'))[0];
    if (!node) return null;
    const originalFnId = node.data('originalFnId');
    const overlay = document.querySelector(`.node-overlay[data-original-fn-id="${originalFnId}"]`);
    if (!overlay) return null;
    const lines = overlay.querySelectorAll('.ancestor-line');
    let targetLine = null;
    lines.forEach(line => { if (line.textContent === 'get-route') targetLine = line; });
    if (!targetLine) return null;
    const rect = targetLine.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  });
  
  if (clickPos) {
    await page.mouse.click(clickPos.x, clickPos.y);
    await new Promise(r => setTimeout(r, 1000));
  }
  
  const result = await page.evaluate(() => {
    const elements = { nodes: cy.nodes().map(n => ({ data: n.data() })), edges: cy.edges().map(e => ({ data: e.data() })) };
    
    // Find all nodes and their positions
    const allNodes = elements.nodes.map(n => ({
      label: n.data.label?.substring(0, 25),
      id: n.data.id
    }));
    
    // Find "get" specifically
    const getNode = elements.nodes.find(n => n.data.label === '"get"');
    
    // Check cytoscape node count
    const cyNodeCount = cy.nodes().length;
    
    return {
      totalNodes: allNodes.length,
      cyNodeCount,
      getNodeExists: !!getNode,
      getNodeId: getNode?.data.id
    };
  });
  
  console.log('Total nodes in elements:', result.totalNodes);
  console.log('Cytoscape nodes:', result.cyNodeCount);
  console.log('"get" exists:', result.getNodeExists);
  console.log('"get" id:', result.getNodeId);
  
  await browser.close();
})();
