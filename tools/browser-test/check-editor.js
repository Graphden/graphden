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

const BASE_URL = process.env.EDITOR_URL || 'http://localhost:9002';
const args = process.argv.slice(2);
const fnName = args[0] || '';
const expandSpecs = args.slice(1); // e.g., ["root:2", "router-fn:1"]

async function checkEditor() {
  // Args mirror edit-test-helpers.js — needed so the renderer
  // doesn't crash in restrictive container environments.
  const browser = await chromium.launch({
    headless: true,
    args: [
      '--js-flags=--max-old-space-size=1024',
      '--disable-dev-shm-usage',
      '--no-sandbox',
      '--no-zygote',
      '--in-process-gpu',
    ],
  });
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
        // Expansion = a real click on the ancestor ROW at that depth
        // (editor-expansion.js sets fullDepth to the clicked level).
        // The old `.expand-control` chevron no longer exists.
        const lines = await overlay.$$('.ancestor-line');
        const targetLine = lines.length > level ? lines[level] : lines[lines.length - 1];
        if (!targetLine) {
          console.log(`  Warning: no ancestor lines available`);
          continue;
        }
        await targetLine.click({ position: { x: 40, y: 8 } });
        await page.waitForTimeout(600);
        console.log(`  Clicked level ${level} ancestor row`);
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

    // Build identifier — read directly from window.BUILD_HASH (the
    // editor no longer auto-logs it; it's exposed for on-demand
    // inspection only).
    const buildHash = await page.evaluate(() => window.BUILD_HASH || null);
    if (buildHash) {
      console.log('\n=== Build Info ===');
      console.log('window.BUILD_HASH =', buildHash);
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
