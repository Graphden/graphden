// What does the editor ask the server for, and how much does it get back?
//
// Counts, not milliseconds — the same argument as the rest of
// docs/PERF_BUDGETS.md. "The first paint makes 5 API calls and pulls 40 KB" is
// 5 and 40 KB on any machine; the time it takes is the host's business. And
// counts are what actually regressed: /api/graph/entities was 4.5 MB before it
// was scoped, and the whole lazy-fn-index work exists because the editor used
// to mirror every fn in the graph. A request count and a byte count would have
// caught both the day they landed.
//
// Nothing is instrumented in the app. Playwright sees every request by
// construction, which is the browser-side equivalent of reading
// pg_stat_statements instead of wrapping the JDBC calls.
//
// Writes perf/runs/frontend.edn in the same shape the kaocha plugin emits, so
// `bb perf` reads it with no special case.
//
// Run:  node perf-frontend.js          (needs a running editor: `bb wt up`)

const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const { newContext, waitForServerHealthy, BASE } = require('./edit-test-helpers');

const OUT = path.resolve(__dirname, '../../perf/runs/frontend.edn');

// Round bytes to the nearest KB before recording. Response sizes wobble by a
// few bytes run to run — a uuid in a payload, a timestamp one digit longer —
// and a budget that trips on that is noise wearing a gate's uniform. KB is
// coarse enough to be stable and fine enough that a real payload regression
// (the 4.5 MB /api/graph/entities kind) is unmissable.
const kb = (bytes) => Math.round(bytes / 1024);

// One scenario = one page, so nothing another scenario did can leak into these
// counts through a warm HTTP cache or a populated localStorage.
async function measure(name, drive) {
  const { browser, page } = await newContext(chromium);
  const apiCalls = [];
  let bytes = 0;

  page.on('response', async (res) => {
    const url = res.url();
    if (!url.includes('/api/')) return;
    apiCalls.push(url.replace(BASE, ''));
    try {
      const body = await res.body();
      bytes += body.length;
    } catch (_) {
      // A response whose body is gone (redirect, cancelled nav) still counts as
      // a round trip; only its size is unknown. Losing the count would be worse
      // than under-counting the bytes.
    }
  });

  try {
    await drive(page);
    const domNodes = await page.evaluate(() => document.querySelectorAll('*').length);
    return { name, requests: apiCalls.length, kb: kb(bytes), domNodes, urls: apiCalls };
  } finally {
    await browser.close();
  }
}

async function waitForGraph(page) {
  // `attached`, not visible: the SVG path exists as soon as /api/graph/layout
  // returned and drew, which is the moment the first paint's requests are done.
  await page.waitForSelector('#edge-lines path', { state: 'attached', timeout: 30000 });
  // Two frames: the first paints, the second settles the measured-height
  // reflow. Without it the DOM-node count is read mid-layout and moves between
  // runs for no reason the code is responsible for.
  await page.evaluate(() => new Promise((r) =>
    requestAnimationFrame(() => requestAnimationFrame(r))));
}

// Poll a predicate in the page. Throws on timeout rather than moving on: a
// scenario whose drive step quietly did nothing still yields tidy-looking
// numbers, and those numbers become a budget.
async function waitForCondition(page, fn, arg, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (await page.evaluate(fn, arg)) return;
    if (Date.now() > deadline) throw new Error('perf scenario precondition never became true');
    await page.waitForTimeout(100);
  }
}

async function main() {
  await waitForServerHealthy();

  const results = [];

  results.push(await measure('load-web-server', async (page) => {
    // A fn must be pre-selected via the URL hash or no graph mounts at all.
    await page.goto(`${BASE}/#web-server`, { waitUntil: 'domcontentloaded' });
    await waitForGraph(page);
  }));

  // The sidebar paints namespaces only; a namespace's fn leaves load on expand
  // (?scope=namespace). This scenario guards that staying true — if the tree
  // ever goes back to shipping every fn up front, the count here changes and
  // the load payload explodes. No other check would notice.
  results.push(await measure('sidebar-expand-namespace', async (page) => {
    await page.goto(`${BASE}/#web-server`, { waitUntil: 'domcontentloaded' });
    await waitForGraph(page);
    // Two clicks, as edit-sidebar-lazy.test.js does: `core` expands to child
    // NAMESPACES, and only a leaf namespace has fn rows to lazily fetch.
    await page.waitForSelector('[data-ns-path="core"] .ns-label', { timeout: 20000 });
    await page.click('[data-ns-path="core"] .ns-label');
    await page.waitForSelector('[data-ns-path="core.arithmetic"] .ns-label', { timeout: 20000 });
    const before = await page.evaluate(() =>
      document.querySelectorAll('.entity-item').length);
    await page.click('[data-ns-path="core.arithmetic"] .ns-label');
    // Prove the click DID something before believing the numbers. A selector
    // that silently matches nothing produces a scenario that measures the page
    // load twice and calls it an expand — which is exactly what the first draft
    // of this file did, and it looked completely healthy.
    await waitForCondition(page, (n) =>
      document.querySelectorAll('.entity-item').length > n, before);
  }));

  const counters = results.flatMap((r) => [
    `  :frontend/${r.name}-requests ${r.requests}`,
    `  :frontend/${r.name}-kb ${r.kb}`,
    `  :frontend/${r.name}-dom-nodes ${r.domNodes}`,
  ]).join('\n');

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, `{:counters\n {\n${counters}}\n :gauges {}\n :namespaces []}\n`);

  for (const r of results) {
    console.log(`${r.name}: ${r.requests} API calls, ${r.kb} KB, ${r.domNodes} DOM nodes`);
    for (const u of r.urls) console.log(`    ${u}`);
  }
  console.log(`\nperf: report written to ${OUT}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
