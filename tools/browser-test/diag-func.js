const p = require('playwright');

(async () => {
  const b = await p.chromium.launch();
  const ctx = await b.newContext();
  const page = await ctx.newPage();
  await page.goto('http://localhost:9002/#app.server.web-server');
  await page.waitForTimeout(1500);

  async function report(label) {
    const data = await page.evaluate(() => {
      const r = { nodes: [], edges: [] };
      if (!window.cy) return r;
      window.cy.nodes().forEach(n => {
        const d = n.data();
        r.nodes.push({
          id: n.id().slice(-8),
          label: (d.label || '').replace(/\n/g, '/'),
          kind: d.kind,
        });
      });
      window.cy.edges().forEach(e => {
        const d = e.data();
        const src = window.cy.getElementById(d.source);
        const tgt = window.cy.getElementById(d.target);
        r.edges.push({
          name: d.argName,
          src: (src.data('label') || d.source).replace(/\n/g, '/'),
          tgt: (tgt.data('label') || d.target).replace(/\n/g, '/'),
        });
      });
      return r;
    });
    console.log(`\n== ${label} ==`);
    console.log(`Nodes: ${data.nodes.length}, Edges: ${data.edges.length}`);
    console.log('Nodes:');
    data.nodes.forEach(n => console.log(`  [${n.id}] ${n.kind || '-'}: ${n.label}`));
    console.log('Edges:');
    data.edges.forEach(e => console.log(`  ${e.src} --${e.name}--> ${e.tgt}`));
  }

  await report('INITIAL');

  // Expand path-gated-response — dispatch mousedown (handler listens for mousedown)
  await page.evaluate(() => {
    const overlays = document.querySelectorAll('.node-overlay');
    for (const o of overlays) {
      const lines = o.querySelectorAll('.ancestor-line');
      for (const l of lines) {
        if (l.textContent.trim() === 'path-gated-response') {
          const target = lines[lines.length - 1];
          const rect = target.getBoundingClientRect();
          target.dispatchEvent(new MouseEvent('mousedown', {
            bubbles: true, cancelable: true,
            clientX: rect.left + rect.width/2,
            clientY: rect.top + rect.height/2,
            button: 0,
          }));
          return;
        }
      }
    }
  });
  await page.waitForTimeout(1000);

  await report('AFTER deep expand path-gated-response');

  await page.screenshot({ path: '/tmp/after-expand.png', fullPage: false });

  await b.close();
})();
