const { chromium } = require('playwright');
const BASE_URL = 'http://localhost:9002';

async function run() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  await context.addInitScript(() => {
    localStorage.setItem('graphden.prefs.theme', 'dark');
  });
  const page = await context.newPage();
  page.on('console', m => console.log(`[${m.type()}] ${m.text()}`));
  page.on('pageerror', e => console.log(`[ERR] ${e.message}`));
  await page.goto(BASE_URL + '/#app.server.web-server');
  await page.waitForTimeout(900);
  await page.screenshot({ path: '/tmp/editor-dark.png', fullPage: false });
  console.log('Saved /tmp/editor-dark.png');
  await browser.close();
}
run();
