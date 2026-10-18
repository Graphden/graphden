// Component palette on a hiccup :children chain — the append chooser's
// "Append fn-ref" picker is typed by the chain's ELEMENT type, so the
// component library is its Compatible section; a named instance is made
// by appending the component and extending it in place (which retired the
// separate "New from template…" button, 2026-09-16).
//
// Coverage:
//   • Seed an owner fn with parent :stack (a hiccup :children chain).
//   • The chooser offers Append literal / Append fn-ref — and no template button.
//   • /api/types/candidates with the elem type (hiccup-node) lists the
//     component library; the picker's Compatible section lists :button.
//   • Pick :button → a ref item; ⋯ → Extend on its card → the item now
//     references the child (parent-ids = [button]); the owner still executes.
//
// Run from this directory:  node edit-append-fnref-palette.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');
const {extendInPlace} = require('./tutorial-tour-helpers');

const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const OWNER_FN = 'pal-owner' + RUN_ID;
const INST_FN = 'pal-btn' + RUN_ID;

async function paletteActionSnapshot(page) {
  return page.evaluate(() => {
      const proof = window.paletteActionProof?.state;
      if (!proof) return {observerInstalled: false};
      const overlays = proof.candidateId ? [...document.querySelectorAll('.node-overlay')]
        .filter(el => el.dataset.originalFnId === proof.candidateId).slice(0, 4) : [];
      const nodeIds = [...graph.nodes.values()].filter(node => node.data.originalFnId === proof.candidateId).map(node => node.id).slice(0, 4);
      const bindings = (graphData.bindings || []).filter(row => row['fn-id'] === proof.appendOwnerId);
      const bindingIds = new Set(bindings.map(row => row.id));
      return {observerInstalled: true, candidateId: proof.candidateId, appendOwnerId: proof.appendOwnerId,
        appendPending: proof.appendPending, appendResult: proof.appendResult, selectedRootId: selectedFnId,
        renderGeneration: _renderGen, pendingRenders: [...proof.pendingRenders.keys()].slice(0, 4), pendingLoads: proof.pendingLoads.size,
        targetNodeIds: nodeIds, targetOverlays: overlays.map(el => {
          const r = el.getBoundingClientRect();
          return {nodeId: el.dataset.nodeId, connected: el.isConnected, x: r.x, y: r.y, width: r.width, height: r.height,
            nativeRootActions: el.querySelectorAll('.ancestor-line[data-level="0"] button.more-actions-trigger').length};
        }),
        bindingIds: [...bindingIds].slice(0, 4), itemBindingIds: (graphData['list-items'] || [])
          .filter(row => bindingIds.has(row['binding-id']) && row['ref-fn-id'] === proof.candidateId)
          .map(row => row['binding-id']).slice(0, 4)};
    }).catch(() => ({observerUnavailable: true}));
}

async function cleanup(page) {
  // Owner first (its item references the instance), then the instance.
  try { await deleteFnByName(page, OWNER_FN); } catch (_) {}
  try { await deleteFnByName(page, INST_FN); } catch (_) {}
}

(async () => {
  const {browser, page} = await newContext(chromium);
  const actionReplies = [], pendingReplies = [];
  page.on('response', response => {
    if (response.request().method() !== 'POST' || !new URL(response.url()).pathname.startsWith('/api/sequence/append/')) return;
    const receipt = {status: response.status(), bindingId: null};
    actionReplies.push(receipt);
    pendingReplies.push((async () => {
      const body = await response.json().catch(() => null);
      receipt.bindingId = typeof body?.['binding-id'] === 'string'
        && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(body['binding-id']) ? body['binding-id'] : null;
    })());
  });
  console.log('edit-append-fnref-palette — typed picker → component appended → extended in place');
  try {
    await cleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    const stackFn = (await getEntities(page, 'stack')).fns.find((f) => f.name === 'stack');
    assert(stackFn, ':stack exists');
    await api(page, 'POST', '/api/entities/fn', 'name=' + OWNER_FN + '&parent-ids=' + stackFn.id);
    const owner = (await getEntities(page, OWNER_FN)).fns.find((f) => f.name === OWNER_FN);
    assert(owner, 'owner fn created');

    const candidates = await page.evaluate(async () => {
      const r = await authFetch(API.api_types_candidates, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({expected: 'hiccup-node'})});
      return ((await r.json()).candidates || []).map((c) => c.name);
    });
    for (const comp of ['button', 'card', 'heading', 'paragraph']) {
      assert(candidates.includes(comp), 'palette (hiccup-node candidates) offers :' + comp);
    }

    await page.goto(BASE + '/#' + OWNER_FN);
    await page.waitForSelector('.placeholder-binder[data-fn-name="' + OWNER_FN + '"]', {timeout: 60000});
    await page.click('.placeholder-binder[data-fn-name="' + OWNER_FN + '"]');
    await page.waitForSelector('.free-arg-bind-chooser', {timeout: 15000});
    const labels = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button')).map((b) => b.textContent.trim()));
    assert(!labels.some((l) => l.includes('New from template')),
      'the chooser no longer offers a template button: ' + JSON.stringify(labels));
    assert(labels.includes('Append fn-ref'), 'the chooser offers Append fn-ref: ' + JSON.stringify(labels));
    await page.evaluate(() => Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
      .find((b) => b.textContent.trim() === 'Append fn-ref').click());
    await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
    const expected = await page.evaluate(() => document.querySelector('.fn-picker-expected')?.textContent || '');
    // hiccup-node is a union alias — the header prints its structural form
    // (bool|float|int|null|text|[any]), not the alias name; what matters is
    // that the picker IS typed (a header shows) and :button lands in the
    // Compatible section below.
    assert(/^Expected: .+/.test(expected), 'the picker is typed by the element type: ' + expected);
    await page.fill('.fn-picker-popover input', 'button');
    const rowSel = '.fn-picker-popover .fn-picker-row.fn-picker-row-compat[data-fn-name$=".button"]';
    await page.waitForSelector(rowSel, {timeout: 30000});
    // Observe the native promises without awaiting or changing their order.
    // Picker dismissal alone is not a receipt that append/render completed.
    await page.evaluate(() => {
      const state = {candidateId: null, appendOwnerId: null, appendPending: false, appendResult: null,
        pendingRenders: new Map(), pendingLoads: new Set(), appendPromise: null};
      const append = postSequenceAppend, render = renderGraph, load = loadGraphData;
      postSequenceAppend = function(fnId, body) {
        state.candidateId = body.ref || null; state.appendOwnerId = fnId; state.appendPending = true;
        const result = append.apply(this, arguments);
        state.appendPromise = result;
        void Promise.resolve(result).then(value => { state.appendPending = false; state.appendResult = value === true; },
          () => { state.appendPending = false; state.appendResult = false; });
        return result;
      };
      renderGraph = function() {
        const result = render.apply(this, arguments), generation = _renderGen;
        state.pendingRenders.set(generation, result);
        void Promise.resolve(result).then(() => state.pendingRenders.delete(generation), () => state.pendingRenders.delete(generation));
        return result;
      };
      loadGraphData = function() {
        const result = load.apply(this, arguments);
        state.pendingLoads.add(result);
        void Promise.resolve(result).then(() => state.pendingLoads.delete(result), () => state.pendingLoads.delete(result));
        return result;
      };
      window.paletteActionProof = {state, restore() { postSequenceAppend = append; renderGraph = render; loadGraphData = load; }};
    });
    await page.click(rowSel);
    await page.waitForFunction(() => !document.querySelector('.fn-picker-popover'), null, {timeout: 30000, polling: 150});
    const deadline = Date.now() + 60000;
    await page.evaluate(async timeout => {
      const state = paletteActionProof.state;
      if (!state.appendPromise) throw Error('Picker closed without an observed append');
      let timer;
      try {
        await Promise.race([(async () => {
          if (await state.appendPromise !== true) throw Error('Native append did not succeed');
          do {
            await Promise.all([...state.pendingLoads, ...state.pendingRenders.values()]);
          } while (state.pendingLoads.size || state.pendingRenders.size);
        })(), new Promise((_resolve, reject) => { timer = setTimeout(() => reject(Error('Native append render deadline')), timeout); })]);
      } finally { clearTimeout(timer); }
    }, Math.max(1, deadline - Date.now()));
    await page.waitForFunction(expectedRoot => {
      const state = paletteActionProof.state;
      if (selectedFnId !== expectedRoot || state.appendResult !== true || state.pendingLoads.size || state.pendingRenders.size) return false;
      const bindings = new Set((graphData.bindings || []).filter(row => row['fn-id'] === state.appendOwnerId).map(row => row.id));
      if (!(graphData['list-items'] || []).some(row => bindings.has(row['binding-id']) && row['ref-fn-id'] === state.candidateId)) return false;
      return [...document.querySelectorAll('.node-overlay')].some(el => el.dataset.originalFnId === state.candidateId
        && [...el.querySelectorAll('.ancestor-line[data-level="0"] button.more-actions-trigger')].some(button => {
          const r = button.getBoundingClientRect(); return r.width > 0 && r.height > 0;
        }));
    }, owner.id, {timeout: Math.max(1, deadline - Date.now())});
    let replyTimer;
    try {
      await Promise.race([Promise.all(pendingReplies), new Promise((_resolve, reject) => {
        replyTimer = setTimeout(() => reject(Error('Append receipt deadline')), Math.max(1, deadline - Date.now()));
      })]);
    } finally { clearTimeout(replyTimer); }
    assert(actionReplies.length === 1 && actionReplies[0].status === 200,
      'the actual append returned HTTP 200 before extending its rendered native card');
    await extendInPlace(page, 'button', INST_FN);
    const inst = (await getEntities(page, INST_FN)).fns.find((f) => f.name === INST_FN);
    const buttonFn = (await getEntities(page, 'button')).fns.find((f) => f.name === 'button');
    assert(inst && (inst['parent-ids'] || []).includes(buttonFn.id), 'the instance is parented to :button');
    const after = await getEntities(page, OWNER_FN);
    const bindIds = after.bindings.filter((b) => b['fn-id'] === owner.id).map((b) => b.id);
    const items = (after['list-items'] || []).filter((it) => bindIds.includes(it['binding-id']));
    assert(items.length === 1 && items[0]['ref-fn-id'] === inst.id,
      'the chain item now references the instance: ' + JSON.stringify(items));

    const exec = await page.evaluate(async (ownerName) => {
      const r = await authFetch(API.api_execute, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({'fn-name': ownerName, args: {label: 'Hi'}})});
      return await r.json();
    }, OWNER_FN);
    assert(exec.status === 'succeeded', 'owner executes: ' + JSON.stringify(exec).slice(0, 120));
    assert(JSON.stringify(exec.result).includes('"button"'),
      'rendered hiccup contains the button instance: ' + JSON.stringify(exec.result).slice(0, 120));

    await cleanup(page);
    console.log('PASS');
    await page.evaluate(() => window.paletteActionProof?.restore());
    await browser.close();
    process.exit(0);
  } catch (e) {
    const action = await paletteActionSnapshot(page);
    console.error(JSON.stringify({diagnostic: 'palette-append-failure', action, responses: actionReplies.slice(-4)}));
    console.error('FAIL:', e.message);
    try { await cleanup(page); } catch (_) {}
    await browser.close();
    process.exit(1);
  }
})();
