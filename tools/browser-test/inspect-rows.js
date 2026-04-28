const { chromium } = require('playwright');
const BASE_URL = 'http://localhost:9002';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  await context.addInitScript(() => localStorage.setItem('graphden.prefs.theme', 'dark'));
  const page = await context.newPage();
  await page.goto(BASE_URL + '/#app.server.web-server');
  await page.waitForTimeout(1100);
  const rows = await page.evaluate(() => {
    const overlays = document.querySelectorAll('.node-overlay');
    const out = [];
    overlays.forEach((ov, i) => {
      const lines = ov.querySelectorAll('.ancestor-line');
      lines.forEach((ln, j) => {
        const cs = getComputedStyle(ln);
        out.push({
          overlay: i, line: j,
          inlineBg: ln.style.background,
          computedBg: cs.backgroundColor,
          text: ln.textContent.slice(0, 30)
        });
      });
    });
    return out;
  });
  console.log(JSON.stringify(rows, null, 2));
  await browser.close();
})();
