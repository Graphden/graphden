const { chromium } = require('playwright');
const BASE_URL = 'http://localhost:9002';
const URL_HASH = '#app.server.web-server';

async function snap(theme, collapsed, file) {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  await context.addInitScript(({ theme, collapsed }) => {
    localStorage.setItem('graphden.prefs.theme', theme);
    localStorage.setItem('graphden.prefs.sidebar-collapsed', collapsed ? '1' : '0');
  }, { theme, collapsed });
  const page = await context.newPage();
  page.on('pageerror', e => console.log(`[ERR ${file}] ${e.message}`));
  await page.goto(BASE_URL + '/' + URL_HASH);
  await page.waitForTimeout(1100);
  await page.screenshot({ path: '/tmp/' + file });
  await browser.close();
  console.log('Saved /tmp/' + file);
}

(async () => {
  await snap('light', false, 'state-light.png');
  await snap('dark', false, 'state-dark.png');
  await snap('light', true, 'state-light-collapsed.png');
  await snap('dark', true, 'state-dark-collapsed.png');
})();
