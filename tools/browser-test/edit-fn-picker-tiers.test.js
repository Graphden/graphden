// fn-picker tiers + narrowing — the picker ranks candidates by their WHOLE
// signature, and every value the reader binds tightens the next choice.
//
// Two map children:
//   • `-open`  — nothing bound. :func's picker reads Expected (item:a) → b;
//                Compatible is tiered (Exact fit / Extra inputs / Ignores the
//                input), the last tier folded, rows grouped under namespace
//                headers with bare names.
//   • `-text`  — :coll already holds "graph", "den". Unification binds
//                a := text, so the same picker reads Expected (item:text) → b
//                and its Exact fit is strictly smaller: only text-accepting
//                callees survive.
// Typing a filter unfolds every tier (a tour that says "type X and click it"
// must never find X hidden).
//
// Run from this directory:  node edit-fn-picker-tiers.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
const OPEN = 'test-picker-tiers-open';
const TEXT = 'test-picker-tiers-text';

async function openFuncPicker(page, fnName) {
  await page.goto(BASE + '/#' + fnName);
  await page.waitForSelector('.node-overlay[data-fn-name="' + fnName + '"]', {timeout: 30000});
  await page.waitForSelector('.placeholder-binder', {timeout: 30000});
  // :func is the callable slot — the one placeholder that is NOT the list anchor.
  await page.evaluate(() => Array.from(document.querySelectorAll('.placeholder-binder'))
    .find((b) => !b.classList.contains('is-seq-anchor')).click());
  await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
  // The tiers arrive with the server's candidate set.
  await page.waitForFunction(() => document.querySelectorAll('.fn-picker-tier-header').length > 0,
    null, {timeout: 30000, polling: 100});
  await page.waitForTimeout(400);
}

function readPicker(page) {
  return page.evaluate(() => {
    const p = document.querySelector('.fn-picker-popover');
    const tiers = Array.from(p.querySelectorAll('.fn-picker-tier-header')).map((h) => ({
      text: h.textContent.trim(),
      count: Number((h.textContent.match(/·\s*(\d+)/) || [])[1] || 0),
      folded: h.getAttribute('aria-expanded') === 'false',
      cls: h.className,
    }));
    const compat = p.querySelector('#fn-picker-list-compat');
    return {
      expected: p.querySelector('.fn-picker-expected')?.textContent.trim(),
      tiers,
      nsHeaders: compat.querySelectorAll('.fn-picker-ns-header').length,
      rows: compat.querySelectorAll('.fn-picker-row').length,
      // A row under a namespace header carries the bare name (no dots).
      dottedUnderNs: Array.from(compat.children).reduce((acc, el) => {
        if (el.classList.contains('fn-picker-ns-header')) acc.inNs = true;
        else if (el.classList.contains('fn-picker-tier-header')) acc.inNs = false;
        else if (acc.inNs && el.classList.contains('fn-picker-row')
                 && /\./.test(el.querySelector('.fn-picker-row-main')?.textContent || '')) acc.n++;
        return acc;
      }, {inNs: false, n: 0}).n,
    };
  });
}

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  console.log('fn-picker-tiers — whole-signature tiers + narrowing by bound values');
  try {
    await deleteFnByName(page, OPEN);
    await deleteFnByName(page, TEXT);
    const map = (await getEntities(page, 'map')).fns.find((f) => f.name === 'map');
    assert(map, ':map resolved');
    await api(page, 'POST', '/api/entities/fn', 'name=' + OPEN + '&parent-ids=' + map.id);
    await api(page, 'POST', '/api/entities/fn', 'name=' + TEXT + '&parent-ids=' + map.id);
    const textFn = (await getEntities(page, TEXT)).fns.find((f) => f.name === TEXT);
    assert(textFn, TEXT + ' created');
    for (const v of ['graph', 'den']) {
      const r = await api(page, 'POST', '/api/sequence/append/' + textFn.id, {value: v});
      assert(r && !r.error && r.status !== 400, 'appended "' + v + '" to :coll: ' + JSON.stringify(r).slice(0, 120));
    }

    // ---- open: a free type variable ----
    await openFuncPicker(page, OPEN);
    const open = await readPicker(page);
    assert(/item:a\)/.test(open.expected || ''),
      'nothing bound → Expected keeps the variable: ' + open.expected);
    const tierNames = open.tiers.map((t) => t.text.replace(/[▼▶]\s*/, '').replace(/\s*·.*$/, ''));
    assert(tierNames[0] === 'Exact fit', 'Exact fit leads: ' + JSON.stringify(tierNames));
    const ignores = open.tiers.find((t) => /Ignores the input/.test(t.text));
    assert(ignores && ignores.folded, 'the nullary constants are folded away: ' + JSON.stringify(open.tiers));
    assert(ignores && ignores.count > 100, 'a free (item:a) slot admits many constants positionally: ' + (ignores && ignores.count));
    assert(open.nsHeaders >= 2, 'rows are grouped under namespace headers: ' + open.nsHeaders);
    assert(open.dottedUnderNs === 0, 'a row under a namespace header carries the bare name');
    const openExact = open.tiers[0].count;

    // Typing unfolds every tier and keeps the target reachable.
    await page.fill('.fn-picker-search', 'str-upper');
    await page.waitForFunction(() => Array.from(document.querySelectorAll('.fn-picker-row-compat'))
      .some((r) => /(^|[./])str-upper$/.test(r.dataset.fnName || '')), null, {timeout: 15000, polling: 100});
    const typed = await readPicker(page);
    assert(typed.tiers.every((t) => !t.folded), 'a typed filter unfolds every tier: ' + JSON.stringify(typed.tiers));
    await page.keyboard.press('Escape');

    // ---- text: :coll bound → a := text ----
    await openFuncPicker(page, TEXT);
    const text = await readPicker(page);
    assert(/item:text\)/.test(text.expected || ''),
      'binding :coll to text narrows :func\'s Expected: ' + text.expected);
    const textExact = text.tiers[0].count;
    assert(textExact > 0 && textExact < openExact,
      'Exact fit shrinks once the item type is known: ' + openExact + ' → ' + textExact);
    await page.fill('.fn-picker-search', 'str-upper');
    await page.waitForFunction(() => Array.from(document.querySelectorAll('.fn-picker-row-compat'))
      .some((r) => /(^|[./])str-upper$/.test(r.dataset.fnName || '')), null, {timeout: 15000, polling: 100});
    const strUpperTier = await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('.fn-picker-row-compat'))
        .find((r) => /(^|[./])str-upper$/.test(r.dataset.fnName || ''));
      let el = row;
      while (el && !el.classList.contains('fn-picker-tier-header')) el = el.previousElementSibling;
      return el ? el.textContent.trim() : null;
    });
    assert(/Exact fit/.test(strUpperTier || ''), 'str-upper (one text arg) is an exact fit for (item:text): ' + strUpperTier);
    await page.keyboard.press('Escape');

    await deleteFnByName(page, OPEN);
    await deleteFnByName(page, TEXT);
    console.log('✓ fn-picker-tiers verified — tiers / folding / namespaces / narrowing');
    await browser.close();
    process.exit(0);
  } catch (e) {
    console.error('FAIL:', e.message);
    await browser.close();
    process.exit(1);
  }
})();
