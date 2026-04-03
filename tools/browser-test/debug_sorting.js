const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  // Capture console messages
  page.on('console', msg => {
    console.log('BROWSER:', msg.text());
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Expand create-route to level 2
  await page.evaluate(() => {
    setExpansionLevel('35ed3970-9143-4a2d-b322-351080ec31bc', 2);
  });
  await new Promise(r => setTimeout(r, 2000));

  // Make a direct API call to see what the server returns
  const result = await page.evaluate(async () => {
    const resp = await fetch('/api/graph/layout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        'root-id': '2b0e8780-b37c-435f-99f7-3a89fa07f7de', // web-server
        expansions: {
          '35ed3970-9143-4a2d-b322-351080ec31bc': 2 // entity-form-create-route level 2
        }
      })
    });
    return resp.json();
  });

  // Find shared nodes
  const findShared = () => {
    const incoming = {};
    for (const e of result.edges) {
      const target = e.data.target;
      incoming[target] = (incoming[target] || 0) + 1;
    }
    const shared = [];
    for (const [id, count] of Object.entries(incoming)) {
      if (count > 1) {
        const node = result.nodes.find(n => n.data.id === id);
        shared.push({
          id: id,
          label: (node?.data?.label || '').substring(0, 30).replace(/\n/g, '|'),
          parents: result.edges.filter(e => e.data.target === id).map(e => {
            const src = result.nodes.find(n => n.data.id === e.data.source);
            return (src?.data?.label || '').substring(0, 25).replace(/\n/g, '|');
          })
        });
      }
    }
    return shared;
  };

  console.log('\n=== SHARED NODES ===');
  const shared = findShared();
  shared.forEach(s => {
    console.log(`${s.label}: parents = [${s.parents.join(', ')}]`);
  });

  // Find create-route node
  const createRouteNode = result.nodes.find(n =>
    (n.data.label || '').includes('entity-form-create-route')
  );

  if (createRouteNode) {
    console.log('\n=== CREATE-ROUTE CHILDREN ===');
    const children = result.edges
      .filter(e => e.data.source === createRouteNode.data.id)
      .map(e => {
        const tgt = result.nodes.find(n => n.data.id === e.data.target);
        const pos = result['grid-pos']?.[e.data.target];
        return {
          argName: e.data.argName || 'N/A',
          label: (tgt?.data?.label || '').substring(0, 25).replace(/\n/g, '|'),
          type: tgt?.data?.type,
          row: pos?.row,
          col: pos?.col
        };
      })
      .sort((a, b) => (a.row || 0) - (b.row || 0));

    children.forEach(c => {
      console.log(`  [row=${c.row}, col=${c.col}] ${c.argName}: ${c.label} (${c.type})`);
    });

    // Also show grid positions
    console.log('\n=== CREATE-ROUTE POSITION ===');
    const createPos = result['grid-pos']?.[createRouteNode.data.id];
    console.log(`create-route: row=${createPos?.row}, col=${createPos?.col}`);
  }

  // Find handler (shared node)
  const handlerNode = result.nodes.find(n =>
    (n.data.label || '').includes('entity-form-handler')
  );
  if (handlerNode) {
    const pos = result['grid-pos']?.[handlerNode.data.id];
    console.log(`\nhandler: row=${pos?.row}, col=${pos?.col}`);

    // Find parents
    const parents = result.edges
      .filter(e => e.data.target === handlerNode.data.id)
      .map(e => {
        const src = result.nodes.find(n => n.data.id === e.data.source);
        const srcPos = result['grid-pos']?.[e.data.source];
        return {
          label: (src?.data?.label || '').substring(0, 25).replace(/\n/g, '|'),
          row: srcPos?.row,
          col: srcPos?.col
        };
      });
    console.log('handler parents:');
    parents.forEach(p => {
      console.log(`  [row=${p.row}, col=${p.col}] ${p.label}`);
    });
  }

  await browser.close();
})();
