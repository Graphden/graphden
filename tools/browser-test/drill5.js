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

  const result = await page.evaluate(() => {
    // find mkw node id
    let mkwId = null;
    document.querySelectorAll('.node-overlay[data-node-id]').forEach(o => {
      const l = o.querySelector('.ancestor-line');
      if (l && l.textContent.trim() === 'ring-method-kw') mkwId = o.dataset.nodeId;
    });
    // find edges whose id mentions mkwId as source (fn-ROOT_MKW-...)
    const edges = [];
    if (window.graphEdges) {
      graphEdges.forEach(e => {
        if (e.data.source === mkwId || (e.data.source && e.data.source.includes(mkwId.replace(/^fn-[^_]+_/, 'fn-')))) {
          edges.push({id: e.data.id, source: e.data.source, target: e.data.target, argName: e.data.argName});
        }
      });
    }
    return { mkwId, edges };
  });
  console.log('mkwId:', result.mkwId);
  console.log('edges from mkw:', result.edges);

  // simpler: list all edge label overlays with same source node as mkw's node-id
  const edgesFromMkw = await page.evaluate(() => {
    let mkwId = null;
    document.querySelectorAll('.node-overlay[data-node-id]').forEach(o => {
      const l = o.querySelector('.ancestor-line');
      if (l && l.textContent.trim() === 'ring-method-kw') mkwId = o.dataset.nodeId;
    });
    if (!mkwId) return 'mkw not found';
    const results = [];
    document.querySelectorAll('.edge-label-overlay').forEach(el => {
      const eid = el.dataset.edgeId;
      // edge-id pattern often contains source node id
      if (eid && eid.includes(mkwId)) {
        results.push({ edgeId: eid, label: el.textContent.trim() });
      }
    });
    return results;
  });
  console.log('edges starting from mkw:', JSON.stringify(edgesFromMkw, null, 2));

  await browser.close();
})();
