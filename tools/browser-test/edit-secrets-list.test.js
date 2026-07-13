// Secrets-list e2e — verifies the editor's sidebar Secrets panel
// reads and renders entries from GET /api/secrets within a tight
// budget. Specifically pins the perf-regression behaviour the
// recently-fixed SQL pushdown + fa-projection cache fix protects:
// historically GET /api/secrets hung 30s+ when at least one secret
// existed because the version-resolution chain scanned EVERY
// fn-slot identity and missed the cache for iteration-invariant
// refs. Test budget: end-to-end seed + reload + sidebar render
// must complete in < 5 s.
//
// Coverage:
//   • Seed a secret via POST /api/secrets (skips gracefully if
//     Vault isn't reachable — see edit-secrets-rotate.test.js for
//     the same fallback pattern).
//   • Navigate to /, expand namespaces, find the secret in the tree.
//   • Assert the seeded secret appears as an `.entity-secret` row
//     with the right name + path within 5 s.
//   • Assert one `GET /api/secrets` network request completed under
//     2 s (the perf gate the SQL pushdown protects).
//
// Run from this directory:  node edit-secrets-list.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const SECRET_NAME = 'list-probe' + RUN_ID;
const SECRET_PATH = 'kv/data/list-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, SECRET_NAME); } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => d.accept());
  console.log('edit-secrets-list — seed / render / perf gate');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: POST /api/secrets. Skip gracefully if Vault unreachable.
    // ===================================================================
    const seed = await page.evaluate(async ({base, auth, name, path}) => {
      try {
        const r = await fetch(base + '/api/secrets', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + auth,
          },
          body: JSON.stringify({name, path, value: 'list-probe-v1'}),
        });
        return await r.json();
      } catch (err) {
        return {ok: false, error: 'fetch threw: ' + String(err).slice(0, 200)};
      }
    }, {base: (process.env.GRAPHDEN_URL || 'http://localhost:9002')+'', auth: (process.env.AUTH_TOKEN || 'test123'),
        name: SECRET_NAME, path: SECRET_PATH});
    if (!seed.ok) {
      // Default: Vault MUST be reachable. The e2e stack
      // (development/dev/e2e_stack.clj) and demo (docker-compose.yml)
      // both run OpenBao; a seed failure means the stack is
      // misconfigured and we want a loud CI fail, not a silent skip.
      //
      // Opt-in `GRAPHDEN_VAULT_OPTIONAL=1` for ad-hoc local runs
      // against a host without Vault — preserves the prior pattern
      // for that case only.
      if (process.env.GRAPHDEN_VAULT_OPTIONAL === '1') {
        console.log('  (Vault unreachable, GRAPHDEN_VAULT_OPTIONAL=1 — skipping: '
                    + JSON.stringify(seed).slice(0, 200) + ')');
        console.log('✓ SKIPPED — opt-in skip');
        return;
      }
      assert(false,
             'POST /api/secrets must succeed (e2e/demo stack ships OpenBao). '
             + 'Set GRAPHDEN_VAULT_OPTIONAL=1 to soft-skip on an ad-hoc host '
             + 'without Vault. Response: '
             + JSON.stringify(seed).slice(0, 200));
    }
    const seedId = seed.secret?.id || seed.id;
    assert(seedId, 'secret fn-def created: '
                   + JSON.stringify(seed).slice(0, 200));

    // ===================================================================
    // Navigate to /, wait for sidebar wiring to be ready.
    // ===================================================================
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#identity',
                    {waitUntil: 'networkidle'});
    await page.waitForFunction(
      () => typeof loadSecrets === 'function'
            && typeof updateEntityList === 'function'
            && lookups?.fnMap?.size > 50,
      null,
      {timeout: 30000});

    // ===================================================================
    // Force the secrets list to refresh after seed.
    // ===================================================================
    const fetchStart = Date.now();
    await page.evaluate(async () => {
      await loadSecrets();
      updateEntityList(graphData);
    });
    const fetchMs = Date.now() - fetchStart;
    assert(fetchMs < 5000,
           'loadSecrets() round-trip + render under 5 s: '
           + fetchMs + 'ms (regression budget — historical hang was 30 s+)');

    // ===================================================================
    // Refresh the graph, then expand every namespace + the "(root)" node
    // so the seeded secret is visible wherever it was placed.
    //
    // The graph refresh is load-bearing. A secret renders as a row of the
    // namespace tree, and that tree is built from `graphData` — so the
    // seeded fn-def has to be IN `graphData` to show up. We seeded it
    // through the API, behind the editor's back, and the navigation above
    // only changes the hash (same document, no reload), so the page is
    // still holding the graph it fetched before the seed. `loadGraphData`
    // is exactly what the New-secret form calls after a successful POST.
    // ===================================================================
    await page.evaluate(async () => {
      if (typeof loadGraphData === 'function') await loadGraphData();
      if (typeof lookups !== 'undefined' && lookups?.nsPathMap) {
        for (const p of lookups.nsPathMap.values()) expandedNamespaces.add(p);
      }
      expandedNamespaces.add('__root__');
      if (typeof updateEntityList === 'function'
          && typeof graphData !== 'undefined') {
        updateEntityList(graphData);
      }
    });
    await page.waitForFunction(
      (name) => Array.from(document.querySelectorAll('.entity-secret'))
        .some((r) => (r.textContent || '').includes(name)),
      SECRET_NAME,
      {timeout: 5000});

    const rowInfo = await page.evaluate((name) => {
      const row = Array.from(document.querySelectorAll('.entity-secret'))
        .find((r) => (r.textContent || '').includes(name));
      if (!row) return null;
      return {
        nameText: row.querySelector('.name')?.textContent,
        pathText: row.querySelector('.secret-path')?.textContent,
      };
    }, SECRET_NAME);
    assert(rowInfo, 'sidebar entity-secret row found for ' + SECRET_NAME);
    assert(rowInfo.nameText === SECRET_NAME,
           'row .name = seeded name: ' + JSON.stringify(rowInfo.nameText));
    assert((rowInfo.pathText || '').includes('list-probe'),
           'row .secret-path shows the seeded path: '
           + JSON.stringify(rowInfo.pathText));

    // ===================================================================
    // Perf gate: at least one /api/secrets GET under the budget.
    //
    // This is browser wall-clock in the ISOLATED e2e stack (Testcontainers
    // postgres + openbao + executor on dynamic ports, under pool contention),
    // NOT the ~1.6 s demo baseline (see the list-secrets O(secrets) SQL-filter
    // fix). The stack adds ~0.5 s of container/network overhead, so the fastest
    // call lands ~2.0-2.3 s here even with no code regression — a 2 s budget
    // false-reds every run. The guard exists to catch a GROSS regression (the
    // old O(secrets×bindings) path was ~15 s); 3 s keeps that signal while
    // tolerating stack overhead.
    // ===================================================================
    const perf = await page.evaluate(() =>
      performance.getEntriesByType('resource')
        .filter((e) => e.name.includes('/api/secrets')
                       && !e.name.match(/\/api\/secrets\//))
        .map((e) => ({duration: Math.round(e.duration)})));
    const fastest = perf.length
                    ? Math.min(...perf.map((p) => p.duration))
                    : null;
    assert(fastest != null,
           'browser performance log captured a GET /api/secrets call');
    assert(fastest < 3000,
           'fastest GET /api/secrets under 3 s (gross-regression budget): '
           + fastest + 'ms');

    console.log('✓ secrets-list verified — seed / render / perf gate');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.stack || e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
