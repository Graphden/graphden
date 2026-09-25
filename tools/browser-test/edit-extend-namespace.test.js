// Extend-namespace e2e — the Extend popover's "in <ns>" line + the
// unlocked namespace-move for in-use fns.
//
// Coverage:
//   • Extending a PACKAGE fn (:add) with a last-used namespace set →
//     the popover's select defaults to THAT namespace (not the
//     package's), and Save lands the child there.
//   • Extending the user's OWN fn → the select defaults to the
//     parent's namespace (module stays together).
//   • A fn WITH a child (in use) still offers "Move to another
//     namespace…" in its ⋯ → Namespace menu — the old isFnEditable
//     gate hid it, making mature namespaces unreorganisable.
//
// Run from this directory:  node edit-extend-namespace.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName,
       waitForServerHealthy} = require('./edit-test-helpers');

const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const CHILD_OF_PKG = 'extns-child-pkg' + RUN_ID;
const OWN_FN = 'extns-own' + RUN_ID;
const CHILD_OF_OWN = 'extns-child-own' + RUN_ID;
const SUB_NS = 'extnssub' + RUN_ID;
const CHILD_IN_SUB = 'extns-child-sub' + RUN_ID;

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

async function cleanup(page) {
  for (const n of [CHILD_IN_SUB, CHILD_OF_OWN, CHILD_OF_PKG, OWN_FN]) {
    try { await deleteFnByName(page, n); } catch (_) {}
  }
  // The sub-namespace phase D creates — emptied above, so the delete lands.
  try {
    const ents = await getEntities(page);
    const sub = (ents.namespaces || []).find((n) => n.name === SUB_NS);
    if (sub) await api(page, 'DELETE', '/api/entities/ns/' + sub.id);
  } catch (_) {}
}

// Open the ⋯ row-actions menu on the CARD of `ownerName` and pick `action`
// in it. Each find-and-act runs inside ONE poll: the canvas re-renders its
// card overlays (and the popover with them) on its own schedule, and a
// one-shot lookup that landed between a teardown and its repaint found
// nothing — `ov` undefined, a TypeError, a red gate.
async function pickCardAction(page, ownerName, action) {
  await page.waitForFunction((name) => {
    const ov = Array.from(document.querySelectorAll('.node-overlay')).find((o) =>
      o.textContent.trim().startsWith(name)
      && o.querySelector('button.more-actions-trigger'));
    if (!ov) return false;
    ov.querySelector('button.more-actions-trigger')
      .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    return true;
  }, ownerName, {timeout: 90000, polling: 200});
  await page.waitForFunction((act) => {
    const item = document.querySelector('.row-actions-popover [data-action="' + act + '"]');
    if (!item) return false;
    item.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    return true;
  }, action, {timeout: 15000, polling: 100});
}

// Open the Extend popover on the CARD of `ownerName` (must be the
// selected fn's own card on the canvas).
async function openExtendPopover(page, ownerName) {
  await pickCardAction(page, ownerName, 'extend-fn');
  await page.waitForSelector('.arg-value-edit-popover .extend-ns-select',
    {timeout: 15000});
}

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-extend-namespace — "in <ns>" defaults + in-use ns-move');
  let failed = false;

  try {
    await cleanup(page);
    await waitForServerHealthy();

    const ents = await getEntities(page);
    const appNs = (ents.namespaces || []).find((n) => n.name === 'app');
    const coreNs = (ents.namespaces || []).find((n) => n.name === 'core');
    assert(appNs && coreNs, 'baseline namespaces resolved (:app + :core)');

    // ================================================================
    // Phase A: package parent → the child defaults to the PARENT's ns
    // (core.arithmetic), whatever namespace was used last.
    // ================================================================
    await page.goto(BASE + '/#core.arithmetic.add');
    await page.waitForFunction(
      () => typeof graphData !== 'undefined'
        && (graphData?.fns || []).some((f) => f.name === 'add'),
      null, {timeout: 30000, polling: 100});
    // A remembered last-used namespace must NOT win over the parent's.
    await page.evaluate((nsId) => gdRememberLastNs(nsId), appNs.id);
    const arithNs = (await getEntities(page)).namespaces
      .find((n) => n.name === 'arithmetic' && n['parent-id'] === coreNs.id);
    assert(arithNs, 'core.arithmetic resolved');

    await openExtendPopover(page, 'add');
    const pkgDefault = await page.evaluate(() =>
      document.querySelector('.arg-value-edit-popover .extend-ns-select').value);
    assert(pkgDefault === arithNs.id,
      "package parent defaults to the PARENT's ns (core.arithmetic), not the last-used one"
      + ' (got ' + pkgDefault + ')');
    // ↑ walks up the path: core.arithmetic → core → (root), then disables.
    await page.click('.arg-value-edit-popover .extend-ns-up');
    const afterUp = await page.evaluate(() => ({
      value: document.querySelector('.arg-value-edit-popover .extend-ns-select').value,
      disabled: document.querySelector('.arg-value-edit-popover .extend-ns-up').disabled,
    }));
    assert(afterUp.value === coreNs.id && !afterUp.disabled,
      '↑ moves the choice to the parent namespace (core): ' + JSON.stringify(afterUp));
    await page.click('.arg-value-edit-popover .extend-ns-up');
    const atRoot = await page.evaluate(() => ({
      value: document.querySelector('.arg-value-edit-popover .extend-ns-select').value,
      disabled: document.querySelector('.arg-value-edit-popover .extend-ns-up').disabled,
    }));
    assert(atRoot.value === '' && atRoot.disabled,
      '↑ reaches (root) and disables: ' + JSON.stringify(atRoot));
    // The test's child goes to :app, chosen by hand — the select still works.
    await page.selectOption('.arg-value-edit-popover .extend-ns-select', appNs.id);
    await page.evaluate((name) => {
      const pop = document.querySelector('.arg-value-edit-popover');
      const input = pop.querySelector('.arg-value-edit-input');
      input.value = name;
      input.dispatchEvent(new Event('input', {bubbles: true}));
      Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
        .find((b) => b.textContent.trim() === 'Save').click();
    }, CHILD_OF_PKG);
    await page.waitForFunction((name) =>
      (graphData?.fns || []).some((f) => f.name === name),
      CHILD_OF_PKG, {timeout: 30000, polling: 200});
    const pkgChild = (await getEntities(page, CHILD_OF_PKG)).fns.find(
      (f) => f.name === CHILD_OF_PKG);
    assert(pkgChild && pkgChild['namespace-id'] === appNs.id,
      'child of the package fn landed in :app');
    console.log("  phase A: package-parent extend defaulted to the parent's ns; ↑ walks up ✓");

    // ================================================================
    // Phase B: own parent → child defaults to the PARENT's ns.
    // ================================================================
    const constFn = (await getEntities(page)).fns.find((f) => f.name === 'const');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + OWN_FN + '&parent-ids=' + constFn.id
              + '&namespace-id=' + coreNs.id);
    await page.goto(BASE + '/#core.' + OWN_FN);
    await page.waitForFunction((name) =>
      (graphData?.fns || []).some((f) => f.name === name),
      OWN_FN, {timeout: 30000, polling: 200});
    await openExtendPopover(page, OWN_FN);
    const ownDefault = await page.evaluate(() =>
      document.querySelector('.arg-value-edit-popover .extend-ns-select').value);
    assert(ownDefault === coreNs.id,
      "own parent defaults to the parent's ns (got " + ownDefault + ')');
    // Save a child so OWN_FN becomes IN USE for phase C.
    await page.evaluate((name) => {
      const pop = document.querySelector('.arg-value-edit-popover');
      const input = pop.querySelector('.arg-value-edit-input');
      input.value = name;
      input.dispatchEvent(new Event('input', {bubbles: true}));
      Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
        .find((b) => b.textContent.trim() === 'Save').click();
    }, CHILD_OF_OWN);
    await page.waitForFunction((name) =>
      (graphData?.fns || []).some((f) => f.name === name),
      CHILD_OF_OWN, {timeout: 30000, polling: 200});
    console.log('  phase B: own-parent extend defaulted to :core ✓');

    // ================================================================
    // Phase D: `+` creates a NEW sub-namespace under the choice, typed
    // as one segment, and the child lands in it — one Save.
    // ================================================================
    await page.goto(BASE + '/#core.' + OWN_FN);
    await page.waitForFunction((name) =>
      (graphData?.fns || []).some((f) => f.name === name),
      OWN_FN, {timeout: 30000, polling: 200});
    await openExtendPopover(page, OWN_FN);
    await page.click('.arg-value-edit-popover .extend-ns-plus');
    const subRow = await page.evaluate(() => ({
      hidden: document.querySelector('.arg-value-edit-popover .extend-ns-new').hidden,
      prefix: document.querySelector('.arg-value-edit-popover .extend-ns-new-prefix').textContent,
    }));
    assert(!subRow.hidden && subRow.prefix === 'core.',
      '+ opens the sub-namespace row with the parent path as prefix: ' + JSON.stringify(subRow));
    await page.fill('.arg-value-edit-popover .extend-ns-new-input', SUB_NS);
    await page.evaluate((name) => {
      const pop = document.querySelector('.arg-value-edit-popover');
      const input = pop.querySelector('.arg-value-edit-input');
      input.value = name;
      input.dispatchEvent(new Event('input', {bubbles: true}));
      Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
        .find((b) => b.textContent.trim() === 'Save').click();
    }, CHILD_IN_SUB);
    await page.waitForFunction((name) =>
      (graphData?.fns || []).some((f) => f.name === name),
      CHILD_IN_SUB, {timeout: 30000, polling: 200});
    const all = await getEntities(page, CHILD_IN_SUB);
    const subNs = (all.namespaces || []).find((n) => n.name === SUB_NS && n['parent-id'] === coreNs.id);
    const subChild = (all.fns || []).find((f) => f.name === CHILD_IN_SUB);
    assert(subNs, 'the sub-namespace core.' + SUB_NS + ' was created');
    assert(subChild && subChild['namespace-id'] === subNs.id,
      'the child landed in the new sub-namespace');
    console.log('  phase D: + created core.' + SUB_NS + ' and the child landed there ✓');

    // ================================================================
    // Phase C: OWN_FN now has a child — its ⋯ → Namespace menu must
    // still offer the move (the old gate hid it for in-use fns).
    // ================================================================
    await page.goto(BASE + '/#core.' + OWN_FN);
    // The `ns` entry lives INSIDE the ⋯ row-actions popover on OWN_FN's card.
    await pickCardAction(page, OWN_FN, 'namespace-move');
    await page.waitForSelector('.ns-menu', {timeout: 15000});
    const nsMenu = await page.evaluate(() => ({
      buttons: Array.from(document.querySelectorAll('.ns-menu .ns-menu-btn'))
        .map((b) => b.textContent),
    }));
    assert(nsMenu.buttons.some((t) => /Move to another namespace/.test(t)),
      'in-use fn still offers Move (got ' + JSON.stringify(nsMenu.buttons) + ')');
    console.log('  phase C: in-use fn offers Move to another namespace ✓');
  } catch (e) {
    console.error('FAIL:', e.message);
    failed = true;
  } finally {
    try { await cleanup(page); } catch (_) {}
    await browser.close();
  }
  console.log(failed ? 'FAIL' : 'PASS');
  process.exit(failed ? 1 : 0);
})();
