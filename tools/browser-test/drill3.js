const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2400,height:1400}})).newPage();
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

  for (const name of ['path-gated-response','router-ring-response','router-response-body','router-result','internal-request','ring-method-entry','ring-method','ring-method-kw']) {
    await clickAnyRow(name);
  }
  await page.waitForTimeout(700);

  // Search for edges labelled 'default'
  const defs = await page.evaluate(() => {
    const labels = [];
    document.querySelectorAll('.edge-label-overlay').forEach(el => {
      labels.push({
        text: el.textContent.trim(),
        edgeId: el.dataset.edgeId
      });
    });
    return labels.filter(l => l.text && l.text.includes('default'));
  });
  console.log('default labels:', JSON.stringify(defs, null, 2));

  // find the mkw node to see its right-hand side
  const mkwBox = await page.evaluate(() => {
    const o = document.querySelector('.node-overlay[data-original-fn-id]');
    const mkws = Array.from(document.querySelectorAll('.node-overlay[data-original-fn-id]')).filter(o => {
      const l = o.querySelector('.ancestor-line');
      return l && l.textContent.trim() === 'ring-method-kw';
    });
    return mkws.map(o => o.getBoundingClientRect().toJSON());
  });
  console.log('mkw box:', JSON.stringify(mkwBox, null, 2));

  // Take a targeted screenshot around mkw
  if (mkwBox.length) {
    const b = mkwBox[0];
    await page.screenshot({ path: '/tmp/editor-screenshot.png', clip: {
      x: Math.max(0, b.x - 100),
      y: Math.max(0, b.y - 50),
      width: Math.min(1200, 2400 - b.x + 100),
      height: 300
    }});
  }
  await browser.close();
})();
