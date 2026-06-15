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
    await page.goto('http://localhost:9002/');
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
    await page.fill('.secrets-popover input[name="name"]',
                    'auto-fill-probe-name');
    await page.waitForTimeout(100);
    const autofill = await page.evaluate(
      () => document.querySelector('.secrets-popover input[name="path"]')?.value);
    assert(autofill === 'auto/fill/probe/name',
           'path auto-fills from name (hyphens → slashes): '
           + JSON.stringify(autofill));

    // ===================================================================
    // Phase D: fill the rest + submit. Dev env has no VAULT_ADDR →
    // server responds 5xx-ish with `:vault/not-configured`. The
    // popover surfaces the error AND stays open.
    // ===================================================================
    await page.fill('.secrets-popover input[name="value"]', 'secret-value');
    await page.evaluate(() => {
      document.querySelector('.secrets-popover [data-act="submit"]')?.click();
    });
    await page.waitForFunction(
      () => {
        const e = document.querySelector('.secrets-popover .popover-error');
        return e && !e.hasAttribute('hidden')
               && (e.textContent || '').length > 0;
      },
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
    // In this dev env we expect the `:vault/not-configured` reason,
    // but be lenient — any non-empty error means the path worked.
    if (/vault/i.test(errorState.errorText)) {
      console.log('  (note: dev env has no VAULT_ADDR — expected error reason)');
    }

    // ===================================================================
    // Phase E: Cancel dismisses the popover.
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector('.secrets-popover [data-act="cancel"]')?.click();
    });
    await page.waitForFunction(
      () => !document.querySelector('.secrets-popover'),
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
