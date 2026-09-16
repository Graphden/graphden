// Component palette on a hiccup :children chain — the append chooser's
// "Append fn-ref" picker is typed by the chain's ELEMENT type, so the
// component library is its Compatible section; a named instance is made
// by appending the component and extending it in place (which retired the
// separate "New from template…" button, 2026-09-16).
//
// Coverage:
//   • Seed an owner fn with parent :stack (a hiccup :children chain).
//   • The chooser offers Append literal / Append fn-ref — and no template button.
//   • /api/types/candidates with the elem type (hiccup-node) lists the
//     component library; the picker's Compatible section lists :button.
//   • Pick :button → a ref item; ⋯ → Extend on its card → the item now
//     references the child (parent-ids = [button]); the owner still executes.
//
// Run from this directory:  node edit-append-fnref-palette.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');
const {extendInPlace} = require('./tutorial-tour-helpers');

const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const OWNER_FN = 'pal-owner' + RUN_ID;
const INST_FN = 'pal-btn' + RUN_ID;

async function cleanup(page) {
  // Owner first (its item references the instance), then the instance.
  try { await deleteFnByName(page, OWNER_FN); } catch (_) {}
  try { await deleteFnByName(page, INST_FN); } catch (_) {}
}

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('edit-append-fnref-palette — typed picker → component appended → extended in place');
  try {
    await cleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    const stackFn = (await getEntities(page, 'stack')).fns.find((f) => f.name === 'stack');
    assert(stackFn, ':stack exists');
    await api(page, 'POST', '/api/entities/fn', 'name=' + OWNER_FN + '&parent-ids=' + stackFn.id);
    const owner = (await getEntities(page, OWNER_FN)).fns.find((f) => f.name === OWNER_FN);
    assert(owner, 'owner fn created');

    const candidates = await page.evaluate(async () => {
      const r = await authFetch(API.api_types_candidates, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({expected: 'hiccup-node'})});
      return ((await r.json()).candidates || []).map((c) => c.name);
    });
    for (const comp of ['button', 'card', 'heading', 'paragraph']) {
      assert(candidates.includes(comp), 'palette (hiccup-node candidates) offers :' + comp);
    }

    await page.goto(BASE + '/#' + OWNER_FN);
    await page.waitForSelector('.placeholder-binder[data-fn-name="' + OWNER_FN + '"]', {timeout: 60000});
    await page.click('.placeholder-binder[data-fn-name="' + OWNER_FN + '"]');
    await page.waitForSelector('.free-arg-bind-chooser', {timeout: 15000});
    const labels = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button')).map((b) => b.textContent.trim()));
    assert(!labels.some((l) => l.includes('New from template')),
      'the chooser no longer offers a template button: ' + JSON.stringify(labels));
    assert(labels.includes('Append fn-ref'), 'the chooser offers Append fn-ref: ' + JSON.stringify(labels));
    await page.evaluate(() => Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
      .find((b) => b.textContent.trim() === 'Append fn-ref').click());
    await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
    const expected = await page.evaluate(() => document.querySelector('.fn-picker-expected')?.textContent || '');
    // hiccup-node is a union alias — the header prints its structural form
    // (bool|float|int|null|text|[any]), not the alias name; what matters is
    // that the picker IS typed (a header shows) and :button lands in the
    // Compatible section below.
    assert(/^Expected: .+/.test(expected), 'the picker is typed by the element type: ' + expected);
    await page.fill('.fn-picker-popover input', 'button');
    const rowSel = '.fn-picker-popover .fn-picker-row.fn-picker-row-compat[data-fn-name$=".button"]';
    await page.waitForSelector(rowSel, {timeout: 30000});
    await page.click(rowSel);
    await page.waitForFunction(() => !document.querySelector('.fn-picker-popover'), null, {timeout: 30000, polling: 150});
    console.log('  :button appended from the Compatible section');

    await extendInPlace(page, 'button', INST_FN);
    const inst = (await getEntities(page, INST_FN)).fns.find((f) => f.name === INST_FN);
    const buttonFn = (await getEntities(page, 'button')).fns.find((f) => f.name === 'button');
    assert(inst && (inst['parent-ids'] || []).includes(buttonFn.id), 'the instance is parented to :button');
    const after = await getEntities(page, OWNER_FN);
    const bindIds = after.bindings.filter((b) => b['fn-id'] === owner.id).map((b) => b.id);
    const items = (after['list-items'] || []).filter((it) => bindIds.includes(it['binding-id']));
    assert(items.length === 1 && items[0]['ref-fn-id'] === inst.id,
      'the chain item now references the instance: ' + JSON.stringify(items));

    const exec = await page.evaluate(async (ownerName) => {
      const r = await authFetch(API.api_execute, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({'fn-name': ownerName, args: {label: 'Hi'}})});
      return await r.json();
    }, OWNER_FN);
    assert(exec.status === 'succeeded', 'owner executes: ' + JSON.stringify(exec).slice(0, 120));
    assert(JSON.stringify(exec.result).includes('"button"'),
      'rendered hiccup contains the button instance: ' + JSON.stringify(exec.result).slice(0, 120));

    await cleanup(page);
    console.log('PASS');
    await browser.close();
    process.exit(0);
  } catch (e) {
    console.error('FAIL:', e.message);
    try { await cleanup(page); } catch (_) {}
    await browser.close();
    process.exit(1);
  }
})();
