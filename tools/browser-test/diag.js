const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({viewport:{width:2200,height:1400}})).newPage();
  await page.goto('http://localhost:9002/#web-server', { waitUntil: 'networkidle' });
  await page.waitForTimeout(700);

  // Before expansion — what edges exist?
  const before = await page.evaluate(() => {
    if (!window.cy) return 'no cy';
    return window.cy.edges().map(e => ({
      src: e.source().data('originalFnId') ? (document.querySelector(`[data-node-id="${e.source().id()}"] .ancestor-line`)?.textContent.trim() || e.source().id().slice(-8)) : e.source().id().slice(-8),
      tgt: e.target().data('originalFnId') ? (document.querySelector(`[data-node-id="${e.target().id()}"] .ancestor-line`)?.textContent.trim() || e.target().id().slice(-8)) : e.target().id().slice(-8),
      argName: e.data('argName')
    }));
  });
  console.log('BEFORE EXPAND:');
  before.forEach(e => console.log(`  ${e.src} --${e.argName}--> ${e.tgt}`));

  // Now expand path-gated-response
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
  await page.waitForTimeout(1200);

  const after = await page.evaluate(() => {
    if (!window.cy) return 'no cy';
    return window.cy.edges().map(e => ({
      src: document.querySelector(`[data-node-id="${e.source().id()}"] .ancestor-line`)?.textContent.trim() || e.source().id().slice(-8),
      tgt: document.querySelector(`[data-node-id="${e.target().id()}"] .ancestor-line`)?.textContent.trim() || e.target().id().slice(-8),
      argName: e.data('argName')
    }));
  });
  console.log('\nAFTER EXPAND path-gated-response:');
  after.forEach(e => console.log(`  ${e.src} --${e.argName}--> ${e.tgt}`));
  await browser.close();
})();
