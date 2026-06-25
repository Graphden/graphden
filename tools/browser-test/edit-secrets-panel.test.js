// Secrets panel CRUD e2e — sidebar Secrets section + new-secret form.
//
// Scoped to UI behaviour: this dev instance doesn't have VAULT_ADDR
// wired, so actual `POST /api/secrets` returns
// `{:type :vault/not-configured}`. The test verifies:
//
//   • Sidebar Secrets section renders at the top of the namespace
//     tree with a header `+` button (auth-gated).
//   • Clicking + opens the `.secrets-popover` create form with
//     name / namespace-picker / path / value / description / Cancel
//     / Create buttons.
//   • Path auto-fills from name (per-character slash transform).
//   • Submit fires `POST /api/secrets`; in this dev env the
//     response carries `{ok:false :type :vault/not-configured}`
//     and the error message surfaces in the popover.
//   • Cancel button dismisses the popover cleanly.
//
// When this instance gets a real vault (`bb deploy` with VAULT_ADDR
// set), the create flow lands the secret + fn-def; verifying that
// happy-path is a follow-up — see `edit-secrets-panel-roundtrip` in
// the future.
//
// Run from this directory:  node edit-secrets-panel.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');


// Per-run unique probe name. The legacy hardcoded `auto-fill-probe-
// name` worked on dev (no vault → POST never persisted), but on the
// e2e isolated stack openbao IS configured: the first run actually
// creates the secret, the second hits 409 Conflict + the wait-for
// `.popover-error` assertion timed out (the error DID surface, but
// not the `:vault/not-configured` one the test expected; the
// generic 409 path doesn't render via `.popover-error` the same
// way). Randomising the name keeps every run a clean create.
const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_NAME = 'auto-fill-probe-name' + RUN_ID;
const EXPECTED_PATH = ('auto/fill/probe/name' + RUN_ID).replace(/-/g, '/');


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 200));
    d.accept();
  });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-secrets-panel — sidebar section + new-secret form + error path');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/');
    await page.waitForSelector('.sidebar-secrets', {timeout: 15000});

    // ===================================================================
    // Phase A: section renders with `+` button (we're authed via
    // newContext's localStorage seed).
    // ===================================================================
    const sectionState = await page.evaluate(() => {
      const sec = document.querySelector('.sidebar-secrets');
      const header = sec?.querySelector('.ns-header');
      const label = header?.querySelector('.ns-label');
      const addBtn = header?.querySelector('.sidebar-action-add');
      return {
        present: !!sec,
        labelText: label?.textContent?.trim(),
        hasAddBtn: !!addBtn,
      };
    });
    assert(sectionState.present, '.sidebar-secrets section rendered');
    assert(sectionState.labelText === 'Secrets',
           'header label is "Secrets": ' + sectionState.labelText);
    assert(sectionState.hasAddBtn,
           '+ add button visible (admin authed)');

    // ===================================================================
    // Phase B: click + → create form popover.
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector('.sidebar-secrets .sidebar-action-add')?.click();
    });
    await page.waitForSelector('.secrets-popover', {timeout: 5000});
    const formState = await page.evaluate(() => {
      const p = document.querySelector('.secrets-popover');
      return {
        title: p?.querySelector('.popover-title')?.textContent?.trim(),
        hasName: !!p?.querySelector('input[name="name"]'),
        hasPath: !!p?.querySelector('input[name="path"]'),
        hasValue: !!p?.querySelector('input[name="value"]'),
        hasDesc: !!p?.querySelector('input[name="description"]'),
        hasNsPick: !!p?.querySelector('[data-act="pick-ns"]'),
        hasSubmit: !!p?.querySelector('[data-act="submit"]'),
        hasCancel: !!p?.querySelector('[data-act="cancel"]'),
      };
    });
    assert(formState.title === 'New secret',
           'popover title "New secret": '
           + JSON.stringify(formState.title));
    assert(formState.hasName && formState.hasPath
           && formState.hasValue && formState.hasDesc,
           'form has name + path + value + description fields');
    assert(formState.hasNsPick,
           'namespace picker chip present');
    assert(formState.hasSubmit && formState.hasCancel,
           'Create + Cancel buttons present');

    // ===================================================================
    // Phase C: auto-fill path from name. The handler replaces `-`
    // with `/` and strips leading underscores.
    // ===================================================================
    await page.fill('.secrets-popover input[name="name"]', PROBE_NAME);
    // Path autofills via input listener; poll for the expected value
    // instead of guessing the listener's debounce window.
    await page.waitForFunction(
      (expected) =>
        document.querySelector('.secrets-popover input[name="path"]')?.value
          === expected,
      EXPECTED_PATH, {timeout: 2000, polling: 50});
    const autofill = await page.evaluate(
      () => document.querySelector('.secrets-popover input[name="path"]')?.value);
    assert(autofill === EXPECTED_PATH,
           'path auto-fills from name (hyphens → slashes): '
           + JSON.stringify(autofill));

    // ===================================================================
    // Phase D: error path. Original test assumed every submit fails
    // because dev had no VAULT_ADDR → every POST hit
    // `:vault/not-configured`. The e2e isolated stack DOES have
    // openbao wired, so submit succeeds → popover dismisses → the
    // wait-for-error times out. Reproduce a guaranteed error by
    // submitting the SAME secret twice: second POST returns 200 with
    // `{ok:false, reason:"name-taken"}`, the popover surfaces it.
    // ===================================================================
    await page.fill('.secrets-popover input[name="value"]', 'secret-value');
    // First submit — succeeds (or fails with vault error on dev).
    // We don't assert on its outcome; the popover may close (success)
    // or stay open (vault-not-configured). Re-open the popover in
    // either case so phase D can drive the duplicate-name path.
    await page.evaluate(() => {
      document.querySelector('.secrets-popover [data-act="submit"]')?.click();
    });
    // Wait until either the popover dismisses (success) OR a visible
    // popover-error appears (vault-not-configured). Both are valid
    // first-submit outcomes for this test.
    await page.waitForFunction(() => {
      const pop = document.querySelector('.secrets-popover');
      if (!pop) return true;
      const err = pop.querySelector('.popover-error');
      return err && !err.hasAttribute('hidden')
             && (err.textContent || '').trim().length > 0;
    },null,  {timeout: 5000, polling: 100});
    // Reopen if closed (success path), else carry on (error path).
    const popoverClosed = await page.evaluate(
      () => !document.querySelector('.secrets-popover'));
    if (popoverClosed) {
      await page.click('.sidebar-secrets [data-act="create"]');
      await page.waitForSelector('.secrets-popover', {timeout: 5000});
      await page.fill('.secrets-popover input[name="name"]', PROBE_NAME);
      await page.fill('.secrets-popover input[name="value"]', 'secret-value');
      // Path-autofill listener again — wait for the input to populate.
      await page.waitForFunction(
        (expected) =>
          document.querySelector('.secrets-popover input[name="path"]')?.value
            === expected,
        EXPECTED_PATH, {timeout: 2000, polling: 50});
    }
    // Now submit the same name → server rejects (name-taken on isolated
    // stack with vault; vault-not-configured on bare dev). EITHER way
    // the popover surfaces an error and stays open.
    await page.evaluate(() => {
      document.querySelector('.secrets-popover [data-act="submit"]')?.click();
    });
    await page.waitForFunction(
      () => {
        const e = document.querySelector('.secrets-popover .popover-error');
        return e && !e.hasAttribute('hidden')
               && (e.textContent || '').length > 0;
      },
      null,
      {timeout: 10000});
    const errorState = await page.evaluate(() => {
      const p = document.querySelector('.secrets-popover');
      const e = p?.querySelector('.popover-error');
      return {
        popoverStillOpen: !!p,
        errorText: e?.textContent?.trim() || '',
      };
    });
    assert(errorState.popoverStillOpen,
           'popover stays open on server rejection');
    assert(errorState.errorText.length > 0,
           'error message surfaces: '
           + JSON.stringify(errorState.errorText).slice(0, 200));
    // Reason narrators differ across deployments. Log both shapes.
    if (/vault/i.test(errorState.errorText)) {
      console.log('  (note: dev env without VAULT_ADDR — vault/not-configured)');
    } else if (/already exists|name-taken/i.test(errorState.errorText)) {
      console.log('  (note: vault wired — name-taken on duplicate submit)');
    }

    // ===================================================================
    // Phase E: Cancel dismisses the popover.
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector('.secrets-popover [data-act="cancel"]')?.click();
    });
    await page.waitForFunction(
      () => !document.querySelector('.secrets-popover'),
      null,
      {timeout: 3000});
    const dismissed = await page.evaluate(
      () => !document.querySelector('.secrets-popover'));
    assert(dismissed, 'Cancel removes the popover from DOM');

    console.log('✓ secrets panel verified — section / form / autofill / submit-error / cancel');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    // POST /api/secrets isn't atomic — when the Vault write fails it
    // can still leave the fn-def behind in PG. That orphan hangs
    // future GET /api/secrets calls (handler calls Vault for the
    // missing path, blocks the request thread, eventually kills
    // /health, restart loop). Force-delete by name.
    try {
      const {deleteFnByName} = require('./edit-test-helpers');
      await deleteFnByName(page, 'auto-fill-probe-name');
    } catch (_) {}
    await browser.close();
  }
})();
