// Problem ACTIONS in the Inspector (stage 2 of the lens epic):
//
// 1. Lint — a duplicate pair; selecting one shows the finding under the
//    Inspector's Lint heading with "Not an issue"; clicking it swaps the
//    section to its hidden entry with "Restore", the root
//    `lint-suppressions` fn exists, the lint lens chip drops to 0;
//    "Restore" brings the finding back.
// 2. Runs — a persisted failed run; the Runs tab lists it under
//    "Unresolved failures" with ✕; clicking ✕ dismisses it (the block
//    empties, the failed chip drops to 0).
// 3. Bindings — a type-breaking binding; the arg's row in the Bindings tab
//    carries the checker's message.
//
// Run from this directory:  node edit-inspector-problems.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, nodeApi,
       deleteFnByName, waitFor} = require('./edit-test-helpers');

const RUN = '-' + process.pid.toString(36) + '-' + Date.now().toString(36);
const NAME_A = 'test-insp-dup-a' + RUN;
const NAME_B = 'test-insp-dup-b' + RUN;
const NAME_F = 'test-insp-fail' + RUN;
const NAME_T = 'test-insp-type' + RUN;
const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

async function cleanup(page) {
  for (const n of [NAME_A, NAME_B, NAME_F, NAME_T, 'lint-suppressions']) {
    try { await deleteFnByName(page, n); } catch (_) { /* best-effort */ }
  }
}

// Select by hash + a real reload (a hash-only goto keeps the booted
// document and its primed caches), then open the Inspector tab under
// test — Overview is the default, the Lint section and the arg rows
// live on Bindings, the failures on Runs.
async function selectFn(page, name, tab) {
  await page.goto(BASE + '/#' + name);
  await page.reload();
  await page.waitForFunction(
    () => graphReady() && !!document.querySelector('button.more-actions-trigger') && !graph.animating,
    null, {timeout: 30000, polling: 100});
  await page.waitForSelector('[data-insp-tab="' + tab + '"]', {timeout: 15000});
  await page.evaluate((t) => document.querySelector('[data-insp-tab="' + t + '"]').click(), tab);
}

const chipCount = (page, kind) => page.evaluate((k) => {
  const c = document.querySelector('#kind-filters .kind-toggle[data-kind="' + k + '"] .kind-count');
  return c ? parseInt(c.textContent || '0', 10) : NaN;
}, kind);

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('inspector-problems — Lint Not-an-issue/Restore, Runs ✕ dismiss, Bindings diagnostics');
  try {
    await cleanup(page);

    // --- fixtures ----------------------------------------------------------
    const ents = await getEntities(page, 'assoc');
    const assocFn = ents.fns.find(f => f.name === 'assoc');
    const slot = (nm) => synthArgs(ents).find(
      a => a['fn-id'] === assocFn.id && a.name === nm && !a['source-id']);
    const constFn = (await getEntities(page, 'const')).fns.find(f => f.name === 'const');
    assert(assocFn && slot('map') && constFn, 'assoc / const baseline resolved');
    const mk = async (name) => {
      await api(page, 'POST', '/api/entities/fn', 'name=' + name + '&parent-ids=' + assocFn.id);
      const fn = (await getEntities(page, name)).fns.find(f => f.name === name);
      assert(fn, name + ' created');
      await api(page, 'POST', '/api/entities/binding', 'fn-id=' + fn.id + '&slot-id=' + slot('map')['slot-id'] + '&value=' + encodeURIComponent('{"class":"x"}'));
      await api(page, 'POST', '/api/entities/binding', 'fn-id=' + fn.id + '&slot-id=' + slot('key')['slot-id'] + '&value=' + encodeURIComponent('"title"'));
      await api(page, 'POST', '/api/entities/binding', 'fn-id=' + fn.id + '&slot-id=' + slot('value')['slot-id'] + '&ref-fn-id=' + constFn.id);
      return fn;
    };
    const fnA = await mk(NAME_A);
    await mk(NAME_B);

    const pj = (await getEntities(page, 'parse-json')).fns.find(f => f.name === 'parse-json');
    await api(page, 'POST', '/api/entities/fn', 'name=' + NAME_F + '&parent-ids=' + pj.id);
    const fnF = (await getEntities(page, NAME_F)).fns.find(f => f.name === NAME_F);
    const run = await nodeApi('POST', '/api/execute',
      {'fn-id': fnF.id, args: {string: 'not json at all'}, 'persist?': true});
    assert((await run.json()).status === 'failed', 'the probe run failed');

    const hs = await getEntities(page, 'http-server');
    const httpServer = hs.fns.find(f => f.name === 'http-server');
    const portArg = synthArgs(hs).find(a => a['fn-id'] === httpServer.id && a.name === 'port' && !a['source-id']);
    await api(page, 'POST', '/api/entities/fn', 'name=' + NAME_T + '&parent-ids=' + httpServer.id);
    const fnT = (await getEntities(page, NAME_T)).fns.find(f => f.name === NAME_T);
    await api(page, 'POST', '/api/entities/binding',
      'fn-id=' + fnT.id + '&slot-id=' + portArg['slot-id'] + '&value=' + encodeURIComponent('"oops"'));

    // --- 1. Lint section -----------------------------------------------------
    await selectFn(page, NAME_A, 'bindings');
    const lintSection = () => page.evaluate(() => {
      const s = document.querySelector('#gd-insp-detail .gd-insp-lint');
      return s ? {hidden: !!s.hidden, text: s.textContent, hasSuppress: !!s.querySelector('button.lint-suppress'),
                  hasRestore: !!s.querySelector('button.lint-restore')} : null;
    });
    const lintSeen = await waitFor(async () => {
      const s = await lintSection();
      return s && !s.hidden && /duplicate-definition/.test(s.text) && s.hasSuppress;
    }, 20000);
    assert(lintSeen, 'Inspector Lint section shows the finding with Not an issue: ' + JSON.stringify(await lintSection()));
    await page.evaluate(() => document.querySelector('#gd-insp-detail .gd-insp-lint button.lint-suppress').click());
    const suppressed = await waitFor(async () => {
      const s = await lintSection();
      return s && !s.hidden && s.hasRestore && !s.hasSuppress && /Marked not an issue/.test(s.text);
    }, 20000);
    assert(suppressed, 'Not an issue swaps the section to its hidden entry with Restore');
    const store = await waitFor(async () => (await getEntities(page, 'lint-suppressions')).fns.some(f => f.name === 'lint-suppressions'), 10000);
    assert(store, 'the suppression lives in the graph (root `lint-suppressions`)');
    const chipZero = await waitFor(async () => (await chipCount(page, 'lint')) === 0 || Number.isNaN(await chipCount(page, 'lint')), 15000);
    assert(chipZero, 'lint chip count drops after the suppression: ' + await chipCount(page, 'lint'));
    await page.evaluate(() => document.querySelector('#gd-insp-detail .gd-insp-lint button.lint-restore').click());
    const restored = await waitFor(async () => {
      const s = await lintSection();
      return s && !s.hidden && s.hasSuppress;
    }, 20000);
    assert(restored, 'Restore brings the finding back into the section');

    // --- 2. Runs tab ----------------------------------------------------------
    await selectFn(page, NAME_F, 'stats');
    const failures = () => page.evaluate(() => {
      const b = document.querySelector('#gd-insp-runs .execute-history-failures');
      return b ? {hidden: !!b.hidden, text: b.textContent, rows: b.querySelectorAll('.execute-history-failure').length} : null;
    });
    const failSeen = await waitFor(async () => {
      const f = await failures();
      return f && !f.hidden && f.rows === 1 && /Malformed JSON/i.test(f.text);
    }, 20000);
    assert(failSeen, 'Runs tab lists the unresolved failure with its message: ' + JSON.stringify(await failures()));
    await page.evaluate(() => document.querySelector('#gd-insp-runs .execute-history-failure button.error-log-ack').click());
    const dismissed = await waitFor(async () => {
      const f = await failures();
      return f && (f.hidden || f.rows === 0);
    }, 20000);
    assert(dismissed, '✕ dismisses the failure — the block empties');
    const failedChip = await waitFor(async () => (await chipCount(page, 'failed')) === 0 || Number.isNaN(await chipCount(page, 'failed')), 15000);
    assert(failedChip, 'failed chip count drops after the dismiss: ' + await chipCount(page, 'failed'));

    // --- 3. Bindings tab diagnostics ---------------------------------------------
    await selectFn(page, NAME_T, 'bindings');
    const diag = await waitFor(() => page.evaluate(() => {
      const rows = [...document.querySelectorAll('#gd-insp-detail .gd-bind-row')];
      const row = rows.find(r => r.querySelector('.gd-bind-name')?.textContent === 'port');
      const d = row?.querySelector('.gd-bind-diag');
      return !!d && /oops|Type-check failed|expects/.test(d.textContent);
    }), 20000);
    assert(diag, 'the port arg row carries the recorded type diagnostic');

    console.log('PASS');
    process.exitCode = 0;
  } catch (e) {
    console.error('FAIL:', e.message);
    try {
      const dump = await page.evaluate(() => ({
        detail: document.querySelector('#gd-insp-detail')?.innerHTML.slice(0, 600),
        runs: document.querySelector('#gd-insp-runs')?.innerHTML.slice(0, 400),
      }));
      console.error('  state:', JSON.stringify(dump));
    } catch (_) { /* page may be gone */ }
    process.exitCode = 1;
  } finally {
    await cleanup(page);
    await browser.close();
  }
})();
