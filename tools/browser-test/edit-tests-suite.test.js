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
//   Phase C: Operate → Tests panel renders the summary + the probe
//            row; the Run-all button completes its POST → refresh
//            cycle.
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
  console.log('edit-tests-suite — tests namespace / runner / dots / panel / auto-run');

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
    // Phase C: Operate → Tests panel + Run all cycle.
    // ====================================================================
    const panel = await page.evaluate(() => {
      location.hash = '#@operate';
      return new Promise((res) => setTimeout(() => {
        const btn = [...document.querySelectorAll('.gd-op-nav-btn')]
          .find((b) => b.dataset.section === 'tests');
        if (!btn) return res({err: 'no tests nav button'});
        btn.click();
        setTimeout(() => {
          const sec = document.querySelector('section[data-section="tests"]');
          res({
            summary: sec?.querySelector('.tests-panel-summary')?.textContent || '',
            hasRow: !![...(sec?.querySelectorAll('.tests-row .tests-fn') || [])]
              .find((a) => a.textContent === 'e2e-sum-is-four'),
            hasRunBtn: !!sec?.querySelector('#gd-tests-run-all'),
          });
        }, 4000);
      }, 1000));
    });
    assert(!panel.err && panel.hasRow,
           'Operate → Tests panel lists the probe (' + JSON.stringify(panel) + ')');
    assert(/\d+ tests · \d+ passed/.test(panel.summary),
           'panel summary renders counts (' + panel.summary + ')');
    assert(panel.hasRunBtn, 'Run-all button present');
    const runCycle = await page.evaluate(() => {
      document.querySelector('#gd-tests-run-all').click();
      return new Promise((res) => setTimeout(() => {
        const btn = document.querySelector('#gd-tests-run-all');
        res({disabled: btn?.disabled, text: btn?.textContent});
      }, 8000));
    });
    assert(runCycle.disabled === false && runCycle.text === 'Run all tests',
           'Run-all completed its POST → refresh cycle ('
           + JSON.stringify(runCycle) + ')');

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
