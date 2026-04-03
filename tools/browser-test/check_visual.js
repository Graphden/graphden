const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  // Capture console messages
  page.on('console', msg => {
    if (msg.type() === 'error') {
      console.log('CONSOLE ERROR:', msg.text());
    }
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Check initial state
  const initialNodes = await page.evaluate(() => {
    return cy ? cy.nodes().length : 0;
  });
  console.log('Initial nodes:', initialNodes);

  // Click on entity-form-create-route to expand
  const createRouteNode = await page.evaluate(() => {
    const nodes = cy.nodes();
    for (let i = 0; i < nodes.length; i++) {
      const label = nodes[i].data('label') || '';
      if (label.includes('entity-form-create-route')) {
        return nodes[i].id();
      }
    }
    return null;
  });

  if (createRouteNode) {
    console.log('Found create-route node:', createRouteNode);

    // Simulate click to expand
    await page.evaluate((nodeId) => {
      const node = cy.getElementById(nodeId);
      if (node) {
        node.trigger('tap');
      }
    }, createRouteNode);

    await new Promise(r => setTimeout(r, 1000));

    // Check nodes after click
    const afterClickNodes = await page.evaluate(() => {
      return cy ? cy.nodes().length : 0;
    });
    console.log('Nodes after click:', afterClickNodes);

    // Check for overlapping nodes
    const overlaps = await page.evaluate(() => {
      const positions = {};
      const overlapping = [];
      cy.nodes().forEach(n => {
        const pos = n.position();
        const key = Math.round(pos.x) + ',' + Math.round(pos.y);
        if (positions[key]) {
          overlapping.push(positions[key] + ' and ' + n.id() + ' at ' + key);
        } else {
          positions[key] = n.id();
        }
      });
      return overlapping;
    });

    if (overlaps.length > 0) {
      console.log('OVERLAPPING NODES:');
      overlaps.forEach(o => console.log('  ' + o));
    } else {
      console.log('No overlapping nodes detected');
    }
  }

  await browser.close();
})();
