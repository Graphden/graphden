// Free-arg literal-bind path. Click a free-arg placeholder → chooser
// pops up → pick "Bind literal" → value-edit popover → type a literal
// → Save → verify storage now carries the value.
//
// Run from this directory:  node edit-free-arg-literal.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, deleteFnByName, waitFor} =
  require('./edit-test-helpers');

const TEST_NAME = 'test-free-arg-literal';

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('free-arg-literal — Phase 4: bind a literal into a free slot');
  try {
    await deleteFnByName(page, TEST_NAME);

    // Same setup as fn-picker-filter: fn parented to :str-len with an
    // inheriting :string free arg (no value, no ref → renders as
    // a clickable placeholder).
    const ents = await getEntities(page);
    const strLen = ents.fns.find(f => f.name === 'str-len');
    const stringArg = synthArgs(ents).find(
      a => a['fn-id'] === strLen.id && a.name === 'string' && !a['source-id']);
    assert(strLen && stringArg, ':str-len.string baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + TEST_NAME + '&parent-ids=' + strLen.id);
    const fn = (await getEntities(page)).fns.find(f => f.name === TEST_NAME);
    // No explicit "POST inheriting arg" step — slot/binding model
    // exposes the inherited :string slot automatically.

    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#' + TEST_NAME);
    await page.waitForTimeout(2500);

    // Click the unset-placeholder's `.placeholder-binder` button →
    // chooser. The overlay wrapper has pointer-events:none (so drag
    // events pass through to cytoscape), so clicking the wrapper or
    // its inner div is a no-op — only the binder button is wired.
    const placeholderClicked = await page.evaluate(() => {
      const placeholder = Array.from(document.querySelectorAll('.node-overlay'))
        .find(el => /^unset-/.test(el.getAttribute('data-node-id') || ''));
      if (!placeholder) return {error: 'no placeholder overlay'};
      const btn = placeholder.querySelector('.placeholder-binder')
                 || placeholder.querySelector('button');
      if (!btn) return {error: 'no .placeholder-binder button'};
      btn.click();
      return {clicked: true};
    });
    assert(!placeholderClicked.error, placeholderClicked.error || 'placeholder clicked');
    await page.waitForTimeout(200);

    // Pick "Bind literal" from the chooser.
    const litClicked = await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll(
        '.free-arg-bind-chooser button'))
        .find(b => /Bind literal/.test(b.textContent || ''));
      if (!btn) return {error: 'no Bind literal button in chooser'};
      btn.click();
      return {clicked: true};
    });
    assert(!litClicked.error, litClicked.error || 'literal button clicked');
    // Value-form is fetched async from /api/value-form — wait for the
    // input to mount rather than a fixed timeout. The host opens
    // synchronously (`.value-form-host` + `.value-form-loading`
    // visible immediately) but `.arg-value-edit-input` appears only
    // after the fetch resolves.
    const formReady = await waitFor(
      () => page.evaluate(() => !!document.querySelector('.arg-value-edit-input')),
      3000);
    assert(formReady, 'value-edit input rendered');

    const hintProbe = await page.evaluate(() => {
      const h = document.querySelector('.arg-value-edit-hint');
      const i = document.querySelector('.arg-value-edit-input');
      return {hint: h && h.textContent, hasInput: !!i};
    });
    assert(hintProbe.hasInput, 'value-edit input rendered (post-waitFor)');
    assert(/Expected.*text/i.test(hintProbe.hint || ''),
           'hint reads "Expected: text" (the slot type from str-len): '
           + JSON.stringify(hintProbe.hint));

    // Type a literal string and click Save. The value-form mounts a
    // plain text <input> for the :text slot, so we type the bare
    // string `hello` (no JSON-encoding). Earlier the input was
    // smart-parsed and the test had to wrap in quotes; the current
    // form-rendering path is text-as-text and the round-trip
    // becomes user-typing-clean.
    await page.evaluate(() => {
      const i = document.querySelector('.arg-value-edit-input');
      i.value = 'hello';
      i.dispatchEvent(new Event('input', {bubbles: true}));
    });
    await page.waitForTimeout(150);

    // Live status should be ✓ (text into :text slot).
    const okStatus = await page.evaluate(() => {
      const s = document.querySelector('.arg-value-edit-status');
      return {text: s && s.textContent, ok: s && s.classList.contains('ok')};
    });
    assert(okStatus.ok,
           '"hello" against :text slot shows ✓: ' + JSON.stringify(okStatus));

    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.arg-value-edit-buttons .arg-value-edit-btn'))
        .find(b => b.textContent.trim() === 'Save').click();
    });
    // Save is a POST to /api/entities/binding followed by initGraph
    // which refetches the editor's lookups; poll for the binding row
    // instead of a fixed 2s wait — under load (full e2e parallel
    // pressure on docker), the save+refetch chain can exceed 2s and
    // the original `waitForTimeout(2000)` produced a flake on cold
    // cache.
    let ownBinding;
    const bound = await waitFor(async () => {
      const after = await getEntities(page);
      ownBinding = (after.bindings || []).find(
        b => b['fn-id'] === fn.id && b['slot-id'] === stringArg['slot-id']);
      return ownBinding && ownBinding.value === 'hello';
    }, 5000);
    assert(bound,
           'binding.value === "hello" after save: ' + JSON.stringify(ownBinding));
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('free-arg-literal — PASS');
})().catch(e => {
  console.error('free-arg-literal — FAIL:', e.message);
  process.exit(1);
});
