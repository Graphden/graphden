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

  // Look ONLY at the optional strip (italic + dashed top border). Its text is what matters.
  const data = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('.node-overlay[data-original-fn-id]').forEach(o => {
      const firstLine = o.querySelector('.ancestor-line');
      const fname = firstLine ? firstLine.textContent.trim() : '';
      // The badge div we emit has fontStyle italic + dashed top border
      const divs = o.querySelectorAll('div');
      for (const d of divs) {
        const style = d.getAttribute('style') || '';
        if (style.includes('dashed') && style.includes('italic')) {
          out.push({ fname, badge: d.textContent.trim() });
          break;
        }
      }
    });
    return out;
  });
  console.log(JSON.stringify(data, null, 2));

  // Also check for any DEFAULT edges
  const defaultEdges = await page.evaluate(() => {
    const res = [];
    document.querySelectorAll('.edge-label-overlay').forEach(el => {
      if (el.textContent.trim() === 'default') res.push(el.dataset.edgeId);
    });
    return res.length;
  });
  console.log('default-edge count:', defaultEdges);

  await browser.close();
})();
