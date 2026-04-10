const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1600, height: 1000 } })).newPage();
  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);
  // Expand entity-form-edit-route to route
  await page.evaluate(() => {
    for (const o of document.querySelectorAll('.node-overlay[data-original-fn-id]')) {
      const first = o.querySelector('.ancestor-line');
      if (first && first.textContent.trim() === 'entity-form-edit-route') {
        for (const l of o.querySelectorAll('.ancestor-line')) {
          if (l.textContent.trim() === 'route') { l.dispatchEvent(new MouseEvent('mousedown', {bubbles:true})); return; }
        }
      }
    }
  });
  await page.waitForTimeout(800);

  // Get ALL children of editor-routes in row order, plus entity-form-handler position
  const info = await page.evaluate(() => {
    const nodeLabels = {};
    const nodePos = {};
    cy.nodes().forEach(n => {
      const label = (n.data('label')||'').split('\n')[0];
      nodeLabels[n.id()] = label;
      nodePos[n.id()] = n.position();
    });

    // Find editor-routes node
    let editorRoutesId = null;
    cy.nodes().forEach(n => {
      if ((n.data('label')||'').startsWith('editor-routes')) editorRoutesId = n.id();
    });

    // Get children of editor-routes (edges from it)
    const children = [];
    cy.edges().forEach(e => {
      if (e.data('source') === editorRoutesId) {
        const tid = e.data('target');
        children.push({
          argName: e.data('argName'),
          target: nodeLabels[tid],
          row: Math.round(nodePos[tid].y)
        });
      }
    });
    children.sort((a, b) => a.row - b.row);

    // Find entity-form-handler
    let efhRow = null;
    cy.nodes().forEach(n => {
      if ((n.data('label')||'').startsWith('entity-form-handler')) {
        efhRow = Math.round(n.position().y);
      }
    });

    // Find entity-form-create-route's children
    let efcrChildren = [];
    cy.nodes().forEach(n => {
      if ((n.data('label')||'').startsWith('entity-form-create-route')) {
        cy.edges().forEach(e => {
          if (e.data('source') === n.id()) {
            const tid = e.data('target');
            efcrChildren.push({
              argName: e.data('argName'),
              target: nodeLabels[tid],
              row: Math.round(nodePos[tid].y)
            });
          }
        });
      }
    });
    efcrChildren.sort((a, b) => a.row - b.row);

    return { children, efhRow, efcrChildren };
  });

  console.log('editor-routes children (by row):');
  info.children.forEach((c, i) => {
    const marker = c.target.includes('entity-form-create') ? ' <<<' :
                   c.target.includes('entity-form-edit') ? ' <<<' : '';
    console.log(`  ${i}: row=${c.row} ${c.argName} → ${c.target}${marker}`);
  });

  console.log('\nentity-form-handler at row:', info.efhRow);
  console.log('\nentity-form-create-route children:');
  info.efcrChildren.forEach(c => console.log(`  ${c.argName} → ${c.target} (row ${c.row})`));

  // Check rule 5: splitting siblings must be adjacent
  const createIdx = info.children.findIndex(c => c.target.includes('entity-form-create'));
  const editIdx = info.children.findIndex(c => c.target.includes('entity-form-edit'));
  console.log(`\ncreate at index ${createIdx}, edit at index ${editIdx}, gap: ${Math.abs(createIdx - editIdx) - 1} siblings between them`);

  await browser.close();
})();
