// Branch-diff modal click-to-navigate e2e — extends the existing
// `edit-branch-local.test.js` (which covered the branch-local badge
// in the modal) with the click → navigate-to-fn flow.
//
// Flow:
//   1. Create a feat branch + a fn-def on it (so the diff modal has
//      at least one "Added in feat" :fn row).
//   2. Switch back to :main.
//   3. Click the Δ button on the feat row → modal opens.
//   4. Modal shows the probe fn in the "Added in feat" section.
//   5. Click the row → modal closes + URL/hash updates to the probe.
//
// Run from this directory:  node edit-branch-diff-navigate.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, openBranchPopover} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FEAT_BRANCH = 'diff-nav-feat' + RUN_ID;
const PROBE_FN = 'diff-nav-probe' + RUN_ID;


async function cleanup(page) {
  try {
    // Probe lives on feat — query through the branch header.
    const ents = await page.evaluate(async (branch) => {
      const r = await window.authFetch('/api/graph/entities',
                                       {headers: {'X-Graphden-Branch': branch}});
      return r.ok ? r.json() : null;
    }, FEAT_BRANCH);
    const probe = (ents?.fns || []).find((f) => f.name === PROBE_FN);
    if (probe) {
      await page.evaluate(async ({id, branch}) => {
        await window.authFetch('/api/entities/fn/' + id, {
          method: 'DELETE',
          headers: {'X-Graphden-Branch': branch},
        });
      }, {id: probe.id, branch: FEAT_BRANCH});
    }
  } catch (_) {}
  try {
    await api(page, 'DELETE',
              '/api/branches/' + encodeURIComponent(FEAT_BRANCH));
  } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 200));
    d.accept();
  });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-branch-diff-navigate — modal opens + row click navigates to fn');

  try {
    await cleanup(page);
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});

    // ===================================================================
    // Phase A: seed a feat branch + a fn-def visible only on feat.
    // ===================================================================
    const branchResp = await api(page, 'POST', '/api/branches',
                                 {name: FEAT_BRANCH});
    assert(branchResp?.ok,
           'feat branch created: '
           + JSON.stringify(branchResp).slice(0, 200));

    // Find a small, harmless parent (`:identity`) and use it for the
    // probe. We create the probe directly on the feat branch via the
    // branch header.
    const mainEnts = await api(page, 'GET', '/api/graph/entities');
    const identity = mainEnts.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity baseline resolved');

    const created = await page.evaluate(async ({name, parentId, branch}) => {
      const body = new URLSearchParams();
      body.set('name', name);
      body.set('parent-ids', parentId);
      const r = await window.authFetch('/api/entities/fn', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Graphden-Branch': branch,
        },
        body: body.toString(),
      });
      return {status: r.status, body: await r.text()};
    }, {name: PROBE_FN, parentId: identity.id, branch: FEAT_BRANCH});
    assert(created.body.includes('created successfully'),
           'probe fn-def created on feat: ' + JSON.stringify(created).slice(0, 200));

    // ===================================================================
    // Phase B: from :main, open branch popover + click Δ on feat row.
    // The diff modal should appear with the probe in "Added in feat".
    // ===================================================================
    let opened = await openBranchPopover(page);
    assert(opened, 'branch popover opens on chip click');

    await page.click(
      '.branch-row[data-branch-name="' + FEAT_BRANCH + '"] .branch-row-diff');
    await page.waitForSelector('.branch-diff-modal:not(.hidden)',
                               {timeout: 10000});
    await page.waitForFunction(
      () => {
        const m = document.querySelector('.branch-diff-modal');
        return m && !m.querySelector('.branch-diff-loading');
      },
      null,
      {timeout: 15000});

    const diffState = await page.evaluate(() => {
      const modal = document.querySelector('.branch-diff-modal');
      const sections = Array.from(modal.querySelectorAll('.branch-diff-section'));
      const addedInSource = sections.find((s) =>
        /Added in/.test(s.querySelector('.branch-diff-section-head')
                          ?.textContent || ''));
      const rows = Array.from(
        addedInSource?.querySelectorAll('.branch-diff-row') || []);
      return {
        modalVisible: !modal.classList.contains('hidden'),
        sectionsCount: sections.length,
        addedInSourceRows: rows.length,
        clickableRows: rows.filter(
          (r) => r.classList.contains('branch-diff-row-clickable')).length,
        rowFnIds: rows.map((r) => r.getAttribute('data-diff-fn-id')),
      };
    });
    assert(diffState.modalVisible, 'diff modal visible (loading dismissed)');
    assert(diffState.sectionsCount >= 1,
           'diff modal renders at least one section: '
           + diffState.sectionsCount);
    assert(diffState.addedInSourceRows >= 1,
           'at least one row in "Added in feat" section: '
           + diffState.addedInSourceRows);
    assert(diffState.clickableRows >= 1,
           'at least one row carries .branch-diff-row-clickable: '
           + diffState.clickableRows);

    // ===================================================================
    // Phase C: click the probe row → "lives only on feat — switch?"
    // confirm fires → switchToBranch(feat) is called with the probe's
    // qualified name pushed as the hash so the post-reload editor
    // selects it. (Earlier the click invoked `selectFn(id)` directly,
    // which silently no-op'd because lookups.fnMap on main didn't
    // know the feat-only fn.)
    // ===================================================================
    const probeFnId = await page.evaluate(async (branch) => {
      const r = await window.authFetch('/api/graph/entities',
                                       {headers: {'X-Graphden-Branch': branch}});
      const ents = await r.json();
      return ents.fns.find((f) => f.name && f.name.startsWith('diff-nav-probe-'))?.id;
    }, FEAT_BRANCH);
    assert(probeFnId, 'probe fn-id resolved for navigation check');

    // Stub switchToBranch so the test doesn't actually reload — we
    // only want to verify which branch the dialog confirm dispatches
    // to.
    await page.evaluate(() => {
      window.__switchToBranchCalledWith = null;
      window.switchToBranch = function(name) {
        window.__switchToBranchCalledWith = name;
      };
    });

    // Click the matching row. The shared dialog handler auto-accepts
    // the confirm.
    await page.evaluate((id) => {
      const row = document.querySelector(
        '.branch-diff-row[data-diff-fn-id="' + id + '"]');
      row?.click();
    }, probeFnId);

    // Row-click dismisses the modal AND kicks an async switchToBranch +
    // hash update. Wait for ALL of it to settle before the single read
    // below — waiting only on the modal raced the still-pending nav/hash.
    await page.waitForFunction(
      ({ probe, feat }) => {
        const m = document.querySelector('.branch-diff-modal');
        const hidden = !m || m.classList.contains('hidden');
        return hidden
          && window.__switchToBranchCalledWith === feat
          && (location.hash || '').includes(probe);
      },
      { probe: PROBE_FN, feat: FEAT_BRANCH },
      {timeout: 5000});

    const navState = await page.evaluate(() => ({
      modalHidden: !!document.querySelector('.branch-diff-modal.hidden')
                   || !document.querySelector('.branch-diff-modal:not(.hidden)'),
      switchTo: window.__switchToBranchCalledWith,
      hash: location.hash,
    }));
    assert(navState.modalHidden, 'modal dismissed after row click');
    assert(navState.switchTo === FEAT_BRANCH,
           'switchToBranch invoked with feat branch: '
           + JSON.stringify(navState.switchTo) + ' vs ' + FEAT_BRANCH);
    assert(navState.hash.includes(PROBE_FN),
           'URL hash carries probe name for post-reload selection: '
           + JSON.stringify(navState.hash));

    console.log('✓ branch-diff modal click-to-navigate verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
