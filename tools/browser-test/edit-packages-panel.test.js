// Packages panel lifecycle e2e — the sidebar Packages section end to end.
//
// Publishes a package at two versions (setup, via the JSON API), then
// drives the FULL editor panel workflow in a real browser:
//
//   • Sidebar Packages section renders (auth-gated), header "Packages".
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
// PERF: install / update materialize fns + invalidate the whole graph
// cache (a full ~3600-fn recompile), and update additionally full-scans
// the binding + binding-list-item tables to rewrite refs. On the
// memory-constrained e2e stack (2.25 GB) that can freeze the server for
// tens of seconds — the per-phase timeouts below are sized for it. This
// is a known runtime-perf issue (the operations KNOW which fn-ids they
// touched, so they could delta-invalidate instead of full-recompile);
// tracked for the perf pass, not a bug in the panel UI itself.
//
// Run from this directory:  node edit-packages-panel.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, nodeApiJson} = require('./edit-test-helpers');


// Per-run unique package name so reruns against a shared stack stay
// clean (the ephemeral e2e stack is fresh per `bb test-e2e`, but this
// keeps `node edit-packages-panel.test.js` against the dev demo safe too).
const RUN_ID = process.pid.toString(36) + Date.now().toString(36);
const PKG = 'panel-e2e-' + RUN_ID;


// Poll the panel for a stable end-state, riding out the transient
// "Loading…" re-renders the sidebar emits when it rebuilds the section.
async function panelState(page) {
  return page.evaluate(() => {
    const sec = document.querySelector('.sidebar-packages');
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
      label: sec?.querySelector('.ns-label')?.textContent?.trim(),
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
  await nodeApiJson('POST', '/api/packages/publish',
    {name: PKG, version: '1.0.0', 'ns-root': 'app.contact-demo'});
  await nodeApiJson('POST', '/api/packages/publish',
    {name: PKG, version: '1.1.0', 'ns-root': 'app.contact-demo'});

  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => { console.log('  [dialog]:', d.message().slice(0, 120)); d.accept(); });
  page.on('console', (m) => {
    if (m.type() === 'error') console.log('  (console.error: ' + m.text().slice(0, 160) + ')');
  });
  console.log('edit-packages-panel — install / update / uninstall lifecycle');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/');
    // Un-collapse the sidebar in case a stale pref persisted (a fresh
    // e2e context won't have it, but keep the dev-demo run robust).
    await page.evaluate(() => document.body.classList.remove('sidebar-collapsed'));
    await page.waitForSelector('.sidebar-packages', {timeout: 15000});

    // ===================================================================
    // Phase A: section renders + panel content loads (auth-gated).
    // ===================================================================
    await page.waitForFunction(() => {
      const sec = document.querySelector('.sidebar-packages');
      return sec && sec.querySelector('[data-packages-panel]');
    }, null, {timeout: 15000, polling: 100});
    const a = await panelState(page);
    assert(a.hasSection, '.sidebar-packages section rendered');
    assert(a.label === 'Packages', 'header label is "Packages": ' + a.label);
    assert(a.loaded, 'panel content loaded (not stuck on Loading…)');
    assert(a.emptyNotice, 'nothing installed yet → empty-state notice shown');

    // ===================================================================
    // Phase B: browse <details> lists both published versions.
    // ===================================================================
    await page.evaluate(() => {
      const d = document.querySelector('.sidebar-packages details.packages-available');
      if (d) d.open = true;
    });
    await page.waitForFunction((pkg) => {
      const posts = [...document.querySelectorAll('.sidebar-packages .packages-install-btn')]
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
      const d = document.querySelector('.sidebar-packages details.packages-available');
      if (d) d.open = true;
      // Scope by BOTH name and version — the registry may hold other
      // packages that also publish a 1.0.0 (matching version alone would
      // click the wrong row).
      const btn = [...document.querySelectorAll('.sidebar-packages .packages-install-btn')]
        .find((x) => {
          const p = x.getAttribute('hx-post') || '';
          return p.includes('name=' + pkg) && p.includes('version=1.0.0');
        });
      btn && btn.click();
    }, PKG);
    // install materializes the version's fns + refreshes the registry;
    // on the memory-constrained e2e stack that recompile can stall the
    // server for tens of seconds (see PERF note at the top of this file),
    // so give it generous headroom rather than flaking at 15s.
    await page.waitForFunction((pkg) => {
      const root = document.querySelector('.sidebar-packages [data-packages-panel]');
      const t = root && root.querySelector(':scope > .packages-panel-table');
      return t && [...t.querySelectorAll('tbody tr td:first-child')]
        .some((td) => td.textContent === pkg);
    }, PKG, {timeout: 60000, polling: 250});
    const c = await panelState(page);
    const cRow = c.installedRows.find((r) => r.name === PKG);
    assert(cRow && cRow.version === '1.0.0',
      'installed table shows ' + PKG + ' @ 1.0.0 after Install: ' + JSON.stringify(cRow));

    // ===================================================================
    // Phase D: Update 1.0.0 → 1.1.0 via the version input + ↑.
    // ===================================================================
    await page.evaluate((pkg) => {
      const root = document.querySelector('.sidebar-packages [data-packages-panel]');
      const tr = [...root.querySelectorAll(':scope > .packages-panel-table tbody tr')]
        .find((r) => r.querySelector('td')?.textContent === pkg);
      tr.querySelector('.packages-version-input').value = '1.1.0';
      tr.querySelector('.packages-update-btn').click();
    }, PKG);
    // update materializes the target version AND rewrites the project's
    // refs (a full binding/binding-list-item scan) THEN recompiles — the
    // heaviest panel op; on the constrained stack it can freeze the server
    // for the better part of a minute, so wait up to 90s.
    await page.waitForFunction((pkg) => {
      const root = document.querySelector('.sidebar-packages [data-packages-panel]');
      const t = root && root.querySelector(':scope > .packages-panel-table');
      return t && [...t.querySelectorAll('tbody tr')].some((tr) => {
        const td = [...tr.querySelectorAll('td')];
        return td[0]?.textContent === pkg && td[1]?.textContent === '1.1.0';
      });
    }, PKG, {timeout: 90000, polling: 250});
    const d = await panelState(page);
    const dRow = d.installedRows.find((r) => r.name === PKG);
    assert(dRow && dRow.version === '1.1.0',
      'installed row updated to 1.1.0 after ↑: ' + JSON.stringify(dRow));

    // ===================================================================
    // Phase E: Uninstall (× → confirm) → back to empty-state.
    // ===================================================================
    // Use page.click (not an evaluate .click) so Playwright drives the
    // hx-confirm dialog: a synchronous window.confirm() fired from inside
    // a page.evaluate deadlocks the page.on('dialog') auto-accept, and the
    // hx-delete never fires. There is exactly one installed pin here, so
    // the child-combinator selector is unambiguous.
    await page.click(
      '.sidebar-packages [data-packages-panel] > .packages-panel-table .packages-uninstall');
    // uninstall only drops the pin (no materialize/recompile), so it's
    // cheap — but the server may still be recovering from the update
    // recompile above, so keep a comfortable margin.
    await page.waitForFunction((pkg) => {
      const root = document.querySelector('.sidebar-packages [data-packages-panel]');
      if (!root) return false;
      const t = root.querySelector(':scope > .packages-panel-table');
      const stillThere = t && [...t.querySelectorAll('tbody tr td:first-child')]
        .some((td) => td.textContent === pkg);
      return !stillThere;
    }, PKG, {timeout: 45000, polling: 250});
    const e = await panelState(page);
    assert(!e.installedRows.some((r) => r.name === PKG),
      'installed table no longer lists ' + PKG + ' after uninstall');
    assert(e.emptyNotice,
      'the sole pin gone → empty-state notice returns');

    console.log('✓ packages panel verified — render / browse / install / update / uninstall');
  } catch (err) {
    process.exitCode = 1;
    console.error('✗ test failed:', err.message);
  } finally {
    await browser.close();
  }
})();
