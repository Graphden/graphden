// Regression: a level-0 value-binding that migrates to a fn-ref-reached
// child (filling that child's free arg slot) must NOT also render on
// the binding's owning card. Per substitution semantics the literal
// lives only at the deepest consumer.
//
// Repro: navigate to `:ex-outer`, click the `list` row to expand.
// The outer wrapper substitutes its body, revealing the inner
// `:_ex-pair-with-first` → `:_ex-pair-like` chain plus the
// fn-ref-reached `:_ex-list-of-one` child. The literal "first"
// should appear ONCE — on `:_ex-list-of-one`. Pre-fix: TWICE
// (once on `:_ex-pair-with-first`, once on `:_ex-list-of-one`).
//
// Run from this directory:  node regression-migrate-on-fn-ref.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');

(async () => {
  const browser = await chromium.launch({
    args: ['--no-sandbox', '--no-zygote', '--in-process-gpu']});
  const page = await browser.newPage();
  console.log('regression-migrate-on-fn-ref — value-binding migrates, parent suppresses');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#ex-outer');
    await page.waitForFunction(
      () => typeof cy !== 'undefined' && cy && cy.nodes().length > 0
            && !!document.querySelector('button.more-actions-trigger')
            && !cy.animated(),
      null,
      {timeout: 20000, polling: 100});

    // Click "list" row to expand the inner wrapper's body.
    const click = await page.evaluate(() => {
      const lines = Array.from(document.querySelectorAll('.ancestor-line'));
      const target = lines.find(l => (l.textContent || '').trim().startsWith('list'));
      if (!target) return {error: 'no list row'};
      target.dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
      return {clicked: true};
    });
    if (click.error) throw new Error(click.error);
    // Poll until expansion settles — arg-* overlays render after
    // the click triggers layout + Cytoscape paint.
    await page.waitForFunction(() => {
      if (typeof cy === 'undefined') return false;
      const args = cy.nodes()
        .filter(n => (n.data('id') || '').startsWith('arg-'))
        .map(n => n.data('label'));
      return args.length > 0 && !cy.animated();
    },null,  {timeout: 8000, polling: 100});

    const overlays = await page.evaluate(() => {
      if (typeof cy === 'undefined') return null;
      return cy.nodes()
        .filter(n => (n.data('id') || '').startsWith('arg-'))
        .map(n => n.data('label'));
    });

    if (!overlays) throw new Error('cy not initialised');

    const firstCount = overlays.filter(l => l === '"first"').length;
    if (firstCount !== 1) {
      throw new Error(
        '"first" arg-overlay should appear EXACTLY ONCE (lives at the ' +
        ':_ex-list-of-one consumer); got ' + firstCount +
        ' — overlays: ' + JSON.stringify(overlays));
    }

    console.log('  ✓ "first" arg-overlay appears exactly once');
    console.log('  ✓ overlays: ' + JSON.stringify(overlays));
    console.log('regression-migrate-on-fn-ref — PASS');
  } catch (e) {
    console.error('regression-migrate-on-fn-ref — FAIL:', e.message);
    await browser.close();
    process.exit(1);
  }
  await browser.close();
})();
