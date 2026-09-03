// Lint drawer tab (docs/GRAPH_LINT.md):
//
// 1. Two fn-defs written through the API that duplicate each other
//    (same parent, same three bindings) make GET /partials/lint list a
//    duplicate-definition row naming both.
// 2. The editor shows it: the Lint tab in the diagnostics drawer renders
//    the server partial with the row; the tab badge counts it.
// 3. "Not an issue" hides the row, the panel's hidden list counts one,
//    and the suppression is IN THE GRAPH — a root `lint-suppressions` fn
//    now exists; the partial no longer lists the pair.
// 4. "Restore" brings the row back.
//
// Run from this directory:  node edit-lint-panel.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, nodeApi,
       deleteFnByName, waitFor} = require('./edit-test-helpers');

const RUN = '-' + process.pid.toString(36) + '-' + Date.now().toString(36);
const NAME_A = 'test-lint-dup-a' + RUN;
const NAME_B = 'test-lint-dup-b' + RUN;
const SUPPRESSIONS = 'lint-suppressions';

async function lintPartial() {
  const r = await nodeApi('GET', '/partials/lint');
  return r.text();
}

async function cleanup(page) {
  for (const n of [NAME_A, NAME_B, SUPPRESSIONS]) {
    try { await deleteFnByName(page, n); } catch (_) { /* best-effort */ }
  }
}

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('lint-panel — duplicate pair → panel row + badge → not-an-issue (graph-stored) → restore');
  try {
    await cleanup(page);

    // Baseline: :assoc's map / key / value slots, :const as the value ref.
    const ents = await getEntities(page, 'assoc');
    const assocFn = ents.fns.find(f => f.name === 'assoc');
    assert(assocFn, 'assoc baseline resolved');
    const slot = (nm) => synthArgs(ents).find(
      a => a['fn-id'] === assocFn.id && a.name === nm && !a['source-id']);
    const [mapSlot, keySlot, valueSlot] = [slot('map'), slot('key'), slot('value')];
    assert(mapSlot && keySlot && valueSlot, 'assoc slots resolved');
    const constFn = (await getEntities(page, 'const')).fns.find(f => f.name === 'const');
    assert(constFn, 'const baseline resolved');

    // Two identical children: {:map {:class "x"} :key "title" :value :const}.
    const mk = async (name) => {
      await api(page, 'POST', '/api/entities/fn', 'name=' + name + '&parent-ids=' + assocFn.id);
      const fn = (await getEntities(page, name)).fns.find(f => f.name === name);
      assert(fn, name + ' created');
      await api(page, 'POST', '/api/entities/binding',
                'fn-id=' + fn.id + '&slot-id=' + mapSlot['slot-id']
                + '&value=' + encodeURIComponent('{"class":"x"}'));
      await api(page, 'POST', '/api/entities/binding',
                'fn-id=' + fn.id + '&slot-id=' + keySlot['slot-id']
                + '&value=' + encodeURIComponent('"title"'));
      await api(page, 'POST', '/api/entities/binding',
                'fn-id=' + fn.id + '&slot-id=' + valueSlot['slot-id']
                + '&ref-fn-id=' + constFn.id);
      return fn;
    };
    await mk(NAME_A);
    await mk(NAME_B);

    // === Server partial lists the pair ===
    const listed = await waitFor(async () => {
      const body = await lintPartial();
      return body.includes('#' + NAME_A) && body.includes('#' + NAME_B);
    }, 15000);
    assert(listed, '/partials/lint lists both duplicates');

    // === Editor: the Lint tab in the diagnostics drawer ===
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/#' + NAME_A);
    await page.waitForFunction(
      () => graphReady() && !!document.querySelector('button.more-actions-trigger') && !graph.animating,
      null, {timeout: 30000, polling: 100});
    await page.waitForSelector('#gd-diag-nav button[data-section="lint"]', {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('#gd-diag-nav button[data-section="lint"]').click();
    });
    const rowSel = (name) => page.evaluate((n) => {
      const rows = [...document.querySelectorAll('.sidebar-lint .lint-list > .lint-row')];
      return rows.find(r => [...r.querySelectorAll('.lint-fn')].some(a => a.textContent.includes(n))) || null;
    }, name);
    // The page's ctx learns about writes made through another ctx via the
    // async delta path, so re-fetch the panel on each poll (the same
    // refresh opening the drawer does) rather than wait on a stale swap.
    const refetch = () => page.evaluate(() => {
      if (typeof window.reloadDiagnosticsSections === 'function') window.reloadDiagnosticsSections();
    });
    const rowSeen = await waitFor(async () => {
      if (await rowSel(NAME_A)) return true;
      await refetch();
      await new Promise(r => setTimeout(r, 900));
      return !!(await rowSel(NAME_A));
    }, 30000);
    assert(rowSeen, 'Lint tab lists the duplicate pair');
    const badge = await page.evaluate(() => {
      const b = document.querySelector('#gd-diag-nav button[data-section="lint"] .gd-diag-badge');
      return b && !b.hidden ? b.textContent : null;
    });
    assert(badge && parseInt(badge, 10) >= 1, 'Lint tab badge counts the warning: ' + badge);

    // === Not an issue → row gone, hidden list counts one, graph holds it ===
    await page.evaluate((n) => {
      const rows = [...document.querySelectorAll('.sidebar-lint .lint-list > .lint-row')];
      const row = rows.find(r => [...r.querySelectorAll('.lint-fn')].some(a => a.textContent.includes(n)));
      row.querySelector('button.lint-suppress').click();
    }, NAME_A);
    const hidden = await waitFor(async () => {
      const gone = !(await rowSel(NAME_A));
      const summary = await page.evaluate(() => {
        const s = document.querySelector('.sidebar-lint .lint-hidden-summary');
        return s ? s.textContent : '';
      });
      return gone && /1 marked not an issue/.test(summary);
    }, 20000);
    assert(hidden, 'row hidden and counted in the hidden list');
    // The hidden list still links the names (that is what Restore is for),
    // so "no longer listed" means: no open finding, one hidden entry.
    const unlisted = await waitFor(async () => {
      const body = await lintPartial();
      return body.includes('lint-empty') && /1 marked not an issue/.test(body)
        && !/lint-list[\s\S]*#/.test(body);
    }, 10000);
    assert(unlisted, '/partials/lint shows the pair only in the hidden list');
    const store = (await getEntities(page, SUPPRESSIONS)).fns.find(f => f.name === SUPPRESSIONS);
    assert(store, 'the suppression lives in the graph as the root `' + SUPPRESSIONS + '` fn');

    // === Restore → the row is back ===
    await page.evaluate(() => {
      const d = document.querySelector('.sidebar-lint details.lint-hidden');
      d.open = true;
      d.querySelector('button.lint-restore').click();
    });
    const restored = await waitFor(async () => !!(await rowSel(NAME_A)), 20000);
    assert(restored, 'Restore shows the finding again');

    console.log('PASS');
    process.exitCode = 0;
  } catch (e) {
    console.error('FAIL:', e.message);
    try {
      const dump = await page.evaluate(() => {
        const s = document.querySelector('.sidebar-lint');
        return s ? s.innerHTML.slice(0, 1200) : '(no .sidebar-lint on the page)';
      });
      console.error('  lint section:', dump);
      const inPage = await page.evaluate(async () => (await (await fetch('/partials/lint')).text()).slice(0, 300));
      console.error('  in-page fetch now:', inPage);
      console.error('  nodeApi fetch now:', (await lintPartial()).slice(0, 300));
    } catch (_) { /* page may be gone */ }
    process.exitCode = 1;
  } finally {
    await cleanup(page);
    await browser.close();
  }
})();
