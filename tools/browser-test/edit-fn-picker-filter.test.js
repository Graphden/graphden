// Type-aware fn-picker — when opened from a slot with a known
// expected type, compatible candidates float to the top with a green
// ✓ glyph; incompatible ones get the dimmed `effects-chip-incompat`
// styling.
//
// Run from this directory:  node edit-fn-picker-filter.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, deleteFnByName} =
  require('./edit-test-helpers');

const TEST_NAME = 'test-fn-picker-filter';

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('fn-picker-filter — type-compatible candidates float + ✓');
  try {
    await deleteFnByName(page, TEST_NAME);

    // Build: tiny fn parented to :str-len, with an inheriting :string
    // arg (no value, no ref-id) so it renders as a free-arg
    // placeholder. :str-len's :string slot is :text-typed — that's
    // what drives the picker's expectedType filter.
    const ents = await getEntities(page);
    const strLen = ents.fns.find(f => f.name === 'str-len');
    const stringArg = synthArgs(ents).find(
      a => a['fn-id'] === strLen.id && a.name === 'string' && !a['source-id']);
    assert(strLen && stringArg, ':str-len.string baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + TEST_NAME + '&parent-ids=' + strLen.id);
    const fn = (await getEntities(page)).fns.find(f => f.name === TEST_NAME);
    assert(fn, 'test fn created');
    // In the slot/binding model the inherited :string slot shows up
    // automatically as a free placeholder (no own binding needed —
    // synth-args produces an anchor row for the (fn, slot) pair on
    // every inheriting child). The legacy "POST inheriting arg"
    // step the old test had here is gone.

    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + TEST_NAME);
    await page.waitForTimeout(2500);

    // Find the unset-placeholder overlay (data-node-id starts with
    // "unset-"). The click handler now sits on a `.placeholder-binder`
    // BUTTON child (the overlay wrapper has pointer-events:none so
    // clicks pass through cytoscape's drag handler), so target the
    // button directly — clicking the wrapper does nothing.
    const opened = await page.evaluate(() => {
      const placeholder = Array.from(document.querySelectorAll('.node-overlay'))
        .find(el => /^unset-/.test(el.getAttribute('data-node-id') || ''));
      if (!placeholder) return {error: 'no placeholder overlay'};
      const btn = placeholder.querySelector('.placeholder-binder')
                 || placeholder.querySelector('button');
      if (!btn) return {error: 'no .placeholder-binder button'};
      btn.click();
      return {clicked: true};
    });
    assert(!opened.error, opened.error || 'placeholder clicked');
    await page.waitForTimeout(200);

    // The chooser popover offers "Bind literal" / "Bind fn-ref".
    // Pick fn-ref to reach the picker.
    const refClicked = await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll(
        '.free-arg-bind-chooser button'))
        .find(b => /Bind fn-ref/.test(b.textContent || ''));
      if (!btn) return {error: 'no Bind fn-ref button'};
      btn.click();
      return {clicked: true};
    });
    assert(!refClicked.error, refClicked.error || 'fn-ref button clicked');
    await page.waitForTimeout(300);

    // Picker should now be open with expectedType = 'text' (the
    // :string slot's resolved type via :str-len's primary). Different
    // fns should land in different compat buckets — :text returners
    // ✓, others dimmed.
    // Probe 1 (no filter): compat candidates float to top with ✓.
    const probe = await page.evaluate(() => {
      const picker = document.querySelector('.fn-picker-popover');
      if (!picker) return {error: 'fn-picker not open'};
      const rows = Array.from(picker.querySelectorAll('.fn-picker-row'));
      return {
        total: rows.length,
        compat: rows.filter(r =>
          r.classList.contains('fn-picker-row-compat')).length,
        // Each compat row should carry a ✓ glyph child.
        firstCompatHasOk: (() => {
          const first = rows.find(r =>
            r.classList.contains('fn-picker-row-compat'));
          return first ? !!first.querySelector('.fn-picker-row-ok') : false;
        })()
      };
    });
    assert(!probe.error, probe.error || 'picker open');
    assert(probe.total > 0, 'picker shows candidates: ' + JSON.stringify(probe));
    assert(probe.compat > 0,
           'at least one type-compatible fn is marked compat: '
           + JSON.stringify(probe));
    assert(probe.firstCompatHasOk,
           'first compat row carries the ✓ glyph');

    // Probe 2: text-search filtering narrows the candidate list.
    // The picker now HIDES type-incompatible fns by default (a UX
    // improvement over the old "dim them with a strikethrough"
    // behavior — incompat candidates would never be a valid choice
    // anyway, surfacing them is noise). Test the load-bearing
    // behavior: filtering by name narrows the list and the surviving
    // rows are all type-compat.
    //
    // `lower` / `upper` are :text → :text transformers that show up
    // in the str-len.string slot's compat set; filtering on `lower`
    // surfaces at least one row, and every row is compat.
    await page.evaluate(() => {
      const inp = document.querySelector('.fn-picker-search');
      if (inp) {
        inp.value = 'lower';
        inp.dispatchEvent(new Event('input', {bubbles: true}));
      }
    });
    await page.waitForTimeout(150);
    const filterProbe = await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll(
        '.fn-picker-popover .fn-picker-row'));
      return {
        total: rows.length,
        compat: rows.filter(r =>
          r.classList.contains('fn-picker-row-compat')).length,
        incompat: rows.filter(r =>
          r.classList.contains('fn-picker-row-incompat')).length,
      };
    });
    assert(filterProbe.total > 0,
           'filter "lower" surfaces at least one row (text → text candidates exist): '
           + JSON.stringify(filterProbe));
    assert(filterProbe.compat === filterProbe.total,
           'every surviving row is type-compat (incompat hidden in the new UX): '
           + JSON.stringify(filterProbe));

    // Close the picker to keep the page clean.
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('fn-picker-filter — PASS');
})().catch(e => {
  console.error('fn-picker-filter — FAIL:', e.message);
  process.exit(1);
});
