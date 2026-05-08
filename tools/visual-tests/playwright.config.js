// Playwright config for Graphden visual-regression tests.
//
// Three viewport projects so a single `bb visual` run catches
// desktop, iPad-portrait and iPad-landscape regressions in one
// shot. Each project produces its own baseline PNG suffix
// (`*-chromium-desktop-linux.png`, etc.) — committing all three
// flavours is intentional: the sidebar auto-collapses below 900px,
// so the iPad-portrait baseline is fundamentally different from
// desktop, not just resized.
//
// First run: `npm run update` (= `bb visual-update`) creates the
// committed baselines. Subsequent runs: `npm test` (= `bb visual`)
// diffs against them.

const { defineConfig } = require('@playwright/test');

const BASE_URL = process.env.GRAPHDEN_URL || 'http://localhost:9002';

module.exports = defineConfig({
  testDir: './tests',
  retries: 0,
  use: {
    baseURL: BASE_URL,
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
    serviceWorkers: 'block',
  },
  expect: {
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.02,
      animations: 'disabled',
      caret: 'hide',
    },
  },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  projects: [
    {
      name: 'desktop',
      use: {
        browserName: 'chromium',
        viewport: { width: 1440, height: 900 },
        // No-touch hover on desktop. Matches a developer machine.
        hasTouch: false,
      },
    },
    {
      name: 'ipad-landscape',
      use: {
        browserName: 'chromium',
        viewport: { width: 1024, height: 768 },
        hasTouch: true,
        // Force the touch media-query branch (--icon-size, etc.) by
        // making this context look like a touch device. Playwright
        // exposes `hasTouch` but Chromium also gates `(hover: none)
        // and (pointer: coarse)` on `isMobile` — turn that on too.
        isMobile: true,
      },
    },
    {
      name: 'ipad-portrait',
      use: {
        browserName: 'chromium',
        viewport: { width: 768, height: 1024 },
        hasTouch: true,
        isMobile: true,
      },
    },
  ],
});
