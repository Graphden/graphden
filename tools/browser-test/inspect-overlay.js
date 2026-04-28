const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  const page = await ctx.newPage();
  await page.goto('http://localhost:9002/#app.server.internal-request', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  const overlays = await page.$$('.node-overlay[data-original-fn-id]');
  console.log('overlay count:', overlays.length);
  for (const o of overlays.slice(0, 4)) {
    const lines = await o.$$('.ancestor-line');
    const texts = [];
    for (const l of lines) {
      const t = await l.textContent();
      const info = await l.evaluate(el => {
        const cs = window.getComputedStyle(el);
        return { bg: cs.backgroundColor, height: el.offsetHeight, padding: cs.padding };
      });
      texts.push({text: JSON.stringify(t), ...info});
    }
    console.log('node:', texts);
  }
  await browser.close();
})();
