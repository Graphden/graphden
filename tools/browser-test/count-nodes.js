const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2400,height:1400}})).newPage();
  await page.goto('http://localhost:9002/#web-server', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  async function clickAnyRow(label) {
    for (let a = 0; a < 3; a++) {
      const os = await page.$$('.node-overlay[data-original-fn-id]');
      for (const o of os) {
        try {
          const ls = await o.$$('.ancestor-line');
          for (const l of ls) {
            const t = (await l.textContent() || '').trim();
            if (t === label) { await l.click(); await page.waitForTimeout(250); return true; }
          }
        } catch (_) {}
      }
      await page.waitForTimeout(100);
    }
    return false;
  }
  for (const n of ['path-gated-response','router-ring-response','router-response-body','router-response-headers','router-response-status','router-result','internal-request','ring-method-entry','ring-method','ring-method-kw','ring-uri-entry','ring-uri','ring-query-string-entry','ring-query-string','ring-headers-entry','ring-headers','ring-body-entry','ring-body','ring-body-input-stream']) {
    await clickAnyRow(n);
  }
  await page.waitForTimeout(800);
  const counts = await page.evaluate(() => {
    const tally = {};
    document.querySelectorAll('.node-overlay[data-original-fn-id]').forEach(o => {
      const l = o.querySelector('.ancestor-line');
      if (!l) return;
      const t = l.textContent.trim();
      tally[t] = (tally[t] || 0) + 1;
    });
    return Object.entries(tally).filter(([k,v]) => v > 1).sort((a,b) => b[1] - a[1]);
  });
  console.log('duplicated nodes:');
  counts.forEach(([k,v]) => console.log(`  ${k} × ${v}`));
  await browser.close();
})();
