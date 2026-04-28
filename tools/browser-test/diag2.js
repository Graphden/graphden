const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2200,height:1400}})).newPage();
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

  // BEFORE EXPANSION
  const before = await page.evaluate(() => {
    if (!window.cy) return 'no cy';
    const nameMap = {};
    document.querySelectorAll('.node-overlay[data-node-id]').forEach(o => {
      const l = o.querySelector('.ancestor-line');
      nameMap[o.dataset.nodeId] = l ? l.textContent.trim() : '';
    });
    return window.cy.edges().map(e => ({
      src: nameMap[e.source().id()] || e.source().id().slice(-12),
      tgt: nameMap[e.target().id()] || e.target().id().slice(-12),
      argName: e.data('argName')
    }));
  });
  console.log('=== BEFORE EXPAND ===');
  before.forEach(e => console.log(`  ${e.src} --${e.argName}--> ${e.tgt}`));

  await clickAnyRow('path-gated-response');
  await page.waitForTimeout(1200);

  const after = await page.evaluate(() => {
    const nameMap = {};
    document.querySelectorAll('.node-overlay[data-node-id]').forEach(o => {
      const l = o.querySelector('.ancestor-line');
      nameMap[o.dataset.nodeId] = l ? l.textContent.trim() : '';
    });
    return window.cy.edges().map(e => ({
      src: nameMap[e.source().id()] || e.source().id().slice(-12),
      tgt: nameMap[e.target().id()] || e.target().id().slice(-12),
      argName: e.data('argName')
    }));
  });
  console.log('\n=== AFTER EXPAND ===');
  after.forEach(e => console.log(`  ${e.src} --${e.argName}--> ${e.tgt}`));
  await browser.close();
})();
