// The Explorer's PROBLEM lenses (failed / type errors / lint):
//
// 1. Two duplicating fn-defs (same parent, same three bindings) written
//    through the API make GET /api/lint list one warning naming both,
//    and the `lint` lens chip counts it.
// 2. In the editor: the lint chip shows the count, its namespace row
//    carries a ⚐ chip, both rows carry a ⚐1 marker, and toggling the lens
//    hides every row without a finding while keeping these two.
// 3. A persisted failed run (parse-json on garbage) makes GET /api/failures
//    count it against the fn; the `failed` chip counts it, the row carries
//    ✕1 and the lens keeps it.
//
// Run from this directory:  node edit-problem-lenses.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, nodeApi,
       deleteFnByName, waitFor} = require('./edit-test-helpers');

const RUN = '-' + process.pid.toString(36) + '-' + Date.now().toString(36);
const NAME_A = 'test-lens-dup-a' + RUN;
const NAME_B = 'test-lens-dup-b' + RUN;
const NAME_F = 'test-lens-fail' + RUN;

async function jsonApi(path) {
  const r = await nodeApi('GET', path);
  return r.json();
}

async function cleanup(page) {
  for (const n of [NAME_A, NAME_B, NAME_F, 'lint-suppressions']) {
    try { await deleteFnByName(page, n); } catch (_) { /* best-effort */ }
  }
}

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('problem-lenses — lint pair + failed run → /api/lint + /api/failures → chips, ns chips, row markers, lens focus');
  try {
    await cleanup(page);

    // --- lint pair -------------------------------------------------------
    const ents = await getEntities(page, 'assoc');
    const assocFn = ents.fns.find(f => f.name === 'assoc');
    const slot = (nm) => synthArgs(ents).find(
      a => a['fn-id'] === assocFn.id && a.name === nm && !a['source-id']);
    const [mapSlot, keySlot, valueSlot] = [slot('map'), slot('key'), slot('value')];
    assert(assocFn && mapSlot && keySlot && valueSlot, 'assoc baseline resolved');
    const constFn = (await getEntities(page, 'const')).fns.find(f => f.name === 'const');
    const mk = async (name) => {
      await api(page, 'POST', '/api/entities/fn', 'name=' + name + '&parent-ids=' + assocFn.id);
      const fn = (await getEntities(page, name)).fns.find(f => f.name === name);
      assert(fn, name + ' created');
      await api(page, 'POST', '/api/entities/binding', 'fn-id=' + fn.id + '&slot-id=' + mapSlot['slot-id'] + '&value=' + encodeURIComponent('{"class":"x"}'));
      await api(page, 'POST', '/api/entities/binding', 'fn-id=' + fn.id + '&slot-id=' + keySlot['slot-id'] + '&value=' + encodeURIComponent('"title"'));
      await api(page, 'POST', '/api/entities/binding', 'fn-id=' + fn.id + '&slot-id=' + valueSlot['slot-id'] + '&ref-fn-id=' + constFn.id);
      return fn;
    };
    const fnA = await mk(NAME_A);
    const fnB = await mk(NAME_B);
    const lintListed = await waitFor(async () => {
      const rows = await jsonApi('/api/lint');
      return rows.some(r => (r['fn-ids'] || []).includes(fnA.id) && (r['fn-ids'] || []).includes(fnB.id));
    }, 15000);
    assert(lintListed, '/api/lint names the pair in one warning');

    // --- failed run --------------------------------------------------------
    const pj = (await getEntities(page, 'parse-json')).fns.find(f => f.name === 'parse-json');
    assert(pj, 'parse-json baseline resolved');
    await api(page, 'POST', '/api/entities/fn', 'name=' + NAME_F + '&parent-ids=' + pj.id);
    const fnF = (await getEntities(page, NAME_F)).fns.find(f => f.name === NAME_F);
    assert(fnF, NAME_F + ' created');
    // An OBJECT body goes out as JSON (a string would be form-encoded).
    const run = await nodeApi('POST', '/api/execute',
      {'fn-id': fnF.id, args: {string: 'not json at all'}, 'persist?': true});
    const runBody = await run.json().catch(() => ({}));
    assert(runBody.status === 'failed', 'the run failed as intended: ' + JSON.stringify(runBody).slice(0, 120));
    const failListed = await waitFor(async () => {
      const rows = await jsonApi('/api/failures');
      return rows.some(r => r['fn-id'] === fnF.id && r.count >= 1);
    }, 15000);
    assert(failListed, '/api/failures counts the failed run against the fn');

    // --- editor ------------------------------------------------------------
    // The context's page already booted (and primed its caches) before
    // the probe fns existed; a hash-only goto keeps that document, so
    // reload for a real boot — what a user opening the editor gets.
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/#' + NAME_A);
    await page.reload();
    await page.waitForFunction(
      () => graphReady() && !!document.querySelector('button.more-actions-trigger') && !graph.animating,
      null, {timeout: 30000, polling: 100});
    const chipCount = (kind) => page.evaluate((k) => {
      const c = document.querySelector('#kind-filters .kind-toggle[data-kind="' + k + '"] .kind-count');
      return c ? parseInt(c.textContent || '0', 10) : NaN;
    }, kind);
    const chipsPrimed = await waitFor(async () => (await chipCount('lint')) >= 1 && (await chipCount('failed')) >= 1, 20000);
    assert(chipsPrimed, 'lint + failed chips carry counts: lint=' + await chipCount('lint') + ' failed=' + await chipCount('failed'));

    // The probe fns are namespace-less → the "(primitives)" bucket, whose
    // rows lazy-load on expand. Open it.
    await page.evaluate(() => {
      const label = [...document.querySelectorAll('#entity-list .ns-label')]
        .find(el => el.textContent === '(primitives)');
      const header = label?.parentElement;
      if (header && header.getAttribute('aria-expanded') !== 'true') header.click();
    });
    await waitFor(() => page.evaluate((n) =>
      [...document.querySelectorAll('#entity-list .entity-item[data-fn-id]')]
        .some(el => el.querySelector('.name')?.textContent === n), NAME_A), 20000);

    // The (root) bucket's rows: markers on the pair and on the failing fn.
    const marker = (name, cls) => page.evaluate(([n, c]) => {
      const row = [...document.querySelectorAll('#entity-list .entity-item[data-fn-id]')]
        .find(el => el.querySelector('.name')?.textContent === n);
      const m = row ? row.querySelector('.' + c) : null;
      return m ? m.textContent : null;
    }, [name, cls]);
    const markersSeen = await waitFor(async () =>
      (await marker(NAME_A, 'kind-marker-lint')) === '⚐1'
      && (await marker(NAME_B, 'kind-marker-lint')) === '⚐1'
      && (await marker(NAME_F, 'kind-marker-failed')) === '✕1', 20000);
    assert(markersSeen, 'row markers: ⚐1 on both duplicates, ✕1 on the failing fn');

    // Namespace chips on the root bucket header.
    const nsChips = await page.evaluate(() => {
      const chips = [...document.querySelectorAll('#entity-list .ns-problem-chip')].map(c => c.textContent);
      return chips;
    });
    assert(nsChips.some(t => /^⚐ \d+/.test(t)) && nsChips.some(t => /^✕ \d+/.test(t)),
      'namespace rows carry ⚐ / ✕ chips: ' + JSON.stringify(nsChips));

    // The lint lens keeps the pair and hides an unrelated plain fn.
    await page.evaluate(() => toggleKind('lint'));
    const focused = await waitFor(() => page.evaluate(([a, b, f]) => {
      const rows = [...document.querySelectorAll('#entity-list .entity-item[data-fn-id]')];
      const shown = (n) => rows.find(el => el.querySelector('.name')?.textContent === n && !el.hidden);
      // Namespace groups hide non-matching rows in place; the (primitives)
      // bucket re-renders only the visible ones — "gone" is either.
      const gone = (n) => !shown(n);
      return !!shown(a) && !!shown(b) && gone(f);
    }, [NAME_A, NAME_B, NAME_F]), 10000);
    assert(focused, 'lint lens shows the pair and hides the (non-lint) failing fn');
    await page.evaluate(() => toggleKind('all'));

    console.log('PASS');
    process.exitCode = 0;
  } catch (e) {
    console.error('FAIL:', e.message);
    try {
      const dump = await page.evaluate(async () => ({
        lintTotal: typeof getLintTotal === 'function' ? getLintTotal() : 'nofn',
        failTotal: typeof getFailureTotal === 'function' ? getFailureTotal() : 'nofn',
        apiLint: await (await fetch('/api/lint')).text().then(t => t.slice(0, 200)),
        apiFailures: await (await fetch('/api/failures')).text().then(t => t.slice(0, 200)),
        chips: [...document.querySelectorAll('#kind-filters .kind-toggle')].map(b => b.dataset.kind + '=' + (b.querySelector('.kind-count')?.textContent ?? '-')),
        rows: [...document.querySelectorAll('#entity-list .entity-item[data-fn-id]')]
          .filter(el => /test-lens/.test(el.textContent)).map(el => el.outerHTML.slice(0, 300)),
        nsHeads: [...document.querySelectorAll('#entity-list .ns-header, #entity-list .ns-label')].slice(0, 6).map(el => el.textContent.slice(0, 60)),
      }));
      console.error('  state:', JSON.stringify(dump));
    } catch (_) { /* page may be gone */ }
    process.exitCode = 1;
  } finally {
    await cleanup(page);
    await browser.close();
  }
})();
