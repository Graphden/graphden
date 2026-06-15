// Effects explainer popover e2e — click an effect chip on a fn-card
// → popover with plain-English description + canonical tag.
//
// Coverage:
//   • Navigate to `:current-time-ms` (base-fn with declared
//     `:effects #{:time}`).
//   • Verify a `.effects-chip-time` chip renders on the card's
//     bottom strip.
//   • Click the chip → popover (.type-explainer with `.visible`)
//     mounts under it.
//   • Popover lists "Effect" title + the time description +
//     the canonical `:time` structural row.
//   • Escape dismisses the popover.
//
// Run from this directory:  node edit-effects-explainer.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');


const TARGET_FN = 'current-time-ms';


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
  console.log('edit-effects-explainer — click effect chip → popover → dismiss');

  try {
    await page.goto('http://localhost:9002/#' + TARGET_FN);
    await page.waitForTimeout(2500);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForSelector('.effects-chip-time', {timeout: 15000});

    // ===================================================================
    // Phase A: chip visible.
    // ===================================================================
    const initial = await page.evaluate(() => {
      const chips = Array.from(document.querySelectorAll('.effects-chip-time'));
      return {
        chipCount: chips.length,
        chipText: chips[0]?.textContent?.trim(),
      };
    });
    assert(initial.chipCount >= 1,
           ':time effect chip renders on :current-time-ms card: '
           + initial.chipCount);
    assert(/time/i.test(initial.chipText || ''),
           'chip glyph reads "time": ' + JSON.stringify(initial.chipText));

    // ===================================================================
    // Phase B: click chip → popover opens.
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector('.effects-chip-time')?.click();
    });
    await page.waitForFunction(
      () => {
        const p = document.querySelector('.type-explainer.visible');
        return !!p && (p.textContent || '').length > 0;
      },
      {timeout: 5000});

    const popoverState = await page.evaluate(() => {
      const p = document.querySelector('.type-explainer.visible');
      const title = p?.querySelector('.type-explainer-title')?.textContent?.trim();
      const human = p?.querySelector('.type-explainer-human')?.textContent?.trim();
      const struct = p?.querySelector('.type-explainer-structural')?.textContent?.trim();
      return {
        visible: !!p,
        title,
        human,
        struct,
      };
    });
    assert(popoverState.visible, 'effect explainer popover visible');
    assert(popoverState.title === 'Effect',
           'popover title is "Effect": '
           + JSON.stringify(popoverState.title));
    assert(/time|clock|wall/i.test(popoverState.human || ''),
           'description mentions wall-clock / time: '
           + JSON.stringify(popoverState.human));
    assert(popoverState.struct === ':time',
           'structural row carries canonical ":time" tag: '
           + JSON.stringify(popoverState.struct));

    // ===================================================================
    // Phase C: Escape dismisses.
    // ===================================================================
    await page.keyboard.press('Escape');
    await page.waitForFunction(
      () => {
        const p = document.querySelector('.type-explainer');
        return !p || !p.classList.contains('visible');
      },
      {timeout: 3000});
    const dismissed = await page.evaluate(() => {
      const p = document.querySelector('.type-explainer');
      return !p || !p.classList.contains('visible');
    });
    assert(dismissed, 'Escape dismisses the effect explainer');

    console.log('✓ effects explainer verified — chip / popover / description / dismiss');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
