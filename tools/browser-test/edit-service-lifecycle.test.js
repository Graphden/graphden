// Service lifecycle e2e — full Phase-1 service flow through the
// editor's ⚙ popover.
//
// Now that the HOF-forwarding type-check fix landed
// (graphden.crud.type-check: type-check-binding-direct! accepts a
// scalar-returning ref into a `[:fn ...]` slot when the synthesized
// callable signature is a fn-subtype of the slot), a from-scratch
// service-eligible probe is constructable in tests:
//
//   {:parent :const :args {:value "tick"}}   ; thunk
//   {:parent :future :args {:body :thunk}}   ; service-eligible
//
// Coverage (Phase A–E):
//   A. ⚙ button enabled on the probe; popover opens in CREATE mode.
//   B. Title / Save / no Delete button / branch-picker / no sibling-warn.
//   C. Create & reconcile — :service row persists, reconciler tracks
//      the run in the `running` atom.
//   D. Re-open popover → EDIT mode (title flips, Delete appears,
//      enabled pre-filled).
//   E. Cross-branch sibling-warn: POST a sibling service-row on a
//      feat branch, re-open popover, ⚠ warn renders + names the
//      sibling branch.
//   F. Toggle :enabled? false → Save → reconcile → row disabled.
//   G. Delete row → reconciler stops + row gone.
//
// Run from this directory:  node edit-service-lifecycle.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const TICK_FN = 'tutorial-service-tick' + RUN_ID;
const PROBE_FN = 'tutorial-service-probe' + RUN_ID;
const PROBE_BRANCH = 'service-lifecycle-feat' + RUN_ID;


async function cleanup(page) {
  try {
    const services = await api(page, 'GET', '/api/services');
    const list = (services && services.services) || [];
    for (const s of list) {
      if (s['fn-name'] === PROBE_FN) {
        await api(page, 'DELETE', '/api/entities/service/' + s.id);
      }
    }
    await api(page, 'POST', '/api/services/reconcile');
  } catch (_) {}
  try { await deleteFnByName(page, PROBE_FN); } catch (_) {}
  try { await deleteFnByName(page, TICK_FN); } catch (_) {}
  try {
    await api(page, 'DELETE',
              '/api/branches/' + encodeURIComponent(PROBE_BRANCH));
  } catch (_) {}
}


async function openServicePopover(page) {
  await page.goto('about:blank');
  await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + PROBE_FN);
  // Wait for cytoscape to mount the fn-card with its `⋯` trigger and
  // for the post-mount fit animation to drain — without the
  // cy.animated() gate, dispatchEvent below races autowait stability.
  await page.waitForFunction(
    () => typeof cy !== 'undefined' && cy && cy.nodes().length > 0
          && !!document.querySelector('button.more-actions-trigger')
          && !cy.animated(),
    {timeout: 20000, polling: 100});
  await page.evaluate(() => initGraph());
  // initGraph re-renders overlays; wait again for the post-rebuild
  // settle before dispatching.
  await page.waitForFunction(
    () => typeof cy !== 'undefined' && cy && cy.nodes().length > 0
          && !!document.querySelector('button.more-actions-trigger')
          && !cy.animated(),
    {timeout: 20000, polling: 100});
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  await page.waitForSelector('.row-actions-popover', {timeout: 5000});
  const clicked = await page.evaluate(() => {
    const popover = document.querySelector('.row-actions-popover');
    const gear = Array.from(popover?.querySelectorAll('button') || [])
      .find((b) => b.textContent.trim() === '⚙');
    if (!gear || gear.getAttribute('aria-disabled') === 'true') return false;
    gear.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    return true;
  });
  if (!clicked) return false;
  try {
    await page.waitForSelector('.service-popover.visible', {timeout: 5000});
  } catch (_) { return false; }
  return true;
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 300));
    d.accept();
  });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-service-lifecycle — create / badge / sibling-warn / toggle / delete');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: const-parented thunk (`:value "tick"`) + future-parented
    // probe binding `:body` to the thunk. The HOF-forwarding type-check
    // accepts this binding (thunk's static signature [:fn {} :text] is
    // a subtype of :body's [:fn {} :any] slot). Zero free args + :process
    // effect transitively from :future → service-eligible.
    // ===================================================================
    const ents = await getEntities(page);
    const future = ents.fns.find((f) => f.name === 'future');
    const constFn = ents.fns.find(
      (f) => f.name === 'const' && (f['parent-ids'] || []).length === 0);
    assert(future && constFn, ':future + :const baselines resolved');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + TICK_FN + '&parent-ids=' + constFn.id);
    const tickEnts = await getEntities(page);
    const tick = tickEnts.fns.find((f) => f.name === TICK_FN);
    assert(tick, 'thunk fn-def created');
    const constSlots = tickEnts['fn-slots'].filter(
      (fs) => fs['fn-id'] === constFn.id);
    const constSlotsById = Object.fromEntries(
      tickEnts.slots.map((s) => [s.id, s]));
    const valueSlot = constSlots
      .map((fs) => constSlotsById[fs['slot-id']])
      .find((s) => s.name === 'value');
    assert(valueSlot, ':const.value slot resolved');
    const valueBindResp = await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + tick.id + '&slot-id=' + valueSlot.id
              + '&value=' + encodeURIComponent('"tick"'));
    assert(JSON.stringify(valueBindResp).includes('created successfully'),
           'thunk :value bound to literal "tick"');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + future.id);
    const probeEnts = await getEntities(page);
    const probe = probeEnts.fns.find((f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created');
    const futureFnSlots = probeEnts['fn-slots'].filter(
      (fs) => fs['fn-id'] === future.id);
    const slotsById = Object.fromEntries(
      probeEnts.slots.map((s) => [s.id, s]));
    const bodySlot = futureFnSlots
      .map((fs) => slotsById[fs['slot-id']])
      .find((s) => s.name === 'body');
    assert(bodySlot, ':future.body slot resolved');
    const bodyBindResp = await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + probe.id + '&slot-id=' + bodySlot.id
              + '&ref-fn-id=' + tick.id);
    assert(JSON.stringify(bodyBindResp).includes('created successfully'),
           'probe :body bound to thunk via ref (HOF-forwarding accepted): '
           + JSON.stringify(bodyBindResp).slice(0, 200));

    // ===================================================================
    // Phase A: open the popover via the actual ⚙ gear button.
    // ===================================================================
    let opened = await openServicePopover(page);
    assert(opened, '⚙ button enabled + popover opens (service-eligible)');

    const createState = await page.evaluate(() => {
      const p = document.querySelector('.service-popover.visible');
      return {
        title: p.querySelector('.service-popover-title')?.textContent,
        saveLabel: p.querySelector('.service-popover-save-btn')?.textContent,
        deleteBtnExists: !!p.querySelector('.service-popover-delete-btn'),
        branchPickerOptionCount: p.querySelectorAll(
          '.service-popover-branch-select option').length,
        siblingWarnPresent: !!p.querySelector('.service-popover-sibling-warn'),
      };
    });
    assert(createState.title?.includes('Make service'),
           'title is "Make service: …" for create flow: '
           + JSON.stringify(createState.title));
    assert(createState.saveLabel === 'Create & reconcile',
           'save button reads "Create & reconcile"');
    assert(!createState.deleteBtnExists,
           'no Delete button — nothing exists to delete yet');
    assert(createState.branchPickerOptionCount >= 2,
           'branch picker carries (any) + main: ' + createState.branchPickerOptionCount);
    assert(!createState.siblingWarnPresent,
           'no cross-branch sibling-warn at create time');

    // Click Create & reconcile.
    await page.evaluate(() => {
      document.querySelector('.service-popover-save-btn').click();
    });
    // Wait for the :service row to appear in storage AND for the
    // reconciler atom to track it as running. Match by fn-id, NOT
    // fn-name — the latter goes through a backend JOIN that returns
    // null for a brief window after row creation, which produced an
    // observable race here (v11 / v12 diagnostic).
    await page.waitForFunction(
      async (fnId) => {
        try {
          const r = await window.authFetch('/api/services');
          const body = await r.json();
          const s = body.services?.find((x) => x['fn-id'] === fnId);
          return !!s && !!s['enabled?'] && !!s.running;
        } catch (_) { return false; }
      },
      probe.id,
      {timeout: 15000, polling: 200});
    const persistedSvc = await page.evaluate(async (fnId) => {
      const r = await window.authFetch('/api/services');
      const body = await r.json();
      return body.services?.find((s) => s['fn-id'] === fnId);
    }, probe.id);
    assert(persistedSvc && persistedSvc['enabled?'],
           ':service row persisted with :enabled? true: '
           + JSON.stringify(persistedSvc).slice(0, 200));
    assert(persistedSvc.running,
           'reconciler tracked the start in the running atom: '
           + JSON.stringify(persistedSvc.running).slice(0, 200));

    // ===================================================================
    // Phase B: re-open popover → EDIT mode.
    // ===================================================================
    opened = await openServicePopover(page);
    assert(opened, '⚙ re-opens after service exists');
    const editState = await page.evaluate(() => {
      const p = document.querySelector('.service-popover.visible');
      return {
        title: p.querySelector('.service-popover-title')?.textContent,
        saveLabel: p.querySelector('.service-popover-save-btn')?.textContent,
        deleteBtnExists: !!p.querySelector('.service-popover-delete-btn'),
        enabledChecked: !!p.querySelector('.service-popover-enabled')?.checked,
      };
    });
    assert(editState.title?.includes('Service:'),
           'title flips to "Service: …" (edit mode)');
    assert(editState.saveLabel === 'Save & reconcile',
           'save button reads "Save & reconcile"');
    assert(editState.deleteBtnExists,
           'Delete button appears for existing service');
    assert(editState.enabledChecked,
           'enabled checkbox pre-filled true');

    // ===================================================================
    // Phase C: cross-branch sibling-warn. Create a feat branch + a
    // second service-row scoped to feat. Re-open popover on main → ⚠.
    // ===================================================================
    await page.evaluate(() => {
      if (typeof hideServicePopover === 'function') hideServicePopover();
    });
    await page.waitForFunction(
      () => !document.querySelector('.service-popover.visible'),
      {timeout: 3000, polling: 50});

    const branchResp = await api(page, 'POST', '/api/branches',
                                 {name: PROBE_BRANCH});
    assert(branchResp?.ok, 'feat branch created');
    const branches = await api(page, 'GET', '/api/branches');
    const featId = branches.branches.find(
      (b) => b.name === PROBE_BRANCH)?.id;
    await api(page, 'POST', '/api/entities/service',
              'fn-id=' + probe.id + '&enabled?=true&restart-policy=always'
              + '&branch-id=' + featId);
    await page.evaluate(async () => {
      if (typeof refreshServicesCache === 'function') {
        await refreshServicesCache();
      }
    });

    opened = await openServicePopover(page);
    assert(opened, '⚙ re-opens after sibling service inserted');
    const siblingState = await page.evaluate(() => {
      const p = document.querySelector('.service-popover.visible');
      const warn = p.querySelector('.service-popover-sibling-warn');
      return {
        warnPresent: !!warn,
        warnText: warn?.textContent || '',
      };
    });
    assert(siblingState.warnPresent,
           '⚠ sibling-warn renders when sibling service exists on another branch');
    assert(siblingState.warnText.includes(PROBE_BRANCH),
           'warn text names the sibling branch: '
           + JSON.stringify(siblingState.warnText).slice(0, 200));

    // Clear the sibling row before Phase D — `loadServiceForFn`
    // returns the FIRST match for the fn-id; with two services on
    // different branches, the popover may bind to whichever the
    // backend orders first. Dropping the sibling keeps the toggle/
    // delete flow unambiguously about the main-branch row.
    const allSvcs = await api(page, 'GET', '/api/services');
    for (const s of (allSvcs.services || [])) {
      if (s['fn-name'] === PROBE_FN && s['branch-id'] === featId) {
        await api(page, 'DELETE', '/api/entities/service/' + s.id);
      }
    }
    await api(page, 'POST', '/api/services/reconcile');
    await page.evaluate(async () => {
      if (typeof refreshServicesCache === 'function') {
        await refreshServicesCache();
      }
    });

    // ===================================================================
    // Phase D: toggle :enabled? false → Save → row disabled.
    // ===================================================================
    opened = await openServicePopover(page);
    assert(opened, '⚙ re-opens for toggle');
    await page.evaluate(() => {
      const cb = document.querySelector('.service-popover-enabled');
      cb.checked = false;
      cb.dispatchEvent(new Event('change', {bubbles: true}));
      document.querySelector('.service-popover-save-btn').click();
    });
    // Wait for the toggle to settle into storage.
    await page.waitForFunction(
      async (svcId) => {
        try {
          const r = await window.authFetch('/api/services');
          const body = await r.json();
          const s = body.services?.find((x) => x.id === svcId);
          return s && !s['enabled?'];
        } catch (_) { return false; }
      },
      persistedSvc.id,
      {timeout: 15000, polling: 200});
    const disabledSvc = await page.evaluate(async (svcId) => {
      const r = await window.authFetch('/api/services');
      const body = await r.json();
      return body.services?.find((s) => s.id === svcId);
    }, persistedSvc.id);
    assert(disabledSvc && !disabledSvc['enabled?'],
           'main-branch service flipped :enabled? false: '
           + JSON.stringify(disabledSvc).slice(0, 200));

    // ===================================================================
    // Phase E: Delete the main-branch row.
    // ===================================================================
    opened = await openServicePopover(page);
    assert(opened, '⚙ re-opens for delete');
    await page.evaluate(() => {
      document.querySelector('.service-popover-delete-btn').click();
    });
    // Wait for the row to disappear from /api/services.
    await page.waitForFunction(
      async (svcId) => {
        try {
          const r = await window.authFetch('/api/services');
          const body = await r.json();
          return !body.services?.some((s) => s.id === svcId);
        } catch (_) { return false; }
      },
      persistedSvc.id,
      {timeout: 15000, polling: 200});
    const afterDelete = await page.evaluate(async (svcId) => {
      const r = await window.authFetch('/api/services');
      const body = await r.json();
      return body.services?.find((s) => s.id === svcId);
    }, persistedSvc.id);
    assert(!afterDelete,
           'main-branch service gone after Delete: ' + JSON.stringify(afterDelete));

    console.log('✓ full service lifecycle verified — create/badge/sibling-warn/toggle/delete');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
