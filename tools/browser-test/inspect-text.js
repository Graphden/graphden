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
    const out = [];
    document.querySelectorAll('.node-overlay .ancestor-line').forEach((ln, j) => {
      const cs = getComputedStyle(ln);
      out.push({
        i: j, bg: cs.backgroundColor, color: cs.color,
        text: ln.textContent.slice(0, 25)
      });
    });
    return out.slice(0, 8);
  });
  console.log(JSON.stringify(rows, null, 2));
  await browser.close();
})();
