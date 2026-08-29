// Graph canvas — keyboard navigation.
//
// The canvas was the last mouse-only surface. Node cards are `div`s with a
// click handler and no tabindex, so the graph — the thing the editor exists
// to show — could not be reached from the keyboard at all.
//
// Movement follows the EDGES, not the screen: → steps to an argument, ←
// steps back to the consumer. That distinction is what this test is mostly
// about — asserting "focus moved somewhere" would pass for an implementation
// that walked the DOM in creation order and told the user nothing true about
// the graph. So each move is checked against the model: the node focused
// after → must be one the previous node actually has an edge to.
//
// Also pinned: the viewport follows focus (the canvas has no scroll box —
// `#graph-layer` is a CSS transform, so `scrollIntoView` does nothing and
// panning has to be done by hand), and moves are announced with the edge
// counts, which is the only structural picture a screen reader gets.
//
// Read-only.
//
// Run:  node edit-a11y-canvas.test.js

const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

const PROBE_FN = 'web-server';

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium);
  console.log('edit-a11y-canvas — walking the graph by its edges');

  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  try {
    await page.goto('about:blank');
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.waitForFunction(
      () => graphReady() && !graph.animating && document.querySelectorAll('.node-overlay').length > 1,
      null, {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A — cards are focusable and named.
    // ===================================================================
    const cards = await page.evaluate(() => {
      const els = [...document.querySelectorAll('.node-overlay')];
      return {
        count: els.length,
        tabStops: els.filter((el) => el.getAttribute('tabindex') === '0').length,
        allFocusable: els.every((el) => el.hasAttribute('tabindex')),
        fnCardsNamed: els.filter((el) => el.dataset.originalFnId)
                         .every((el) => !!el.getAttribute('aria-label')),
        sampleName: els.find((el) => el.dataset.originalFnId)?.getAttribute('aria-label'),
      };
    });
    assert(cards.count > 1, 'the graph has several cards: ' + cards.count);
    assert(cards.allFocusable, 'every card is focusable');
    assert(cards.tabStops === 1,
           'exactly one card is the tab stop (roving tabindex), got ' + cards.tabStops);
    assert(cards.fnCardsNamed,
           'function cards carry an accessible name (e.g. "' + cards.sampleName + '")');

    // ===================================================================
    // Phase B — → follows an OUTGOING edge, ← comes back.
    // ===================================================================
    const startId = await page.evaluate(() => {
      // Start from a card that has at least one outgoing edge.
      const el = [...document.querySelectorAll('.node-overlay')]
        .find((n) => gv.node(n.dataset.nodeId)?.outgoingEdges().length > 0);
      if (!el) return null;
      el.focus();
      return el.dataset.nodeId;
    });

    if (!startId) {
      console.log('  – edge-walk skipped: no card with an outgoing edge');
    } else {
      const expected = await page.evaluate((id) => gv.node(id).outgoingEdges()
        .map((e) => e.target()?.id()).filter(Boolean), startId);

      await page.keyboard.press('ArrowRight');
      await page.waitForTimeout(500);
      const afterRight = await page.evaluate(() => document.activeElement?.dataset?.nodeId);
      assert(afterRight && afterRight !== startId, 'ArrowRight moves to another card');
      assert(expected.includes(afterRight),
             'and it is a node the previous one actually has an edge to — '
             + afterRight + ' ∈ ' + JSON.stringify(expected));

      // ← must land back on a node that has an edge TO here.
      const backExpected = await page.evaluate((id) => gv.node(id).incomingEdges()
        .map((e) => e.source()?.id()).filter(Boolean), afterRight);
      await page.keyboard.press('ArrowLeft');
      await page.waitForTimeout(500);
      const afterLeft = await page.evaluate(() => document.activeElement?.dataset?.nodeId);
      assert(backExpected.includes(afterLeft),
             'ArrowLeft follows an incoming edge back — ' + afterLeft
             + ' ∈ ' + JSON.stringify(backExpected));

      // Only ONE card is tabbable after all that movement.
      const stops = await page.evaluate(() => [...document.querySelectorAll('.node-overlay')]
        .filter((el) => el.getAttribute('tabindex') === '0').length);
      assert(stops === 1, 'still exactly one tab stop after moving, got ' + stops);
    }

    // ===================================================================
    // Phase C — the viewport follows focus.
    // ===================================================================
    // Pan the graph so the focused card is off-screen, then move focus and
    // check the pan changed. scrollIntoView cannot do this — the layer is a
    // CSS transform — so this is the assertion that the manual pan works.
    const panned = await page.evaluate(() => {
      const el = [...document.querySelectorAll('.node-overlay')]
        .find((n) => gv.node(n.dataset.nodeId)?.outgoingEdges().length > 0);
      el.focus();
      // Shove everything far to the left.
      setViewportPan(viewport.pan.x - 4000, viewport.pan.y);
      applyViewportTransform();
      return {panX: viewport.pan.x, id: el.dataset.nodeId};
    });
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(600);
    const afterPan = await page.evaluate(() => {
      const active = document.activeElement;
      const box = active.getBoundingClientRect();
      const view = document.getElementById('graph-container').getBoundingClientRect();
      return {
        panX: viewport.pan.x,
        onScreen: box.left >= view.left - 1 && box.right <= view.right + 1,
      };
    });
    assert(afterPan.panX !== panned.panX,
           'moving to an off-screen card pans the viewport (' + panned.panX
           + ' → ' + afterPan.panX + ')');
    assert(afterPan.onScreen, 'and the newly focused card is actually visible');

    // ===================================================================
    // Phase D — moves are announced, with the structure.
    // ===================================================================
    const spoken = await page.evaluate(async () => {
      const region = document.getElementById('gd-a11y-announcer');
      if (region) region.textContent = '';
      const el = [...document.querySelectorAll('.node-overlay')]
        .find((n) => gv.node(n.dataset.nodeId)?.outgoingEdges().length > 0);
      el.focus();
      return true;
    });
    assert(spoken, 'probe card focused');
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(700);
    const announcement = await page.evaluate(
      () => document.getElementById('gd-a11y-announcer')?.textContent || '');
    assert(announcement.length > 0, 'the move is announced: "' + announcement + '"');
    assert(/argument|consumer/.test(announcement),
           'and the announcement carries the edge structure, not just a name: "'
           + announcement + '"');

    // ===================================================================
    // Phase E — Enter opens the card in the inspector.
    // ===================================================================
    const opened = await page.evaluate(() => {
      const el = [...document.querySelectorAll('.node-overlay')]
        .find((n) => n.dataset.originalFnId);
      el.focus();
      return el.dataset.originalFnId;
    });
    await page.keyboard.press('Enter');
    await page.waitForTimeout(800);
    const inspector = await page.evaluate(() => ({
      hasTitle: !!document.querySelector('.gd-insp-name'),
      activeCard: !!document.querySelector('.node-overlay.gd-node-active'),
    }));
    assert(inspector.hasTitle && inspector.activeCard,
           'Enter opens the focused card in the inspector (fn ' + opened + ')');

    // ===================================================================
    // Phase F — the canvas keys do NOT reach inside a card.
    // ===================================================================
    // A card hosts the ⋯ trigger, its description tooltip and the row-action
    // popover. Those own Escape and Enter for their own purposes, so the
    // canvas must only claim a key when the CARD ITSELF has focus. The first
    // implementation claimed it anywhere in the subtree and marked it
    // consumed, which stole Escape from a popover open inside the card —
    // the landing gate found it via the tour-history suite, several tests
    // away from anything about the canvas.
    const notStolen = await page.evaluate(() => {
      const card = document.querySelector('.node-overlay');
      const inner = card.querySelector('button, [tabindex], span');
      if (!inner) return {skip: true};
      // Pretend a control inside the card has focus and fire Escape at it.
      let seenByOthers = false;
      const probe = (e) => { if (e.key === 'Escape') seenByOthers = !e.defaultPrevented; };
      document.addEventListener('keydown', probe);
      inner.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}));
      document.removeEventListener('keydown', probe);
      return {skip: false, seenByOthers};
    });
    if (notStolen.skip) {
      console.log('  – key-scope probe skipped: no inner element');
    } else {
      assert(notStolen.seenByOthers,
             'Escape aimed at a control INSIDE a card still reaches the handlers '
             + 'that own it — the canvas must not consume it');
    }

    assert(pageErrors.length === 0, 'no page errors: ' + JSON.stringify(pageErrors));
    console.log('a11y-canvas — PASS');
  } finally {
    await browser.close();
  }
})();
