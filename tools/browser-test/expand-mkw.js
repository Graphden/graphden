const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:1600,height:1000}})).newPage();
  await page.goto('http://localhost:9002/#web-server', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);

  async function clickAnyRow(label) {
    const overlays = await page.$$('.node-overlay[data-original-fn-id]');
    for (const o of overlays) {
      const lines = await o.$$('.ancestor-line');
      for (const l of lines) {
        const t = (await l.textContent() || '').trim();
        if (t === label) { await l.click(); await page.waitForTimeout(400); return true; }
      }
    }
    return false;
  }

  for (const name of ['path-gated-response','router-ring-response','router-response-body','router-result','internal-request','ring-method-entry','ring-method']) {
    const ok = await clickAnyRow(name);
    console.log(`click ${name}:`, ok);
  }
  await page.waitForTimeout(500);
  await page.screenshot({ path: '/tmp/editor-screenshot.png', fullPage: false });

  const overlays = await page.$$('.node-overlay[data-original-fn-id]');
  for (const o of overlays) {
    const lines = await o.$$('.ancestor-line');
    for (const l of lines) {
      const t = (await l.textContent() || '').trim();
      if (t === 'ring-method-kw') {
        const html = await o.evaluate(el => el.outerHTML);
        console.log('MKW OVERLAY HTML:');
        console.log(html.substring(0, 4000));
        break;
      }
    }
  }
  await browser.close();
})();
