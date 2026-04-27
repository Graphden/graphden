const { chromium } = require('playwright');
const BASE_URL = 'http://localhost:9002';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  await context.addInitScript(() => {
    localStorage.setItem('graphden.prefs.theme', 'dark');
  });
  const page = await context.newPage();
  await page.goto(BASE_URL + '/#app.server.web-server');
  await page.waitForTimeout(1100);
  const info = await page.evaluate(() => {
    const out = {};
    out.bodyClass = document.body.className;
    const bodyCs = getComputedStyle(document.body);
    out.bodyBg = bodyCs.background.slice(0, 100);
    const root = getComputedStyle(document.documentElement);
    out.varBg = root.getPropertyValue('--bg').trim();
    out.varFg = root.getPropertyValue('--fg').trim();
    const overlay = document.querySelector('.node-overlay');
    if (overlay) {
      const cs = getComputedStyle(overlay);
      out.overlayInline = overlay.style.background;
      out.overlayComputed = cs.backgroundColor;
      out.overlayBorder = cs.borderColor;
      const firstRow = overlay.querySelector('.ancestor-line');
      if (firstRow) {
        out.rowInline = firstRow.style.background;
        out.rowBoxShadow = firstRow.style.boxShadow;
        out.rowComputed = getComputedStyle(firstRow).backgroundColor;
      }
    }
    return out;
  });
  console.log(JSON.stringify(info, null, 2));
  await browser.close();
})();
