const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();
  page.on('console', msg => {
    if (msg.text().includes('invalid endpoints')) {
      console.log('EDGE ERROR:', msg.text().substring(0, 200));
    }
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Expand create-route to level 2
  await page.evaluate(() => {
    setExpansionLevel('35ed3970-9143-4a2d-b322-351080ec31bc', 2);
  });
  await new Promise(r => setTimeout(r, 2000));

  // Check positions
  const positions = await page.evaluate(() => {
    const result = [];
    cy.nodes().forEach(n => {
      const label = (n.data('label') || '').substring(0, 30).replace(/\n/g, '|');
      const pos = n.position();
      if (label.includes('entity-form') || label.includes('handler')) {
        result.push({
          id: n.id(),
          label: label,
          x: Math.round(pos.x),
          y: Math.round(pos.y)
        });
      }
    });
    return result.sort((a, b) => a.y - b.y || a.x - b.x);
  });

  console.log('Key node positions after expansion to level 2:');
  positions.forEach(p => {
    console.log(`  [${p.y}, ${p.x}] ${p.label}`);
  });

  // Check for problems - nodes that overlap or have edges going up
  const problems = await page.evaluate(() => {
    const issues = [];
    cy.edges().forEach(e => {
      const src = cy.getElementById(e.data('source'));
      const tgt = cy.getElementById(e.data('target'));
      if (src.length && tgt.length) {
        const srcY = src.position().y;
        const tgtY = tgt.position().y;
        const srcX = src.position().x;
        const tgtX = tgt.position().x;

        // Edge goes up (target higher than source)
        if (tgtY < srcY - 10) {
          issues.push({
            type: 'edge_up',
            edge: e.id(),
            srcLabel: (src.data('label') || '').substring(0, 20),
            tgtLabel: (tgt.data('label') || '').substring(0, 20),
            srcY: Math.round(srcY),
            tgtY: Math.round(tgtY)
          });
        }
      }
    });
    return issues;
  });

  if (problems.length > 0) {
    console.log('\nPROBLEMS FOUND:');
    problems.forEach(p => {
      console.log(`  ${p.type}: ${p.srcLabel} (y=${p.srcY}) -> ${p.tgtLabel} (y=${p.tgtY})`);
    });
  } else {
    console.log('\nNo problems found');
  }

  await browser.close();
})();
