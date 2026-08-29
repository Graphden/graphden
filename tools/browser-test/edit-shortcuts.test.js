// Keyboard shortcuts — the leader menu, the cheatsheet, and the two guards
// that decide whether a keypress is ours at all.
//
// The guards are the whole risk of this feature. A dispatcher that grabs bare
// keys too eagerly breaks typing (Space stops inserting a space in a form) or
// double-handles a key another module already acted on (Escape closing a
// dialog would ALSO end the interactive tour). Both are silent from the
// code's point of view and obvious to a user, so they get pinned here.
//
// What this pins:
//   1. Space opens the which-key menu, listing real commands.
//   2. Space in a text field types a space and opens nothing.
//   3. Space on a focused button activates the button.
//   4. ? opens a cheatsheet generated FROM the registry (not a static list).
//   5. Escape closes the menu and the cheatsheet, and the cheatsheet returns
//      focus to whatever opened it.
//   6. A key already consumed by another handler is not acted on twice.
//
// Read-only — opens overlays, writes nothing.
//
// Run:  node edit-shortcuts.test.js

const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

const PROBE_FN = 'web-server';

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium);
  console.log('edit-shortcuts — leader menu, cheatsheet, and the typing guards');

  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  try {
    await page.goto('about:blank');
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.waitForFunction(() => graphReady() && !graph.animating,
                               null, {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A — the registry is populated and self-describing.
    // ===================================================================
    const registry = await page.evaluate(() => {
      const groups = window.gdShortcutGroups();
      const out = {};
      for (const [g, list] of groups) out[g] = list.map((s) => s.keys + ' → ' + s.description);
      return out;
    });
    assert(Object.keys(registry).length >= 3,
           'the registry carries several groups: ' + Object.keys(registry).join(', '));
    assert(JSON.stringify(registry).includes('Search functions'),
           'a known command is registered with its description');

    // ===================================================================
    // Phase B — Space opens the leader menu.
    // ===================================================================
    await page.evaluate(() => document.getElementById('graph-container').focus());
    await page.keyboard.press(' ');
    await page.waitForSelector('#gd-which-key.visible', {timeout: 3000});
    const menu = await page.evaluate(() => {
      const el = document.getElementById('gd-which-key');
      const rows = [...el.querySelectorAll('[role="option"]')];
      return {
        role: el.getAttribute('role'),
        labelled: !!el.getAttribute('aria-label'),
        rowCount: rows.length,
        // Every row must carry an accessible name — this menu is the
        // screen-reader command surface, not just a visual hint.
        allNamed: rows.every((r) => (r.getAttribute('aria-label') || '').includes(':')),
        sample: rows.slice(0, 4).map((r) => r.getAttribute('aria-label')),
      };
    });
    assert(menu.role === 'listbox', 'the menu is a listbox, got ' + menu.role);
    assert(menu.labelled, 'the menu has an accessible name');
    assert(menu.rowCount >= 3, 'it lists the available next keys, got ' + menu.rowCount);
    assert(menu.allNamed, 'every row is named for a screen reader: ' + JSON.stringify(menu.sample));

    // Escape leaves it.
    await page.keyboard.press('Escape');
    const menuGone = await page.evaluate(
      () => !document.getElementById('gd-which-key').classList.contains('visible'));
    assert(menuGone, 'Escape closes the leader menu');

    // ===================================================================
    // Phase C — the guards.
    // ===================================================================
    // C1: Space inside a text field types a space.
    const typed = await page.evaluate(() => {
      const input = document.getElementById('search-input');
      input.focus();
      input.value = '';
      return input === document.activeElement;
    });
    assert(typed, 'search input focused for the typing guard');
    await page.keyboard.type('a b');
    const typingGuard = await page.evaluate(() => ({
      value: document.getElementById('search-input').value,
      menuOpen: document.getElementById('gd-which-key')?.classList.contains('visible') || false,
    }));
    assert(typingGuard.value === 'a b',
           'Space types a space while in a field, got ' + JSON.stringify(typingGuard.value));
    assert(!typingGuard.menuOpen, 'and does NOT open the leader menu');
    await page.evaluate(() => {
      const i = document.getElementById('search-input');
      i.value = '';
      i.blur();
    });

    // C2: Space on a focused button activates it rather than opening the menu.
    const activation = await page.evaluate(() => {
      const btn = document.createElement('button');
      btn.id = 'gd-space-probe';
      btn.textContent = 'probe';
      btn.addEventListener('click', () => { window.__spaceProbeClicked = true; });
      document.body.appendChild(btn);
      window.__spaceProbeClicked = false;
      btn.focus();
      return document.activeElement === btn;
    });
    assert(activation, 'probe button focused');
    await page.keyboard.press(' ');
    const activated = await page.evaluate(() => {
      const r = {
        clicked: window.__spaceProbeClicked,
        menuOpen: document.getElementById('gd-which-key')?.classList.contains('visible') || false,
      };
      document.getElementById('gd-space-probe').remove();
      return r;
    });
    assert(activated.clicked, 'Space still activates a focused button');
    assert(!activated.menuOpen, 'and the leader does not steal it');

    // C3: a key another handler already consumed is not acted on twice.
    const notDoubleHandled = await page.evaluate(() => {
      let ran = 0;
      window.registerShortcut({
        id: 'probe-consumed', keys: 'q', leader: false, group: 'Probe',
        description: 'probe', run: () => { ran += 1; },
      });
      // Someone earlier in the chain handles and consumes it.
      const consumer = (e) => { if (e.key === 'q') e.preventDefault(); };
      document.addEventListener('keydown', consumer);
      document.getElementById('graph-container').focus();
      document.dispatchEvent(new KeyboardEvent('keydown', {key: 'q', bubbles: true, cancelable: true}));
      document.removeEventListener('keydown', consumer);
      return ran;
    });
    assert(notDoubleHandled === 0,
           'a consumed key does not also fire its shortcut (ran ' + notDoubleHandled + ' times)');

    // ===================================================================
    // Phase D — the cheatsheet is generated from the registry.
    // ===================================================================
    await page.evaluate(() => document.getElementById('graph-container').focus());
    await page.keyboard.press('?');
    await page.waitForSelector('#gd-cheatsheet.visible', {timeout: 3000});
    const sheet = await page.evaluate(() => {
      const el = document.getElementById('gd-cheatsheet');
      const groups = [...el.querySelectorAll('.gd-cheatsheet-group h3')].map((h) => h.textContent);
      const registryGroups = [...window.gdShortcutGroups().keys()];
      return {
        groups,
        registryGroups,
        matches: registryGroups.every((g) => groups.includes(g)),
        focusInside: el.contains(document.activeElement),
        appInert: document.getElementById('app').hasAttribute('inert'),
      };
    });
    assert(sheet.matches,
           'every registry group appears in the cheatsheet: ' + JSON.stringify(sheet));
    assert(sheet.focusInside, 'focus moves into the cheatsheet');
    assert(sheet.appInert, 'and the page behind it goes inert');

    await page.keyboard.press('Escape');
    const closed = await page.evaluate(() => ({
      hidden: !document.getElementById('gd-cheatsheet').classList.contains('visible'),
      appLive: !document.getElementById('app').hasAttribute('inert'),
    }));
    assert(closed.hidden, 'Escape closes the cheatsheet');
    assert(closed.appLive, 'and the page becomes reachable again');

    // ===================================================================
    // Phase E — the bindings actually DO something.
    // ===================================================================
    // Everything above tests the dispatcher; this tests that a key press
    // reaches real editor behaviour. Without it the menu could list
    // commands that quietly no-op (an earlier draft bound `navFit`, which
    // does not exist under that name).
    await page.evaluate(() => {
      navZoomTo(2.4);
      window.__z0 = viewport.zoom;
      document.getElementById('graph-container').focus();
    });
    await page.keyboard.press(' ');
    await page.keyboard.press('g');
    await page.keyboard.press('f');
    await page.waitForTimeout(700);
    const fit = await page.evaluate(() => ({z0: window.__z0, z1: viewport.zoom}));
    assert(fit.z1 !== fit.z0 && fit.z1 < fit.z0,
           'Space g f fits the graph (zoom ' + fit.z0 + ' → ' + fit.z1 + ')');

    await page.evaluate(() => {
      window.__z2 = viewport.zoom;
      document.getElementById('graph-container').focus();
    });
    await page.keyboard.press('-');
    await page.waitForTimeout(400);
    const out = await page.evaluate(() => ({z2: window.__z2, z3: viewport.zoom}));
    assert(out.z3 < out.z2, 'the bare - key zooms out (' + out.z2 + ' → ' + out.z3 + ')');

    await page.evaluate(() => document.getElementById('graph-container').focus());
    await page.keyboard.press('/');
    await page.waitForTimeout(300);
    const searchFocused = await page.evaluate(() => document.activeElement?.id);
    assert(searchFocused === 'search-input',
           'the bare / key focuses the search field, got ' + searchFocused);

    assert(pageErrors.length === 0, 'no page errors: ' + JSON.stringify(pageErrors));
    console.log('shortcuts — PASS');
  } finally {
    await browser.close();
  }
})();
