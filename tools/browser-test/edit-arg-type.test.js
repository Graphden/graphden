// Phase 2 — arg-type flip via the type chip on an arg-overlay.
//
// Click the chip → <select> popover with every VALUE_KIND →
// pick a different type → Save. Backend should:
//   - update arg.type
//   - clear arg.value and arg.ref-id (the bound literal/ref no longer
//     fits the new type)
//
// Run from this directory:  node edit-arg-type.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, deleteFnByName} =
  require('./edit-test-helpers');

const TEST_NAME = 'test-arg-type-flip';

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('arg-type-flip — Phase 2: type chip → <select> → save');
  try {
    await deleteFnByName(page, TEST_NAME);

    // Seed: fn parented to :str-len with a bound :string="hello".
    // Type chip renders only on overlay'd args, not unset placeholders.
    const ents = await getEntities(page);
    const strLen = ents.fns.find(f => f.name === 'str-len');
    const stringArg = synthArgs(ents).find(
      a => a['fn-id'] === strLen.id && a.name === 'string' && !a['source-id']);
    assert(strLen && stringArg, ':str-len.string baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + TEST_NAME + '&parent-ids=' + strLen.id);
    const fn = (await getEntities(page)).fns.find(f => f.name === TEST_NAME);
    await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + fn.id + '&slot-id=' + stringArg['slot-id'] +
              '&value=' + encodeURIComponent('"hello"'));
    const arg = synthArgs(await getEntities(page)).find(
      a => a['fn-id'] === fn.id && a['slot-id'] === stringArg['slot-id']);
    assert(arg && arg.value === 'hello', ':string="hello" seeded');

    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#' + TEST_NAME);
    await page.waitForTimeout(2500);

    // The arg-overlay carries a `.arg-type-chip` showing the resolved
    // type ("text" inherited from str-len's :string slot).
    const chipClicked = await page.evaluate(() => {
      const overlay = Array.from(document.querySelectorAll('.node-overlay'))
        .find(el => {
          const inner = el.querySelector('div');
          // Stored value is the string "hello"; the renderer JSON-
          // stringifies non-trivial values, so the visible text is `"hello"`.
          return inner && (inner.textContent || '').trim() === '"hello"';
        });
      if (!overlay) return {error: 'arg-overlay for "hello" not found'};
      const chip = overlay.querySelector('.arg-type-chip');
      if (!chip) return {error: 'no .arg-type-chip on arg-overlay'};
      return {clicked: (chip.click(), true), chipText: chip.textContent.trim()};
    });
    assert(!chipClicked.error, chipClicked.error || 'chip clicked');
    assert(chipClicked.chipText === 'text',
           'chip shows "text" before flip: ' + JSON.stringify(chipClicked));
    await page.waitForTimeout(250);

    // The type-edit popover renders a <select> with every VALUE_KIND.
    const selectProbe = await page.evaluate(() => {
      const sel = document.querySelector('.arg-value-edit-popover select');
      if (!sel) return {error: '<select> not rendered'};
      return {
        options: Array.from(sel.options).map(o => o.value)
      };
    });
    assert(!selectProbe.error, selectProbe.error || 'select rendered');
    for (const k of ['null', 'int', 'text', 'bool', 'jsonb', 'any', 'fn']) {
      assert(selectProbe.options.includes(k),
             '<select> offers ' + k + ' option');
    }

    // Flip to :int and click Save.
    await page.evaluate(() => {
      const sel = document.querySelector('.arg-value-edit-popover select');
      sel.value = 'int';
      sel.dispatchEvent(new Event('change', {bubbles: true}));
    });
    await page.evaluate(() => {
      Array.from(document.querySelectorAll(
        '.arg-value-edit-buttons .arg-value-edit-btn'))
        .find(b => b.textContent.trim() === 'Save').click();
    });
    await page.waitForTimeout(2500);

    // Storage state: value cleared, ref-id cleared, slot type-override
    // pointed at :int. Type flip lives on the binding's
    // `:type-override-fn-id` (no longer a per-arg field), so the assert
    // walks bindings/list-items rather than the synth view.
    const afterEnts = await getEntities(page);
    const afterBinding = afterEnts.bindings.find(b => b.id === arg['binding-id']);
    assert(afterBinding && afterBinding.value === null,
           'binding.value cleared on type flip: ' + JSON.stringify(afterBinding));
    assert(afterBinding && afterBinding['ref-fn-id'] === null,
           'binding.ref-fn-id cleared on type flip');
    const intFn = afterEnts.fns.find(f => f.name === 'int' && (!f['parent-ids'] || f['parent-ids'].length === 0));
    assert(intFn && afterBinding['type-override-fn-id'] === intFn.id,
           'binding.type-override-fn-id points at :int: '
           + JSON.stringify({override: afterBinding['type-override-fn-id'], intFn: intFn && intFn.id}));
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('arg-type-flip — PASS');
})().catch(e => {
  console.error('arg-type-flip — FAIL:', e.message);
  process.exit(1);
});
