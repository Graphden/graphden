const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2400,height:1400}})).newPage();
  let lastReq = null;
  page.on('request', r => { if (r.url().includes('/api/graph/layout')) { try { lastReq = JSON.parse(r.postData() || '{}'); } catch(_) {} } });
  await page.goto('http://localhost:9002/#web-server', { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  async function clickAnyRow(label) {
    const os = await page.$$('.node-overlay[data-original-fn-id]');
    for (const o of os) {
      try {
        const ls = await o.$$('.ancestor-line');
        for (const l of ls) {
          const t = (await l.textContent() || '').trim();
          if (t === label) { await l.click(); await page.waitForTimeout(500); return true; }
        }
      } catch (_) {}
    }
    return false;
  }
  await clickAnyRow('path-gated-response');
  await page.waitForTimeout(800);
  console.log(JSON.stringify(lastReq, null, 2));
  await browser.close();
})();
