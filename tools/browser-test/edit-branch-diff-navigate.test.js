// Compare-entry + ghost-navigation e2e (UX-v3: Δ toggles COMPARE
// MODE; the "lives only on the other branch" navigation now rides
// the Explorer's ghost rows).
//
// Flow:
//   1. Create a feat branch + a fn-def on it.
//   2. From :main, click Δ on the feat row → the Δ chip appears.
//   3. The probe renders as a GHOST row in the (expanded) Explorer.
//   4. Click the ghost → confirm → switchToBranch(feat) with the
//      probe's name in the hash for post-reload selection.
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
    await page.waitForSelector('#gd-diff-chip', {timeout: 20000});
    assert(true, 'Δ enters compare mode (chip present)');

    // The feat-only probe has no row on main — it must appear as a
    // dimmed GHOST in the expanded root group.
    await page.evaluate(() => {
      const h = document.querySelector('.ns-header-pseudo');
      if (h && h.getAttribute('aria-expanded') !== 'true') h.click();
    });
    await page.waitForFunction((nm) => {
      return Array.from(document.querySelectorAll('.gd-diff-ghost .name'))
        .some((n) => n.textContent === nm);
    }, PROBE_FN, {timeout: 20000});
    assert(true, 'ghost row for the feat-only probe appears');

    // Stub switchToBranch so the test doesn't actually reload — we
    // only want to verify which branch the ghost click dispatches to.
    await page.evaluate(() => {
      window.__switchToBranchCalledWith = null;
      window.switchToBranch = function(name) {
        window.__switchToBranchCalledWith = name;
      };
    });
    await page.evaluate((nm) => {
      const g = Array.from(document.querySelectorAll('.gd-diff-ghost'))
        .find((el) => el.querySelector('.name')?.textContent === nm);
      g?.click();
    }, PROBE_FN);
    await page.waitForFunction(
      ({ probe, feat }) => window.__switchToBranchCalledWith === feat
        && (location.hash || '').includes(probe),
      { probe: PROBE_FN, feat: FEAT_BRANCH },
      {timeout: 5000});
    assert(true, 'ghost click switches to feat with the probe in the hash');
    await page.evaluate(() => window.gdExitDiffMode());

    console.log('✓ compare-entry + ghost navigation verified');

  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
