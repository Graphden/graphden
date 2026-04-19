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

  // Capture expansions state
  const state = await page.evaluate(() => {
    const es = {};
    if (window.expansionState) {
      window.expansionState.forEach((spec, nodeId) => {
        es[nodeId] = { fullDepth: spec.fullDepth, partialFns: Array.from(spec.partialFns || []) };
      });
    }
    return { expansions: es, selected: window.selectedFnId };
  });
  console.log(JSON.stringify(state, null, 2));
  await browser.close();
})();
