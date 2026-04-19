const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2400,height:1600}})).newPage();
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

  const toExpand = ['path-gated-response','router-ring-response','router-response-body','router-response-headers','router-response-status','router-result','internal-request','ring-method-entry','ring-method','ring-method-kw'];
  for (const name of toExpand) { await clickAnyRow(name); }
  await page.waitForTimeout(800);
  await page.screenshot({ path: '/tmp/editor-screenshot.png', fullPage: false });
  await browser.close();
})();
