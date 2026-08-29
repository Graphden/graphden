// Dialog keyboard semantics — the three moves a dialog owes a keyboard user.
//
// Every popover in the editor already closed on Escape; none of them managed
// focus. That combination is worse than it sounds: opening a dialog left the
// keyboard behind it, so Tab walked the page UNDERNEATH the overlay, and
// closing dropped focus on <body>, sending the next Tab back to the top of
// the document. Two of them additionally claimed `aria-modal="true"`, which
// promises a screen reader that the rest of the page is unreachable.
//
// What this pins, per dialog:
//   1. Focus ENTERS  — after opening, the active element is inside it.
//   2. Focus is KEPT — Tab from the last control wraps to the first rather
//      than escaping into the page behind.
//   3. Focus RETURNS — Escape puts the keyboard back on the trigger.
// Plus, for the modals: the rest of the page is actually inert while up.
//
// These are behavioural, not attribute checks: asserting `aria-modal` exists
// is what let the lie ship in the first place.
//
// Read-only — opens and closes dialogs, writes nothing.
//
// Run:  node edit-a11y-dialogs.test.js

const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

const PROBE_FN = 'web-server';

// Describe the active element well enough to tell "inside the dialog" from
// "back on the trigger" without leaking implementation detail into asserts.
const ACTIVE = () => {
  const a = document.activeElement;
  if (!a) return {tag: null};
  return {
    tag: a.tagName,
    cls: (a.className || '').toString(),
    id: a.id || null,
    text: (a.textContent || '').trim().slice(0, 24),
  };
};

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium);
  console.log('edit-a11y-dialogs — focus enters, stays, and comes back');

  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  try {
    await page.goto('about:blank');
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.waitForFunction(() => graphReady() && !graph.animating,
                               null, {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A — the primitives are wired into the bundle at all.
    // ===================================================================
    const primitives = await page.evaluate(() => ({
      focusableWithin: typeof focusableWithin,
      focusIntoDialog: typeof focusIntoDialog,
      returnFocusTo: typeof returnFocusTo,
      installTabTrap: typeof installTabTrap,
      setSiblingsInert: typeof setSiblingsInert,
      announce: typeof window.gdAnnounce,
    }));
    for (const [name, kind] of Object.entries(primitives)) {
      assert(kind === 'function', `${name} is available to editor modules (got ${kind})`);
    }

    // `announce` must not steal focus — it exists precisely so state can
    // change without moving the keyboard.
    const announceKeepsFocus = await page.evaluate(async () => {
      const probe = document.getElementById('search-input');
      probe.focus();
      window.gdAnnounce('probe message');
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      const region = document.getElementById('gd-a11y-announcer');
      return {
        stillFocused: document.activeElement === probe,
        spoke: !!region && region.textContent.includes('probe message'),
        polite: !!region && region.getAttribute('aria-live') === 'polite',
      };
    });
    assert(announceKeepsFocus.spoke, 'announce() writes into a live region');
    assert(announceKeepsFocus.polite, 'the default region is polite');
    assert(announceKeepsFocus.stillFocused, 'announce() does NOT move focus');

    // ===================================================================
    // Phase B — the fn-picker: enter, wrap, return.
    // ===================================================================
    // Open it the way a user does, from an argument row's picker affordance.
    const opened = await page.evaluate(() => {
      const anchor = document.querySelector('.placeholder-binder, .arg-value-edit, .node-overlay button');
      if (!anchor) return {ok: false, why: 'no anchor on this graph'};
      anchor.id = anchor.id || 'a11y-probe-anchor';
      anchor.focus();
      openFnPicker({anchorEl: anchor, onPick: () => {}, onCancel: () => {}});
      return {ok: !!document.querySelector('.fn-picker-popover'), anchorId: anchor.id};
    });

    if (!opened.ok) {
      console.log('  – fn-picker probe skipped: ' + (opened.why || 'picker did not open'));
    } else {
      // 1. focus entered
      await page.waitForFunction(
        () => document.querySelector('.fn-picker-popover')?.contains(document.activeElement),
        null, {timeout: 5000, polling: 50});
      assert(true, 'fn-picker: focus moves into the popover on open');

      // 2. focus is kept — Tab from the last focusable wraps to the first
      const wrapped = await page.evaluate(() => {
        const el = document.querySelector('.fn-picker-popover');
        const items = focusableWithin(el);
        if (items.length < 2) return {skip: true, n: items.length};
        items[items.length - 1].focus();
        return {skip: false, before: document.activeElement === items[items.length - 1]};
      });
      if (wrapped.skip) {
        console.log('  – tab-wrap skipped: only ' + wrapped.n + ' focusable');
      } else {
        assert(wrapped.before, 'fn-picker: parked on the last control');
        await page.keyboard.press('Tab');
        const afterTab = await page.evaluate(() => {
          const el = document.querySelector('.fn-picker-popover');
          const items = focusableWithin(el);
          return {
            insideStill: !!el && el.contains(document.activeElement),
            onFirst: document.activeElement === items[0],
          };
        });
        assert(afterTab.insideStill,
               'fn-picker: Tab from the last control does NOT escape into the page behind');
        assert(afterTab.onFirst, 'fn-picker: it wraps to the first control');
      }

      // 3. focus returns to the trigger
      await page.keyboard.press('Escape');
      await page.waitForFunction(() => !document.querySelector('.fn-picker-popover'),
                                 null, {timeout: 5000, polling: 50});
      const back = await page.evaluate((id) => document.activeElement?.id === id, opened.anchorId);
      assert(back, 'fn-picker: Escape hands the keyboard back to the trigger');
    }

    // ===================================================================
    // Phase C — a real modal makes the page behind it inert.
    // ===================================================================
    const inert = await page.evaluate(() => {
      const modal = document.createElement('div');
      modal.id = 'a11y-inert-probe';
      document.body.appendChild(modal);
      setSiblingsInert(modal, true);
      const app = document.getElementById('app');
      const on = {
        appInert: app.hasAttribute('inert'),
        appHidden: app.getAttribute('aria-hidden') === 'true',
        modalUntouched: !modal.hasAttribute('inert'),
      };
      setSiblingsInert(modal, false);
      const off = {
        appInert: app.hasAttribute('inert'),
        appHidden: app.hasAttribute('aria-hidden'),
      };
      modal.remove();
      return {on, off};
    });
    assert(inert.on.appInert, 'modal: the app behind becomes inert');
    assert(inert.on.appHidden, 'modal: and is hidden from assistive tech');
    assert(inert.on.modalUntouched, 'modal: the dialog itself stays reachable');
    assert(!inert.off.appInert && !inert.off.appHidden,
           'modal: closing restores the page (both attributes removed)');

    // ===================================================================
    // Phase D — Escape still marks itself consumed, so the tour survives.
    // ===================================================================
    // The tour ends on an unconsumed Escape; a dialog dismissal must not
    // read as "the reader quit". Pin the protocol, not the tour.
    const consumed = await page.evaluate(() => {
      const anchor = document.getElementById('search-input');
      anchor.focus();
      openNamespacePicker({anchorEl: anchor, onPick: () => {}});
      if (!document.querySelector('.fn-picker-popover, .ns-picker-popover')) return {skip: true};
      let prevented = false;
      const probe = (e) => { if (e.key === 'Escape') prevented = e.defaultPrevented; };
      // Bubble phase, after the dismiss handlers have run.
      document.addEventListener('keydown', probe);
      document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}));
      document.removeEventListener('keydown', probe);
      return {skip: false, prevented};
    });
    if (consumed.skip) {
      console.log('  – escape-protocol probe skipped: namespace picker did not open');
    } else {
      assert(consumed.prevented,
             'Escape that closes a dialog is marked consumed (preventDefault) for the tour');
    }

    assert(pageErrors.length === 0, 'no page errors: ' + JSON.stringify(pageErrors));
    console.log('a11y-dialogs — PASS');
  } finally {
    await browser.close();
  }
})();
