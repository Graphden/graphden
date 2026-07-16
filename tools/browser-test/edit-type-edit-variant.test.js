// Variant type-row edit e2e — open via pencil, append a third branch,
// Save → PUT /api/entities/fn/:id with constraint=["variant", tag1,
// type1, …].
//
// Coverage:
//   • Seed a variant `[ok: int, err: text]` via POST /api/entities/fn.
//   • Open the edit popover via `window.openTypeEditForm`.
//   • Verify both branches prefilled as pair-rows.
//   • Click "+ add row", fill `pending: bool`, Save.
//   • Verify the fn's `constraint` is now ["variant", ok, int, err,
//     text, pending, bool] (length 7 — tag + 3 pairs).
//
// Run from this directory:  node edit-type-edit-variant.test.js

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName, waitFor} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const VAR_FN = 'edit-var-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, VAR_FN); } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => d.accept());
  console.log('edit-type-edit-variant — open / add branch / save / storage');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: variant via generic /api/entities/fn POST with constraint
    // ===================================================================
    // /api/entities/fn returns a 200 with an HTML success page (legacy
     // htmx hook) — we don't need the response body; verify creation
     // via the entities snapshot below.
    await api(page, 'POST', '/api/entities/fn',
      'name=' + VAR_FN
      + '&constraint=' + encodeURIComponent(
          JSON.stringify(['variant', 'ok', 'int', 'err', 'text'])));

    const ents = await getEntities(page);
    const varFn = ents.fns.find((f) => f.name === VAR_FN);
    assert(varFn, 'variant resolves: id=' + varFn?.id);
    assert(Array.isArray(varFn.constraint)
           && varFn.constraint[0] === 'variant',
           'seeded constraint is a variant: '
           + JSON.stringify(varFn.constraint));

    // ===================================================================
    // Navigate + force re-fetch.
    // ===================================================================
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + VAR_FN,
                    {waitUntil: 'networkidle'});
    await page.waitForFunction(
      () => typeof openTypeEditForm === 'function'
            && typeof initGraph === 'function'
            && typeof lookups === 'object'
            && lookups?.fnMap?.size > 0,
      null,
      {timeout: 30000});
    await page.evaluate(async () => { await initGraph(); });
    // Poll until the variant lands in the editor's in-memory
    // lookups (initGraph kicks off async loads).
    await page.waitForFunction(
      (fnId) => !!lookups?.fnMap?.get(fnId),
      varFn.id, {timeout: 15000, polling: 100});
    const inLookups = await page.evaluate(
      (fnId) => !!lookups?.fnMap?.get(fnId), varFn.id);
    assert(inLookups, 'variant in editor lookups');

    // ===================================================================
    // Open the edit popover.
    // ===================================================================
    await page.evaluate((fnId) => {
      window.openTypeEditForm(fnId, document.body);
    }, varFn.id);
    await page.waitForSelector('.type-create-popover', {timeout: 5000});
    await page.waitForFunction(
      () => document.querySelectorAll('.type-create-popover .type-create-pair-row')
              .length >= 2,
      null,
      {timeout: 5000});

    // ===================================================================
    // Phase A: prefilled with 2 branches.
    // ===================================================================
    const prefillState = await page.evaluate(() => {
      const el = document.querySelector('.type-create-popover');
      const rows = Array.from(el?.querySelectorAll('.type-create-pair-row') || []);
      const pairs = rows.map((r) => ({
        tag: r.querySelector('.type-create-pair-key')?.value,
        type: r.querySelector('.type-create-pair-val')?.value,
      }));
      return {
        pairs,
        submitText: el?.querySelector('.type-create-submit')?.textContent?.trim(),
      };
    });
    assert(prefillState.pairs.some((p) => p.tag === 'ok' && p.type === 'int'),
           'branch "ok: int" prefilled: '
           + JSON.stringify(prefillState.pairs));
    assert(prefillState.pairs.some((p) => p.tag === 'err' && p.type === 'text'),
           'branch "err: text" prefilled');
    assert(/Save/i.test(prefillState.submitText || ''),
           'Save button: ' + JSON.stringify(prefillState.submitText));

    // ===================================================================
    // Phase B: add branch "pending: bool", Save.
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector('.type-create-popover .type-create-pair-add')?.click();
    });
    await page.waitForFunction(
      () => document.querySelectorAll('.type-create-popover .type-create-pair-row')
              .length >= 3,
      null,
      {timeout: 3000});
    await page.evaluate(() => {
      const rows = document.querySelectorAll('.type-create-popover .type-create-pair-row');
      const last = rows[rows.length - 1];
      const set = (input, v) => {
        const proto = Object.getPrototypeOf(input);
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(input, v);
        input.dispatchEvent(new Event('input', {bubbles: true}));
      };
      set(last.querySelector('.type-create-pair-key'), 'pending');
      set(last.querySelector('.type-create-pair-val'), 'bool');
    });
    await page.evaluate(() => {
      document.querySelector('.type-create-popover .type-create-submit')?.click();
    });
    await page.waitForFunction(
      () => {
        const el = document.querySelector('.type-create-popover');
        return !el || el.style.display === 'none';
      },
      null,
      {timeout: 15000});
    // Poll storage until the constraint has 3 branches (length 7).
    const settled = await waitFor(async () => {
      const e = await getEntities(page);
      const f = e.fns.find((f) => f.id === varFn.id);
      return f && Array.isArray(f.constraint) && f.constraint.length === 7;
    }, 5000);
    assert(settled, 'variant constraint did not settle to 3 branches in 5s');

    // ===================================================================
    // Phase C: storage — constraint has 3 branches now.
    // ===================================================================
    const ents2 = await getEntities(page);
    const varFn2 = ents2.fns.find((f) => f.id === varFn.id);
    assert(varFn2 && Array.isArray(varFn2.constraint),
           'fn still has constraint after edit');
    assert(varFn2.constraint[0] === 'variant',
           'constraint head is "variant": '
           + JSON.stringify(varFn2.constraint[0]));
    assert(varFn2.constraint.length === 7,
           'constraint length is 7 (variant + 3*(tag,type)): '
           + varFn2.constraint.length);
    const flatPairs = varFn2.constraint.slice(1);
    const tags = flatPairs.filter((_, i) => i % 2 === 0);
    assert(tags.includes('pending'),
           'new branch tag "pending" present: '
           + JSON.stringify(tags));

    console.log('✓ variant-edit verified — open / prefill / add branch / save / constraint');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.stack || e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
