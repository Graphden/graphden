// Lessons 31, 28, 21 — services, package distribution, asset overrides.
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// These three are the tours that CARRY a `:requires` yet still run on this
// stack: it is single-tenant, so services are manageable, the registry is
// open, and the Assets panel exists. The org tours (16-21) need a tenancy
// addon and can only be checked as LOCKED — that assertion lives in
// edit-tutorial-tour-ops.test.js.
//
// Run from this directory:  node edit-tutorial-tour-platform.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect,
  extendViaRowActions, createRootNamespace, createFnInNamespace,
  setParentViaStrip, finishAndDelete, tourTitle, bindFirstPlaceholder,
  bindFnRefPlaceholder,
  openOperateSection,
} = require('./tutorial-tour-helpers');

const ASSET_PATH = 'packages/app/editor/editor-styles.css';
const ASSET_MARKER = '/* tour-25-guard */';

// Belt-and-braces: the lesson reverts the override through the UI, but a
// failure mid-walk would leave the row behind and every later page load on
// this stack would serve it.
async function revertAssetViaApi(page, base) {
  await page.evaluate(async ({b, p}) => {
    await window.authFetch(b + '/api/assets/revert?path=' + encodeURIComponent(p),
                           {method: 'DELETE'}).catch(() => {});
  }, {b: base, p: ASSET_PATH}).catch(() => {});
}

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  // Uninstall and revert both confirm natively.
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-platform — lessons 31 / 28 / 21');
  let failed = false;
  const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
  try {
    await hardCleanup(page);

    // ---------- lesson 32 — services (the row, not a deploy) ----------
    await page.goto(BASE + '/?tutorial=32');
    await waitTourTitle(page, 'Fns that keep running', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 32 Next');
    await waitTourTitle(page, 'A thunk to run');
    await filterAndSelect(page, 'const', 'const');
    await extendViaRowActions(page, 'tutorial-tick', 'const');
    await waitTourTitle(page, 'Give it something to return', 150000);
    await filterAndSelect(page, 'tutorial-tick', 'tutorial-tick');
    // `:any` slots parse the literal as JSON — a bare word is rejected.
    await bindFirstPlaceholder(page, '"tick"');
    await waitTourTitle(page, 'Wrap it in a future', 150000);
    await filterAndSelect(page, 'future', 'future');
    await extendViaRowActions(page, 'tutorial-daemon', 'future');
    await waitTourTitle(page, 'Point it at the thunk', 150000);
    await filterAndSelect(page, 'tutorial-daemon', 'tutorial-daemon');
    await bindFnRefPlaceholder(page, 'tutorial-tick');
    // The step gates on SELECTION, not on a button — the bind already leaves
    // the daemon selected, so this only waits for the gate to clear.
    await waitTourTitle(page, 'tutorial-daemon is open', 150000);
    await filterAndSelect(page, 'tutorial-daemon', 'tutorial-daemon');
    await waitTourTitle(page, 'Open the service settings', 150000);
    // ⋯ → ⚙. The gear is server-rendered in the row-actions partial, and a
    // blocked one is aria-disabled — NOT `button.disabled` — so a plain
    // `.click()` on it silently does nothing. Assert the enabled shape.
    await page.waitForSelector('button.more-actions-trigger', {timeout: 30000});
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    const gear = await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '⚙');
      if (!btn) return {found: false};
      const blocked = btn.disabled || btn.getAttribute('aria-disabled') === 'true'
        || btn.className.includes('action-icon-disabled');
      if (!blocked) btn.click();
      return {found: true, blocked, title: btn.getAttribute('title')};
    });
    assert(gear.found, 'the ⚙ button is in the row-actions popover');
    assert(!gear.blocked,
      'the ⚙ is enabled once the free arg is bound (got: ' + gear.title + ')');
    await page.waitForSelector('.service-popover', {timeout: 20000});
    await waitTourTitle(page, 'Create it — switched OFF', 150000);
    // The lesson is explicit: create it DISABLED. A fn with no :process
    // work would otherwise be started by the reconciler on this stack.
    await page.waitForSelector('.service-popover-enabled', {timeout: 15000});
    await page.evaluate(() => {
      const box = document.querySelector('.service-popover-enabled');
      if (box.checked) box.click();
    });
    const enabledOff = await page.$eval('.service-popover-enabled', (b) => !b.checked);
    assert(enabledOff, 'the Enabled box is unticked before creating');
    await page.evaluate(() => document.querySelector('.service-popover-save-btn').click());
    // The popover CLOSES on a successful write (the node stays in the DOM,
    // hidden, with its stale "Saving…" label) — so the row is confirmed
    // through the API, and Delete needs the gear opened again.
    await page.waitForFunction(() => {
      const el = document.querySelector('.service-popover');
      return !el || el.style.display === 'none';
    }, null, {timeout: 20000, polling: 200});
    const created = await page.evaluate(async () => {
      const d = await (await window.authFetch('/api/services')).json();
      return (d.services || []).some((s) => s['fn-name'] === 'tutorial-daemon');
    });
    assert(created, 'the :service row exists after Create service');
    assert(await clickTourButton(page, 'Next'), 'lesson 32 created Next');
    await waitTourTitle(page, 'What the row means', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 32 row-means Next');
    await waitTourTitle(page, 'Remove it');
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '⚙').click();
    });
    await page.waitForSelector('.service-popover-delete-btn', {timeout: 20000});
    await page.evaluate(() => document.querySelector('.service-popover-delete-btn').click());
    await page.waitForFunction(async () => {
      const d = await (await window.authFetch('/api/services')).json();
      return !(d.services || []).some((s) => s['fn-name'] === 'tutorial-daemon');
    }, null, {timeout: 20000, polling: 500});
    assert(await clickTourButton(page, 'Next'), 'lesson 32 removed Next');
    await waitTourTitle(page, "That's supervision", 150000);
    await finishAndDelete(page);
    console.log('  lesson 32: walked + cleaned (service row created, then deleted)');

    // ---------- lesson 29 — publish / install / uninstall ----------
    await page.goto(BASE + '/?tutorial=29');
    await waitTourTitle(page, 'Sharing more than one fn', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 29 Next');
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
      const pins = await api(page, 'GET', '/api/packages/installed');
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
    assert(await clickTourButton(page, 'Next'), 'lesson 29 pin Next');
    await waitTourTitle(page, 'Uninstall');
    await page.evaluate(() => {
      document.querySelector('[data-packages-panel] .packages-uninstall').click();
    });
    await page.waitForFunction(
      () => !document.querySelector('[data-packages-panel] .packages-uninstall'),
      null, {timeout: 30000, polling: 300});
    await waitTourTitle(page, "That's distribution", 150000);
    await finishAndDelete(page);
    console.log('  lesson 29: walked + cleaned (published, pinned, unpinned)');

    // ---------- lesson 22 — editing the editor's own assets ----------
    await page.goto(BASE + '/?tutorial=22');
    await waitTourTitle(page, 'The editor is served from the graph too', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 22 Next');
    await waitTourTitle(page, 'Open Assets');
    // Through the account menu, with the retry the nav's re-render needs —
    // the same helper five other lessons' walks use. The old one-shot
    // `gdShellSurface('operate')` + single click raced at a phone viewport.
    await openOperateSection(page, 'assets');
    await waitTourTitle(page, 'Open the stylesheet', 150000);
    await page.waitForSelector('[data-section="assets"] .gd-asset-row', {timeout: 20000});
    await page.evaluate((path) => {
      const row = Array.from(document.querySelectorAll('[data-section="assets"] .gd-asset-row'))
        .find((r) => r.textContent.includes(path));
      row.querySelector('.gd-asset-edit-btn').click();
    }, ASSET_PATH);
    await waitTourTitle(page, 'Change something you will notice', 150000);
    // The textarea is CodeMirror-enhanced — write through the gdCode seam so
    // the view and the serialized value stay in sync (see edit-asset-override).
    await page.waitForFunction(() => {
      const t = document.querySelector('#gd-asset-editor textarea[name="content"]');
      return t && t.value.includes('--gd-');
    }, null, {timeout: 20000, polling: 200});
    await page.$eval('#gd-asset-editor textarea[name="content"]',
      (t, marker) => { window.gdCode.set(t, window.gdCode.get(t) + '\n' + marker + '\n'); },
      ASSET_MARKER);
    await page.evaluate(() => document.querySelector('#gd-asset-editor .gd-asset-save-btn').click());
    await page.waitForSelector('.gd-asset-chip-override', {timeout: 30000});
    await waitTourTitle(page, 'Reload to run it', 150000);
    // The step exists because the running page still holds the OLD bundle —
    // and the tour has to survive the reload it asks for.
    await page.reload({waitUntil: 'networkidle'});
    await waitTourTitle(page, 'Reload to run it', 60000);
    const rolled = await page.evaluate(async () => {
      const href = document.querySelector('link[href*="editor.css"]').getAttribute('href');
      const baked = (await (await fetch('/version')).json()).frontend.slice(0, 12);
      return !href.includes(baked);
    });
    assert(rolled, 'the reloaded shell links the rolled ?v= (override in effect)');
    assert(await clickTourButton(page, 'Next'), 'lesson 22 reload Next');
    await waitTourTitle(page, 'See exactly what you changed', 150000);
    // After the reload the shell remounts: wait for the panel's rows, not
    // just the nav click, before hunting for the override row.
    await page.evaluate(() => {
      gdShellSurface('operate');
      document.querySelector('#gd-operate-nav button[data-section="assets"]')?.click();
    });
    await page.waitForSelector('[data-section="assets"] .gd-asset-row', {timeout: 30000});
    await page.waitForSelector('.gd-asset-chip-override', {timeout: 20000});
    await page.evaluate(() => {
      document.querySelector('.gd-asset-chip-override')
        .closest('.gd-asset-row').querySelector('.gd-asset-edit-btn').click();
    });
    await page.waitForSelector('#gd-asset-editor .gd-asset-diff-btn', {timeout: 30000});
    await page.evaluate(() => document.querySelector('#gd-asset-editor .gd-asset-diff-btn').click());
    // CodeMirror mounts only the visible lines, so the marker — the last line
    // of a 4000-line file — proves the merge view opened AT the change.
    await page.waitForFunction((marker) => {
      const pane = document.querySelector('#gd-asset-editor .gd-asset-diff');
      return !!pane && pane.textContent.includes(marker);
    }, ASSET_MARKER, {timeout: 20000});
    assert(await clickTourButton(page, 'Next'), 'lesson 22 diff Next');
    await waitTourTitle(page, 'Put it back', 150000);
    await page.waitForSelector('#gd-asset-editor .gd-asset-revert-btn', {timeout: 20000});
    await page.evaluate(() => document.querySelector('#gd-asset-editor .gd-asset-revert-btn').click());
    await page.waitForFunction(() => !document.querySelector('.gd-asset-chip-override'),
      null, {timeout: 30000, polling: 300});
    await waitTourTitle(page, 'What this is for', 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 22 Finish');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 20000, polling: 200});
    // The lesson creates no graph entities, so there is no cleanup prompt —
    // the only trace it could leave is the override row, and that is reverted.
    const overrideGone = await page.evaluate(async () => {
      const r = await fetch('/version');
      const baked = (await r.json()).frontend.slice(0, 12);
      const href = document.querySelector('link[href*="editor.css"]').getAttribute('href');
      return {baked, href};
    });
    console.log('  lesson 22: walked + reverted (' + overrideGone.href + ')');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour title at failure:', await tourTitle(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-platform-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-platform-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await revertAssetViaApi(page, BASE);
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
