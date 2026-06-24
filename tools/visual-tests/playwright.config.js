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

// Chromium launch flags applied to every project. Mirrors the
// canonical set used by `tools/browser-test/edit-test-helpers.js`
// (one source of truth for "what Chrome args make graphden's
// editor render in restrictive container environments"):
//
// - `--js-flags=--max-old-space-size=1024` — cap V8 heap. Default
//   ~4GB caused the dev container's chrome to compete with java +
//   pg for host RAM and get OOM-killed.
// - `--disable-dev-shm-usage` — fall back to /tmp; default
//   /dev/shm in some containers is 64 MB, exhausted by heavy
//   DOM mutation.
// - `--no-sandbox` — required when running as root in a container
//   (crbug/638180).
// - `--no-zygote` + `--in-process-gpu` — the renderer zygote
//   pre-fork + GPU subprocess fail to initialise in restrictive
//   namespaces, every page.goto crashes with "Page crashed".
//   `--no-zygote` disables the pre-fork; `--in-process-gpu`
//   collapses the GPU sub-process into main; renderer itself
//   stays in its own process so Cytoscape stays responsive.
const CHROMIUM_LAUNCH_ARGS = [
  '--js-flags=--max-old-space-size=1024',
  '--disable-dev-shm-usage',
  '--no-sandbox',
  '--no-zygote',
  '--in-process-gpu',
];

module.exports = defineConfig({
  testDir: './tests',
  retries: 0,
  use: {
    baseURL: BASE_URL,
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
    serviceWorkers: 'block',
    launchOptions: { args: CHROMIUM_LAUNCH_ARGS },
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
