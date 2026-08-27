// Execute popover advanced flows e2e — Cancel during a running
// execution + Repeat from history.
//
// Target fn: :sleep — a single-arg (ms) base-fn honoring Thread.interrupt.
// Picking 5000ms gives a generous window to click Cancel.
//
// Coverage:
//   • run with persist=true → execution row is created
//   • cancel button surfaces during :pending
//   • cancel POST flips the status; polling renders "Cancelled."
//   • history panel lists the cancelled row
//   • Repeat refills the args form with the run's args
//
// Run from this directory:  node edit-execute-advanced.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');


const TARGET_FN = 'sleep';
// Default sync timeout is 10s; pick something longer so the server
// returns :pending and the cancel-button path lights up.
const SLEEP_MS = 15000;


async function openExecutePopover(page, fnName) {
  await page.goto('about:blank');
  await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + fnName);
  await page.waitForFunction(
    () => graphReady()
          && !!document.querySelector('button.more-actions-trigger')
          && !graph.animating,
    null,
    {timeout: 20000, polling: 100});
  await page.evaluate(() => initGraph());
  await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
  // initGraph repopulates `graphData` asynchronously; wait for the
  // target fn to land in it rather than a fixed 500 ms.
  await page.waitForFunction(
    (name) => {
      const fns = (typeof graphData !== 'undefined' && graphData?.fns) || [];
      return fns.some((f) => f.name === name);
    },
    fnName,
    {timeout: 5000, polling: 100});
  const ok = await page.evaluate(async (name) => {
    const fns = (typeof graphData !== 'undefined' && graphData?.fns) || [];
    const fn = fns.find((f) => f.name === name);
    if (!fn) return false;
    const anchor = document.querySelector('button.more-actions-trigger')
                   || document.body;
    await window.showExecutePopover(fn, anchor);
    return true;
  }, fnName);
  if (!ok) return false;
  try {
    await page.waitForSelector('.execute-popover', {timeout: 5000});
  } catch (_) { return false; }
  return true;
}


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
  console.log('edit-execute-advanced — Cancel during pending + Repeat from history');

  try {
    // ===================================================================
    // Phase A: open the execute popover for :sleep.
    // ===================================================================
    const opened = await openExecutePopover(page, TARGET_FN);
    assert(opened, '▶ execute popover opens for :sleep');

    // ===================================================================
    // Phase B: fill :ms, enable persist, click Run, then Cancel.
    // The persist toggle stores the run so the history panel can pick
    // it up. :sleep has `:time` effect — Run isn't gated on confirm.
    // ===================================================================
    // Fill the :ms input via Playwright's `fill` so the keystrokes
    // are actual input events (more reliable than direct DOM
    // mutation, which can race with the form's value tracking).
    // Regression: the Run form inside the graph-refresh window.
    // The free-args are derived from fn-slots / bindings / slots, and those arrive
    // in the per-view SUBTREE payload — `initGraph()` rebuilds `lookups` from
    // `?scope=index`, which carries none of them. Open the popover in that window
    // and the form used to report "No free arguments" for an fn that takes one: a
    // Run button with nothing to pass to it. Under load the suite hit this for
    // real, and it surfaced as a 45 s `page.fill` timeout on an input that was
    // never going to exist.
    //
    // Hold the subtree back so the window is wide, wait until it is genuinely open
    // (`lookups.slotMap` empty), and re-open the popover there. The number field
    // must still be built.
    await page.route('**/api/graph/entities?scope=subtree*', async (route) => {
      await new Promise((r) => setTimeout(r, 3000));
      await route.continue();
    });
    await page.evaluate(() => {
      document.querySelector('.execute-popover')?.remove();
      initGraph();                      // deliberately NOT awaited
    });
    await page.waitForFunction(() => (lookups?.slotMap?.size ?? 1) === 0,
                               null, {timeout: 20000, polling: 20});
    const reopened = await openExecutePopover(page, TARGET_FN);
    assert(reopened, '▶ popover re-opens during a graph refresh');
    // 45s (was 20s): the form's arg build inside the held-open refresh
    // window waits on the 3s-delayed subtree AND, when the preceding
    // writes left the compiled registry invalidated, on a request-path
    // recompile burst — the same class the comment above measured as a
    // 45s page.fill stall. A "No free arguments" REGRESSION fails at any
    // bound; the size only buys honest margin for the deliberate window.
    await page.waitForSelector('.execute-popover input[data-field-kind="number"]',
                               {timeout: 45000});
    console.log('  ✓ Run form still builds its args when opened during a refresh');
    await page.unroute('**/api/graph/entities?scope=subtree*');

    await page.fill('.execute-popover input[data-field-kind="number"]',
                    String(SLEEP_MS));
    await page.check('.execute-popover .execute-confirm-checkbox');
    const formState = await page.evaluate(() => {
      const p = document.querySelector('.execute-popover');
      const input = p.querySelector('input[data-field-kind="number"]');
      return {
        msInputFilled: !!input,
        msValue: input?.value,
        persistChecked: !!p.querySelector('.execute-persist-checkbox')?.checked,
        confirmChecked: !!p.querySelector('.execute-confirm-checkbox')?.checked,
      };
    });
    assert(formState.msInputFilled,
           ':ms input rendered and filled: '
           + JSON.stringify(formState));
    assert(formState.persistChecked,
           'persist auto-checked (fn has effects): '
           + formState.persistChecked);
    assert(formState.confirmChecked,
           'effects-confirm checkbox toggled on');

    // Click Run. The handler is async — wait for the cancel button to
    // surface (it only appears once the server returns :pending).
    await page.evaluate(() => {
      document.querySelector('.execute-popover .execute-run-btn')?.click();
    });
    // The default sync timeout is 10s; cancel-btn surfaces after the
    // server flips to :pending. Bump the wait accordingly.
    await page.waitForFunction(
      () => {
        const cb = document.querySelector(
          '.execute-popover .execute-cancel-btn');
        return cb && cb.style.display !== 'none';
      },
      null,
      {timeout: 20000});

    const beforeCancel = await page.evaluate(() => {
      const cb = document.querySelector(
        '.execute-popover .execute-cancel-btn');
      return {
        cancelVisible: !!cb && cb.style.display !== 'none',
        execId: cb?.dataset.execId,
        pendingMarker: !!document.querySelector('.execute-popover .execute-pending'),
      };
    });
    assert(beforeCancel.cancelVisible, 'Cancel button surfaces after Run');
    assert(beforeCancel.execId,
           'cancel button carries execution-id: ' + beforeCancel.execId);

    // ===================================================================
    // Phase C: click Cancel. Poll for the "Cancelled." marker.
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector('.execute-popover .execute-cancel-btn')?.click();
    });
    await page.waitForFunction(
      () => {
        const el = document.querySelector(
          '.execute-popover .execute-cancelled');
        return el && /cancel/i.test(el.textContent || '');
      },
      null,
      {timeout: 10000});
    const cancelled = await page.evaluate(() => {
      const el = document.querySelector(
        '.execute-popover .execute-cancelled');
      return el?.textContent;
    });
    assert(/cancel/i.test(cancelled || ''),
           'result host shows "Cancelled.": ' + JSON.stringify(cancelled));

    // ===================================================================
    // Phase D: open history. The cancelled run should appear with the
    // submitted args (ms=SLEEP_MS).
    // ===================================================================
    // History is always mounted below the form in the Runs tab.
    await page.waitForSelector(
      '#gd-insp-runs .execute-history-row',
      {timeout: 10000});
    const historyState = await page.evaluate(() => {
      const rows = document.querySelectorAll(
        '#gd-insp-runs .execute-history-row');
      const first = rows[0];
      return {
        rowCount: rows.length,
        firstRowText: (first?.textContent || '').slice(0, 200),
        firstRowHasRepeat: !!first?.querySelector('.execute-history-repeat-btn'),
      };
    });
    assert(historyState.rowCount >= 1,
           'history panel lists at least one row: ' + historyState.rowCount);
    assert(historyState.firstRowHasRepeat,
           'first row has Repeat button');

    // ===================================================================
    // Phase E: Repeat the cancelled run. Args form should refill.
    // ===================================================================
    // Clear current arg value so we can verify refill.
    await page.evaluate(() => {
      const p = document.querySelector('.execute-popover');
      const labelEl = Array.from(p.querySelectorAll('.execute-arg-label'))
        .find((l) => l.textContent.trim() === 'ms');
      const row = labelEl?.closest('.execute-arg-row');
      const input = row?.querySelector('input, textarea');
      if (input) {
        input.value = '';
        input.dispatchEvent(new Event('input', {bubbles: true}));
      }
    });

    await page.evaluate(() => {
      const row = document.querySelector(
        '#gd-insp-runs .execute-history-row');
      row?.querySelector('.execute-history-repeat-btn')?.click();
    });
    await page.waitForFunction(
      (expected) => {
        const p = document.querySelector('.execute-popover');
        const labelEl = Array.from(p.querySelectorAll('.execute-arg-label'))
          .find((l) => l.textContent.trim() === 'ms');
        const input = labelEl?.closest('.execute-arg-row')
          ?.querySelector('input, textarea');
        return input && input.value === String(expected);
      },
      SLEEP_MS,
      {timeout: 5000});

    const refilled = await page.evaluate(() => {
      const p = document.querySelector('.execute-popover');
      const labelEl = Array.from(p.querySelectorAll('.execute-arg-label'))
        .find((l) => l.textContent.trim() === 'ms');
      return labelEl?.closest('.execute-arg-row')
        ?.querySelector('input, textarea')?.value;
    });
    assert(refilled === String(SLEEP_MS),
           ':ms input refilled by Repeat: ' + refilled);

    console.log('✓ execute Cancel + Repeat verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
