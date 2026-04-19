const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2400,height:1400}})).newPage();
  await page.goto('http://localhost:9002/#web-server', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  async function clickAnyRow(label) {
    for (let a = 0; a < 3; a++) {
      const os = await page.$$('.node-overlay[data-original-fn-id]');
      for (const o of os) {
        try {
          const ls = await o.$$('.ancestor-line');
          for (const l of ls) {
            const t = (await l.textContent() || '').trim();
            if (t === label) { await l.click(); await page.waitForTimeout(250); return true; }
          }
        } catch (_) {}
      }
      await page.waitForTimeout(100);
    }
    return false;
  }
  for (const n of ['path-gated-response','router-ring-response','router-response-body','router-response-headers','router-response-status','router-result','internal-request','ring-method-entry','ring-method','ring-method-kw','ring-query-string-entry','ring-query-string','ring-body-entry','ring-body','ring-body-input-stream']) {
    await clickAnyRow(n);
  }
  await page.waitForTimeout(800);

  // Find all three target nodes and pan viewport to cover them
  const focus = await page.evaluate(() => {
    const names = ['ring-method-kw','ring-query-string','ring-body-input-stream'];
    const rects = [];
    names.forEach(n => {
      document.querySelectorAll('.node-overlay[data-original-fn-id]').forEach(o => {
        const l = o.querySelector('.ancestor-line');
        if (l && l.textContent.trim() === n) rects.push({ n, r: o.getBoundingClientRect().toJSON() });
      });
    });
    return rects;
  });
  console.log(JSON.stringify(focus, null, 2));
  if (focus.length > 0) {
    const xs = focus.map(f => f.r.x).concat(focus.map(f => f.r.right));
    const ys = focus.map(f => f.r.y).concat(focus.map(f => f.r.bottom));
    const x = Math.max(0, Math.min(...xs) - 100);
    const y = Math.max(0, Math.min(...ys) - 100);
    const w = Math.min(2400 - x, Math.max(...xs) - x + 200);
    const h = Math.min(1400 - y, Math.max(...ys) - y + 200);
    await page.screenshot({ path: '/tmp/editor-screenshot.png', clip: { x, y, width: w, height: h } });
  }
  await browser.close();
})();
