// Execute trace e2e (Debug P2) — builds a two-fn ref chain via the
// API (pt-trace-wrap --:value ref--> pt-trace-const), submits a run
// through the REAL execute-popover "Trace path" checkbox, then:
//
//   1. asserts the inline result pane offers "Show path on canvas"
//      and clicking it lands `.path-highlighted` + a timing badge on
//      the traversed (const) card,
//   2. asserts the ✕ clear action restores normal rendering,
//   3. expands History, asserts the traced row carries the "path"
//      button and that it replays the same highlight (and that the
//      untraced-by-default contract holds: a plain run's row has no
//      path button).
//
// Run from this directory:  node edit-execute-trace.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');

const CONST_FN = 'pt-trace-const';
const WRAP_FN = 'pt-trace-wrap';


async function cleanup(page) {
  await deleteFnByName(page, WRAP_FN);
  await deleteFnByName(page, CONST_FN);
}


// Open the ▶ execute popover from the fn card whose data-original-fn-id
// matches (NOT the first `⋯` in the DOM — the ref target's card also
// carries one). The trigger fires on mousedown.
async function openExecutePopoverForCard(page, fnId) {
  await page.waitForFunction(
    (id) => graphReady() && !graph.animating
            && !!document.querySelector(
              '.node-overlay[data-original-fn-id="' + id + '"] button.more-actions-trigger'),
    fnId,
    {timeout: 30000, polling: 100});
  await page.evaluate((id) => {
    document.querySelector(
      '.node-overlay[data-original-fn-id="' + id + '"] button.more-actions-trigger')
      .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
  }, fnId);
  await page.waitForSelector('.row-actions-popover button', {timeout: 30000});
  const opened = await page.evaluate(() => {
    const popover = document.querySelector('.row-actions-popover');
    const runBtn = popover
      && Array.from(popover.querySelectorAll('button'))
        .find(b => b.textContent.trim() === '▶');
    if (!runBtn) return false;
    runBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    return true;
  });
  if (!opened) throw new Error('▶ button not surfaced in row-actions');
  // The chain is fully bound — no arg forms; wait for the options row.
  await page.waitForFunction(
    () => !!document.querySelector(
      '.execute-popover.visible .execute-trace-checkbox'),
    null,
    {timeout: 15000, polling: 100});
}


(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('edit-execute-trace — trace checkbox → run → path highlight → clear → history replay → value capture');

  // Debug P3 — the "+ capture values" checkbox opens a real
  // window.confirm with the estimated-cost line. Auto-answer per the
  // current mode (decline first, accept later) and keep the message so
  // the cost line can be asserted.
  let acceptCaptureDialog = false;
  let lastDialogMessage = null;
  page.on('dialog', (d) => {
    lastDialogMessage = d.message();
    if (acceptCaptureDialog) d.accept();
    else d.dismiss();
  });

  try {
    await cleanup(page);

    // ===================================================================
    // Seed via API: const-parented leaf (value=41) + identity-parented
    // wrapper whose :value slot is REF-bound to the leaf — gives the
    // execution one `:ref` frame for the path-trace seam to record.
    // ===================================================================
    const ents = await getEntities(page, 'identity');
    const identity = ents.fns.find((f) => f.name === 'identity');
    const constFn = ents.fns.find((f) => f.name === 'const')
      || (await getEntities(page, 'const')).fns.find((f) => f.name === 'const');
    assert(identity && constFn, ':identity + :const baselines resolved');
    // :identity inherits :const's single `value` slot.
    const valueSlotId = (() => {
      const fnSlots = ents['fn-slots'] || [];
      const slots = new Map((ents.slots || []).map((s) => [s.id, s]));
      const fs = fnSlots.find((x) => x['fn-id'] === constFn.id
                                     && slots.get(x['slot-id'])?.name === 'value');
      return fs && fs['slot-id'];
    })();
    assert(valueSlotId, ':const `value` slot resolved');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + CONST_FN + '&parent-ids=' + constFn.id);
    await api(page, 'POST', '/api/entities/fn',
              'name=' + WRAP_FN + '&parent-ids=' + identity.id);
    const probeConst = (await getEntities(page, CONST_FN)).fns
      .find((f) => f.name === CONST_FN);
    const probeWrap = (await getEntities(page, WRAP_FN)).fns
      .find((f) => f.name === WRAP_FN);
    assert(probeConst && probeWrap, 'probe chain fns created');
    await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + probeConst.id + '&slot-id=' + valueSlotId + '&value=41');
    await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + probeWrap.id + '&slot-id=' + valueSlotId
              + '&ref-fn-id=' + probeConst.id);
    console.log('  ✓ ref chain seeded: ' + WRAP_FN + ' → ' + CONST_FN);

    // ===================================================================
    // Phase A: real popover — tick "Trace path" (+ persist), Run.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')
                    + '/#' + WRAP_FN);
    await openExecutePopoverForCard(page, probeWrap.id);
    const ran = await page.evaluate(() => {
      const popover = document.querySelector('.execute-popover.visible');
      const traceCb = popover.querySelector('.execute-trace-checkbox');
      const persistCb = popover.querySelector('.execute-persist-checkbox');
      if (!traceCb) return {ok: false, reason: 'no trace checkbox'};
      traceCb.checked = true;
      if (persistCb && !persistCb.disabled) persistCb.checked = true;
      popover.querySelector('.execute-run-btn').click();
      return {ok: true};
    });
    assert(ran.ok, 'Trace path ticked + Run clicked: ' + (ran.reason || 'ok'));

    // Inline result → "Show path on canvas" affordance appears.
    await page.waitForSelector('.execute-popover.visible .execute-show-path-btn',
                               {timeout: 30000});
    assert(true, 'traced run offers "Show path on canvas" in the result pane');

    // ===================================================================
    // Phase B: highlight lands on the traversed card + badge.
    // ===================================================================
    await page.click('.execute-popover.visible .execute-show-path-btn');
    await page.waitForSelector('.path-view-panel', {timeout: 10000});
    const view = await page.evaluate(() => ({
      highlighted: [...document.querySelectorAll('.node-overlay.path-highlighted')]
        .map((el) => el.dataset.originalFnId),
      badges: [...document.querySelectorAll('.path-trace-badge')]
        .map((el) => el.textContent),
      layerActive: document.getElementById('graph-layer')
        .classList.contains('path-view-active'),
      panelText: document.querySelector('.path-view-panel').textContent,
    }));
    assert(view.highlighted.includes(probeConst.id),
           'traversed card highlighted: ' + JSON.stringify(view.highlighted));
    assert(view.badges.some((t) => /ms|cache/.test(t)),
           'badge shows duration or cache-hit: ' + JSON.stringify(view.badges));
    assert(view.layerActive, 'graph layer dims non-path cards');
    assert(/Execution path: \d+ fn/.test(view.panelText),
           'summary panel text: ' + JSON.stringify(view.panelText));

    // ===================================================================
    // Phase C: ✕ clear restores normal rendering. (The panel lives
    // outside the execute popover, so this real pointerdown also
    // dismisses the popover — the standard outside-click behaviour;
    // Phase D reopens it.)
    // ===================================================================
    await page.click('.path-view-clear');
    const cleared = await page.evaluate(() => ({
      panel: !!document.querySelector('.path-view-panel'),
      highlighted: document.querySelectorAll('.node-overlay.path-highlighted').length,
      badges: document.querySelectorAll('.path-trace-badge').length,
      layerActive: document.getElementById('graph-layer')
        .classList.contains('path-view-active'),
    }));
    assert(!cleared.panel && cleared.highlighted === 0 && cleared.badges === 0
           && !cleared.layerActive,
           'clear restores normal rendering: ' + JSON.stringify(cleared));

    // ===================================================================
    // Phase D: an UNtraced persisted run's history row has NO path
    // button (off-by-default contract), the traced one does, and the
    // history "path" button replays the highlight.
    // ===================================================================
    await api(page, 'POST', '/api/execute',
              {'fn-id': probeWrap.id, 'args': {}, 'persist?': true});
    await openExecutePopoverForCard(page, probeWrap.id);
    await page.click('.execute-popover.visible .execute-history-toggle');
    await page.waitForSelector(
      '.execute-popover.visible .execute-history-path-btn', {timeout: 30000});
    const hist = await page.evaluate(() => {
      const popover = document.querySelector('.execute-popover.visible');
      return {
        rows: popover.querySelectorAll('.execute-history-row').length,
        pathBtns: popover.querySelectorAll('.execute-history-path-btn').length,
      };
    });
    assert(hist.rows >= 2 && hist.pathBtns >= 1 && hist.pathBtns < hist.rows,
           'path button only on traced rows (' + hist.pathBtns + '/'
           + hist.rows + ' rows)');
    await page.click('.execute-popover.visible .execute-history-path-btn');
    await page.waitForSelector('.path-view-panel', {timeout: 10000});
    const replay = await page.evaluate(() =>
      [...document.querySelectorAll('.node-overlay.path-highlighted')]
        .map((el) => el.dataset.originalFnId));
    assert(replay.includes(probeConst.id),
           'history "path" replays the highlight: ' + JSON.stringify(replay));

    // ===================================================================
    // Phase E (Debug P3): the "+ capture values" second-step control.
    // Declining the confirm dialog reverts the checkbox; the run then
    // captures NO values.
    // ===================================================================
    await openExecutePopoverForCard(page, probeWrap.id);
    const secondStep = await page.evaluate(() => {
      const popover = document.querySelector('.execute-popover.visible');
      const captureCb = popover.querySelector('.execute-capture-values-checkbox');
      return {present: !!captureCb, disabled: !!captureCb?.disabled};
    });
    assert(secondStep.present && secondStep.disabled,
           'capture-values checkbox ships disabled until Trace path is on');
    await page.click('.execute-popover.visible .execute-trace-checkbox');
    const unlocked = await page.evaluate(() =>
      !document.querySelector(
        '.execute-popover.visible .execute-capture-values-checkbox').disabled);
    assert(unlocked, 'ticking Trace path unlocks capture values');

    acceptCaptureDialog = false;
    lastDialogMessage = null;
    // The click blocks on the modal confirm until our dialog handler
    // dismisses it, so lastDialogMessage is set once it resolves.
    await page.click('.execute-popover.visible .execute-capture-values-checkbox');
    assert(lastDialogMessage && /Estimated cost: up to ~\d+ KB/.test(lastDialogMessage),
           'confirm dialog shows the estimated cost line: '
           + JSON.stringify(lastDialogMessage));
    const declined = await page.evaluate(() =>
      document.querySelector(
        '.execute-popover.visible .execute-capture-values-checkbox').checked);
    assert(!declined, 'declining the dialog reverts the checkbox');

    await page.evaluate(() => {
      document.querySelector('.execute-popover.visible .execute-run-btn').click();
    });
    await page.waitForSelector('.execute-popover.visible .execute-show-path-btn',
                               {timeout: 30000});
    await page.click('.execute-popover.visible .execute-show-path-btn');
    await page.waitForSelector('.path-view-panel', {timeout: 10000});
    const noValues = await page.evaluate(() =>
      document.querySelectorAll('.path-value-badge').length);
    assert(noValues === 0, 'declined capture → no value badges on the path view');

    // ===================================================================
    // Phase F (Debug P3): accepting the confirm captures values — the
    // path view shows a value badge whose popover carries the value.
    // ===================================================================
    await openExecutePopoverForCard(page, probeWrap.id);
    await page.click('.execute-popover.visible .execute-trace-checkbox');
    acceptCaptureDialog = true;
    await page.click('.execute-popover.visible .execute-capture-values-checkbox');
    const accepted = await page.evaluate(() =>
      document.querySelector(
        '.execute-popover.visible .execute-capture-values-checkbox').checked);
    assert(accepted, 'accepting the dialog keeps capture values checked');
    await page.evaluate(() => {
      document.querySelector('.execute-popover.visible .execute-run-btn').click();
    });
    await page.waitForSelector('.execute-popover.visible .execute-show-path-btn',
                               {timeout: 30000});
    await page.click('.execute-popover.visible .execute-show-path-btn');
    await page.waitForSelector('.path-value-badge', {timeout: 10000});
    const valBadge = await page.evaluate(() => {
      const badge = document.querySelector('.path-value-badge');
      badge.click();
      return badge.textContent;
    });
    assert(/value/.test(valBadge), 'value badge rendered: ' + valBadge);
    await page.waitForSelector('.path-value-popover', {timeout: 5000});
    const popText = await page.evaluate(() =>
      document.querySelector('.path-value-popover').textContent);
    assert(popText.includes('41'),
           'value popover shows the captured return (41): '
           + JSON.stringify(popText.slice(0, 120)));
    await page.click('.path-view-clear');
    const clearedValues = await page.evaluate(() => ({
      badges: document.querySelectorAll('.path-value-badge').length,
      popoverVisible: !!document.querySelector('.path-value-popover')
        && document.querySelector('.path-value-popover').style.display !== 'none',
    }));
    assert(clearedValues.badges === 0 && !clearedValues.popoverVisible,
           'clear removes value badges + popover: '
           + JSON.stringify(clearedValues));

    console.log('PASS');
  } catch (e) {
    console.error('FAIL:', e.message);
    process.exitCode = 1;
  } finally {
    try { await page.close(); } catch (_) {}
    try { await cleanup(null); } catch (_) {}
    await browser.close();
  }
})();
