// Execute popover smoke — opens the ▶ popover on :add, fills the
// nums textarea, clicks Run, asserts the inline result is 6. Then
// re-runs with persist?=true and verifies the History panel surfaces
// the persisted row.
//
// Run from this directory:  node edit-execute.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');


async function openExecutePopoverFor(page, fnNameHash) {
  await page.goto('about:blank');
  await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + fnNameHash);
  await page.waitForTimeout(2500);

  // The ▶ button lives inside the row-actions popover, anchored to
  // the fn-card's `⋯` trigger. The trigger fires on mousedown (NOT
  // click) — Playwright's .click() doesn't dispatch mousedown by
  // itself, so use page.dispatchEvent.
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  await page.waitForTimeout(500);
  // Find ▶ in the surfaced row-actions popover and click.
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
  // Forms load via /api/value-form — give them time to mount.
  await page.waitForTimeout(2500);
}


async function fillNumsAndRun(page, jsonText, persistFlag) {
  return await page.evaluate(
    ({json, persist}) => {
      const popover = document.querySelector('.execute-popover.visible');
      if (!popover) return { ok: false, reason: 'no popover' };
      const textarea = popover.querySelector('textarea[data-form-field]');
      if (!textarea) return { ok: false, reason: 'no textarea' };
      textarea.value = json;
      textarea.dispatchEvent(new Event('input', { bubbles: true }));
      textarea.dispatchEvent(new Event('change', { bubbles: true }));
      if (persist) {
        const persistCb = popover.querySelector('.execute-persist-checkbox');
        if (persistCb && !persistCb.disabled) persistCb.checked = true;
      }
      popover.querySelector('.execute-run-btn').click();
      return { ok: true };
    },
    { json: jsonText, persist: !!persistFlag });
}


async function readResult(page) {
  await page.waitForTimeout(2000);
  return await page.evaluate(() => {
    const popover = document.querySelector('.execute-popover.visible');
    if (!popover) return { ok: false };
    const scalar = popover.querySelector('.execute-result-scalar');
    const err = popover.querySelector('.execute-error-pane');
    return {
      ok: true,
      scalarText: scalar?.textContent,
      errorText: err?.querySelector('.execute-error-head')?.textContent,
    };
  });
}


(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('edit-execute — ▶ popover, fill args, Run, History panel');
  try {
    // === Phase A: inline run (no persist) ===
    await openExecutePopoverFor(page, 'core.arithmetic.add');
    const titleA = await page.locator('.execute-popover-title').textContent();
    assert(titleA.includes(':add'),
           'popover title reads "Run :add" (got ' + titleA + ')');
    const argRowCountA = await page.locator('.execute-arg-row').count();
    assert(argRowCountA === 1, 'one free-arg row rendered (nums)');

    // Auto-focus: the first form input should be the active element.
    // requestAnimationFrame fires near-immediately but give it a beat.
    await page.waitForTimeout(150);
    const focusedTag = await page.evaluate(() =>
      document.activeElement?.tagName);
    assert(focusedTag === 'TEXTAREA' || focusedTag === 'INPUT',
           'first form input auto-focused (got ' + focusedTag + ')');

    const _ = await fillNumsAndRun(page, '[1, 2, 3]', false);
    const resultA = await readResult(page);
    assert(resultA.scalarText === '6',
           '(add [1,2,3]) → 6 rendered as scalar (got '
           + JSON.stringify(resultA) + ')');

    // === Phase B: persist run + history panel reveals row ===
    // Close the popover (click ×) so we reopen fresh.
    await page.evaluate(() => {
      document.querySelector('.execute-popover-close')?.click();
    });
    await page.waitForTimeout(500);
    await openExecutePopoverFor(page, 'core.arithmetic.add');
    await fillNumsAndRun(page, '[10, 20]', true);
    const resultB = await readResult(page);
    assert(resultB.scalarText === '30',
           '(add [10,20]) with persist? → 30 (got '
           + JSON.stringify(resultB) + ')');

    // Click History toggle — panel should reveal at least one row.
    await page.evaluate(() => {
      document.querySelector('.execute-history-toggle').click();
    });
    await page.waitForTimeout(1500);
    const historyState = await page.evaluate(() => {
      const host = document.querySelector('.execute-history-host');
      const visible = host && host.style.display !== 'none';
      const rows = host?.querySelectorAll('.execute-history-row') || [];
      return {
        visible,
        rowCount: rows.length,
        firstStatus: rows[0]?.querySelector('.execute-history-status')?.textContent,
        firstPreview: rows[0]?.querySelector('.execute-history-preview')?.textContent,
        repeatBtnExists: !!rows[0]?.querySelector('.execute-history-repeat-btn'),
      };
    });
    assert(historyState.visible, 'history panel revealed after toggle');
    assert(historyState.rowCount >= 1,
           'at least one persisted row in history (got ' + historyState.rowCount + ')');
    assert(historyState.firstStatus === 'succeeded',
           'first row status is succeeded (got ' + historyState.firstStatus + ')');
    assert(historyState.repeatBtnExists, 'Repeat button rendered on history row');

    // === Phase C: Repeat button refills the form with the row's args ===
    // First clear the textarea so we can detect that Repeat re-populates it.
    await page.evaluate(() => {
      const ta = document.querySelector(
        '.execute-popover.visible textarea[data-form-field]');
      if (ta) { ta.value = ''; ta.dispatchEvent(new Event('input', { bubbles: true })); }
      // Click the FIRST row's Repeat button — that's the most recent run
      // ([10, 20] persist=true from Phase B).
      const btn = document.querySelector(
        '.execute-history-row .execute-history-repeat-btn');
      btn?.click();
    });
    await page.waitForTimeout(800);  // applyHistoryArgs is async (fetch)
    const refilled = await page.evaluate(() => {
      const ta = document.querySelector(
        '.execute-popover.visible textarea[data-form-field]');
      return ta?.value;
    });
    assert(refilled && refilled.includes('10') && refilled.includes('20'),
           'Repeat re-filled the form with the prior run\'s args (got '
           + JSON.stringify(refilled) + ')');

    // === Phase D: Enter key triggers Run (when not inside textarea) ===
    // Move focus to the popover container so Enter doesn't land in
    // the textarea (where Enter inserts a newline — that path is
    // intentionally NOT intercepted).
    await fillNumsAndRun(page, '[100, 200]', false);   // baseline result via click
    await page.waitForTimeout(800);
    await page.evaluate(() => {
      // Tab focus off the textarea — focus the popover root so the
      // keydown handler's "tag !== TEXTAREA" gate lets Enter through.
      const ta = document.querySelector(
        '.execute-popover.visible textarea[data-form-field]');
      ta?.blur();
      const popover = document.querySelector('.execute-popover.visible');
      popover?.focus?.();
      // Refill textarea with a distinct value, then dispatch Enter on
      // the popover (not the textarea).
      if (ta) {
        ta.value = '[7, 8]';
        ta.dispatchEvent(new Event('input', { bubbles: true }));
        ta.blur();
      }
      popover?.dispatchEvent(new KeyboardEvent('keydown',
        { key: 'Enter', bubbles: true }));
    });
    await page.waitForTimeout(2000);
    const enterResult = await page.evaluate(() => {
      const scalar = document.querySelector(
        '.execute-popover.visible .execute-result-scalar');
      return scalar?.textContent;
    });
    assert(enterResult === '15',
           'Enter-key triggered Run, (add [7,8]) → 15 rendered (got '
           + JSON.stringify(enterResult) + ')');

    console.log('✓ execute popover smoke verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
