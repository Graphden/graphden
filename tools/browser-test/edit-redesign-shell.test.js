// edit-redesign-shell — e2e coverage for the 2026-08 editor redesign shell.
//
// Exercises the NEW surfaces so the redesign is regression-covered:
//   1. Surface switching — the account chip's menu opens Organization; the
//      brand button returns to Build (the rail is retired, 2026-08-15).
//   2. Inspector on selection  — selecting a fn fills the right inspector
//                                (name + resolved effects), no empty state.
//   3. Workspace switcher      — scoping to a namespace root filters the
//                                explorer to that subtree.
//   4. Details toggle          — reveals / hides the card metadata strips.
//
// Run from this directory:  node edit-redesign-shell.test.js
// Points at GRAPHDEN_URL (default http://localhost:9002).
const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  console.log('edit-redesign-shell — rail / inspector / workspace / details toggle');

  try {
    await page.goto(BASE + '/#web-server');
    // Shell + a rendered graph + the inspector are all up.
    await page.waitForSelector('#gd-brand-home', {timeout: 30000});
    await page.waitForFunction(
      () => !!document.querySelector('.node-overlay')
            && !!document.querySelector('#gd-inspector .gd-insp-name'),
      null, {timeout: 30000, polling: 100});
    // The Overview tab is a server partial now (/partials/inspector-overview)
    // — wait for its swap before reading the effects chips.
    await page.waitForFunction(
      () => !!document.querySelector('#gd-insp-overview .effects-chip'),
      null, {timeout: 15000, polling: 100});

    // --- 1. Inspector reflects the selected fn ---
    const insp = await page.evaluate(() => ({
      name: (document.querySelector('#gd-inspector .gd-insp-name') || {}).textContent,
      hasEmpty: !!document.querySelector('#gd-inspector .gd-insp-empty'),
      effects: document.querySelectorAll('#gd-inspector .effects-chip').length,
    }));
    assert(insp.name === 'web-server', 'inspector shows the selected fn name (got ' + insp.name + ')');
    assert(!insp.hasEmpty, 'inspector left the empty state once a fn is selected');
    assert(insp.effects > 0, 'inspector lists this fn’s effects (got ' + insp.effects + ')');

    // --- 2. Menu: Organization reveals the ops panels; brand → Build ---
    // Surface ENTRY is the account chip's menu now (rail retired); the way
    // back is the brand button. Packages split by intent (spec §1/§4):
    // INSTALL browse lives on the Explorer context chip (#gd-pkg-chip);
    // Organization hosts the read-mostly GOVERNANCE view.
    await page.click('#auth-lock-btn');
    await page.waitForSelector('#auth-popover .auth-menu-item', {timeout: 5000});
    await page.click('#auth-popover .auth-menu-item:text-is("Organization")');
    await page.waitForSelector('#gd-operate:not([hidden])', {timeout: 5000});
    const opVisible = await page.evaluate(
      () => !!document.querySelector('#gd-operate-panels .sidebar-packages-governance'));
    assert(opVisible, 'Organization surface hosts the packages GOVERNANCE panel');
    const surfaceHash = await page.evaluate(() => location.hash);
    assert(surfaceHash === '#@organization',
           'surface deep-link hash set (got ' + surfaceHash + ')');
    await page.click('#gd-brand-home');
    const opHidden = await page.evaluate(
      () => document.getElementById('gd-operate').hidden === true);
    assert(opHidden, 'brand button returns to Build and hides Organization');

    // --- 2b. Settings is a nav+pane surface (sections, not a card grid) ---
    await page.evaluate(() => window.gdShellSurface('settings'));
    await page.waitForSelector('#gd-settings:not([hidden])', {timeout: 5000});
    const setInitial = await page.evaluate(() => {
      const pane = (k) => document.querySelector('#gd-settings-panels [data-section="' + k + '"]');
      return {
        appearanceShown: !pane('appearance').hidden,
        accessHidden: pane('access').hidden,
        navCurrent: document.querySelector('#gd-settings-nav [aria-current="page"]')?.dataset.section,
      };
    });
    assert(setInitial.appearanceShown, 'Settings opens on the Appearance section');
    assert(setInitial.accessHidden, 'the other sections start hidden');
    assert(setInitial.navCurrent === 'appearance',
           'the nav marks Appearance current (got ' + setInitial.navCurrent + ')');
    await page.click('#gd-settings-nav [data-section="access"]');
    const setSwitched = await page.evaluate(() => {
      const pane = (k) => document.querySelector('#gd-settings-panels [data-section="' + k + '"]');
      return {
        accessShown: !pane('access').hidden,
        appearanceHidden: pane('appearance').hidden,
        navCurrent: document.querySelector('#gd-settings-nav [aria-current="page"]')?.dataset.section,
      };
    });
    assert(setSwitched.accessShown && setSwitched.appearanceHidden,
           'clicking a nav entry swaps the visible pane');
    assert(setSwitched.navCurrent === 'access', 'and moves aria-current');
    // Deep link `@settings/<section>` selects that section.
    await page.evaluate(() => window.gdRouteSurfaceHash('@settings/build'));
    const deepLinked = await page.evaluate(() =>
      !document.querySelector('#gd-settings-panels [data-section="build"]').hidden);
    assert(deepLinked, '@settings/build deep-links the About-this-build section');
    await page.evaluate(() => window.gdShellSurface('build'));

    // --- 3. A namespace filter scopes the explorer to a root ---
    // Through the "+ filter" menu (the namespaces checklist), like a reader.
    await page.click('#gd-filter-add');
    await page.waitForSelector('.gd-filter-add-pop .gd-ws-opt[data-ws]', {timeout: 5000});
    // Pick a top-level namespace that is NOT the selected fn's (app), so the
    // filter visibly drops the others.
    const picked = await page.evaluate(() => {
      const items = [...document.querySelectorAll('.gd-filter-add-pop .gd-ws-opt[data-ws]')]
        .map((i) => i.getAttribute('data-ws')).filter(Boolean);
      return items.find((n) => n !== 'app') || items[0];
    });
    assert(picked, 'the add-filter menu lists namespace roots');
    await page.click('.gd-filter-add-pop .gd-ws-opt[data-ws="' + picked + '"]');
    await page.waitForFunction(() => {
      const b = document.querySelector('#gd-ws-chip b');
      return b && b.textContent === '1 filter';
    }, null, {timeout: 5000});
    const scoped = await page.evaluate(() => {
      const headers = [...document.querySelectorAll('#entity-list [data-ns-path]')]
        .filter((h) => !h.hidden)
        .map((h) => h.getAttribute('data-ns-path'));
      // Every top-level namespace header shown must be the picked root (or its
      // descendant); "app" (the other root) must be gone.
      const topLevel = headers.filter((p) => p && p.indexOf('.') === -1);
      return { chip: (document.querySelector('#gd-ws-chip b') || {}).textContent,
               chips: [...document.querySelectorAll('#gd-filter-chips .kind-label')].map((e) => e.textContent),
               topLevel, appGone: !topLevel.includes('app') };
    });
    assert(scoped.chip === '1 filter', 'the view chip counts the filter (' + scoped.chip + ')');
    assert(scoped.chips.join() === 'in ' + picked, 'an "in <root>" chip appeared (' + scoped.chips.join() + ')');
    assert(scoped.appGone, 'scoping hid the out-of-scope "app" namespace');

    // Namespaces OR within the axis: ticking "app" as a second root (the
    // menu stays open — a multi-select) brings it back under the scope.
    await page.click('.gd-filter-add-pop .gd-ws-opt[data-ws="app"]');
    const appBack = await page.evaluate(() => {
      const top = [...document.querySelectorAll('#entity-list [data-ns-path]')]
        .filter((h) => !h.hidden)
        .map((h) => h.getAttribute('data-ns-path'))
        .filter((p) => p && p.indexOf('.') === -1);
      return { back: top.includes('app'), chip: document.querySelector('#gd-ws-chip b').textContent };
    });
    assert(appBack.back, 'ticking "app" as a second root shows it under the scope');
    assert(appBack.chip === '2 filters', 'the chip counts both (' + appBack.chip + ')');
    // Close the menu (Escape) and clear — the × on a chip, then ◍ all.
    await page.keyboard.press('Escape');
    await page.click('#gd-filter-chips .gd-filter-chip');
    assert(await page.evaluate(() => document.querySelectorAll('#gd-filter-chips .gd-filter-chip').length === 1),
      'a chip\'s × removes that one filter');
    await page.evaluate(() => toggleKind('all'));
    assert(await page.evaluate(() => document.querySelector('#gd-ws-chip b').textContent === 'All functions'),
      '◍ all clears the rest');

    // --- 4. Details toggle reveals / hides the card metadata strips ---
    // The test env opts into full cards, so a strip is present + visible now.
    const beforeToggle = await page.evaluate(() => {
      const s = document.querySelector('.effects-strip');
      return { present: !!s, visible: !!(s && s.offsetParent !== null) };
    });
    assert(beforeToggle.present, 'an effects strip exists on the canvas');
    assert(beforeToggle.visible, 'effects strip is visible with details on');
    await page.click('.nav-btn[aria-label="Show card details"]');
    const afterToggle = await page.evaluate(() => {
      const s = document.querySelector('.effects-strip');
      return { compactClass: document.body.classList.contains('gd-cards-compact'),
               visible: !!(s && s.offsetParent !== null) };
    });
    assert(afterToggle.compactClass, 'Details toggle turned on compact mode');
    assert(!afterToggle.visible, 'compact mode hides the effects strip');

    console.log('redesign-shell — PASS');
  } catch (e) {
    console.error('✗ test failed:', e.message);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
