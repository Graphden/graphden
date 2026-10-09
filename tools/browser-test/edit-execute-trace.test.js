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
const {assert, newContext, api, getEntities, nodeApi} =
  require('./edit-test-helpers');

const RUN_ID = process.pid + '-' + Date.now().toString(36);
const CONST_FN = 'pt-trace-const-' + RUN_ID;
const WRAP_FN = 'pt-trace-wrap-' + RUN_ID;
const owned = [];
const receiptFile = '/tmp/graphden-trace-owned-' + RUN_ID + '.json';
const headers = {'X-Graphden-Branch': 'main'};
const saveOwned = () => require('node:fs').writeFileSync(receiptFile, JSON.stringify(owned), {mode: 0o600});

async function cleanup(page) {
  for (const receipt of [...owned].reverse()) {
    if (receipt.removed) continue;
    assert(receipt.confirmed, 'unconfirmed creation receipt retained');
    const data = await getEntities(page, receipt.id);
    const row = data.fns.find(fn => fn.id === receipt.id);
    if (!row) { receipt.removed = true; saveOwned(); continue; }
    assert(row.name === receipt.name && row['namespace-id'] === receipt['namespace-id'],
      'exact cleanup identity unchanged');
    const response = await nodeApi('DELETE', '/api/entities/fn/' + receipt.id, undefined, headers);
    assert(response.ok, 'exact owned function delete succeeded');
    assert(!(await getEntities(page, receipt.id)).fns.some(fn => fn.id === receipt.id),
      'exact owned function absent');
    receipt.removed = true;
    saveOwned();
  }
}

async function createProbe(page, name, parentId) {
  const response = await nodeApi('POST', '/api/entities/fn',
    new URLSearchParams({name, 'parent-ids': parentId}).toString(), headers);
  const id = response.headers.get('X-Graphden-Created-Id');
  assert(response.ok && /^[a-f0-9-]{36}$/.test(id || ''), 'canonical creation UUID receipt');
  const receipt = {id, name, confirmed: false};
  owned.push(receipt);
  saveOwned();
  const row = (await getEntities(page, id)).fns.find(fn => fn.id === id);
  assert(row && row.name === name, 'canonical created function identity');
  Object.assign(receipt, {'namespace-id': row['namespace-id'], confirmed: true});
  saveOwned();
  return row;
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
  const options = page.locator('.execute-popover.visible .execute-options');
  assert(!(await options.evaluate(element => element.open)), 'Run options start collapsed');
  await options.locator('summary').focus();
  await page.keyboard.press('Enter');
  assert(await options.evaluate(element => element.open), 'native keyboard disclosure opens Run options');
}


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
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

    // ===================================================================
    // Seed via API: const-parented leaf (value=41) + const-parented
    // wrapper whose :value slot is REF-bound to the leaf — gives the
    // execution one `:ref` frame for the path-trace seam to record.
    // ===================================================================
    const ents = await getEntities(page, 'const');
    const constFn = ents.fns.find((f) => f.name === 'const')
      || (await getEntities(page, 'const')).fns.find((f) => f.name === 'const');
    assert(constFn, ':const baseline resolved');
    const valueSlotId = (() => {
      const fnSlots = ents['fn-slots'] || [];
      const slots = new Map((ents.slots || []).map((s) => [s.id, s]));
      const fs = fnSlots.find((x) => x['fn-id'] === constFn.id
                                     && slots.get(x['slot-id'])?.name === 'value');
      return fs && fs['slot-id'];
    })();
    assert(valueSlotId, ':const `value` slot resolved');

    const probeConst = await createProbe(page, CONST_FN, constFn.id);
    const probeWrap = await createProbe(page, WRAP_FN, constFn.id);
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
    // The run's own fn is the trace's outermost frame — the card that
    // was run lights up too, never the one dimmed card on its own path.
    assert(view.highlighted.includes(probeWrap.id),
           'the run\'s root card highlighted: ' + JSON.stringify(view.highlighted));
    assert(view.badges.some((t) => /ms|cache/.test(t)),
           'badge shows duration or cache-hit: ' + JSON.stringify(view.badges));
    assert(view.layerActive, 'graph layer dims non-path cards');
    assert(/Execution path: \d+ fn/.test(view.panelText),
           'summary panel text: ' + JSON.stringify(view.panelText));

    // ===================================================================
    // Phase C: ✕ clear restores normal rendering. (The run pane lives
    // in the inspector's Runs tab and survives canvas clicks; Phase D
    // re-opens it for the wrap fn, which simply remounts the pane.)
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
    // History is always mounted below the form in the Runs tab.
    await page.waitForSelector(
      '#gd-insp-runs .execute-history-path-btn', {timeout: 30000});
    const hist = await page.evaluate(() => {
      const runs = document.getElementById('gd-insp-runs');
      return {
        rows: runs.querySelectorAll('.execute-history-row').length,
        pathBtns: runs.querySelectorAll('.execute-history-path-btn').length,
      };
    });
    assert(hist.rows >= 2 && hist.pathBtns >= 1 && hist.pathBtns < hist.rows,
           'path button only on traced rows (' + hist.pathBtns + '/'
           + hist.rows + ' rows)');
    await page.click('#gd-insp-runs .execute-history-path-btn');
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
    // A short value prints inline on the chip; the popover is the full view.
    assert(/= (value|41)/.test(valBadge), 'value badge rendered: ' + valBadge);
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

    // Width depends on glyphs and the card, not only character count.
    const leafBinding = (await getEntities(page, probeConst.id)).bindings
      .find((binding) => binding['fn-id'] === probeConst.id
        && binding['slot-id'] === valueSlotId);
    assert(leafBinding, 'captured leaf binding located');
    for (const value of ['界'.repeat(20), 'trace-value-'.repeat(20)]) {
      await api(page, 'PUT', '/api/entities/binding/' + leafBinding.id,
        'value=' + encodeURIComponent(JSON.stringify(value)));
      const run = await api(page, 'POST', '/api/execute', {
        'fn-id': probeWrap.id, args: {}, 'persist?': true, 'trace?': true, 'capture-values?': true,
      });
      assert(run.status === 'succeeded' && run['path-trace'], 'long value captured');
      await page.evaluate((trace) => showExecutionPathView(trace), run['path-trace']);
      const badge = page.locator('.node-overlay[data-original-fn-id="' + probeConst.id
        + '"] .path-value-badge');
      const geometry = await badge.evaluate((element) => {
        const card = element.closest('.node-overlay').getBoundingClientRect();
        const rect = element.getBoundingClientRect();
        return {inside: rect.left >= card.left && rect.right <= card.right,
          clipped: element.scrollWidth > element.clientWidth,
          overflow: getComputedStyle(element).textOverflow,
          text: element.textContent, popup: element.getAttribute('aria-haspopup')};
      });
      assert(geometry.inside && geometry.popup === 'dialog', 'value stays in its card and announces disclosure');
      assert(geometry.text.endsWith('…') || (geometry.clipped && geometry.overflow === 'ellipsis'),
        'clipped value has a visible ellipsis');
      await badge.focus();
      await page.keyboard.press('Enter');
      assert(await page.locator('.path-value-popover-body').textContent() === JSON.stringify(value, null, 2),
        'keyboard disclosure shows the complete captured value');
      const valueBody = page.locator('.path-value-popover-body');
      await valueBody.focus();
      await page.keyboard.press('ArrowRight');
      assert(await valueBody.evaluate((element) => document.activeElement === element
        && element.tabIndex === 0), 'captured value can receive keyboard scrolling');
      await page.click('.path-value-popover [data-gd-pop-x]');
      assert(await badge.evaluate(element => document.activeElement === element), 'visible Close restores badge focus');
      await page.keyboard.press('Escape');
      assert(await page.locator('.path-view-panel').count() === 0,
        'second Escape clears the path after the value popup closes');
      await page.evaluate(id => openTraceView(id), run['execution-id']);
      const details = page.locator('.trace-view-panel .trace-row[data-fn-id="' + probeConst.id
        + '"] .trace-value').first();
      const initiallyOpen = JSON.stringify(value, null, 2).length <= 80;
      // Native disclosure regression: both server initial states are valid.
      await details.waitFor();
      assert(await details.evaluate(element => element.open) === initiallyOpen,
        'short captured values start open; long values start collapsed');
      await details.locator('summary').focus();
      if (initiallyOpen) {
        await page.keyboard.press('Space');
        assert(!(await details.evaluate(element => element.open)), 'Space closes initially open value details');
      }
      await page.keyboard.press('Enter');
      assert(await details.evaluate(element => element.open), 'Enter opens native value details without row interception');
      await page.keyboard.press('Space');
      assert(!(await details.evaluate(element => element.open)), 'Space closes native value details');
      // End native disclosure regression.
      await page.keyboard.press('Escape');
    }

    // Capture caps cannot promise a recoverable full value.
    await api(page, 'PUT', '/api/entities/binding/' + leafBinding.id,
      'value=' + encodeURIComponent(JSON.stringify('x'.repeat(5000))));
    const cappedRun = await api(page, 'POST', '/api/execute', {
      'fn-id': probeWrap.id, args: {}, 'trace?': true, 'capture-values?': true,
    });
    await page.evaluate(trace => showExecutionPathView(trace), cappedRun['path-trace']);
    const unavailable = page.locator('.node-overlay[data-original-fn-id="' + probeConst.id + '"] .path-value-badge');
    assert(await unavailable.textContent() === '= unavailable', 'cap is labelled unavailable rather than expandable full data');
    await unavailable.click();
    assert(await page.locator('.path-value-popover-body').count() === 0, 'cap explanation does not expose an earlier captured value');
    assert((await page.locator('.path-value-popover-note').textContent()).includes('not captured'), 'cap explanation states value was not stored');
    await page.click('.path-value-popover [data-gd-pop-x]');
    await page.setViewportSize({width: 390, height: 240});
    const inspectorClose = page.getByRole('button', {name: 'Close inspector', exact: true});
    if (await inspectorClose.isVisible()) await inspectorClose.click();
    await page.evaluate(async id => {
      toggleCollapsed(true);
      for (let frame = 0; frame < 2; frame++) await new Promise(requestAnimationFrame);
      const badge = document.querySelector('.node-overlay[data-original-fn-id="' + id + '"] .path-value-badge');
      const rect = badge.getBoundingClientRect();
      const surface = document.getElementById('graph-surface').getBoundingClientRect();
      setViewportPan(viewport.pan.x + surface.left + surface.width / 2 - rect.left,
        viewport.pan.y + surface.top + surface.height / 3 - rect.top);
      for (let frame = 0; frame < 2; frame++) await new Promise(requestAnimationFrame);
    }, probeConst.id);
    for (const dark of [false, true]) {
      await page.evaluate(value => document.body.classList.toggle('theme-dark', value), dark);
      await unavailable.focus();
      await page.keyboard.press('Enter');
      const popup = page.getByRole('dialog', {name: 'Captured value', exact: true});
      await popup.waitFor();
      const bounds = await popup.boundingBox();
      assert(bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= 390
        && bounds.y + bounds.height <= 240, 'narrow captured-value popup stays within viewport');
      assert(await popup.getByRole('button', {name: 'Close captured value', exact: true}).isVisible(),
        'visible Close remains available in both themes');
      await page.keyboard.press('Tab');
      assert(await popup.evaluate(element => element.contains(document.activeElement)), 'Tab stays in the owned value dialog');
      await page.screenshot({path: '/tmp/graphden-trace-value-' + (dark ? 'dark' : 'light') + '.png'});
      await page.keyboard.press('Escape');
      assert(await unavailable.evaluate(element => document.activeElement === element), 'Escape restores the value badge after narrow disclosure');
    }
    await page.evaluate(() => document.body.classList.remove('theme-dark'));
    await page.keyboard.press('Escape');

    console.log('PASS');
  } catch (e) {
    console.error('FAIL:', e.message);
    process.exitCode = 1;
  } finally {
    try { await page.close(); } catch (_) {}
    try { await cleanup(null); } catch (_) { console.error('FAIL: exact trace fixture cleanup refused'); process.exitCode = 1; }
    await browser.close();
  }
})();
