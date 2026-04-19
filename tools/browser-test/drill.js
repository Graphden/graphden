const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:1800,height:1100}})).newPage();
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

  for (const name of ['path-gated-response','router-ring-response','router-response-body','router-result','internal-request','ring-method-entry','ring-method']) {
    console.log(`click ${name}:`, await clickAnyRow(name));
  }
  await page.waitForTimeout(700);
  await page.screenshot({ path: '/tmp/editor-screenshot.png', fullPage: false });

  // dump ring-method-kw overlay
  const overlays = await page.$$('.node-overlay[data-original-fn-id]');
  for (const o of overlays) {
    try {
      const lines = await o.$$('.ancestor-line');
      for (const l of lines) {
        const t = (await l.textContent() || '').trim();
        if (t === 'ring-method-kw') {
          const html = await o.evaluate(el => el.outerHTML);
          console.log('MKW overlay text:', (await o.textContent()).trim().replace(/\s+/g,' '));
          break;
        }
      }
    } catch (_) {}
  }
  await browser.close();
})();
