// Browser test script - captures screenshot and console output from editor
// Usage: node check-editor.js [fn-name] [expand-spec...]
//
// Examples:
//   node check-editor.js web-server                    # Just view web-server
//   node check-editor.js web-server root:2             # Expand root node to level 2
//   node check-editor.js web-server root:1 router-fn:1 # Expand multiple nodes
//
// expand-spec format: "node-name:level" or "root:level" for root node

const { chromium } = require('playwright');

const BASE_URL = 'http://localhost:9002';
const args = process.argv.slice(2);
const fnName = args[0] || '';
const expandSpecs = args.slice(1); // e.g., ["root:2", "router-fn:1"]

async function checkEditor() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1400, height: 900 }
  });
  const page = await context.newPage();

  // Collect console messages
  const consoleLogs = [];
  page.on('console', msg => {
    consoleLogs.push(`[${msg.type()}] ${msg.text()}`);
  });

  // Collect errors
  page.on('pageerror', err => {
    consoleLogs.push(`[ERROR] ${err.message}`);
  });

  try {
    const url = fnName ? `${BASE_URL}/#${fnName}` : BASE_URL;
    console.log(`Loading: ${url}`);

    await page.goto(url, { waitUntil: 'networkidle' });

    // Wait for graph to render
    await page.waitForTimeout(500);

    // Process expand specs
    for (const spec of expandSpecs) {
      const [nodeName, levelStr] = spec.split(':');
      const level = parseInt(levelStr, 10) || 1;

      console.log(`Expanding: ${nodeName} to level ${level}`);

      // Find the overlay for this node
      let overlay;
      if (nodeName === 'root') {
        // Root node is the first fn overlay
        overlay = await page.$('.node-overlay[data-original-fn-id]');
      } else {
        // Find by node name in ancestor lines
        const overlays = await page.$$('.node-overlay[data-original-fn-id]');
        for (const o of overlays) {
          const firstLine = await o.$('.ancestor-line');
          if (firstLine) {
            const text = await firstLine.textContent();
            if (text && text.trim() === nodeName) {
              overlay = o;
              break;
            }
          }
        }
      }

      if (overlay) {
        // Click on the ancestor line at the desired level
        const lines = await overlay.$$('.ancestor-line');
        if (lines.length > level) {
          await lines[level].click();
          await page.waitForTimeout(300); // Wait for animation
          console.log(`  Clicked level ${level}`);
        } else {
          console.log(`  Warning: only ${lines.length} levels available`);
          if (lines.length > 0) {
            await lines[lines.length - 1].click();
            await page.waitForTimeout(300);
          }
        }
      } else {
        console.log(`  Warning: node "${nodeName}" not found`);
      }
    }

    // Wait for final render
    await page.waitForTimeout(500);

    // Take screenshot
    const screenshotPath = `/tmp/editor-screenshot.png`;
    await page.screenshot({ path: screenshotPath, fullPage: false });
    console.log(`\nScreenshot saved: ${screenshotPath}`);

    // Print console output
    console.log('\n=== Console Output ===');
    consoleLogs.forEach(log => console.log(log));

    // Check for build timestamp
    const buildLog = consoleLogs.find(l => l.includes('Build:'));
    if (buildLog) {
      console.log('\n=== Build Info ===');
      console.log(buildLog);
    }

    // Check for errors
    const errors = consoleLogs.filter(l => l.includes('[error]') || l.includes('[ERROR]'));
    if (errors.length > 0) {
      console.log('\n=== Errors ===');
      errors.forEach(e => console.log(e));
    }

  } catch (err) {
    console.error('Error:', err.message);
  } finally {
    await browser.close();
  }
}

checkEditor();
