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
            if (t === label) { await l.click(); await page.waitForTimeout(300); return true; }
          }
        } catch (_) { continue; }
      }
      await page.waitForTimeout(200);
    }
    return false;
  }

  const toExpand = ['path-gated-response','router-ring-response','router-response-body','router-response-headers','router-response-status','router-result','internal-request','ring-method-entry','ring-method','ring-method-kw','ring-uri-entry','ring-uri','ring-query-string-entry','ring-query-string','ring-headers-entry','ring-headers','ring-body-entry','ring-body','ring-body-input-stream'];
  for (const name of toExpand) { await clickAnyRow(name); }
  await page.waitForTimeout(800);

  // For each target fn, list all outgoing edges with label 'default'
  const targets = ['ring-method-kw','ring-query-string','ring-body-input-stream','ring-uri','ring-headers'];
  for (const tgt of targets) {
    const result = await page.evaluate((label) => {
      let nodeId = null;
      document.querySelectorAll('.node-overlay[data-node-id]').forEach(o => {
        const l = o.querySelector('.ancestor-line');
        if (l && l.textContent.trim() === label) nodeId = o.dataset.nodeId;
      });
      if (!nodeId) return { label, found: false };
      const edges = [];
      document.querySelectorAll('.edge-label-overlay').forEach(el => {
        const eid = el.dataset.edgeId;
        if (eid && eid.includes(nodeId) && el.textContent.trim() === 'default') {
          edges.push({ edgeId: eid });
        }
      });
      const overlay = Array.from(document.querySelectorAll(`.node-overlay[data-node-id="${nodeId}"]`))[0];
      const optionalStrip = overlay ? overlay.textContent.match(/\?[a-z-]+/g) : null;
      return { label, found: true, defaultEdgeCount: edges.length, optionalStrip };
    }, tgt);
    console.log(JSON.stringify(result));
  }
  await browser.close();
})();
