// Every covering surface owes the reader a VISIBLE way out.
//
// Escape closes everything in this editor and always has — but Escape is not
// on screen. The bug this pins: the Marketplace surface shipped (2026-09-08)
// with neither. It was missing the shared management-surface CSS, so instead
// of covering Build it rendered INLINE inside the explorer column, on top of
// an inert editor; and no surface had a close control at all, so the only
// exits were the Escape key and the brand logo (which does not read as one).
//
// What this pins:
//   A. Every management surface (enumerated FROM THE DOM, so a new one is
//      covered automatically) actually covers #main-container, and the shared
//      `#gd-surface-exit` control is visible and returns to Build.
//   B. The popovers that TRAP Tab expose a close button — tabbing out is
//      exactly what the trap prevents, so Escape cannot be the only way for a
//      keyboard user, and a pointer user must not have to guess that clicking
//      the thing behind it works.
//
// Read-only: opens and closes chrome, writes nothing.
//
// Run:  node edit-overlay-close.test.js

const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

const PROBE_FN = 'web-server';

// A close control counts only if a user can actually see and hit it.
// Installed as a page global (before any document loads) so each in-page
// probe below can call it without shipping the source through evaluate.
const VISIBLE_PROBE = () => {
  window.__gdVisible = (el) => {
    if (!el) return false;
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0
      && r.top >= 0 && r.left >= 0
      && r.bottom <= window.innerHeight && r.right <= window.innerWidth;
  };
};

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  console.log('edit-overlay-close — every cover has a visible way out');

  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  try {
    await page.addInitScript(VISIBLE_PROBE);
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.waitForSelector('#entity-list', {timeout: 20000});
    await page.waitForFunction(() => typeof window.gdShellSurface === 'function',
                               null, {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A — management surfaces
    // ===================================================================
    // Enumerated from the DOM rather than hard-coded: the failure mode
    // being pinned is "a surface was ADDED and missed the shared chrome",
    // and a hard-coded list would miss the next one exactly the same way.
    const surfaces = await page.evaluate(() => Array.from(
      document.querySelectorAll('#main-container > section[id^="gd-"]'),
    ).map((s) => s.id));

    assert(surfaces.length >= 3,
           'found the management surfaces in the DOM (' + surfaces.join(', ') + ')');

    for (const id of surfaces) {
      // The shell's surface name is the id without the `gd-` prefix
      // (gd-operate → operate, gd-market → market).
      const name = id.replace(/^gd-/, '');
      const shown = await page.evaluate((args) => {
        window.gdShellSurface(args.name);
        const el = document.getElementById(args.id);
        const main = document.getElementById('main-container');
        const r = el.getBoundingClientRect();
        const m = main.getBoundingClientRect();
        const btn = document.getElementById('gd-surface-exit');
        return {
          hidden: !!el.hidden,
          // A surface is a COVER: it fills #main-container. Marketplace
          // failed exactly here — 280px wide, parked under the explorer.
          covers: Math.abs(r.width - m.width) <= 1 && Math.abs(r.height - m.height) <= 1,
          width: Math.round(r.width),
          mainWidth: Math.round(m.width),
          surfaceAttr: document.body.getAttribute('data-surface'),
          exitVisible: window.__gdVisible(btn),
          exitLabel: btn ? btn.getAttribute('aria-label') : null,
        };
      }, {name, id});

      assert(!shown.hidden, name + ': the surface is shown');
      assert(shown.surfaceAttr === name, name + ': body data-surface says "' + name + '"');
      assert(shown.covers,
             name + ': covers #main-container (' + shown.width + 'px of ' + shown.mainWidth + 'px)');
      assert(shown.exitVisible, name + ': a visible close control is on screen');
      assert(/close/i.test(shown.exitLabel || ''),
             name + ': the control names itself a close ("' + shown.exitLabel + '")');

      // And it works: clicking it goes back to Build and takes the cover away.
      await page.click('#gd-surface-exit');
      const back = await page.evaluate((sid) => ({
        surface: document.body.getAttribute('data-surface'),
        hidden: !!document.getElementById(sid).hidden,
        exitGone: getComputedStyle(document.getElementById('gd-surface-exit')).display === 'none',
      }), id);
      assert(back.surface === 'build' && back.hidden,
             name + ': the close control returns to Build and hides the surface');
      assert(back.exitGone, name + ': the close control retires with the surface');
    }

    // ===================================================================
    // Phase B — Tab-trapping popovers expose a close button
    // ===================================================================
    // The `[data-gd-pop-x]` marker is what `ensurePopoverClose`
    // (web/runtime/graphden-popover.js) stamps on the shared × it builds.
    assert(await page.evaluate(() => typeof ensurePopoverClose === 'function'),
           'the shared popover-close helper is in the bundle');

    // ── fn-version history (⌛ on a fn row) ────────────────────────────
    const versions = await page.evaluate(async () => {
      const r = await window.authFetch('/api/graph/entities?scope=index');
      const pool = (await r.json()).fns || [];
      const ent = pool.find((f) => f.name === 'web-server') || pool[0];
      if (!ent) return {skip: true};
      await showFnVersionsPopover(ent, document.getElementById('gd-brand-home'));
      const el = document.getElementById('fn-versions-popover');
      const x = el ? el.querySelector(':scope > [data-gd-pop-x]') : null;
      const seen = window.__gdVisible(x);
      if (x) x.click();
      return {seen, closed: !!(el && el.classList.contains('hidden'))};
    });

    assert(!versions.skip, 'version history: there is a fn to open it on');
    assert(versions.seen, 'version history: a visible × (its ⌛ trigger is gone by then)');
    assert(versions.closed, 'version history: the × closes it');

    // ── namespace picker ──────────────────────────────────────────────
    // It renders from the in-memory namespace cache and no-ops until that
    // has landed, so wait for the cache rather than for a wall-clock guess.
    // (`graphData` is a script-scope binding, not a window property — a
    // `window.graphData` probe reads undefined forever.)
    await page.waitForFunction(
      () => typeof graphData !== 'undefined' && !!graphData
        && Array.isArray(graphData.namespaces),
      null, {timeout: 20000, polling: 100});
    const nsPicker = await page.evaluate(() => {
      let cancelled = false;
      openNamespacePicker({
        anchorEl: document.getElementById('gd-brand-home'),
        onPick: () => {},
        onCancel: () => { cancelled = true; },
      });
      const el = document.querySelector('.fn-picker-popover');
      const x = el ? el.querySelector(':scope > [data-gd-pop-x]') : null;
      const seen = window.__gdVisible(x);
      if (x) x.click();
      // The picker is REMOVED on close, not hidden.
      return {seen, closed: !document.querySelector('.fn-picker-popover'), cancelled};
    });

    assert(nsPicker.seen, 'namespace picker: a visible ×');
    assert(nsPicker.closed, 'namespace picker: the × closes it');
    assert(nsPicker.cancelled,
           'namespace picker: the × cancels the FLOW, not just the popover — '
           + 'the caller has an edit strip waiting on onCancel');

    // ── smart views ───────────────────────────────────────────────────
    const views = await page.evaluate(() => {
      if (typeof gdOpenSmartViewsPop !== 'function') return {skip: true};
      gdOpenSmartViewsPop(document.getElementById('gd-brand-home'));
      const el = document.querySelector('.gd-views-pop');
      const x = el ? el.querySelector(':scope > [data-gd-pop-x]') : null;
      const seen = window.__gdVisible(x);
      if (x) x.click();
      return {seen, closed: !document.querySelector('.gd-views-pop')};
    });

    assert(!views.skip, 'smart views: the popover API is in the bundle');
    assert(views.seen, 'smart views: a visible ×');
    assert(views.closed, 'smart views: the × closes it');

    // ===================================================================
    // Phase C — the scrim-backed `.gd-pop` PANELS
    // ===================================================================
    // Their scrim is a transparent click-catcher, not a dimmed backdrop, so
    // "click outside" is invisible too. The titled panels carry a × (the
    // plain menus — branch policy, protection, the diff chip — do not, and
    // are deliberately not listed here).
    const panels = [
      {label: 'workspace picker', chip: '#gd-ws-chip', pop: '#gd-ws-pop'},
      {label: 'packages panel', chip: '#gd-pkg-chip', pop: '#gd-pkg-pop'},
    ];
    for (const panel of panels) {
      const chipUp = await page.evaluate(
        (sel) => window.__gdVisible(document.querySelector(sel)), panel.chip);
      // The packages chip only appears when the OPTIONAL registry package is
      // installed — no chip, nothing to assert about its panel.
      if (!chipUp) {
        console.log('  · ' + panel.label + ': chip absent on this build — skipped');
        continue;
      }
      await page.click(panel.chip);
      const got = await page.evaluate((sel) => {
        const el = document.querySelector(sel);
        const x = el ? el.querySelector(':scope > [data-gd-pop-x]') : null;
        return {open: !!el, seen: window.__gdVisible(x)};
      }, panel.pop);
      assert(got.open, panel.label + ': the panel opened');
      assert(got.seen, panel.label + ': a visible × (its scrim is transparent)');
      await page.click(panel.pop + ' > [data-gd-pop-x]');
      assert(await page.evaluate((sel) => !document.querySelector(sel), panel.pop),
             panel.label + ': the × closes it');
    }

    // Publish-namespace: a FORM whose only other button is "Publish".
    const nsPublish = await page.evaluate(() => {
      if (typeof window.openNsPublishPopover !== 'function') return {skip: true};
      window.openNsPublishPopover(document.getElementById('gd-brand-home'), 'app.server');
      const el = document.getElementById('gd-nspub-pop');
      const x = el ? el.querySelector(':scope > [data-gd-pop-x]') : null;
      const seen = window.__gdVisible(x);
      if (x) x.click();
      return {seen, closed: !document.getElementById('gd-nspub-pop')};
    });
    assert(!nsPublish.skip, 'publish form: the popover API is in the bundle');
    assert(nsPublish.seen,
           'publish form: a visible × — its only other button submits');
    assert(nsPublish.closed, 'publish form: the × closes it');

    assert(pageErrors.length === 0,
           'no page errors (' + (pageErrors[0] || 'none') + ')');

    console.log('edit-overlay-close — PASS');
  } catch (err) {
    console.error('  ✗ ' + (err?.message || err));
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
