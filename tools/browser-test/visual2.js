const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2200,height:1400}})).newPage();
  await page.goto('http://localhost:9002/#web-server', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
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
  await page.waitForTimeout(1200);
  // click home/reset button to fit view
  const homeBtn = await page.$('[title="Reset zoom"]');
  if (homeBtn) { await homeBtn.click(); await page.waitForTimeout(500); }
  await page.screenshot({ path: '/tmp/editor-screenshot.png', fullPage: false });
  await browser.close();
})();
