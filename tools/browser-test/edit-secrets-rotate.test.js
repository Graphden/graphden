// Secrets-panel rotate-value e2e — happy path against the live Vault
// container.
//
// Coverage:
//   • Seed a secret via POST /api/secrets (creates the fn-def + writes
//     the initial value to Vault).
//   • Expand the "(root)" node, find the secret row, click ↻ rotate.
//   • Fill the new value, click "Rotate".
//   • Verify PUT /api/secrets/:fn-id/value returns 200 + popover
//     closes.
//   • Verify the secret row remains (rotation doesn't drop the
//     fn-def).
//
// Skips gracefully if Vault is unreachable (POST /api/secrets returns
// non-ok with vault-related error) — the form-only assertions are
// covered by edit-secrets-panel.test.js.
//
// Run from this directory:  node edit-secrets-rotate.test.js

const {chromium} = require('playwright');
const {assert, newContext, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const SECRET_NAME = 'edit-rotate-probe' + RUN_ID;
const SECRET_PATH = 'kv/data/edit-rotate-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, SECRET_NAME); } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => d.accept());
  console.log('edit-secrets-rotate — create / ↻ / new value / PUT 200');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed via POST /api/secrets. Skip gracefully if the server is
    // in a restart-loop state (returns nothing / connection reset)
    // — this scenario is documented as an outstanding container
    // stability tail.
    // ===================================================================
    const seed = await page.evaluate(async ({base, auth, name, path}) => {
      try {
        const r = await fetch(base + '/api/secrets', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + auth,
          },
          body: JSON.stringify({name, path, value: 'initial-secret-v1'}),
        });
        return await r.json();
      } catch (err) {
        return {ok: false, error: 'fetch threw: ' + String(err).slice(0, 200)};
      }
    }, {base: (process.env.GRAPHDEN_URL || 'http://localhost:9002')+'', auth: (process.env.AUTH_TOKEN || 'test123'),
        name: SECRET_NAME, path: SECRET_PATH});
    if (!seed.ok) {
      // See edit-secrets-list.test.js for the rationale — default
      // fail-loud, opt-in soft-skip via GRAPHDEN_VAULT_OPTIONAL=1.
      if (process.env.GRAPHDEN_VAULT_OPTIONAL === '1') {
        console.log('  (Vault unreachable, GRAPHDEN_VAULT_OPTIONAL=1 — skipping happy-path: '
                    + JSON.stringify(seed).slice(0, 200) + ')');
        console.log('✓ SKIPPED — opt-in skip; see edit-secrets-panel.test.js for form-only coverage');
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
    // Navigate; verify secret appears in sidebar.
    // ===================================================================
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#identity',
                    {waitUntil: 'networkidle'});
    await page.waitForFunction(
      () => typeof loadSecrets === 'function'
            && lookups?.fnMap?.size > 0,
      null,
      {timeout: 30000});
    // The secrets panel is its own collapsible; force a reload of
    // its data AND a sidebar re-render after the seed.
    await page.evaluate(async () => {
      if (typeof loadSecrets === 'function') await loadSecrets();
      if (typeof updateEntityList === 'function'
          && typeof graphData !== 'undefined') {
        updateEntityList(graphData);
      }
    });
    // Expand every namespace + the "(root)" node so the secret row is
    // visible wherever it was placed, then re-render. The subsequent
    // waitForFunction on the row is the real timing signal.
    // The graph refresh is load-bearing: a secret renders as a row of the
    // namespace tree, which is built from `graphData`. We seeded this one
    // through the API, and the navigation above is hash-only (no reload),
    // so the page still holds the pre-seed graph. `loadGraphData` is what
    // the New-secret form itself calls after a successful POST.
    await page.evaluate(async () => {
      if (typeof loadGraphData === 'function') await loadGraphData();
      // Secrets live in the root bucket (no namespace). Expand and
      // deterministically load it, rather than expanding every namespace —
      // that floods the lazy loader's connection pool and can leave the
      // root fetch queued past the wait below.
      expandedNamespaces.add('__root__');
      if (typeof loadNamespaceFns === 'function') await loadNamespaceFns(null);
      if (typeof updateEntityList === 'function'
          && typeof graphData !== 'undefined') {
        updateEntityList(graphData);
      }
    });
    // The secret row is the actual signal — wait directly. Bumped
    // from 8s → 15s since the prior 800+500ms buffers used to absorb
    // some of the loadSecrets fetch latency.
    await page.waitForFunction(
      (name) => Array.from(document.querySelectorAll('.entity-secret'))
        .some((r) => (r.textContent || '').includes(name)),
      SECRET_NAME,
      {timeout: 15000, polling: 100});

    // ===================================================================
    // Click ↻ on the secret row.
    // ===================================================================
    await page.evaluate((name) => {
      const row = Array.from(document.querySelectorAll('.entity-secret'))
        .find((r) => (r.textContent || '').includes(name));
      const rotateBtn = Array.from(row.querySelectorAll('button'))
        .find((b) => (b.title || '') === 'Rotate value');
      rotateBtn?.click();
    }, SECRET_NAME);
    await page.waitForSelector('[data-popover="rotate-secret"]',
                               {timeout: 5000});

    // ===================================================================
    // Phase A: form structure.
    // ===================================================================
    const formState = await page.evaluate(() => {
      const pop = document.querySelector('[data-popover="rotate-secret"]');
      const title = pop?.querySelector('.popover-title')?.textContent;
      const input = pop?.querySelector('input[name="value"]');
      const submit = pop?.querySelector('[data-act="submit"]');
      return {
        visible: !!pop,
        title,
        inputType: input?.type,
        submitText: submit?.textContent?.trim(),
      };
    });
    assert(formState.visible, 'rotate popover renders');
    assert(/Rotate/.test(formState.title) && formState.title.includes(SECRET_NAME),
           'title says "Rotate <name>": ' + JSON.stringify(formState.title));
    assert(formState.inputType === 'password',
           'value input is type=password: ' + formState.inputType);
    assert(/Rotate/i.test(formState.submitText),
           'Rotate button: ' + formState.submitText);

    // ===================================================================
    // Phase B: enter new value + Rotate → 200 PUT.
    // ===================================================================
    await page.evaluate(() => {
      const input = document.querySelector(
        '[data-popover="rotate-secret"] input[name="value"]');
      input.value = 'rotated-secret-v2';
      input.dispatchEvent(new Event('input', {bubbles: true}));
    });
    const reqPromise = page.waitForResponse(
      (r) => /\/api\/secrets\/[^/]+\/value$/.test(r.url())
             && r.request().method() === 'PUT',
      {timeout: 10000});
    await page.evaluate(() => {
      document.querySelector(
        '[data-popover="rotate-secret"] [data-act="submit"]')?.click();
    });
    const resp = await reqPromise;
    assert(resp.status() === 200,
           'PUT /api/secrets/:id/value returns 200: ' + resp.status());
    const respJson = await resp.json().catch(() => ({}));
    assert(respJson.ok === true,
           'PUT response ok=true: ' + JSON.stringify(respJson).slice(0, 200));

    // ===================================================================
    // Phase C: popover closes; secret row remains.
    // ===================================================================
    await page.waitForFunction(
      () => !document.querySelector('[data-popover="rotate-secret"]'),
      null,
      {timeout: 5000});
    // No additional sleep needed — the secret row's presence is the
    // assertion right below; if it's still there, we're good.
    const rowStillThere = await page.evaluate((name) =>
      Array.from(document.querySelectorAll('.entity-secret'))
        .some((r) => (r.textContent || '').includes(name)),
      SECRET_NAME);
    assert(rowStillThere,
           'secret row remains after rotation (only value changed)');

    console.log('✓ secrets-rotate verified — form / new value / PUT / 200 / row preserved');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.stack || e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
