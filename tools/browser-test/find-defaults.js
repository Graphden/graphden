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
            if (t === label) { await l.click(); await page.waitForTimeout(250); return true; }
          }
        } catch (_) { continue; }
      }
      await page.waitForTimeout(100);
    }
    return false;
  }
  for (const n of ['path-gated-response','router-ring-response','router-response-body','router-response-headers','router-response-status','router-result','internal-request','ring-method-entry','ring-method','ring-method-kw','ring-uri-entry','ring-uri','ring-query-string-entry','ring-query-string','ring-headers-entry','ring-headers','ring-body-entry','ring-body','ring-body-input-stream']) {
    await clickAnyRow(n);
  }
  await page.waitForTimeout(800);

  // Find all default edges and where they come from
  const result = await page.evaluate(() => {
    const edges = [];
    document.querySelectorAll('.edge-label-overlay').forEach(el => {
      if (el.textContent.trim() !== 'default') return;
      const eid = el.dataset.edgeId;
      edges.push({ edgeId: eid });
    });
    return edges;
  });
  console.log(JSON.stringify(result, null, 2));
  await browser.close();
})();
