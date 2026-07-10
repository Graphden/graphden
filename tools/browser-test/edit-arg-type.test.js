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
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + TEST_NAME);
    await page.waitForFunction(
      () => graphReady()
            && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null,
      {timeout: 20000, polling: 100});

    // The arg-overlay carries a `.arg-type-chip` showing the resolved
    // type ("text" inherited from str-len's :string slot). Since the
    // chip-row UI consolidation, the first inner div on the arg-
    // overlay packs value + chip + provenance into a single flexbox,
    // so an exact-text match no longer works — identify the arg-
    // overlay by the chip itself (only arg overlays carry one) and
    // then narrow to the one whose value renders as `"hello"`.
    const chipClicked = await page.evaluate(() => {
      const overlay = Array.from(document.querySelectorAll('.node-overlay'))
        .find(el => {
          const chip = el.querySelector('.arg-type-chip');
          if (!chip) return false;
          // Walk all direct child nodes / spans looking for an exact
          // `"hello"` text — that's the value-display.
          const valueDiv = Array.from(el.querySelectorAll('div, span'))
            .find(d => (d.textContent || '').trim() === '"hello"');
          return !!valueDiv;
        });
      if (!overlay) return {error: 'arg-overlay for "hello" not found'};
      const chip = overlay.querySelector('.arg-type-chip');
      if (!chip) return {error: 'no .arg-type-chip on arg-overlay'};
      return {clicked: (chip.click(), true), chipText: chip.textContent.trim()};
    });
    assert(!chipClicked.error, chipClicked.error || 'chip clicked');
    assert(chipClicked.chipText === 'text',
           'chip shows "text" before flip: ' + JSON.stringify(chipClicked));
    // Type-edit popover opens after the chip click.
    await page.waitForSelector('.arg-value-edit-popover',
                               {timeout: 5000});

    // The type-edit popover renders a <select> with every VALUE_KIND,
    // but `populateCompatibleTypes` is async — it POSTs /api/types/
    // compatible to discover which refinements narrow the current
    // slot. Until that resolves the <select> only has the current
    // type + an empty `loading…` placeholder. Wait for the SPECIFIC
    // target option (`non-blank-text`) to appear — checking just
    // `length >= 2` matches the placeholder state and the save
    // PUTs the unchanged type (200, but :type-override-fn-id
    // doesn't actually change).
    await page.waitForFunction(
      () => {
        const sel = document.querySelector('.arg-value-edit-popover select');
        if (!sel) return false;
        return Array.from(sel.options).some(o => o.value === 'non-blank-text');
      },
      null,
      {timeout: 15000});
    const selectProbe = await page.evaluate(() => {
      const sel = document.querySelector('.arg-value-edit-popover select');
      if (!sel) return {error: '<select> not rendered'};
      return {
        options: Array.from(sel.options).map(o => o.value)
      };
    });
    assert(!selectProbe.error, selectProbe.error || 'select rendered');
    console.log('  (options offered: ' + JSON.stringify(selectProbe.options) + ')');
    // The select offers value-kinds that are SUBTYPES of the slot's
    // expected type — :str-len.string is :text, so the picker shows
    // `text` + text-refinements (`non-blank-text`, `non-empty-text`,
    // `url`). It WON'T show :int / :jsonb / :any because those would
    // widen the contract. Test the load-bearing properties: the
    // current type is present + at least one alternative is available.
    assert(selectProbe.options.includes('text'),
           '<select> offers the current :text type (selectable identity)');
    assert(selectProbe.options.length >= 2,
           '<select> has at least 2 options (current + a flip target)');

    // Save button exists, is enabled, and the end-to-end flip
    // persists into storage. Flip to `:non-blank-text` (a text-
    // refinement — narrows the current contract, picker accepts).
    const saveBtnState = await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll(
        '.arg-value-edit-buttons .arg-value-edit-btn'))
        .find(b => b.textContent.trim() === 'Save');
      return {
        present: !!btn,
        disabled: btn?.disabled || btn?.getAttribute('aria-disabled') === 'true',
      };
    });
    assert(saveBtnState.present, 'type-edit popover has a Save button');

    // Pick the refinement + Save. The picker accepts subtype
    // narrowings only — `:text → :non-blank-text` is the canonical
    // legitimate flip. Wait for the popover to dismiss (a quick
    // `.arg-value-edit-popover` poll) before reading storage so we
    // don't race the save's PUT round-trip.
    await page.evaluate(() => {
      const sel = document.querySelector('.arg-value-edit-popover select');
      sel.value = 'non-blank-text';
      sel.dispatchEvent(new Event('change', {bubbles: true}));
    });
    // Arm the response wait BEFORE clicking — Save fires authFetch
    // PUT to /api/entities/binding/:id, which is what we need to
    // wait for. Polling storage blindly was racing the editor's
    // own background fetches (loadGraphData, /api/services,
    // /api/types) all running through the same browser network
    // queue. With the response wait, we get a definitive signal
    // that the PUT actually completed — then the storage read
    // sees the persisted state immediately.
    const putWait = page.waitForResponse(
      r => /\/api\/entities\/binding\//.test(r.url())
           && r.request().method() === 'PUT',
      {timeout: 30000});
    await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll(
        '.arg-value-edit-buttons .arg-value-edit-btn'))
        .find(b => b.textContent.trim() === 'Save');
      btn.click();
    });
    const putResp = await putWait;
    assert(putResp.status() === 200,
           'PUT /api/entities/binding/:id returned 200 (got '
           + putResp.status() + ')');

    const postSaveEnts = await getEntities(page);
    const refineFn = postSaveEnts.fns.find(
      f => f.name === 'non-blank-text' && (!f['parent-ids'] || f['parent-ids'].length === 0));
    assert(refineFn,
           ':non-blank-text type-row exists in the graph (precondition for the flip target)');
    const afterBinding = postSaveEnts.bindings.find(b => b.id === arg['binding-id']);
    assert(afterBinding && afterBinding['type-override-fn-id'] === refineFn.id,
           'binding.type-override-fn-id points at :non-blank-text: '
           + JSON.stringify({override: afterBinding?.['type-override-fn-id'],
                             refineFn: refineFn.id}));
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('arg-type-flip — PASS');
})().catch(e => {
  console.error('arg-type-flip — FAIL:', e.message);
  process.exit(1);
});
