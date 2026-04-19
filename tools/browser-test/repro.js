const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2400,height:1400}})).newPage();
  await page.goto('http://localhost:9002/#web-server', { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);

  // Single click: path-gated-response's ancestor row.
  async function clickAnyRow(label) {
    const os = await page.$$('.node-overlay[data-original-fn-id]');
    for (const o of os) {
      try {
        const ls = await o.$$('.ancestor-line');
        for (const l of ls) {
          const t = (await l.textContent() || '').trim();
          if (t === label) { await l.click(); await page.waitForTimeout(500); return true; }
        }
      } catch (_) {}
    }
    return false;
  }
  const ok = await clickAnyRow('path-gated-response');
  console.log('clicked path-gated-response:', ok);
  await page.waitForTimeout(800);

  // Dump all nodes containing "default" as edge label
  const edges = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('.edge-label-overlay').forEach(el => {
      if (el.textContent.trim() === 'default') {
        out.push({ edgeId: el.dataset.edgeId });
      }
    });
    return out;
  });
  console.log('default edges:', edges.length);
  edges.forEach(e => console.log(' ', e.edgeId));

  // Find ring-method-kw's node & dump its structure
  const mkw = await page.evaluate(() => {
    const os = Array.from(document.querySelectorAll('.node-overlay[data-original-fn-id]')).filter(o => {
      const l = o.querySelector('.ancestor-line');
      return l && l.textContent.trim() === 'ring-method-kw';
    });
    return os.map(o => ({
      nodeId: o.dataset.nodeId,
      text: o.textContent.trim().replace(/\s+/g, ' '),
      rect: o.getBoundingClientRect().toJSON()
    }));
  });
  console.log('ring-method-kw overlays:', JSON.stringify(mkw, null, 2));

  await browser.close();
})();
