// Type chip inline-expand + ↳ provenance popover e2e.
//
// Coverage:
//   Phase A: navigate to a fn whose arg-overlay carries a structural
//            type-chip; verify the chip is .type-chip-expandable.
//   Phase B: click the chip → inline panel mounts as a floating host
//            element, populated with constituent mini-chips.
//   Phase C: click again → panel hides (toggle).
//   Phase D: find a chip whose slot was narrowed (↳ badge); click the
//            badge → provenance popover renders with the resolution
//            chain.
//   Phase E: dismiss the popover via Escape.
//
// Target fns:
//   • :assoc-fn has both an expandable type chip AND a ↳ badge — one
//     navigation covers all phases.
//
// Run from this directory:  node edit-type-chip-expand.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, waitFor} = require('./edit-test-helpers');


const TARGET_FN = 'assoc-fn';


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-type-chip-expand — inline expand panel + provenance popover');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + TARGET_FN);
    await page.waitForFunction(
      () => graphReady()
            && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null,
      {timeout: 20000, polling: 100});
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForSelector('.type-chip-expandable', {timeout: 15000});
    // The arg-overlay strip is painted once graphData lands; wait for
    // both a type chip AND its provenance badge to render so phase A's
    // counts are stable.
    await page.waitForFunction(
      () => document.querySelectorAll('.type-chip-expandable').length >= 1
            && document.querySelectorAll('.arg-type-provenance').length >= 1,
      null,
      {timeout: 5000, polling: 100});

    // ===================================================================
    // Phase A: at least one expandable chip + one provenance badge.
    // ===================================================================
    const initial = await page.evaluate(() => ({
      expandable: document.querySelectorAll('.type-chip-expandable').length,
      provenance: document.querySelectorAll('.arg-type-provenance').length,
      visibleInlineHosts: Array.from(
        document.querySelectorAll('.type-inline-host'))
          .filter((h) => h.style.display !== 'none').length,
    }));
    assert(initial.expandable >= 1,
           'at least one .type-chip-expandable on :assoc-fn arg-overlay: '
           + initial.expandable);
    assert(initial.provenance >= 1,
           'at least one ↳ provenance badge: ' + initial.provenance);
    assert(initial.visibleInlineHosts === 0,
           'no inline-expand panels visible at rest');

    // ===================================================================
    // Phase B: click the first expandable chip → inline panel appears.
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector('.type-chip-expandable')?.click();
    });
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('.type-inline-host'))
              .some((h) => h.style.display !== 'none'),
      null,
      {timeout: 5000});

    const opened = await page.evaluate(() => {
      const hosts = Array.from(
        document.querySelectorAll('.type-inline-host'))
          .filter((h) => h.style.display !== 'none');
      const expanded = document.querySelector(
        '.type-chip-expandable[aria-expanded="true"]');
      return {
        visibleHostCount: hosts.length,
        firstHostText: hosts[0]?.textContent?.slice(0, 200) || '',
        chipExpanded: !!expanded,
      };
    });
    assert(opened.visibleHostCount >= 1,
           'inline-expand host mounted + visible: '
           + opened.visibleHostCount);
    assert(opened.chipExpanded,
           'chip carries aria-expanded="true" after click');
    assert(opened.firstHostText.length > 0,
           'panel has content: '
           + JSON.stringify(opened.firstHostText).slice(0, 200));

    // ===================================================================
    // Phase C: click again → panel hides (toggle).
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector(
        '.type-chip-expandable[aria-expanded="true"]')?.click();
    });
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('.type-inline-host'))
              .every((h) => h.style.display === 'none'),
      null,
      {timeout: 5000});
    const collapsed = await page.evaluate(() => ({
      visibleHostCount: Array.from(
        document.querySelectorAll('.type-inline-host'))
        .filter((h) => h.style.display !== 'none').length,
      anyExpanded: !!document.querySelector(
        '.type-chip-expandable[aria-expanded="true"]'),
    }));
    assert(collapsed.visibleHostCount === 0, 'all inline panels hidden after second click');
    assert(!collapsed.anyExpanded, 'no chip has aria-expanded="true"');

    // ===================================================================
    // Phase D: click the ↳ provenance badge → provenance popover renders.
    // A single blind `?.click()` races overlay re-renders — the badge can
    // be momentarily absent right after Phase C's toggle, the optional
    // chaining swallows the miss, and the popover never appears. Poll:
    // click only while the popover is absent AND the badge is present.
    // ===================================================================
    const provOpened = await waitFor(() => page.evaluate(() => {
      if (document.querySelector('.provenance-popover')) return true;
      document.querySelector('.arg-type-provenance')?.click();
      return false;
    }), 30000);
    assert(provOpened, 'provenance popover opened from the ↳ badge');
    const provState = await page.evaluate(() => {
      const pop = document.querySelector('.provenance-popover');
      const trigger = document.querySelector(
        '.arg-type-provenance[aria-expanded="true"]');
      return {
        popoverVisible: !!pop,
        triggerExpanded: !!trigger,
        popoverText: pop?.textContent?.slice(0, 300) || '',
        hasResolutionSection: !!pop?.querySelector('.provenance-section, .provenance-resolution'),
      };
    });
    assert(provState.popoverVisible, 'provenance popover rendered');
    assert(provState.triggerExpanded,
           'trigger badge aria-expanded="true" after click');
    assert(provState.popoverText.length > 0,
           'popover has content: '
           + JSON.stringify(provState.popoverText).slice(0, 200));

    // ===================================================================
    // Phase E: Escape dismisses the provenance popover.
    // ===================================================================
    await page.keyboard.press('Escape');
    // (escape dispatched; the following assertion gates the next step)
    const dismissed = await page.evaluate(() => {
      const pop = document.querySelector('.provenance-popover');
      const visible = pop && pop.style.display !== 'none'
                          && !pop.classList.contains('hidden');
      return {dismissed: !visible};
    });
    assert(dismissed.dismissed,
           'provenance popover dismissed on Escape');

    console.log('✓ type chip expand + provenance popover verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
