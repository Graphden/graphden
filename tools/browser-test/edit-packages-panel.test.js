// Packages panel lifecycle e2e — the Build-surface packages chip popover
// end to end (install moved OFF the Organization page in c2e68420:
// install is a build act, so the browse/install browser hangs off the
// #gd-pkg-chip context chip; Operate keeps only the governance view).
//
// Publishes a package at two versions (setup, via the JSON API), then
// drives the FULL editor panel workflow in a real browser:
//
//   • The packages chip reveals (registry package present) and its
//     popover loads the server-rendered panel, header "Packages".
//   • The browse <details> lists the published registry versions, each
//     with an Install button.
//   • Install (by reference) → the pin appears in the installed table
//     with its × uninstall + ↑ update controls.
//   • Update → typing another version + ↑ repoints the pin (the row
//     shows the new version).
//   • Uninstall (× + confirm dialog) → the pin is dropped and the
//     installed table collapses to the empty-state notice.
//
// The registry starts empty on the isolated e2e stack (publishing is a
// runtime action, not part of package loading), so the two versions
// this test publishes are the only browse rows — no cross-test noise.
//
// PERF: install / update used to invalidate the WHOLE graph cache (a full
// ~3600-fn recompile) after writing, which froze the server for tens of
// seconds on the constrained e2e stack (2.25 GB) — the update phase tripped
// its /health idle-timeout. Fixed in perf(packages) e3f52592: these ops now
// delta-invalidate only the fn-ids they touched. The per-phase timeouts below
// are now just GC-stall tolerance (same as the other e2e tests), not freeze
// headroom.
//
// Run from this directory:  node edit-packages-panel.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, nodeApi, nodeApiJson} = require('./edit-test-helpers');


// Per-run unique package name so reruns against a shared stack stay
// clean (the ephemeral e2e stack is fresh per `bb test-e2e`, but this
// keeps `node edit-packages-panel.test.js` against the dev demo safe too).
const RUN_ID = process.pid.toString(36) + Date.now().toString(36);
const PKG = 'panel-e2e-' + RUN_ID;
// This test INSTALLS a package, and installing copies the package's fns into the
// graph and creates a namespace per version. Uninstall only drops the pin — the
// copies stay. So it used to clean up by hand, and that cascade was the single
// most expensive thing in the suite: 21 s of browser work, then 203 s of deletes,
// which is what kept tripping the 300 s per-test cap and leaving fns behind when
// it was killed mid-way.
//
// None of that is necessary. Package pins are BRANCH-SCOPED by design
// (docs/PACKAGE_DISTRIBUTION.md), so the test does its whole lifecycle on a
// throwaway branch — the editor takes `?branch=` from the URL, node-side calls
// send `X-Graphden-Branch` — and cleanup is one DELETE of the branch. The default
// branch never sees the copies at all, so there is nothing to leak and nothing to
// peel: no amount of load can make a single DELETE take five minutes.
const BRANCH = 'pkg-e2e-' + RUN_ID;
const BH = {'X-Graphden-Branch': BRANCH};


// Poll the panel for a stable end-state, riding out the transient
// "Loading…" re-renders the sidebar emits when it rebuilds the section.
async function panelState(page) {
  return page.evaluate(() => {
    const sec = document.querySelector('#gd-pkg-pop');
    const root = sec && sec.querySelector('[data-packages-panel]');
    // Installed table is the DIRECT child of the panel root; the browse
    // table lives inside <details>, so `>` disambiguates them.
    const installed = root && root.querySelector(':scope > .packages-panel-table');
    const rows = installed
      ? [...installed.querySelectorAll('tbody tr')].map((tr) => {
          const td = [...tr.querySelectorAll('td')];
          return {name: td[0]?.textContent, version: td[1]?.textContent};
        })
      : [];
    return {
      hasSection: !!sec,
      label: sec?.querySelector('h5')?.textContent?.trim(),
      loaded: !!root,
      emptyNotice: !!(root && root.querySelector(':scope > .packages-panel-empty')),
      installedRows: rows,
      installBtnPosts: [...(sec?.querySelectorAll('.packages-install-btn') || [])]
        .map((b) => b.getAttribute('hx-post')),
    };
  });
}


(async () => {
  // ---- setup: publish two versions BEFORE the browser loads the panel
  await nodeApiJson('POST', '/api/branches', {name: BRANCH});
  // Publish onto the branch too, so the :package-version rows go with it.
  await nodeApiJson('POST', '/api/packages/publish',
    {name: PKG, version: '1.0.0', 'ns-root': 'app.contact-demo'}, BH);
  await nodeApiJson('POST', '/api/packages/publish',
    {name: PKG, version: '1.1.0', 'ns-root': 'app.contact-demo'}, BH);

// Installing a package MATERIALISES its fns into the graph — 36 of them here.
// Uninstalling does not take them back out: by design, uninstall drops the pin
// only (copy-on-write; see docs/PACKAGE_DISTRIBUTION.md). So this test created
// 36 fns and never removed them, every run, while every assertion passed. The
// suite's leak detector caught it the first time it ran.
//
// Peel them off in layers: a parent cannot be deleted while a live child points
// at it (the server answers 409), and the install builds a tree. Repeat until a
// pass deletes nothing, then report whatever is stuck rather than swallowing it.

  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => { console.log('  [dialog]:', d.message().slice(0, 120)); d.accept(); });
  page.on('console', (m) => {
    if (m.type() === 'error') console.log('  (console.error: ' + m.text().slice(0, 160) + ')');
  });
  console.log('edit-packages-panel — install / update / uninstall lifecycle');

  // The invariant: whatever the panel does on its branch, the DEFAULT branch —
  // the one every other file in the suite runs against — is left exactly as found.
  const defaultFnIds = new Set(
    ((await nodeApiJson('GET', '/api/graph/entities?scope=index')).fns || [])
      .map((f) => f.id));
  // A NAMESPACE is not versioned (like :service), so a package install creates one
  // per version GLOBALLY — the throwaway branch does not carry them off with it,
  // and no editor affordance deletes a namespace. They are empty once the branch is
  // gone, so the test removes them through the CRUD type — which is `ns`, not
  // `namespace` (that is not a type at all, and answers 400).
  const defaultNsIds = new Set(
    ((await nodeApiJson('GET', '/api/graph/entities?scope=index')).namespaces || [])
      .map((n) => n.id));

  // Snapshot the graph BEFORE anything is installed, so cleanup can be exact:
  // delete what this test added, and nothing else.
  const beforeIds = new Set(
    ((await nodeApiJson('GET', '/api/graph/entities')).fns || []).map((f) => f.id));
  // Installing a package also creates a NAMESPACE per version (app.contact-demo@1-0-0,
  // @1-1-0). They are not fns, so the fn cascade never touched them, and they stayed
  // in the sidebar tree of every file that ran after this one.
  const beforeNs = new Set(
    ((await nodeApiJson('GET', '/api/graph/entities?scope=index')).namespaces || [])
      .map((n) => n.id));
  // The suite's 300 s per-test cap kills this file in the gate, so time the phases
  // rather than the whole: a slow install and a slow cleanup are different bugs.
  let phaseStart = Date.now();
  const phase = (label) => {
    console.log('  ⏱ ' + label + ': ' + Math.round((Date.now() - phaseStart) / 1000) + 's');
    phaseStart = Date.now();
  };

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')
                    + '/?branch=' + encodeURIComponent(BRANCH));
    // The chip reveals once window.API confirms the optional registry
    // package is present; its popover lazy-loads the panel on click.
    await page.waitForSelector('#gd-pkg-chip:not([hidden])', {timeout: 15000});
    await page.click('#gd-pkg-chip');

    // ===================================================================
    // Phase A: popover opens + panel content loads (auth-gated).
    // ===================================================================
    await page.waitForFunction(() => {
      const sec = document.querySelector('#gd-pkg-pop');
      return sec && sec.querySelector('[data-packages-panel]');
    }, null, {timeout: 15000, polling: 100});
    const a = await panelState(page);
    assert(a.hasSection, '#gd-pkg-pop packages popover rendered');
    assert(a.label === 'Packages', 'header label is "Packages": ' + a.label);
    assert(a.loaded, 'panel content loaded (not stuck on Loading…)');
    assert(a.emptyNotice, 'nothing installed yet → empty-state notice shown');

    // ===================================================================
    // Phase B: browse <details> lists both published versions.
    // ===================================================================
    await page.evaluate(() => {
      const d = document.querySelector('#gd-pkg-pop details.packages-available');
      if (d) d.open = true;
    });
    await page.waitForFunction((pkg) => {
      const posts = [...document.querySelectorAll('#gd-pkg-pop .packages-install-btn')]
        .map((b) => b.getAttribute('hx-post') || '');
      return posts.some((p) => p.includes('version=1.0.0') && p.includes(pkg))
          && posts.some((p) => p.includes('version=1.1.0') && p.includes(pkg));
    }, PKG, {timeout: 10000, polling: 100});
    const b = await panelState(page);
    assert(b.installBtnPosts.some((p) => p && p.includes(PKG) && p.includes('1.0.0')),
      'browse lists ' + PKG + ' 1.0.0 with an Install button');
    assert(b.installBtnPosts.some((p) => p && p.includes(PKG) && p.includes('1.1.0')),
      'browse lists ' + PKG + ' 1.1.0 with an Install button');

    // ===================================================================
    // Phase C: Install 1.0.0 → pin appears in the installed table.
    // ===================================================================
    await page.evaluate((pkg) => {
      const d = document.querySelector('#gd-pkg-pop details.packages-available');
      if (d) d.open = true;
      // Scope by BOTH name and version — the registry may hold other
      // packages that also publish a 1.0.0 (matching version alone would
      // click the wrong row).
      const btn = [...document.querySelectorAll('#gd-pkg-pop .packages-install-btn')]
        .find((x) => {
          const p = x.getAttribute('hx-post') || '';
          return p.includes('name=' + pkg) && p.includes('version=1.0.0');
        });
      btn && btn.click();
    }, PKG);
    // install materializes the version's fns + delta-recompiles them (see
    // PERF note at the top of this file — no longer a full-graph freeze).
    // The ceiling is GC-stall tolerance for the constrained gate stack, not
    // freeze headroom: 60s flaked a landing gate on 2026-08-20 (install took
    // 1s, the NEXT step never landed inside the minute), while the same run
    // takes ~2s on a warm box. Polling keeps the success path fast.
    await page.waitForFunction((pkg) => {
      const root = document.querySelector('#gd-pkg-pop [data-packages-panel]');
      const t = root && root.querySelector(':scope > .packages-panel-table');
      return t && [...t.querySelectorAll('tbody tr td:first-child')]
        .some((td) => td.textContent === pkg);
    }, PKG, {timeout: 150000, polling: 250});
    const c = await panelState(page);
    const cRow = c.installedRows.find((r) => r.name === PKG);
    assert(cRow && cRow.version === '1.0.0',
      'installed table shows ' + PKG + ' @ 1.0.0 after Install: ' + JSON.stringify(cRow));
    phase('install');

    // ===================================================================
    // Phase D: Update 1.0.0 → 1.1.0 via the version input + ↑.
    // ===================================================================
    await page.evaluate((pkg) => {
      const root = document.querySelector('#gd-pkg-pop [data-packages-panel]');
      const tr = [...root.querySelectorAll(':scope > .packages-panel-table tbody tr')]
        .find((r) => r.querySelector('td')?.textContent === pkg);
      tr.querySelector('.packages-version-input').value = '1.1.0';
      tr.querySelector('.packages-update-btn').click();
    }, PKG);
    // update materializes the target version, rewrites the project's refs,
    // then delta-recompiles only the touched fns (see PERF note) — the
    // heaviest panel op, but no longer a full-graph freeze. 30s covers a GC
    // stall on the constrained stack.
    await page.waitForFunction((pkg) => {
      const root = document.querySelector('#gd-pkg-pop [data-packages-panel]');
      const t = root && root.querySelector(':scope > .packages-panel-table');
      return t && [...t.querySelectorAll('tbody tr')].some((tr) => {
        const td = [...tr.querySelectorAll('td')];
        return td[0]?.textContent === pkg && td[1]?.textContent === '1.1.0';
      });
    }, PKG, {timeout: 150000, polling: 250});
    const d = await panelState(page);
    const dRow = d.installedRows.find((r) => r.name === PKG);
    assert(dRow && dRow.version === '1.1.0',
      'installed row updated to 1.1.0 after ↑: ' + JSON.stringify(dRow));
    phase('update (ref-rewrite)');

    // ===================================================================
    // Phase E: Uninstall (× → confirm) → back to empty-state.
    // ===================================================================
    // Use page.click (not an evaluate .click) so Playwright drives the
    // hx-confirm dialog: a synchronous window.confirm() fired from inside
    // a page.evaluate deadlocks the page.on('dialog') auto-accept, and the
    // hx-delete never fires. There is exactly one installed pin here, so
    // the child-combinator selector is unambiguous.
    await page.click(
      '#gd-pkg-pop [data-packages-panel] > .packages-panel-table .packages-uninstall');
    // uninstall only drops the pin (no materialize/recompile) — the cheapest
    // op; 30s is plenty of GC-stall margin.
    await page.waitForFunction((pkg) => {
      const root = document.querySelector('#gd-pkg-pop [data-packages-panel]');
      if (!root) return false;
      const t = root.querySelector(':scope > .packages-panel-table');
      const stillThere = t && [...t.querySelectorAll('tbody tr td:first-child')]
        .some((td) => td.textContent === pkg);
      return !stillThere;
    }, PKG, {timeout: 150000, polling: 250});
    const e = await panelState(page);
    assert(!e.installedRows.some((r) => r.name === PKG),
      'installed table no longer lists ' + PKG + ' after uninstall');
    assert(e.emptyNotice,
      'the sole pin gone → empty-state notice returns');
    phase('uninstall');

    console.log('✓ packages panel verified — render / browse / install / update / uninstall');
  } catch (err) {
    process.exitCode = 1;
    console.error('✗ test failed:', err.message);
  } finally {
    await browser.close();
    phase('browser work (render/browse/install/update/uninstall)');
    const r = await nodeApi('DELETE', '/api/branches/' + encodeURIComponent(BRANCH));
    if (!r.ok && r.status !== 404) {
      process.exitCode = 1;
      console.error('✗ CLEANUP could not drop branch ' + BRANCH + ' — HTTP ' + r.status);
    }
    for (const ns of ((await nodeApiJson('GET', '/api/graph/entities?scope=index'))
                        .namespaces || []).filter((n) => !defaultNsIds.has(n.id))) {
      const nr = await nodeApi('DELETE', '/api/entities/ns/' + ns.id);
      if (!nr.ok && nr.status !== 404) {
        process.exitCode = 1;
        console.error('✗ CLEANUP left namespace ' + (ns.name || ns.id)
                      + ' — HTTP ' + nr.status);
      }
    }
    phase('cleanup (branch delete + empty namespaces)');
    // Prove it: the copies never reached the branch every other test runs on.
    const leaked = ((await nodeApiJson('GET', '/api/graph/entities?scope=index')).fns || [])
      .filter((f) => !defaultFnIds.has(f.id));
    if (leaked.length) {
      process.exitCode = 1;
      console.error('✗ CLEANUP LEAKED ' + leaked.length + ' fn(s) onto the default branch:');
      leaked.slice(0, 10).forEach((f) => console.error('      ' + (f.name || f.id)));
    }
  }
})();
