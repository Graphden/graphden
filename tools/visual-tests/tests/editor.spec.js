// Visual-regression tests for the Graphden editor.
//
// Captures screenshots at key UI states and diffs against committed
// baselines under tests/editor.spec.js-snapshots/. Run via
// `bb visual` (assert) or `bb visual-update` (refresh baselines).
//
// Cytoscape only mounts its canvas once a fn is selected (no
// canvas → no graph), so every test navigates to /#<fn-name> to
// pre-select a function via the URL hash mechanism.

const { test, expect } = require('@playwright/test');

const AUTH_TOKEN = process.env.AUTH_TOKEN || '';

test.beforeEach(async ({ page }) => {
  if (AUTH_TOKEN) {
    await page.addInitScript((token) => {
      try { localStorage.setItem('graphden.auth.password', token); } catch (_) {}
    }, AUTH_TOKEN);
  }
});

// Wait for the editor to finish first paint:
//  - Cytoscape canvas exists (means /api/graph/layout returned)
//  - One additional rAF so its first frame has been committed
async function waitForGraphRendered(page) {
  await page.waitForSelector('#cy canvas', { state: 'attached', timeout: 20_000 });
  await page.evaluate(() => new Promise(requestAnimationFrame));
  await page.evaluate(() => new Promise(requestAnimationFrame));
}

async function setTheme(page, theme) {
  await page.evaluate((t) => {
    if (t === 'dark') document.body.classList.add('theme-dark');
    else document.body.classList.remove('theme-dark');
    try { localStorage.setItem('graphden.theme', t); } catch (_) {}
  }, theme);
  await page.evaluate(() => new Promise(requestAnimationFrame));
}

test.describe('Editor — visual baselines', () => {
  test('web-server loaded, light theme', async ({ page }) => {
    await page.goto('/#web-server');
    await waitForGraphRendered(page);
    await setTheme(page, 'light');
    await expect(page).toHaveScreenshot('01-web-server-light.png');
  });

  test('web-server loaded, dark theme', async ({ page }) => {
    await page.goto('/#web-server');
    await waitForGraphRendered(page);
    await setTheme(page, 'dark');
    await expect(page).toHaveScreenshot('02-web-server-dark.png');
  });

  test('sidebar with namespaces expanded — verifies entity list rendering', async ({ page }) => {
    await page.goto('/#web-server');
    await waitForGraphRendered(page);
    await setTheme(page, 'light');
    // On narrow viewports the sidebar auto-collapses on first
    // visit. For the snapshot we always want it OPEN — that's the
    // surface we're testing. The floating expand button has
    // `opacity:0; pointer-events:none` while the sidebar is open
    // (so isVisible() can mis-read it); the authoritative source
    // is `body.sidebar-collapsed`.
    const isCollapsed = await page.evaluate(() =>
      document.body.classList.contains('sidebar-collapsed'));
    if (isCollapsed) {
      await page.locator('#sidebar-expand-floating').click();
      await page.waitForTimeout(250);  // slide-in animation
    }
    // Click any collapsed namespace arrow to expand it. We don't
    // care WHICH namespace — the snapshot tests rendering of
    // expanded children styling, not the specific contents.
    const arrow = page.locator('.ns-arrow.collapsed').first();
    if (await arrow.count()) {
      await arrow.click();
      await page.waitForTimeout(150);
    }
    // Crop to the sidebar so this snapshot is small + stable. The
    // graph canvas can shift by a pixel between runs and would
    // otherwise dominate the diff.
    const sidebar = page.locator('#side-menu');
    await expect(sidebar).toHaveScreenshot('03-sidebar-expanded.png');
  });
});
