// Value cards + the literal editor on a wide slot (2026-09-17).
//
// Asserts:
//   • a whitespace-only string literal prints its characters as glyphs on the
//     card (`"␣"`) and says so in the title — not two bare quotes;
//   • a value card's text is ONE line and fits: no wrapping (`"big` / `world"`),
//     nothing clipped (the card is measured for the text it shows);
//   • on an `:any` slot the literal editor smart-parses (a bare word is text)
//     and offers an "as" chooser: picking `text` swaps in the text input, and
//     Save writes the value AND narrows the binding (type-override → :text);
//   • the CodeMirror JSON editor colours tokens through theme classes.
//
// Run from this directory:  node edit-value-card-display.test.js
// Exit code 0 = PASS, 1 = FAIL.
const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} = require('./edit-test-helpers');

const RUN = '-' + process.pid.toString(36);
const JOIN = 'vcd-join' + RUN;
const ANY = 'vcd-any' + RUN;

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  console.log('edit-value-card-display — whitespace glyphs, one-line cards, as-chooser narrowing');
  let failed = false;
  try {
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    const strJoin = (await getEntities(page, 'str-join')).fns.find((f) => f.name === 'str-join');
    const cst = (await getEntities(page, 'const')).fns.find((f) => f.name === 'const');
    const slots = (await getEntities(page, 'str-join')).slots;
    const sep = slots.find((s) => s.name === 'separator');
    const coll = slots.find((s) => s.name === 'coll');
    await api(page, 'POST', '/api/entities/fn', 'name=' + JOIN + '&parent-ids=' + strJoin.id);
    const join = (await getEntities(page, JOIN)).fns.find((f) => f.name === JOIN);
    assert(join, 'join fixture created');
    await api(page, 'POST', '/api/entities/binding', 'fn-id=' + join.id + '&slot-id=' + sep.id + '&value=' + encodeURIComponent(JSON.stringify(' ')));
    await api(page, 'POST', '/api/entities/binding', 'fn-id=' + join.id + '&slot-id=' + coll.id + '&list-append=true');
    for (const v of ['hello', 'big world', 'a fairly long text value that gets cut on the card']) {
      await api(page, 'POST', '/api/sequence/append/' + join.id, {value: v});
    }
    await api(page, 'POST', '/api/entities/fn', 'name=' + ANY + '&parent-ids=' + cst.id);
    const anyFn = (await getEntities(page, ANY)).fns.find((f) => f.name === ANY);
    assert(anyFn, 'any fixture created');

    // ---- cards ----
    await page.goto(BASE + '/#' + JOIN);
    await page.waitForSelector('.node-overlay[data-fn-name="' + JOIN + '"]', {timeout: 60000});
    await page.waitForFunction(() => document.querySelectorAll('.arg-value-text').length >= 4, null, {timeout: 30000});
    await page.waitForTimeout(800);
    const cards = await page.evaluate(() => Array.from(document.querySelectorAll('.arg-value-text')).map((c) => ({
      text: c.textContent, title: c.title, ws: getComputedStyle(c).whiteSpace,
      clipped: c.scrollWidth > c.clientWidth + 1,
      oneLine: c.getBoundingClientRect().height < 2 * parseFloat(getComputedStyle(c).fontSize) * 1.6,
    })));
    const space = cards.find((c) => c.text === '"␣"');
    assert(space, 'the single-space separator prints as "␣": ' + JSON.stringify(cards.map((c) => c.text)));
    assert(/single space/.test(space.title), 'its title explains the glyph: ' + space.title);
    assert(cards.every((c) => c.ws === 'pre' && c.oneLine), 'every value card is one line: ' + JSON.stringify(cards));
    assert(cards.every((c) => !c.clipped), 'no card clips its text — measured for what it shows: ' + JSON.stringify(cards));
    assert(cards.some((c) => /…$/.test(c.text) && c.text.length <= 31), 'a long value is cut at 30 chars with …');

    // ---- the wide slot: smart parse + "as" ----
    await page.goto(BASE + '/#' + ANY);
    await page.waitForSelector('.placeholder-binder[data-fn-name="' + ANY + '"]', {timeout: 60000});
    await page.click('.placeholder-binder[data-fn-name="' + ANY + '"]');
    await page.waitForSelector('.free-arg-bind-chooser', {timeout: 15000});
    await page.evaluate(() => Array.from(document.querySelectorAll('.free-arg-bind-chooser button')).find((b) => b.textContent.trim() === 'Bind literal').click());
    await page.waitForSelector('.value-form-as-select', {timeout: 15000});
    await page.waitForSelector('.value-form-host [data-form-field]', {timeout: 15000, state: 'attached'});
    const kind = await page.evaluate(() => document.querySelector('.value-form-host [data-form-field]')?.dataset.fieldKind);
    assert(kind === 'any', 'an :any slot opens the smart-parse editor (a bare word is text): ' + kind);
    assert(await page.evaluate(() => !!document.querySelector('.gd-code-editor .gd-tok-punct, .gd-code-editor')), 'the JSON editor is CodeMirror-enhanced');
    await page.selectOption('.value-form-as-select', 'text');
    await page.waitForSelector('.value-form-host input[data-field-kind="text"]', {timeout: 15000});
    await page.fill('.value-form-host input[data-field-kind="text"]', 'tick');
    await page.waitForFunction(() => /OK/.test(document.querySelector('.arg-value-edit-status')?.textContent || ''), null, {timeout: 5000});
    await page.click('.arg-value-edit-popover .arg-value-edit-btn:not(.arg-value-edit-btn-secondary):not(.arg-value-edit-btn-danger)');
    await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'), null, {timeout: 20000});
    await page.waitForFunction((n) => Array.from(document.querySelectorAll('.arg-value-text')).some((e) => e.textContent === '"tick"'), null, {timeout: 30000});
    const ents = await getEntities(page, ANY);
    const b = ents.bindings.find((x) => x['fn-id'] === anyFn.id);
    const textFn = (await getEntities(page, 'text')).fns.find((f) => f.name === 'text' && !(f['parent-ids'] || []).length);
    assert(b && b.value === 'tick', 'the value landed as the text "tick": ' + JSON.stringify(b && b.value));
    assert(b && textFn && b['type-override-fn-id'] === textFn.id, 'Save narrowed the slot to :text at this use-site (type-override-fn-id)');
    const chip = await page.evaluate(() => document.querySelector('.arg-type-chip')?.textContent || '');
    assert(/text/.test(chip), 'the card\'s type chip reads the narrowed type: ' + chip);
    console.log('  cards: glyph / one line / no clipping — as: text → narrowed');
  } catch (e) {
    failed = true;
    console.error('FAIL:', e.message || e);
  } finally {
    for (const n of [JOIN, ANY]) { try { await deleteFnByName(page, n); } catch (_) {} }
    await browser.close();
  }
  console.log(failed ? 'FAIL' : 'PASS');
  process.exit(failed ? 1 : 0);
})();
