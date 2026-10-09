// Lessons 35, 32 — finite HTTP and package distribution.
// Lesson25 has its own native file: edit-ui-components.test.js.
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// These are the tours that CARRY a `:requires` yet still run on this
// stack: it has an authenticated HTTP host, an open registry, and writable
// graph namespaces. The org tours (27-30, 33, 36, 37) need
// a tenancy addon and can only be checked as LOCKED — that assertion lives
// in edit-tutorial-tour-picker.test.js.
//
// Run from this directory:  node edit-tutorial-tour-platform.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {handlerPreviewTestOptions} = require('./handler-preview-test-options');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  finishAndDelete, tourWhere, openOperateSection, waitTourClosed,
} = require('./tutorial-tour-helpers');

const {trackPublications, walkLesson35} = require('./tutorial-http-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {...handlerPreviewTestOptions(), boot: false});
  // Uninstall and revert both confirm natively.
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-platform — lessons 35 / 32');
  const cleanupPublications = trackPublications(page);
  let failed = false;
  const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
  try {
    await hardCleanup(page);

    // ---------- lesson 35 — actual finite HTTP publication ----------
    await walkLesson35(page, BASE, finishAndDelete);
    console.log('  lesson 35: real public response + Stop + exact cleanup');

    // ---------- lesson 32 — publish / install / uninstall ----------
    await page.goto(BASE + '/?branch=main');
    await page.evaluate(() => window.openTutorialMenu());
    await page.locator('[data-lesson-id="32"] .gd-tour-btn-primary').click();
    await waitTourTitle(page, 'Sharing more than one fn', 150000);
    const packageBranch = await page.evaluate(() => {
      const state = _tourState;
      const branch = state.created.find(row => row.type === 'branch'
        && row.id === state.sandboxBranchId && row.name === state.sandboxBranch
        && row.receipt === 'created');
      return branch && _tourPrincipalMatches(state) ? {id: branch.id, name: branch.name,
        active: _tourSessionBranch() === branch.name} : null;
    });
    assert(packageBranch?.id && packageBranch.active,
      'lesson 32 chooser created its exact owned active sandbox');
    assert(await clickTourButton(page, 'Next'), 'lesson 32 Next');
    await waitTourTitle(page, 'A namespace to publish');
    await createRootNamespace(page, 'mycorp');
    await waitTourTitle(page, 'Put a function in it', 150000);
    await createFnInNamespace(page, 'mycorp', 'greet');
    await waitTourTitle(page, 'Give it a parent', 150000);
    await setParentViaStrip(page, 'const');
    await waitTourTitle(page, 'Publish the namespace', 150000);
    await page.evaluate(() => {
      document.querySelector('.ns-header[data-ns-path="mycorp"] .ns-publish-btn').click();
    });
    await page.waitForSelector('#gd-nspub-name', {timeout: 15000});
    // The tour popover overlays the dialog — click through the DOM, the way
    // the other guards do, instead of Playwright's hit-testing click.
    await page.evaluate(() => {
      const set = (id, v) => {
        const el = document.getElementById(id);
        el.value = v;
        el.dispatchEvent(new Event('input', {bubbles: true}));
      };
      set('gd-nspub-name', 'mycorp-hello');
      set('gd-nspub-version', '1.0.0');
      document.getElementById('gd-nspub-go').click();
    });
    await page.waitForSelector('#gd-nspub-result.packages-fork-ok', {timeout: 30000});
    await waitTourTitle(page, 'Install your own package', 150000);
    // Close the publish dialog, then install through the packages chip.
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('#gd-nspub-pop'),
      null, {timeout: 10000, polling: 200});
    await page.waitForSelector('#gd-pkg-chip', {timeout: 15000});
    // The panel renders its registry list once per open — a panel opened
    // before the publish shows the pre-publish list, so reopen until the row
    // is there rather than polling a stale render.
    let listed = false;
    for (let attempt = 0; attempt < 4 && !listed; attempt++) {
      await page.evaluate(() => document.querySelector('#gd-pkg-chip').click());
      await page.waitForSelector('[data-packages-panel]', {timeout: 20000});
      listed = await page.waitForFunction(() => {
        return Array.from(
          document.querySelectorAll('[data-packages-panel] .packages-install-btn'))
          .some((b) => (b.closest('tr, li, div')?.textContent || '').includes('mycorp-hello'));
      }, null, {timeout: 8000, polling: 300}).then(() => true).catch(() => false);
      if (!listed) await page.keyboard.press('Escape');
    }
    assert(listed, 'the published package is listed under "+ Install a package"');
    // Diagnostics for the install swap (gate 18, 2026-09-03: the pin never
    // showed after Install, 5/5 attempts, server idle): record what the
    // panel-install response actually carried, and say so on failure.
    const installResp = {};
    const pkgRequests = [];
    const onInstallResp = async (r) => {
      if (!r.url().includes('/api/packages/panel-install')) return;
      try {
        const t = await r.text();
        Object.assign(installResp, {status: r.status(), len: t.length,
          uninstall: t.includes('packages-uninstall'), empty: t.includes('No add-on packages')});
      } catch (e) { installResp.err = String(e).slice(0, 120); }
    };
    const onPkgRequest = (rq) => {
      if (rq.url().includes('/api/packages/')) pkgRequests.push(rq.method() + ' ' + rq.url().replace(/^https?:\/\/[^/]+/, ''));
    };
    page.on('response', onInstallResp);
    page.on('request', onPkgRequest);
    // Diagnostics: WHICH button the selector picked, and whether htmx owns it.
    const clicked = await page.evaluate(() => {
      const all = Array.from(
        document.querySelectorAll('[data-packages-panel] .packages-install-btn'));
      const btn = all.find((b) => (b.closest('tr')?.textContent || '').includes('mycorp-hello'));
      const where = (el) => {
        const ids = [];
        for (let e = el; e; e = e.parentElement) {
          if (e.id) ids.push('#' + e.id);
          else if (e.dataset && e.dataset.section) ids.push('[section=' + e.dataset.section + ']');
        }
        return ids.join(' < ');
      };
      const info = {
        candidates: all.length,
        panels: Array.from(document.querySelectorAll('[data-packages-panel]')).map(where),
        picked: btn ? btn.outerHTML.slice(0, 120) : null,
        pickedIn: btn ? where(btn) : null,
        popOpen: !!document.getElementById('gd-pkg-pop'),
        htmxOwned: !!(btn && btn['htmx-internal-data']),
        inClosedDetails: !!(btn && btn.closest('details:not([open])')),
        hidden: !!(btn && btn.closest('[hidden]')),
      };
      if (btn) btn.click();
      return info;
    });
    console.log('  install click diag: ' + JSON.stringify(clicked));
    try {
      await page.waitForSelector('[data-packages-panel] .packages-uninstall', {timeout: 30000});
    } catch (e) {
      const panel = await page.evaluate(() => ({
        panels: document.querySelectorAll('[data-packages-panel]').length,
        text: (document.querySelector('[data-packages-panel]')?.innerText || '').replace(/\s+/g, ' ').slice(0, 240),
      }));
      const pins = await api(page, 'GET', '/api/packages/installed', undefined,
        {'X-Graphden-Branch': packageBranch.id});
      console.log('  INSTALL DIAG response=' + JSON.stringify(installResp)
        + ' clicked=' + JSON.stringify(clicked)
        + ' requests=' + JSON.stringify(pkgRequests)
        + ' panel=' + JSON.stringify(panel) + ' pins=' + JSON.stringify(pins).slice(0, 200));
      throw e;
    } finally {
      page.off('response', onInstallResp);
      page.off('request', onPkgRequest);
    }
    await waitTourTitle(page, 'A pin, not a copy', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 32 pin Next');
    await waitTourTitle(page, 'Uninstall');
    await page.evaluate(() => {
      document.querySelector('[data-packages-panel] .packages-uninstall').click();
    });
    await page.waitForFunction(
      () => !document.querySelector('[data-packages-panel] .packages-uninstall'),
      null, {timeout: 30000, polling: 300});
    await waitTourTitle(page, "That's distribution", 150000);
    await finishAndDelete(page);
    console.log('  lesson 32: walked + cleaned (published, pinned, unpinned)');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-platform-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-platform-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    try { await cleanupPublications(); }
    catch (error) { failed = true; console.error(error); }
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
