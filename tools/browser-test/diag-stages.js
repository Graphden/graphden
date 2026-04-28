const p = require('playwright');

(async () => {
  const b = await p.chromium.launch();
  const ctx = await b.newContext();
  const page = await ctx.newPage();
  await page.goto('http://localhost:9002/#app.server.web-server');
  await page.waitForFunction(() => window.cy && typeof window.cy.nodes === 'function', { timeout: 10000 });
  await page.waitForTimeout(500);

  async function report(label) {
    const data = await page.evaluate(() => {
      const r = { nodes: [], edges: [] };
      if (!window.cy) return r;
      window.cy.nodes().forEach(n => r.nodes.push({
        id: n.id().slice(-8), label: (n.data('label') || '').replace(/\n/g, '/'),
      }));
      window.cy.edges().forEach(e => {
        const d = e.data();
        const src = window.cy.getElementById(d.source);
        const tgt = window.cy.getElementById(d.target);
        r.edges.push({
          name: d.argName,
          src: (src.data('label') || '').replace(/\n/g, '/'),
          tgt: (tgt.data('label') || '').replace(/\n/g, '/'),
        });
      });
      return r;
    });
    console.log(`\n== ${label} == N=${data.nodes.length} E=${data.edges.length}`);
    // Only print func-related and path-gated-related edges
    const rel = data.edges.filter(e =>
      /func|path-gated|token-gated|text-error|router-ring|router-result|router-response/.test(e.src + e.tgt) ||
      e.name === 'func');
    rel.forEach(e => console.log(`  ${e.src} --${e.name}--> ${e.tgt}`));
  }

  async function clickLastLine(text) {
    await page.evaluate((text) => {
      const overlays = document.querySelectorAll('.node-overlay');
      for (const o of overlays) {
        const lines = o.querySelectorAll('.ancestor-line');
        for (const l of lines) {
          if (l.textContent.trim() === text) {
            const last = lines[lines.length - 1];
            const r = last.getBoundingClientRect();
            last.dispatchEvent(new MouseEvent('mousedown', {
              bubbles: true, cancelable: true,
              clientX: r.left + r.width/2, clientY: r.top + r.height/2,
              button: 0,
            }));
            return;
          }
        }
      }
    }, text);
    await page.waitForTimeout(800);
  }

  async function clickLine(text, idx) {
    await page.evaluate(({text, idx}) => {
      const overlays = document.querySelectorAll('.node-overlay');
      for (const o of overlays) {
        const lines = o.querySelectorAll('.ancestor-line');
        for (const l of lines) {
          if (l.textContent.trim() === text) {
            const t = lines[idx];
            const r = t.getBoundingClientRect();
            t.dispatchEvent(new MouseEvent('mousedown', {
              bubbles: true, cancelable: true,
              clientX: r.left + r.width/2, clientY: r.top + r.height/2,
              button: 0,
            }));
            return;
          }
        }
      }
    }, {text, idx});
    await page.waitForTimeout(800);
  }

  await report('INITIAL');

  // Click path-gated-response line 0 (its own name) — toggles level-0 (collapse)
  // Then click line 1 (expand to parent) — this expands path-gated-response 1 step
  await clickLine('path-gated-response', 1);
  await report('AFTER click path-gated-response line 1');

  await clickLine('path-gated-response', 2);
  await report('AFTER click path-gated-response line 2');

  await clickLastLine('path-gated-response');
  await report('AFTER click path-gated-response last line');

  await b.close();
})();
