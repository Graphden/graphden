const { chromium } = require('playwright');
const BASE_URL = 'http://localhost:9002';

async function snap(theme, file) {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  await context.addInitScript((t) => localStorage.setItem('graphden.prefs.theme', t), theme);
  const page = await context.newPage();
  await page.goto(BASE_URL + '/#app.server.web-server');
  await page.waitForTimeout(1100);
  // Find an open-in-new-tab link and hover it
  const target = await page.evaluate(() => {
    const links = document.querySelectorAll('.open-in-new-tab');
    for (const l of links) {
      const r = l.getBoundingClientRect();
      if (r.width > 0) return { x: r.left + r.width/2, y: r.top + r.height/2, parent: l.closest('.ancestor-line')?.textContent?.slice(0,30) };
    }
    return null;
  });
  if (target) {
    console.log(`[${file}] hovering over ${target.parent} at`, target.x, target.y);
    await page.mouse.move(target.x, target.y);
    await page.waitForTimeout(250);
  }
  // Get computed styles on hover
  const info = await page.evaluate(() => {
    const links = document.querySelectorAll('.open-in-new-tab');
    const out = [];
    for (const l of links) {
      const cs = getComputedStyle(l);
      if (cs.backgroundColor !== 'rgba(0, 0, 0, 0)') {
        out.push({ bg: cs.backgroundColor, opacity: cs.opacity, parent: l.closest('.ancestor-line')?.textContent?.slice(0,25) });
      }
    }
    return out;
  });
  console.log(`[${file}] hovered:`, JSON.stringify(info));
  await page.screenshot({ path: '/tmp/' + file, clip: { x: 300, y: 350, width: 900, height: 200 } });
  await browser.close();
}

(async () => {
  await snap('light', 'hover-light.png');
  await snap('dark', 'hover-dark.png');
})();
