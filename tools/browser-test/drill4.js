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

  // Dump all edges with argName=default and find sources
  const edges = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('.edge-label-overlay').forEach(el => {
      if (el.textContent.trim() === 'default') {
        out.push({ edgeId: el.dataset.edgeId, rect: el.getBoundingClientRect().toJSON() });
      }
    });
    return out;
  });
  console.log('default labels:', JSON.stringify(edges, null, 2));

  // Also dump node positions
  const nodes = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('.node-overlay[data-node-id]').forEach(o => {
      const firstLine = o.querySelector('.ancestor-line');
      out.push({
        nodeId: o.dataset.nodeId,
        firstLine: firstLine ? firstLine.textContent.trim() : null,
        rect: o.getBoundingClientRect().toJSON()
      });
    });
    return out.filter(n => n.rect.x > 0);
  });
  // match edges to source nodes by edgeId structure "e-val-fn-ROOT_SRC-ARG"
  edges.forEach(e => {
    const m = e.edgeId.match(/^e-val-fn-([0-9a-f-]+)_([0-9a-f-]+)-([0-9a-f-]+)$/);
    if (m) {
      const srcNodeId = `fn-${m[1]}_${m[2]}`;
      const src = nodes.find(n => n.nodeId === srcNodeId);
      console.log(`default edge (x=${Math.round(e.rect.x)},y=${Math.round(e.rect.y)}) comes from: ${src ? src.firstLine : 'UNKNOWN'}`);
    }
  });
  await browser.close();
})();
