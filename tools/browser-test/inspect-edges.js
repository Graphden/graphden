const { chromium } = require('playwright');

const BASE_URL = process.env.EDITOR_URL || 'http://localhost:9002';

async function inspect() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newContext({ viewport: { width: 1400, height: 900 } }).then(c => c.newPage());

  // Capture all API requests to /api/graph/layout
  page.on('request', req => {
    if (req.url().includes('/api/graph/layout')) {
      console.log('\n>>> REQUEST:', req.method(), req.url());
      if (req.method() === 'POST') {
        console.log('Body:', req.postData());
      }
    }
  });
  page.on('response', async resp => {
    if (resp.url().includes('/api/graph/layout')) {
      const json = await resp.json().catch(() => null);
      if (json && json.edges) {
        console.log('<<< Edges in response:');
        json.edges.forEach(e => {
          console.log(`  ${e.data.id}`);
          console.log(`    argName: ${JSON.stringify(e.data.argName)}`);
        });
      }
    }
  });

  await page.goto(`${BASE_URL}/#app.common.method-map`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);

  console.log('\n=== AFTER INITIAL LOAD ===');

  // Click root to expand
  const overlay = await page.$('.node-overlay[data-original-fn-id]');
  const lines = await overlay.$$('.ancestor-line');
  console.log(`Available lines: ${lines.length}`);

  for (let i = 0; i < lines.length; i++) {
    const t = await lines[i].textContent();
    console.log(`  line[${i}]: "${t.trim()}"`);
  }

  if (lines.length > 1) {
    console.log('\n=== CLICKING line[1] ===');
    await lines[1].click();
    await page.waitForTimeout(800);
  }

  await browser.close();
}

inspect().catch(e => { console.error(e); process.exit(1); });
