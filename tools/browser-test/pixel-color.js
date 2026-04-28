const { chromium } = require('playwright');
const BASE_URL = 'http://localhost:9002';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  await context.addInitScript(() => localStorage.setItem('graphden.prefs.theme', 'dark'));
  const page = await context.newPage();
  await page.goto(BASE_URL + '/#app.server.web-server');
  await page.waitForTimeout(1500);
  // Get bounding box of the 'router-ring-response' row
  const info = await page.evaluate(() => {
    const lines = document.querySelectorAll('.node-overlay .ancestor-line');
    for (const ln of lines) {
      if (ln.textContent.includes('router-ring-response')) {
        const r = ln.getBoundingClientRect();
        return { x: r.left + 30, y: r.top + r.height/2, w: r.width, h: r.height };
      }
    }
    return null;
  });
  console.log('Row position:', JSON.stringify(info));
  // Take a small screenshot of just that row
  await page.screenshot({ path: '/tmp/row-only.png', clip: { x: info.x - 30, y: info.y - 10, width: 200, height: 21 } });
  console.log('Saved /tmp/row-only.png');
  await browser.close();
})();
