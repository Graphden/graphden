const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2400,height:1400}})).newPage();
  await page.goto('http://localhost:9002/#web-server', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);

  async function clickAnyRow(label) {
    for (let attempt = 0; attempt < 3; attempt++) {
      const overlays = await page.$$('.node-overlay[data-original-fn-id]');
      for (const o of overlays) {
        try {
          const lines = await o.$$('.ancestor-line');
          for (const l of lines) {
            const t = (await l.textContent() || '').trim();
            if (t === label) { await l.click(); await page.waitForTimeout(500); return true; }
          }
        } catch (_) { continue; }
      }
      await page.waitForTimeout(300);
    }
    return false;
  }

  for (const name of ['path-gated-response','router-ring-response','router-response-body','router-result','internal-request','ring-method-entry','ring-method','ring-method-kw']) {
    console.log(`click ${name}:`, await clickAnyRow(name));
  }
  await page.waitForTimeout(700);

  // Find ring-method-kw node-id
  const mkwInfo = await page.evaluate(() => {
    const res = [];
    document.querySelectorAll('.node-overlay[data-original-fn-id]').forEach(o => {
      const lines = Array.from(o.querySelectorAll('.ancestor-line')).map(l => l.textContent.trim());
      if (lines[0] === 'ring-method-kw') {
        res.push({nodeId: o.dataset.nodeId, lines});
      }
    });
    return res;
  });
  console.log('ring-method-kw overlays:', JSON.stringify(mkwInfo, null, 2));

  // Now fetch all edges from cytoscape originating at ring-method-kw
  if (mkwInfo.length) {
    const edges = await page.evaluate((nodeId) => {
      if (!window.cy) return 'no cy';
      const n = window.cy.getElementById(nodeId);
      if (!n.length) return 'node not in cy';
      return n.outgoers('edge').map(e => ({
        id: e.id(),
        argName: e.data('argName'),
        target: e.target().id(),
        targetLabel: e.target().data('label')
      }));
    }, mkwInfo[0].nodeId);
    console.log('ring-method-kw outgoing edges:', JSON.stringify(edges, null, 2));
  }
  await browser.close();
})();
