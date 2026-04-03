const puppeteer = require('puppeteer');

// Show matrix state for delete-entity-route expansion
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

  // Get API response with matrix info
  const apiResult = await page.evaluate(async (fnId) => {
    const rootFnId = await new Promise(resolve => {
      cy.nodes().forEach(n => {
        if (n.data('isRoot')) {
          resolve(n.data('originalFnId'));
        }
      });
    });

    const url = 'http://localhost:9002/api/graph/layout';
    const body = {
      'root-id': rootFnId,
      expansions: { [fnId]: 4 }
    };
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    return await resp.json();
  }, routeId);

  const gridPos = apiResult['grid-pos'] || {};
  const nodes = apiResult.nodes || [];

  // Build matrix visualization
  const matrix = {};
  let maxRow = 0;
  let maxCol = 0;

  for (const [nodeId, pos] of Object.entries(gridPos)) {
    const node = nodes.find(n => n.data.id === nodeId);
    const label = (node?.data.label || '').substring(0, 15).replace(/\n/g, '|');
    matrix[`${pos.row},${pos.col}`] = label;
    maxRow = Math.max(maxRow, pos.row);
    maxCol = Math.max(maxCol, pos.col);
  }

  console.log('=== MATRIX VISUALIZATION ===\n');

  // Print header
  let header = '    ';
  for (let c = 0; c <= Math.min(maxCol, 15); c++) {
    header += `C${c}`.padEnd(17);
  }
  console.log(header);

  // Print rows
  for (let r = 0; r <= maxRow; r++) {
    let row = `R${r}`.padStart(3) + ' ';
    for (let c = 0; c <= Math.min(maxCol, 15); c++) {
      const cell = matrix[`${r},${c}`] || '.';
      row += cell.padEnd(17);
    }
    console.log(row);
  }

  // Find nodes with specific labels
  console.log('\n=== KEY NODES ===');
  for (const [nodeId, pos] of Object.entries(gridPos)) {
    const node = nodes.find(n => n.data.id === nodeId);
    const label = node?.data.label || '';
    if (label.includes('delete-entity') || label.includes('pair') || label.includes('editor-route') || label.includes('favicon')) {
      console.log(`Row ${pos.row}, Col ${pos.col}: ${label.substring(0, 35).replace(/\n/g, '|')}`);
    }
  }

  await browser.close();
})();
