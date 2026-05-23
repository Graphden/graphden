// Execute popover effect-confirm gate — opens the ▶ popover on
// :current-time-ms (declared :effects #{:time}), asserts that:
//   1. The "side effects" banner renders with the effect chip.
//   2. The Run button is disabled until the confirmation checkbox is
//      ticked.
//   3. Ticking the checkbox enables Run.
//   4. Running surfaces a "ran:" runtime-effects strip after the result.
//
// Why :current-time-ms: zero free-args (no form to fill), declared
// `:effects #{:time}`, and the instrumented impl in core/system/impls.clj
// calls `(cr/record-effect! :time)` so runtime-effects should match
// declared (no drift outline). Cheap + deterministic.
//
// Run from this directory:  node edit-execute-effects.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');


async function openExecutePopoverFor(page, fnNameHash) {
  await page.goto('about:blank');
  await page.goto('http://localhost:9002/#' + fnNameHash);
  await page.waitForTimeout(2500);
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  await page.waitForTimeout(500);
  const opened = await page.evaluate(() => {
    const popover = document.querySelector('.row-actions-popover');
    if (!popover) return false;
    const runBtn = Array.from(popover.querySelectorAll('button'))
      .find(b => b.textContent.trim() === '▶');
    if (!runBtn) return false;
    runBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return true;
  });
  if (!opened) throw new Error('▶ button not surfaced in row-actions');
  await page.waitForTimeout(1500);
}


(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('edit-execute-effects — effect-confirm gate + runtime-effects strip');
  try {
    await openExecutePopoverFor(page, 'core.system.current-time-ms');

    // === Phase A: effects banner + Run-disabled-until-confirmed ===
    const initialState = await page.evaluate(() => {
      const popover = document.querySelector('.execute-popover.visible');
      if (!popover) return { ok: false };
      const banner = popover.querySelector('.execute-effects-warning');
      const bannerChips = Array.from(
        popover.querySelectorAll('.execute-effects-warning .effects-chip'))
        .map(c => c.textContent);
      const confirmCb = popover.querySelector('.execute-confirm-checkbox');
      const runBtn = popover.querySelector('.execute-run-btn');
      const persistCb = popover.querySelector('.execute-persist-checkbox');
      return {
        ok: true,
        bannerExists: !!banner,
        bannerChips,
        confirmCbExists: !!confirmCb,
        confirmCbChecked: confirmCb?.checked,
        runDisabled: runBtn?.disabled,
        persistChecked: persistCb?.checked,
        persistDisabled: persistCb?.disabled,
      };
    });
    assert(initialState.ok, 'popover is visible');
    assert(initialState.bannerExists,
           'effects banner rendered for an effectful fn');
    assert(initialState.bannerChips.includes('time'),
           'effects banner shows :time chip (got '
           + JSON.stringify(initialState.bannerChips) + ')');
    assert(initialState.confirmCbExists,
           'effect-confirm checkbox rendered');
    assert(initialState.confirmCbChecked === false,
           'effect-confirm checkbox starts unchecked');
    assert(initialState.runDisabled === true,
           'Run button starts disabled (effect-confirm gate)');
    assert(initialState.persistChecked === true,
           'persist checkbox pre-checked for effectful fns (auto-save)');
    assert(initialState.persistDisabled === true,
           'persist checkbox disabled — backend auto-persists effectful runs');

    // === Phase B: tick confirm → Run enables ===
    await page.evaluate(() => {
      const cb = document.querySelector('.execute-confirm-checkbox');
      cb.checked = true;
      cb.dispatchEvent(new Event('change', { bubbles: true }));
    });
    const afterTick = await page.evaluate(() => {
      const runBtn = document.querySelector('.execute-run-btn');
      return { runDisabled: runBtn?.disabled };
    });
    assert(afterTick.runDisabled === false,
           'Run enables after effect-confirm ticked');

    // === Phase C: run → runtime-effects strip surfaces ===
    await page.evaluate(() => {
      document.querySelector('.execute-run-btn').click();
    });
    await page.waitForTimeout(2000);
    const result = await page.evaluate(() => {
      const popover = document.querySelector('.execute-popover.visible');
      const scalar = popover.querySelector('.execute-result-scalar');
      const strip = popover.querySelector('.execute-runtime-effects-strip');
      const stripChips = Array.from(
        popover.querySelectorAll('.execute-runtime-effects-strip .effects-chip'))
        .map(c => c.textContent);
      const driftChips = Array.from(
        popover.querySelectorAll('.execute-runtime-effects-strip .execute-effects-drift'))
        .map(c => c.textContent);
      const unobservedChips = Array.from(
        popover.querySelectorAll('.execute-runtime-effects-strip .execute-effects-unobserved'))
        .map(c => c.textContent);
      return {
        scalarText: scalar?.textContent,
        stripExists: !!strip,
        stripChips,
        driftChips,
        unobservedChips,
      };
    });
    assert(result.scalarText && /^\d{10,}$/.test(result.scalarText.trim()),
           'current-time-ms returns an epoch-ms integer (got '
           + JSON.stringify(result.scalarText) + ')');
    assert(result.stripExists,
           'runtime-effects "ran:" strip rendered below result');
    assert(result.stripChips.includes('time'),
           'runtime strip shows :time chip — instrumented impl fired (got '
           + JSON.stringify(result.stripChips) + ')');
    assert(result.driftChips.length === 0,
           'no drift outline — declared :time matches runtime :time (got '
           + JSON.stringify(result.driftChips) + ')');
    assert(result.unobservedChips.length === 0,
           'no unobserved chips — declared :time was observed (got '
           + JSON.stringify(result.unobservedChips) + ')');

    // === Phase D: unit-test the unobserved-chip render path via
    // page.evaluate against the bundled renderRuntimeEffectsStrip. No
    // current loaded fn has a declared-effects multiset large enough
    // to trigger the "promised but unobserved" case in a real run, so
    // we synthesise the input directly.
    const stripUnit = await page.evaluate(() => {
      const el = renderRuntimeEffectsStrip(['db'], ['db', 'network']);
      if (!el) return { ok: false };
      const chips = Array.from(el.querySelectorAll('.effects-chip'));
      return {
        ok: true,
        chipCount: chips.length,
        observedDb: chips.filter(c => c.textContent === 'db'
                                  && !c.classList.contains('execute-effects-unobserved'))
                          .length,
        unobservedNetwork: chips.filter(c => c.textContent === 'network'
                                         && c.classList.contains('execute-effects-unobserved'))
                                .length,
        unobservedNetworkTitle: chips
          .find(c => c.classList.contains('execute-effects-unobserved'))?.title,
      };
    });
    assert(stripUnit.ok, 'renderRuntimeEffectsStrip returns DOM for mismatched sets');
    assert(stripUnit.chipCount === 2,
           'strip renders 2 chips (1 observed + 1 unobserved), got '
           + stripUnit.chipCount);
    assert(stripUnit.observedDb === 1,
           ':db chip rendered without unobserved class');
    assert(stripUnit.unobservedNetwork === 1,
           ':network chip rendered with execute-effects-unobserved class');
    assert(stripUnit.unobservedNetworkTitle
           && stripUnit.unobservedNetworkTitle.includes('Declared'),
           'unobserved chip has a descriptive title (got '
           + JSON.stringify(stripUnit.unobservedNetworkTitle) + ')');

    // === Phase E: "already running as a service" rejection path ===
    // The system either runs :web-server via legacy fallback (fresh
    // DB) or a managed :service row. Both surface the same :rejected
    // response from validate-execute. Test asserts the rejection
    // fires; either source is acceptable. We don't try to MUTATE the
    // running state (creating/displacing a service for :web-server
    // would self-DoS the test request).
    const rejection = await page.evaluate(async () => {
      const r = await authFetch('/api/execute', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({'fn-name': 'web-server'}),
      });
      const body = await r.json();
      return {
        httpStatus: r.status,
        status: body.status,
        ok: body.ok,
        reason: body['error-data']?.reason,
        source: body['error-data']?.source,
      };
    });
    assert(rejection.httpStatus === 200,
           'wire HTTP status 200 (rejection is application-level, not transport)');
    // After Phase 1 + :process gate, the running state of :web-server
    // depends on docker startup history. Three valid outcomes:
    //  - :rejected :source :legacy-fallback (legacy fallback alive)
    //  - :rejected :source :service (managed service running)
    //  - :failed "Address already in use" (no rejection path active
    //    but the server is bound — the bind failure IS the protection,
    //    just unwrapped)
    // Accept all three; the invariant is "ad-hoc Run on :web-server
    // doesn't succeed".
    assert(rejection.status === 'rejected' || rejection.status === 'failed',
           'response :status reflects ad-hoc Run failure (got '
           + rejection.status + ')');
    if (rejection.status === 'rejected') {
      assert(rejection.reason === 'already-running-as-service',
             ':error-data :reason is :already-running-as-service (got '
             + rejection.reason + ')');
      assert(rejection.source === 'legacy-fallback' || rejection.source === 'service',
             ':source is :legacy-fallback (fresh DB) or :service (managed) — '
             + 'either way the rejection fired (got ' + rejection.source + ')');
    }

    console.log('✓ effect-confirm gate + runtime-effects strip verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
