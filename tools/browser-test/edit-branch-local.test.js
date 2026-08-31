// Branch-local UI smoke — verifies the two affordances added by the
// `:branch-local?` feature: (a) the service popover's branch picker,
// and (b) the branch-diff modal's "won't propagate" annotation.
//
//   Phase A: service popover surfaces `.service-popover-branch-select`
//            with the "(any — legacy)" option and at least the
//            `main` branch option pre-populated.
//   Phase B: create a fn parented from `:http-server` (effective
//            branch-local) on a fresh feat branch via the API, open
//            the diff modal, assert the row for our probe carries
//            `.branch-diff-row-local-badge` AND dims via the
//            `.branch-diff-row-local` class.
//
// Cleanup: every probe + the feat branch are deleted on PASS/FAIL.
//
// Run from this directory:  node edit-branch-local.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');


const SERVICE_FN = 'core.system.current-time-ms';
const PROBE_FN_NAME = 'branch-local-ui-probe';
const FEAT_BRANCH = 'branch-local-ui-feat';


async function findHttpServerId(page) {
  // `lookups` is module-scope in the editor bundle and isn't exposed on
  // window; fetch via the public /api/graph/entities so the test runs
  // without depending on private symbols.
  const ents = await page.evaluate(async () => {
    const r = await fetch('/api/graph/entities');
    return r.json();
  });
  return (ents.fns || []).find((f) => f.name === 'http-server')?.id || null;
}


async function findMainBranchId(page) {
  const body = await api(page, 'GET', '/api/branches');
  return (body?.branches || []).find((b) => b.name === 'main')?.id || null;
}


async function deleteProbeAndBranch(page) {
  // Best-effort cleanup. Order: fn first (lives on the branch), then
  // the branch itself. Probe lookup must hit feat — feat is the only
  // branch where the fn has a version row.
  try {
    const ents = await page.evaluate(async (branch) => {
      const r = await fetch('/api/graph/entities',
                            { headers: { 'X-Graphden-Branch': branch } });
      return r.json();
    }, FEAT_BRANCH);
    const probe = (ents.fns || []).find((f) => f.name === PROBE_FN_NAME);
    if (probe) {
      await page.evaluate(async ({id, branch}) => {
        await authFetch('/api/entities/fn/' + id,
                        { method: 'DELETE',
                          headers: { 'X-Graphden-Branch': branch } });
      }, { id: probe.id, branch: FEAT_BRANCH });
    }
  } catch (_) {}
  try {
    await api(page, 'DELETE', '/api/branches/' + encodeURIComponent(FEAT_BRANCH));
  } catch (_) {}
}


async function openServicePopover(page, fnHash) {
  await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + fnHash);
  // Wait for the fn-card to render with the `⋯` trigger + cy animation
  // to drain. Without this, dispatchEvent below races the post-mount
  // fit animation and the popover never settles.
  await page.waitForFunction(
    () => graphReady()
          && !!document.querySelector('button.more-actions-trigger')
          && !graph.animating,
    null,
    {timeout: 20000, polling: 100});
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  await page.waitForSelector('.row-actions-popover button', {timeout: 20000});
  await page.evaluate(() => {
    const popover = document.querySelector('.row-actions-popover');
    const gear = Array.from(popover?.querySelectorAll('button') || [])
      .find((b) => b.textContent.trim() === '⚙');
    if (gear) gear.dispatchEvent(new MouseEvent('click', {bubbles: true}));
  });
  // Popover loads through htmx fetch of /partials/service-popover.
  await page.waitForSelector('.service-popover.visible', {timeout: 5000});
}


(async () => {
  const {browser, page} = await newContext(chromium);
  // Swallow the :process rejection alert from Phase A — we never click
  // Save, but a stray click would pop one and stall the test.
  page.on('dialog', (d) => d.accept());
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-branch-local — service picker + diff badge');

  try {
    // ====================================================================
    // Phase A: service popover branch picker
    // ====================================================================
    await openServicePopover(page, SERVICE_FN);
    const pickerState = await page.evaluate(() => {
      const sel = document.querySelector('.service-popover.visible '
                                         + '.service-popover-branch-select');
      if (!sel) return {present: false};
      const opts = Array.from(sel.querySelectorAll('option')).map((o) => ({
        value: o.value,
        text: o.textContent.trim(),
        selected: o.selected,
      }));
      return {present: true,
              defaultValue: sel.value,
              options: opts,
              optionCount: opts.length};
    });
    assert(pickerState.present,
           '.service-popover-branch-select rendered on the popover');
    assert(pickerState.options.some((o) => o.text === '(any — legacy)'),
           '"(any — legacy)" option present for nullable-branch-id rows');
    assert(pickerState.options.some((o) => o.text === 'main'),
           '"main" branch option present in picker');
    assert(pickerState.optionCount >= 2,
           'picker has at least the legacy option + main (' + pickerState.optionCount + ' total)');

    const mainId = await findMainBranchId(page);
    assert(mainId,
           'main branch id resolved via /api/branches');
    // For a brand-new (no existing service) popover on the default
    // branch, default = main's id. The form-submit picks this up so
    // creating a service from the editor scopes it to the active
    // branch by default (most natural for "make this fn run here").
    assert(pickerState.defaultValue === mainId,
           'default selection = main branch id when no existing service'
           + ' (current branch is the editor default)');

    // Dismiss popover before Phase B.
    await page.evaluate(() => {
      if (typeof hideServicePopover === 'function') hideServicePopover();
    });
    await page.waitForFunction(
      () => !document.querySelector('.service-popover.visible'),
      null,
      {timeout: 3000, polling: 50});

    // ====================================================================
    // Phase B: diff modal branch-local annotation
    // ====================================================================
    // Defensive pre-cleanup in case a prior failed run left state.
    await deleteProbeAndBranch(page);

    const branchResp = await api(page, 'POST', '/api/branches',
                                 {name: FEAT_BRANCH});
    assert(branchResp?.ok === true,
           'POST /api/branches created the feat branch (got '
           + JSON.stringify(branchResp).slice(0, 150) + ')');

    const httpServerId = await findHttpServerId(page);
    assert(httpServerId,
           ':http-server fn id resolved via the in-page lookups map');

    // Create the probe fn ON FEAT (X-Graphden-Branch header). Parented
    // from :http-server → effective-branch-local via the parent-ids
    // walker, so the diff renderer's `isFnBranchLocal` must return
    // true for this row.
    const createResp = await page.evaluate(async ({name, parentId, branch}) => {
      const body = new URLSearchParams();
      body.set('name', name);
      body.set('parent-ids', parentId);
      const r = await authFetch('/api/entities/fn', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded',
                   'X-Graphden-Branch': branch },
        body: body.toString(),
      });
      return { ok: r.ok, status: r.status,
               body: await r.text().then((t) => t.slice(0, 200)) };
    }, { name: PROBE_FN_NAME, parentId: httpServerId, branch: FEAT_BRANCH });
    assert(createResp?.ok,
           'probe fn created on feat under :http-server (status '
           + createResp?.status + ')');

    // Reload the entity cache so the diff modal's `lookups.fnMap` knows
    // about the new fn — diff renders the row only if `fnMap` can be
    // walked to detect branch-local. `loadGraphData` rebuilds the
    // module-scope `lookups` from /api/graph/entities.
    // Fire-and-return + poll from the node side: holding a page.evaluate
    // open across the slow network op dies with a phantom "Execution
    // context was destroyed" under load (see edit-phase5-sequence.test.js
    // for the full account). waitForFunction re-issues short evaluates
    // instead, which is immune.
    await page.evaluate(() => {
      if (typeof loadGraphData === 'function') loadGraphData();
    });
    // BEST-EFFORT settle: the probe fn lives on the FEAT branch while the
    // page browses main, so main's branch-scoped `lookups.fnMap` may never
    // contain it — the historical inline poll always gave up silently after
    // its deadline and the diff still rendered from the server partial.
    // Keep the same semantics: bounded wait, swallow the timeout.
    await page.waitForFunction(
      (name) => typeof lookups !== 'undefined'
                && lookups?.fnMap
                && Array.from(lookups.fnMap.values()).some((f) => f.name === name),
      PROBE_FN_NAME,
      {timeout: 5000, polling: 100}).catch(() => {});

    // Drive the REVIEW dialog directly (UX-v3: Δ toggles compare mode;
    // the 📍 badge now lives in the dialog's client-rendered change
    // list and the inspector's diff panel — this asserts the former).
    await page.evaluate((source) => {
      if (typeof showReviewDialog === 'function') {
        showReviewDialog(source);
      }
    }, FEAT_BRANCH);
    await page.waitForFunction(
      () => {
        const m = document.querySelector('.branch-diff-modal');
        return m && !m.classList.contains('hidden')
               && !m.querySelector('.branch-diff-loading')
               && m.querySelector('.bd-review-changes');
      },
      null,
      {timeout: 30000, polling: 100});
    await page.evaluate(() => {
      const d = document.querySelector('.bd-review-changes');
      if (d && !d.open) d.open = true;
    });

    const modalState = await page.evaluate((probeName) => {
      const modal = document.querySelector('.branch-diff-modal');
      if (!modal) return { open: false };
      const rows = Array.from(modal.querySelectorAll('.branch-diff-row'));
      const localRows = rows.filter((r) =>
        r.classList.contains('branch-diff-row-local'));
      const probeRow = rows.find((r) => {
        // Find the row whose summary names our probe. Diff entities
        // emit `:fn` for an added row; the summary html contains the
        // `<strong>name</strong>` of the fn.
        // diff v2: the group header strong carries the fn LABEL
        // (":name" with class attrs), so match on textContent.
        const strong = r.querySelector('strong.branch-diff-fn-name');
        return strong && strong.textContent === (':' + probeName);
      });
      const badge = probeRow?.querySelector('.branch-diff-row-local-badge');
      return {
        open: true,
        rowCount: rows.length,
        localRowCount: localRows.length,
        probeRowFound: !!probeRow,
        probeRowDimmed: probeRow?.classList.contains('branch-diff-row-local'),
        probeBadgePresent: !!badge,
        probeBadgeText: badge?.textContent.trim(),
      };
    }, PROBE_FN_NAME);

    assert(modalState.open,
           'review dialog opened via showReviewDialog(' + FEAT_BRANCH + ')');
    assert(modalState.rowCount >= 1,
           'diff modal lists at least one entry (' + modalState.rowCount + ')');
    assert(modalState.probeRowFound,
           'probe fn (' + PROBE_FN_NAME + ') appears as a diff row');
    assert(modalState.probeRowDimmed,
           'probe row carries `.branch-diff-row-local` (dimmed style)');
    assert(modalState.probeBadgePresent,
           'probe row carries `.branch-diff-row-local-badge`');
    assert(modalState.probeBadgeText?.includes('branch-local'),
           'badge text mentions "branch-local" (got '
           + JSON.stringify(modalState.probeBadgeText) + ')');

    console.log('✓ branch-local UI smoke verified — picker + review-dialog badge');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    // Cleanup always — leaves the dev DB consistent for the next run.
    try { await deleteProbeAndBranch(page); }
    catch (_) {}
    await browser.close();
  }
})();
