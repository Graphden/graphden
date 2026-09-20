// Slot seals e2e — the lock badge, its popover, and the `+` gate
// (editor-overlay-seal.js; `edge-seal-fields` in layout/builder_helpers.clj).
//
// Coverage:
//   • A free slot on the reader's own fn carries a dim 🔓 badge; an optional
//     slot's badge says so (`data-seal="optional"`).
//   • The popover seals the slot (`:terminal`) and requires it here
//     (`:required`, the ratchet) in one Save; the badge turns 🔒 and the
//     stored binding carries both flags.
//   • On a list slot with an item, the popover closes the list
//     (`:list-closed`).
//   • A child extending the sealed fn shows a lock GHOST where the `+`
//     would be — for the sealed slot and for the closed list's tail — and
//     the server refuses the write the ghost stands for (belt and braces).
//   • Lifting the seal on the parent gives the child its `+` back.
//
// Run from this directory:  node edit-seals.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName, BASE} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const SUBS_P = 'seal-subs-parent' + RUN_ID;
const SUBS_C = 'seal-subs-child' + RUN_ID;
const JOIN_P = 'seal-join-parent' + RUN_ID;
const JOIN_C = 'seal-join-child' + RUN_ID;


async function cleanup(page) {
  for (const n of [SUBS_C, JOIN_C, SUBS_P, JOIN_P]) {
    try { await deleteFnByName(page, n); } catch (_) {}
  }
}


async function baseFn(page, name) {
  const ents = await getEntities(page, name);
  return ents.fns.find((f) => f.name === name && (f['parent-ids'] || []).length === 0);
}


async function createFn(page, name, parentId) {
  await api(page, 'POST', '/api/entities/fn', 'name=' + name + '&parent-ids=' + parentId);
  const fn = (await getEntities(page, name)).fns.find((f) => f.name === name);
  assert(fn, name + ' created');
  return fn;
}


// Open the canvas on `name` — a FULL page load each time (a hash-only hop
// from the previous canvas plus a forced `initGraph` is two renders racing,
// which is not what a reader does) — and wait until `waitSelector` is drawn.
async function openCanvas(page, name, waitSelector) {
  await page.goto(BASE + '/?t=' + Date.now().toString(36) + '#' + name);
  await page.waitForFunction(
    (n) => typeof graphReady === 'function' && graphReady()
           && !!document.querySelector('.node-overlay[data-fn-name="' + n + '"]') && !graph.animating,
    name, {timeout: 30000, polling: 100});
  await page.waitForSelector(waitSelector, {timeout: 15000});
}


// The seal badge on the edge label of `argName`, as facts.
async function badgeOf(page, argName) {
  return page.evaluate((n) => {
    const b = document.querySelector('.edge-label-overlay[data-arg-name="' + n + '"] .seal-badge');
    return b ? {glyph: b.textContent, seal: b.dataset.seal, on: b.classList.contains('seal-badge-on'),
                title: b.title} : null;
  }, argName);
}


// Open the popover from the badge, flip the given checkboxes, Save; returns
// the popover's error text ('' when it closed).
async function sealVia(page, argName, flips) {
  if (process.env.SEAL_DEBUG) {
    console.log(await page.evaluate((n) => Array.from(document.querySelectorAll('.edge-label-overlay[data-arg-name="' + n + '"]')).map((ov) => {
      let d = graph.edges.get(ov.dataset.edgeId)?.data || null;
      if (!d) { for (const e of graph.edges.values()) { if (e.data?.seqGroup && ov.dataset.seqGroup === e.data.seqGroup) { d = e.data; break; } } }
      const arg = d ? argRowFromNode(d) : null;
      const info = arg ? gdSealInfo(arg, d) : null;
      return JSON.stringify({cls: ov.className, fn: arg?.['fn-id'], slot: arg?.['slot-id'], own: info?.own, bfs: lookups.bindingsByFn.get(arg?.['fn-id'])});
    }), argName));
  }
  await page.click('.edge-label-overlay[data-arg-name="' + argName + '"] .seal-badge');
  await page.waitForSelector('.arg-value-edit-popover .seal-popover', {timeout: 5000});
  const shape = await page.evaluate(() => Array.from(
    document.querySelectorAll('.arg-value-edit-popover input[data-seal]'))
    .map((i) => ({seal: i.dataset.seal, checked: i.checked, disabled: i.disabled,
                  hint: i.parentNode.querySelector('.seal-popover-hint')?.textContent || ''})));
  for (const key of flips) {
    const box = shape.find((s) => s.seal === key);
    assert(box && !box.disabled, 'the popover offers "' + key + '" enabled: ' + JSON.stringify(shape));
    await page.click('.arg-value-edit-popover input[data-seal="' + key + '"]');
  }
  await page.click('.arg-value-edit-popover .arg-value-edit-btn:not(.arg-value-edit-btn-secondary)');
  await page.waitForFunction(
    () => !document.querySelector('.arg-value-edit-popover')
          || document.querySelector('.arg-value-edit-popover .arg-value-edit-error.visible'),
    null, {timeout: 10000});
  const err = await page.evaluate(() =>
    document.querySelector('.arg-value-edit-popover .arg-value-edit-error.visible')?.textContent || '');
  return {shape, err};
}


async function bindingOf(page, fnId, slotName) {
  const d = await getEntities(page, fnId);
  const slot = (d.slots || []).find((s) => s.name === slotName);
  return (d.bindings || []).find((b) => b['fn-id'] === fnId && b['slot-id'] === slot?.id) || null;
}


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept(); });
  console.log('edit-seals — the lock badge, its popover, the + gate');

  try {
    await cleanup(page);
    await page.goto(BASE + '/');
    await page.waitForSelector('#auth-lock-btn', {timeout: 10000});

    // ===================================================================
    // Seed: a fn on :subs (string / start required, end OPTIONAL) and one
    // on :str-join (coll is a LIST) — each with a child extending it.
    // ===================================================================
    const subs = await baseFn(page, 'subs');
    const join = await baseFn(page, 'str-join');
    assert(subs && join, ':subs and :str-join baselines resolved');
    const subsP = await createFn(page, SUBS_P, subs.id);
    const joinP = await createFn(page, JOIN_P, join.id);
    const subsC = await createFn(page, SUBS_C, subsP.id);
    const joinC = await createFn(page, JOIN_C, joinP.id);
    // One item on the parent's list — closing needs a list binding to close.
    const appended = await page.evaluate(async (id) => {
      const r = await window.authFetch('/api/sequence/append/' + encodeURIComponent(id), {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({value: 'a'})});
      return r.status;
    }, joinP.id);
    assert(appended >= 200 && appended < 300, 'one item appended on the join parent: ' + appended);

    // ===================================================================
    // Phase A: the badges on the parent — dim 🔓 on a free slot the reader
    // may seal; the optional slot says it is optional.
    // ===================================================================
    await openCanvas(page, SUBS_P, '.edge-label-overlay[data-arg-name="end"] .seal-badge');
    const endBadge = await badgeOf(page, 'end');
    const startBadge = await badgeOf(page, 'start');
    assert(endBadge && !endBadge.on && endBadge.glyph === '🔓',
           'a free slot on my own fn carries the dim 🔓 affordance: ' + JSON.stringify(endBadge));
    assert(endBadge.seal === 'optional' && /Optional — the fn runs without it/.test(endBadge.title),
           'the optional slot says so on its badge: ' + JSON.stringify(endBadge));
    assert(startBadge && startBadge.seal === '' && /No seal on this slot/.test(startBadge.title),
           'a required-by-declaration slot with no seal says nothing more: ' + JSON.stringify(startBadge));
    console.log('  badges: 🔓 on my free slots, optional named');

    // ===================================================================
    // Phase B: seal :end and require it here, in one Save.
    // ===================================================================
    const sealed = await sealVia(page, 'end', ['terminal', 'required']);
    assert(sealed.err === '', 'the popover saved without a refusal: ' + sealed.err);
    const offered = Object.fromEntries(sealed.shape.map((s) => [s.seal, s]));
    assert(offered.terminal && !offered.terminal.disabled && !offered.terminal.checked,
           'the popover offered "seal" unchecked: ' + JSON.stringify(sealed.shape));
    assert(offered.required && !offered.required.disabled,
           'and "require it here" — the slot is optional by declaration');
    assert(!offered['list-closed'] && !offered['slot-optional'],
           'no list toggle on a scalar slot, no declaration toggle on a slot I do not own: '
           + JSON.stringify(sealed.shape));
    await page.waitForFunction(
      () => document.querySelector(
        '.edge-label-overlay[data-arg-name="end"] .seal-badge.seal-badge-on'),
      null, {timeout: 15000});
    const endAfter = await badgeOf(page, 'end');
    assert(endAfter.on && endAfter.glyph === '🔒' && endAfter.seal === 'terminal required',
           'the badge turned 🔒 and names both seals: ' + JSON.stringify(endAfter));
    assert(/Sealed here/.test(endAfter.title) && /required here/.test(endAfter.title),
           'its tooltip explains both: ' + endAfter.title);
    const endBinding = await bindingOf(page, subsP.id, 'end');
    assert(endBinding && endBinding.terminal === true && endBinding.required === true,
           'the stored binding carries terminal + required: ' + JSON.stringify(endBinding));
    assert(endBinding['value-present'] !== true && !endBinding['ref-fn-id'],
           'and no value — a sealed TEMPLATE slot, still free on the parent');
    console.log('  popover: sealed + required in one save, badge 🔒, binding flagged');

    // ===================================================================
    // Phase C: close the list on the join parent.
    // ===================================================================
    await openCanvas(page, JOIN_P, '.edge-label-overlay[data-arg-name="coll"] .seal-badge');
    const closed = await sealVia(page, 'coll', ['list-closed']);
    assert(closed.err === '', 'closing the list saved: ' + closed.err);
    const lc = closed.shape.find((s) => s.seal === 'list-closed');
    assert(lc && !lc.disabled, 'the list toggle was offered, enabled — the list has an item');
    await page.waitForSelector('.edge-label-overlay[data-arg-name="coll"] .seal-badge.seal-badge-on',
                               {timeout: 15000});
    const collBinding = await bindingOf(page, joinP.id, 'coll');
    assert(collBinding && collBinding['list-closed'] === true && collBinding['list-append'] === true,
           'the list binding is closed and still the items\' host: ' + JSON.stringify(collBinding));
    const tailOnParent = await page.evaluate(
      () => document.querySelectorAll('.placeholder-binder.is-seq-anchor').length);
    assert(tailOnParent === 1, 'the parent keeps its own append tail — closed is for descendants: '
           + tailOnParent);
    console.log('  list: closed on the parent, parent still appends');

    // ===================================================================
    // Phase D: the children — a lock ghost where the + would be, the seal
    // named on the edge, and the server refusing the write behind it.
    // ===================================================================
    try {
      await openCanvas(page, SUBS_C, '.placeholder-sealed[data-arg-name="end"]');
    } catch (e) {
      console.log('  [debug] child canvas: ' + JSON.stringify(await page.evaluate(() => ({
        placeholders: Array.from(document.querySelectorAll('.placeholder-overlay')).map((p) => p.className + ' ' + (p.dataset.argName || '') + ' ' + p.textContent),
        labels: Array.from(document.querySelectorAll('.edge-label-overlay')).map((l) => l.dataset.argName + ':' + (l.querySelector('.seal-badge')?.dataset.seal ?? 'nobadge')),
        nodes: [...graph.nodes.values()].filter((n) => (n.data || {}).isPlaceholder).map((n) => n.data.argId + ' sealedBy=' + n.data.sealedBy + ' fn=' + n.data.fnId),
        cards: Array.from(document.querySelectorAll('.node-overlay[data-fn-name]')).map((c) => c.dataset.fnName),
      }))));
      throw e;
    }
    const child = await page.evaluate(() => ({
      ghost: document.querySelector('.placeholder-sealed[data-arg-name="end"] .seal-ghost')?.title || '',
      endPlus: !!document.querySelector('.placeholder-binder[data-arg-name="end"]'),
      startPlus: !!document.querySelector('.placeholder-binder[data-arg-name="start"]'),
      badge: document.querySelector('.edge-label-overlay[data-arg-name="end"] .seal-badge')?.dataset.seal,
      badgeTitle: document.querySelector('.edge-label-overlay[data-arg-name="end"] .seal-badge')?.title || '',
    }));
    assert(!child.endPlus && /Sealed in seal-subs-parent/.test(child.ghost),
           'the child shows a lock ghost naming the sealer instead of a + on :end: ' + JSON.stringify(child));
    assert(child.startPlus, 'its other free slot keeps its +');
    assert(child.badge === 'terminal required' && /Sealed in seal-subs-parent/.test(child.badgeTitle)
           && /required since seal-subs-parent/.test(child.badgeTitle),
           'the edge badge on the child names where each seal comes from: ' + JSON.stringify(child));
    const refused = await page.evaluate(async ({fnId, slotId}) => {
      const r = await window.authFetch('/api/entities/binding', {
        method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: 'fn-id=' + fnId + '&slot-id=' + slotId + '&value=' + encodeURIComponent('3')});
      return {status: r.status, text: await r.text()};
    }, {fnId: subsC.id, slotId: endBinding['slot-id']});
    assert(refused.status >= 400 && /terminal|seal/i.test(refused.text),
           'the server refuses the bind the ghost stands for: ' + refused.status + ' '
           + refused.text.slice(0, 160));

    // An inherited list is the parent's binding: the child's card shows it
    // only unfolded (lesson 06) — and there its tail is a lock.
    await openCanvas(page, JOIN_C, '.node-overlay[data-fn-name="' + JOIN_C + '"] .ancestor-line[data-level="1"]');
    const joinRow = await page.$('.node-overlay[data-fn-name="' + JOIN_C + '"] .ancestor-line[data-level="1"]');
    await joinRow.click({position: {x: 40, y: 8}});
    await page.waitForSelector('.placeholder-sealed[data-arg-name="coll"]', {timeout: 30000});
    const joinChild = await page.evaluate(() => ({
      ghost: document.querySelector('.placeholder-sealed[data-arg-name="coll"] .seal-ghost')?.title || '',
      tail: document.querySelectorAll('.placeholder-binder.is-seq-anchor').length,
    }));
    assert(joinChild.tail === 0 && /List closed in seal-join-parent/.test(joinChild.ghost),
           'the closed list shows a ghost, not an append tail, on the child: ' + JSON.stringify(joinChild));
    console.log('  children: ghosts instead of +, server refuses behind them');

    // ===================================================================
    // Phase E: lift the seal on the parent — the child gets its + back.
    // ===================================================================
    await openCanvas(page, SUBS_P, '.edge-label-overlay[data-arg-name="end"] .seal-badge.seal-badge-on');
    const lifted = await sealVia(page, 'end', ['terminal']);
    assert(lifted.err === '', 'lifting the seal saved: ' + lifted.err);
    const lift = lifted.shape.find((s) => s.seal === 'terminal');
    assert(lift && lift.checked, 'the popover showed the seal as set before the flip');
    await page.waitForFunction(() => {
      const b = document.querySelector('.edge-label-overlay[data-arg-name="end"] .seal-badge');
      return b && b.dataset.seal === 'required';
    }, null, {timeout: 15000});
    const endLifted = await bindingOf(page, subsP.id, 'end');
    assert(endLifted && endLifted.terminal !== true && endLifted.required === true,
           'terminal lifted, the ratchet stays: ' + JSON.stringify(endLifted));
    await openCanvas(page, SUBS_C, '.placeholder-binder[data-arg-name="end"]');
    const childAfter = await page.evaluate(() => ({
      plus: !!document.querySelector('.placeholder-binder[data-arg-name="end"]'),
      ghost: !!document.querySelector('.placeholder-sealed[data-arg-name="end"]'),
      dim: document.querySelector('.placeholder-binder[data-arg-name="end"]')?.classList.contains('is-optional'),
    }));
    assert(childAfter.plus && !childAfter.ghost, 'the child has its + on :end back: ' + JSON.stringify(childAfter));
    console.log('  lift: the seal comes off where it was set, the child binds again');

    console.log('PASS');
  } catch (e) {
    console.error('FAIL:', e.message);
    process.exitCode = 1;
  } finally {
    await cleanup(page);
    await browser.close();
  }
})();
