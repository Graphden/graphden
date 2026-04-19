const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2400,height:1400}})).newPage();
  await page.goto('http://localhost:9002/#web-server', { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
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
  await clickAnyRow('path-gated-response');
  await page.waitForTimeout(800);

  // For each node, count parents (incoming edges) and dedupe duplicate node instances
  const data = await page.evaluate(() => {
    // Build node-id → label map
    const nodeInfo = new Map();
    document.querySelectorAll('.node-overlay[data-node-id]').forEach(o => {
      const lines = Array.from(o.querySelectorAll('.ancestor-line')).map(l => l.textContent.trim()).filter(x => x);
      nodeInfo.set(o.dataset.nodeId, { firstLine: lines[0] || '', all: lines, rect: o.getBoundingClientRect().toJSON() });
    });
    // Edges: parse edge-id format. For each edge, find source/target node-id from label overlay data
    // Labels: edge-id in dataset
    // Strategy: scan all cytoscape edges via DOM (we can't access cy obj). Use edge labels we have.
    // Better: call window.cy via debug hook? It's not exposed. Let's use window.graphEdges if set.
    const edgesByTarget = new Map();
    const edgesBySource = new Map();
    if (window.graphEdges) {
      window.graphEdges.forEach(e => {
        const {source, target, argName} = e.data;
        if (!edgesByTarget.has(target)) edgesByTarget.set(target, []);
        edgesByTarget.get(target).push({ source, argName });
        if (!edgesBySource.has(source)) edgesBySource.set(source, []);
        edgesBySource.get(source).push({ target, argName });
      });
    }
    // Report: for each named fn, count how many node-instances exist and their in-edges
    const byName = new Map();
    nodeInfo.forEach((info, nid) => {
      const n = info.firstLine;
      if (!n) return;
      if (!byName.has(n)) byName.set(n, []);
      byName.get(n).push({ nid, inEdges: edgesByTarget.get(nid) || [] });
    });
    const out = [];
    byName.forEach((insts, n) => {
      if (insts.length > 1 || (insts[0].inEdges.length > 1)) {
        out.push({ name: n, count: insts.length, insts: insts.map(i => ({
          inEdgeCount: i.inEdges.length,
          inFromArgs: i.inEdges.map(e => e.argName)
        })) });
      }
    });
    return out;
  });
  console.log(JSON.stringify(data, null, 2));
  await browser.close();
})();
