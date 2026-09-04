// Tests-suite e2e (Roadmap Block 3.1) — the `tests` namespace
// convention end to end:
//
//   Setup:   create `e2eprobe` + `e2eprobe.tests` namespaces via the
//            entity API, then a probe test fn parented on
//            `:assert-eq` with `:actual` / `:expected` both bound to
//            literal 4 (zero free args → runnable).
//   Phase A: POST /api/tests/run → the probe runs and passes;
//            GET /api/tests/status reports `succeeded`.
//   Phase B: editor UI — the ✓ tests lens chip exists; under the
//            lens the probe row renders with a green status dot.
//   Phase C: the lens's [Run all] action completes its POST → re-prime
//            cycle; the Inspector's Test section shows the probe's
//            status and [Run this test] re-renders it from a run.
//   Phase D: auto-run — a metadata edit on the probe rolls its
//            version (status → null), then the write-triggered
//            auto-run re-runs it in the background (status back to
//            `succeeded` without any manual run).
//
// Cleanup: probe fn + both namespaces are deleted on PASS/FAIL.
//
// Run from this directory:  node edit-tests-suite.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities} = require('./edit-test-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
const ROOT_NS = 'e2eprobe';
const PROBE_FN = 'e2e-sum-is-four';


async function findNs(page, name, parentId) {
  const ents = await getEntities(page);
  return (ents.namespaces || []).find(
    (n) => n.name === name && (n['parent-id'] || null) === (parentId || null));
}


async function statusOfProbe(page) {
  const rows = await api(page, 'GET', '/api/tests/status');
  if (!Array.isArray(rows)) return undefined;
  return rows.find((r) => r['fn-name'] === PROBE_FN)?.status ?? null;
}


// Poll /api/tests/status until the probe reports `want` (string or
// null) or the deadline passes. The e2e stack is slow — writes land
// in ~3 s and the auto-run debounce adds 500 ms — so deadlines are
// generous per the write-then-poll >= 30 s rule.
async function waitForStatus(page, want, deadlineMs) {
  const start = Date.now();
  let last;
  while (Date.now() - start < deadlineMs) {
    last = await statusOfProbe(page);
    if (last === want) return last;
    await new Promise((res) => setTimeout(res, 1000));
  }
  return last;
}


async function cleanup(page) {
  try {
    const ents = await getEntities(page, PROBE_FN);
    const probe = (ents.fns || []).find((f) => f.name === PROBE_FN);
    if (probe) await api(page, 'DELETE', '/api/entities/fn/' + probe.id);
  } catch (_) {}
  try {
    const root = await findNs(page, ROOT_NS, null);
    const child = root && await findNs(page, 'tests', root.id);
    if (child) await api(page, 'DELETE', '/api/entities/ns/' + child.id);
    if (root) await api(page, 'DELETE', '/api/entities/ns/' + root.id);
  } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => d.accept());
  console.log('edit-tests-suite — tests namespace / runner / dots / run-all / inspector / auto-run');

  try {
    // ====================================================================
    // Setup: namespaces + probe test fn (assert-eq 4 = 4).
    // ====================================================================
    await api(page, 'POST', '/api/entities/ns', 'name=' + ROOT_NS);
    const rootNs = await findNs(page, ROOT_NS, null);
    assert(rootNs, ROOT_NS + ' namespace created');
    await api(page, 'POST', '/api/entities/ns',
              'name=tests&parent-id=' + rootNs.id);
    const testsNs = await findNs(page, 'tests', rootNs.id);
    assert(testsNs, ROOT_NS + '.tests namespace created');

    const ents = await getEntities(page, 'assert-eq');
    const assertEq = (ents.fns || []).find(
      (f) => f.name === 'assert-eq' && (f['parent-ids'] || []).length === 0);
    assert(assertEq, ':assert-eq base-fn resolved');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + assertEq.id
              + '&namespace-id=' + testsNs.id);
    const probeEnts = await getEntities(page, PROBE_FN);
    const probe = (probeEnts.fns || []).find((f) => f.name === PROBE_FN);
    assert(probe, 'probe test fn created in ' + ROOT_NS + '.tests');

    const slotsById = Object.fromEntries(
      (probeEnts.slots || []).map((s) => [s.id, s]));
    const baseSlots = (probeEnts['fn-slots'] || [])
      .filter((fs) => fs['fn-id'] === assertEq.id)
      .map((fs) => slotsById[fs['slot-id']])
      .filter(Boolean);
    const actualSlot = baseSlots.find((s) => s.name === 'actual');
    const expectedSlot = baseSlots.find((s) => s.name === 'expected');
    assert(actualSlot && expectedSlot, ':actual/:expected slots resolved');
    await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + probe.id + '&slot-id=' + actualSlot.id + '&value=4');
    await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + probe.id + '&slot-id=' + expectedSlot.id + '&value=4');

    // ====================================================================
    // Phase A: run via API, read status.
    // ====================================================================
    const run = await api(page, 'POST', '/api/tests/run');
    assert(run && typeof run.total === 'number' && run.total >= 1,
           'POST /api/tests/run ran ' + run.total + ' test(s)');
    const probeResult = (run.results || []).find((r) => r['fn-name'] === PROBE_FN);
    assert(probeResult && probeResult.status === 'succeeded',
           'probe passed via the runner (' + JSON.stringify(probeResult) + ')');
    const st = await waitForStatus(page, 'succeeded', 30000);
    assert(st === 'succeeded',
           'GET /api/tests/status reports succeeded (got ' + st + ')');

    // ====================================================================
    // Phase B: sidebar — tests chip + green dot on the probe row.
    // ====================================================================
    await page.goto(BASE + '/');
    // No fn selected → no graph; wait on the SIDEBAR being live instead
    // (chip row rendered + the ns tree populated).
    await page.waitForFunction(
      () => !!document.querySelector('#kind-filters .kind-toggle[data-kind="tests"]')
            && !!document.querySelector('#entity-list .ns-header'),
      null, {timeout: 30000, polling: 200});
    const dot = await page.evaluate(async (nsPath) => {
      toggleKind('tests');
      // Children render on parent expand — walk the path segment by
      // segment, clicking each collapsed header.
      const parts = nsPath.split('.');
      let cum = '';
      for (const part of parts) {
        cum = cum ? cum + '.' + part : part;
        const h = [...document.querySelectorAll('#entity-list .ns-header')]
          .find((x) => x.dataset.nsPath === cum);
        if (!h) return {err: cum + ' ns header not visible under the lens'};
        h.click();
        await new Promise((res) => setTimeout(res, 1500));
      }
      await new Promise((res) => setTimeout(res, 2000));
      const row = [...document.querySelectorAll('#entity-list .entity-item')]
        .find((el) => el.querySelector('.name')?.textContent === 'e2e-sum-is-four');
      if (!row) return {err: 'probe row not rendered'};
      return {marker: row.querySelector('.kind-marker-test')?.className || ''};
    }, ROOT_NS + '.tests');
    assert(!dot.err, 'tests lens shows the probe (' + (dot.err || 'ok') + ')');
    assert(dot.marker.includes('test-passed'),
           'probe row carries the green test dot (' + dot.marker + ')');

    // ====================================================================
    // Phase C: the lens's [Run all] + the Inspector's Test section.
    // ====================================================================
    // The lens is on (Phase B) → its action is revealed next to the chips.
    await page.waitForFunction(
      () => { const b = document.getElementById('tests-run-all-btn'); return !!b && !b.hidden; },
      null, {timeout: 30000, polling: 200});
    const runCycle = await page.evaluate(() => {
      const btn = document.getElementById('tests-run-all-btn');
      btn.click();
      return new Promise((res) => setTimeout(() => {
        res({disabled: btn.disabled, text: btn.querySelector('.kind-label')?.textContent});
      }, 8000));
    });
    assert(runCycle.disabled === false && runCycle.text === 'Run all',
           '[Run all] completed its POST → re-prime cycle ('
           + JSON.stringify(runCycle) + ')');

    // Select the probe → Inspector › Bindings carries the Test section.
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.reload();
    await page.waitForFunction(
      () => graphReady() && !!document.querySelector('button.more-actions-trigger') && !graph.animating,
      null, {timeout: 30000, polling: 100});
    await page.waitForSelector('[data-insp-tab="bindings"]', {timeout: 15000});
    await page.evaluate(() => document.querySelector('[data-insp-tab="bindings"]').click());
    await page.waitForSelector('#gd-insp-detail .gd-insp-test:not([hidden]) .test-dot',
                               {timeout: 30000});
    const section = await page.evaluate(() => {
      const s = document.querySelector('#gd-insp-detail .gd-insp-test');
      return {
        dot: s.querySelector('.test-dot')?.className || '',
        label: s.querySelector('.gd-insp-test-status')?.textContent || '',
        hasRun: !!s.querySelector('button.tests-run-one'),
      };
    });
    assert(section.dot.includes('test-passed') && section.label === 'passed',
           'Inspector › Test shows the probe passed (' + JSON.stringify(section) + ')');
    assert(section.hasRun, '[Run this test] present');
    // [Run this test] — the htmx POST renders the section from the run's
    // own result; wait for the swapped-in section to carry a dot again.
    await page.evaluate(() => document.querySelector('#gd-insp-detail .gd-insp-test button.tests-run-one').click());
    await page.waitForFunction(
      () => { const s = document.querySelector('#gd-insp-detail .gd-insp-test'); return !!s && !s.querySelector('.htmx-request') && !!s.querySelector('.test-dot.test-passed'); },
      null, {timeout: 30000, polling: 200});
    const afterRun = await page.evaluate(() => ({
      label: document.querySelector('#gd-insp-detail .gd-insp-test .gd-insp-test-status')?.textContent || '',
    }));
    assert(afterRun.label === 'passed', '[Run this test] re-rendered the section from the run (' + afterRun.label + ')');

    // ====================================================================
    // Phase D: auto-run — edit rolls the version, background re-run
    // restores the status without any manual run.
    // ====================================================================
    await api(page, 'PUT', '/api/entities/fn/' + probe.id,
              'description=' + encodeURIComponent('edited to trigger auto-run'));
    // Stale-by-construction window: the new version has no run yet. The
    // auto-run may finish fast, so observing the null is best-effort —
    // the REQUIRED observation is the automatic return to succeeded.
    const after = await waitForStatus(page, 'succeeded', 45000);
    assert(after === 'succeeded',
           'auto-run re-ran the probe after the edit (status ' + after + ')');

    console.log('PASS');
  } catch (e) {
    console.error('FAIL: ' + e.message);
    process.exitCode = 1;
  } finally {
    await cleanup(page);
    await browser.close();
  }
})();
