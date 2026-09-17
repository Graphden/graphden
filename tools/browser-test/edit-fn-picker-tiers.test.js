// fn-picker — the Explorer-shaped list: fit tiers as order + chips, folded
// namespace groups in browse mode, "Exact match" on top under a typed
// filter, and narrowing by bound values (the Expected type tightens once
// :coll is bound, and the compatible set shrinks with it).
//
// Fixtures: two children of :map — OPEN leaves :coll free, so :func expects
// (item:a) → b; TEXT binds :coll to two strings, so :func expects
// (item:text) → b.
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
  // The server's candidate set is what makes the verdicts and counts final.
  await page.waitForSelector('.fn-picker-list[data-loaded="true"]', {timeout: 30000});
  await page.waitForTimeout(300);
}

function readPicker(page) {
  return page.evaluate(() => {
    const p = document.querySelector('.fn-picker-popover');
    const status = p.querySelector('.fn-picker-status')?.textContent || '';
    const m = status.match(/(\d+) of (\d+)/);
    return {
      expected: p.querySelector('.fn-picker-expected')?.textContent.trim(),
      shown: m ? Number(m[1]) : null,
      total: m ? Number(m[2]) : null,
      groups: Array.from(p.querySelectorAll('.fn-picker-ns-toggle')).map((h) => ({
        text: h.textContent.trim(), open: h.getAttribute('aria-expanded') === 'true'})),
      rows: Array.from(p.querySelectorAll('.fn-picker-row')).map((r) => ({
        name: r.dataset.fnName,
        compat: r.classList.contains('fn-picker-row-compat'),
        chip: r.querySelector('.fn-picker-row-fit')?.textContent || null,
        inExact: !!r.closest('.fn-picker-exact'),
        main: r.querySelector('.fn-picker-row-main')?.textContent || '',
      })),
      otherToggle: p.querySelector('.fn-picker-other-toggle')?.textContent || null,
    };
  });
}

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  console.log('fn-picker-tiers — Explorer-shaped list: tiers as chips, folds, exact match, narrowing');
  try {
    for (const n of [OPEN, TEXT]) { try { await deleteFnByName(page, n); } catch (_) {} }
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

    // ---- OPEN: a free type variable ----
    await openFuncPicker(page, OPEN);
    const open = await readPicker(page);
    assert(/item:a\)/.test(open.expected || ''), 'nothing bound → Expected keeps the variable: ' + open.expected);
    assert(open.total > 100, 'a free (item:a) slot admits many fns: ' + open.total);
    assert(open.groups.length >= 3, 'browse mode groups the fns by namespace: ' + open.groups.length);
    assert(open.groups.some((g) => !g.open), 'a long list starts with most groups folded');
    assert(open.otherToggle && /Show \d+ fns of other types/.test(open.otherToggle),
      'fns of other types sit behind ONE toggle: ' + open.otherToggle);
    // Unfold one group: bare names under the header.
    const folded = await page.evaluate(() => {
      const h = Array.from(document.querySelectorAll('.fn-picker-ns-toggle')).find((x) => x.getAttribute('aria-expanded') === 'false');
      const name = h.querySelector('.fn-picker-ns-name').textContent; h.click(); return name;
    });
    await page.waitForFunction((n) => Array.from(document.querySelectorAll('.fn-picker-ns-toggle'))
      .some((h) => h.querySelector('.fn-picker-ns-name').textContent === n && h.getAttribute('aria-expanded') === 'true'),
      folded, {timeout: 10000, polling: 100});
    const unfolded = await readPicker(page);
    assert(unfolded.rows.length > open.rows.length, 'clicking a folded namespace shows its rows: ' + unfolded.rows.length);
    assert(unfolded.rows.every((r) => !r.inExact && !/\./.test(r.main)),
      'a row under a namespace header carries the bare name');
    // Tiers are chips, not folds: an ignores-the-input constant wears one.
    await page.fill('.fn-picker-search', 'css');
    await page.waitForFunction(() => document.querySelectorAll('.fn-picker-row').length > 0, null, {timeout: 15000, polling: 100});
    const css = await readPicker(page);
    assert(css.rows.some((r) => r.compat && r.chip === 'Ignores the input'),
      'a nullary constant in a 1-arg slot carries the "Ignores the input" chip: ' + JSON.stringify(css.rows.slice(0, 4)));
    // The exact-name hit is the first row, whatever its namespace.
    await page.fill('.fn-picker-search', 'str-upper');
    await page.waitForFunction(() => Array.from(document.querySelectorAll('.fn-picker-row-compat'))
      .some((r) => /(^|[./])str-upper$/.test(r.dataset.fnName || '')), null, {timeout: 15000, polling: 100});
    const typed = await readPicker(page);
    assert(typed.rows[0] && /(^|\.)str-upper$/.test(typed.rows[0].name) && typed.rows[0].inExact,
      'str-upper is the Exact match on top: ' + JSON.stringify(typed.rows[0]));
    assert(typed.rows[0].compat && typed.rows[0].chip === null,
      'an exact fit wears ✓ and no chip: ' + JSON.stringify(typed.rows[0]));
    assert(typed.groups.length === 0, 'a typed filter has no folds');
    await page.keyboard.press('Escape');

    // ---- TEXT: :coll bound → a := text ----
    await openFuncPicker(page, TEXT);
    const text = await readPicker(page);
    assert(/item:text\)/.test(text.expected || ''), 'binding :coll to text narrows :func\'s Expected: ' + text.expected);
    assert(text.total > 0 && text.total < open.total,
      'the compatible set shrinks once the item type is known: ' + open.total + ' → ' + text.total);
    await page.fill('.fn-picker-search', 'str-upper');
    await page.waitForFunction(() => Array.from(document.querySelectorAll('.fn-picker-row-compat'))
      .some((r) => /(^|[./])str-upper$/.test(r.dataset.fnName || '')), null, {timeout: 15000, polling: 100});
    const su = (await readPicker(page)).rows.find((r) => /(^|\.)str-upper$/.test(r.name));
    assert(su && su.compat && su.chip === null && su.inExact,
      'str-upper (one text arg) is an exact fit for (item:text): ' + JSON.stringify(su));
    await page.keyboard.press('Escape');

    await deleteFnByName(page, OPEN);
    await deleteFnByName(page, TEXT);
    console.log('✓ fn-picker-tiers verified — chips / folds / exact match / narrowing');
    await browser.close();
    process.exit(0);
  } catch (e) {
    console.error('FAIL:', e.message);
    try { await deleteFnByName(page, OPEN); await deleteFnByName(page, TEXT); } catch (_) {}
    await browser.close();
    process.exit(1);
  }
})();
