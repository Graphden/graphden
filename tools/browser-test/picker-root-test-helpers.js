'use strict';
const assert = require('node:assert/strict');

// A picker selection receipt exists only while a real picker host has painted
// that root's view. Export/readiness alone must never certify rendering.
async function verifyPickerRootSelection(page, selection, candidate, background = null) {
  await page.waitForFunction(() => window.gdFnPickerGraph?.ready && window.gdEditorRoot?.componentNow('fn-picker'));
  await page.evaluate(candidate => openFnPicker({anchorEl: document.body,
    candidates: [{id: candidate, name: 'root-picker-proof', qualified: 'root-picker-proof', ns: null, effects: []}],
    onPick() {}}), candidate);
  try {
    await page.waitForFunction(selection => gdEditorRoot.componentIdentity('fn-picker') === selection
      && document.querySelector('.fn-picker-row[data-picker-key]'), selection);
    if (background !== null) await page.waitForFunction(background =>
      getComputedStyle(document.querySelector('.fn-picker-row[data-picker-key]')).backgroundColor === background, background);
    await page.locator('.fn-picker-search').focus();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.fn-picker-popover'));
  } finally { await page.evaluate(() => closeFnPicker()); }
}

// The native fallback is still owned by the selected root's lifetime, even
// after the bounded graph renderer has released its individual DOM instance.
async function verifyPickerNativeRootLifetime(page, candidate) {
  const rootPath = await page.evaluate(() => new URL(API.api_ui_root_plan, location.href).pathname);
  const matchesRoot = url => url.pathname === rootPath;
  let releaseHeld;
  const held = new Promise(resolve => { releaseHeld = resolve; });
  let finishHeld;
  const finished = new Promise(resolve => { finishHeld = resolve; });
  let intercepted = false;
  let routeError = null;
  const holdFirstPlan = async route => {
    if (intercepted) { await route.continue(); return; }
    intercepted = true;
    try {
      // Fetch authorized source normally, but keep its old-generation delivery
      // pending while the next real reload supersedes that browser request.
      const response = await route.fetch();
      await page.evaluate(() => { window.__gdPickerRootLifetime.held = true; });
      await held;
      await route.fulfill({response});
    } catch (error) {
      routeError = error;
      await page.evaluate(() => { window.__gdPickerRootLifetime.routeFailed = true; }).catch(() => {});
    } finally { finishHeld(); }
  };
  let routed = false;
  try {
    await page.waitForFunction(() => gdFnPickerGraph.ready && gdEditorRoot.componentNow('fn-picker'));
    await page.evaluate(candidate => {
      const anchor = document.createElement('button');
      anchor.textContent = 'Picker lifetime proof';
      document.body.appendChild(anchor);
      const fixture = {anchor, picked: 0, held: false, routeFailed: false};
      window.__gdPickerRootLifetime = fixture;
      anchor.focus();
      openFnPicker({anchorEl: anchor, candidates: Array.from({length: 121}, (_, index) => ({
        id: candidate, name: 'proof-' + index, qualified: 'lifetime-' + index + '.proof-' + index,
        ns: 'lifetime-' + index, sameNs: index === 0, effects: [],
      })), onPick() { fixture.picked++; }});
      fixture.oldRow = document.querySelector('.fn-picker-row');
      document.querySelector('.fn-picker-search')?.focus();
    }, candidate);
    assert(await page.evaluate(() => document.querySelectorAll('.fn-picker-group').length === 121
      && !!window.__gdPickerRootLifetime.oldRow
      && !document.querySelector('.fn-picker-row[data-picker-key]')
      && gdEditorRoot.componentIdentity('fn-picker') === null),
    'oversized picker uses native rows and has no graph render receipt');
    assert(await page.evaluate(() => {
      const fixture = window.__gdPickerRootLifetime;
      fixture.reloaded = gdEditorRoot.reload();
      return !document.querySelector('.fn-picker-popover')
        && document.activeElement === fixture.anchor
        && gdEditorRoot.componentIdentity('fn-picker') === null;
    }), 'root reload immediately closes native fallback, retires its receipt and restores anchor focus');
    await page.evaluate(async () => {
      const fixture = window.__gdPickerRootLifetime;
      fixture.oldRow.click();
      await fixture.reloaded;
    });
    await page.waitForFunction(() => gdFnPickerGraph.ready && gdEditorRoot.componentNow('fn-picker'));
    assert.equal(await page.evaluate(() => window.__gdPickerRootLifetime.picked), 0,
      'retired oversized native row cannot invoke its old selection callback');

    await page.route(matchesRoot, holdFirstPlan);
    routed = true;
    await page.evaluate(() => { window.__gdPickerRootLifetime.firstReload = gdEditorRoot.reload(); });
    await page.waitForFunction(() => window.__gdPickerRootLifetime.held || window.__gdPickerRootLifetime.routeFailed);
    if (routeError) throw routeError;
    assert(await page.evaluate(candidate => {
      const fixture = window.__gdPickerRootLifetime;
      if (gdFnPickerGraph.ready || gdEditorRoot.componentNow('fn-picker')) return false;
      fixture.anchor.focus();
      openFnPicker({anchorEl: fixture.anchor, candidates: [{
        id: candidate, name: 'loading-proof', qualified: 'loading-proof', ns: null, effects: [],
      }], onPick() { fixture.picked++; }});
      fixture.loadingRow = document.querySelector('.fn-picker-row');
      document.querySelector('.fn-picker-search')?.focus();
      return !!fixture.loadingRow && !fixture.loadingRow.hasAttribute('data-picker-key')
        && gdEditorRoot.componentIdentity('fn-picker') === null;
    }, candidate), 'picker opened during a held root load uses native rows without a render receipt');
    assert(await page.evaluate(() => {
      const fixture = window.__gdPickerRootLifetime;
      fixture.reloaded = gdEditorRoot.reload();
      return !document.querySelector('.fn-picker-popover')
        && document.activeElement === fixture.anchor
        && gdEditorRoot.componentIdentity('fn-picker') === null;
    }), 'next root reload immediately closes a picker opened while its prior root was loading');
    releaseHeld();
    await finished;
    if (routeError) throw routeError;
    await page.evaluate(async () => {
      const fixture = window.__gdPickerRootLifetime;
      await Promise.all([fixture.firstReload, fixture.reloaded]);
      fixture.loadingRow.click();
    });
    await page.waitForFunction(() => gdFnPickerGraph.ready && gdEditorRoot.componentNow('fn-picker'));
    assert(await page.evaluate(() => window.__gdPickerRootLifetime.picked === 0
      && !document.querySelector('.fn-picker-popover')
      && gdEditorRoot.componentIdentity('fn-picker') === null),
    'late old source and retired native row cannot reopen the picker, invoke onPick or certify rendering');
  } finally {
    releaseHeld();
    if (routed) await page.unroute(matchesRoot, holdFirstPlan);
    if (intercepted) await finished;
    await page.evaluate(() => {
      closeFnPicker();
      window.__gdPickerRootLifetime?.anchor.remove();
      delete window.__gdPickerRootLifetime;
    });
  }
}

module.exports = {verifyPickerRootSelection, verifyPickerNativeRootLifetime};
