const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2000,height:1200}})).newPage();
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
            if (t === label) { await l.click(); await page.waitForTimeout(400); return true; }
          }
        } catch (_) { continue; }
      }
      await page.waitForTimeout(200);
    }
    return false;
  }

  for (const name of ['path-gated-response','router-ring-response','router-response-body','router-result','internal-request','ring-method-entry','ring-method','ring-method-kw']) {
    await clickAnyRow(name);
  }
  await page.waitForTimeout(500);

  // Find mkw overlay, zoom in on it
  const box = await page.evaluate(() => {
    const os = Array.from(document.querySelectorAll('.node-overlay[data-original-fn-id]')).filter(o => {
      const l = o.querySelector('.ancestor-line');
      return l && l.textContent.trim() === 'ring-method-kw';
    });
    return os.length ? os[0].getBoundingClientRect().toJSON() : null;
  });
  console.log('mkw bounds:', box);
  if (box) {
    await page.screenshot({ path: '/tmp/editor-screenshot.png', clip: {
      x: Math.max(0, box.x - 40),
      y: Math.max(0, box.y - 30),
      width: Math.min(500, 2000 - box.x + 40),
      height: Math.min(200, 1200 - box.y + 30)
    }});
    console.log('screenshot taken');
  }
  await browser.close();
})();
