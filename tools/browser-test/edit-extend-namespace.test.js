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

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

async function cleanup(page) {
  for (const n of [CHILD_OF_OWN, CHILD_OF_PKG, OWN_FN]) {
    try { await deleteFnByName(page, n); } catch (_) {}
  }
}

// Open the Extend popover on the CARD of `ownerName` (must be the
// selected fn's own card on the canvas).
async function openExtendPopover(page, ownerName) {
  await page.waitForFunction((name) => {
    return Array.from(document.querySelectorAll('.node-overlay')).some((ov) =>
      ov.textContent.trim().startsWith(name)
      && ov.querySelector('button.more-actions-trigger'));
  }, ownerName, {timeout: 90000, polling: 200});
  await page.evaluate((name) => {
    const ov = Array.from(document.querySelectorAll('.node-overlay')).find((o) =>
      o.textContent.trim().startsWith(name)
      && o.querySelector('button.more-actions-trigger'));
    ov.querySelector('button.more-actions-trigger')
      .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
  }, ownerName);
  await page.waitForSelector('.row-actions-popover [data-action="extend-fn"]',
    {timeout: 15000});
  await page.evaluate(() => {
    document.querySelector('.row-actions-popover [data-action="extend-fn"]')
      .dispatchEvent(new MouseEvent('click', {bubbles: true}));
  });
  await page.waitForSelector('.arg-value-edit-popover .extend-ns-select',
    {timeout: 15000});
}

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
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
    // Phase A: package parent + remembered ns → child lands in MY ns.
    // ================================================================
    await page.goto(BASE + '/#core.arithmetic.add');
    await page.waitForFunction(
      () => typeof graphData !== 'undefined'
        && (graphData?.fns || []).some((f) => f.name === 'add'),
      null, {timeout: 30000, polling: 100});
    // Remember :app as the last-used namespace, as if the user had
    // just created something there.
    await page.evaluate((nsId) => gdRememberLastNs(nsId), appNs.id);

    await openExtendPopover(page, 'add');
    const pkgDefault = await page.evaluate(() =>
      document.querySelector('.arg-value-edit-popover .extend-ns-select').value);
    assert(pkgDefault === appNs.id,
      'package parent defaults to the LAST-USED ns, not core.arithmetic'
      + ' (got ' + pkgDefault + ')');
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
    console.log('  phase A: package-parent extend defaulted to :app ✓');

    // ================================================================
    // Phase B: own parent → child defaults to the PARENT's ns.
    // ================================================================
    const identity = (await getEntities(page)).fns.find((f) => f.name === 'identity');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + OWN_FN + '&parent-ids=' + identity.id
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
    // Phase C: OWN_FN now has a child — its ⋯ → Namespace menu must
    // still offer the move (the old gate hid it for in-use fns).
    // ================================================================
    await page.goto(BASE + '/#core.' + OWN_FN);
    // The `ns` entry lives INSIDE the ⋯ row-actions popover — open it
    // on OWN_FN's card first.
    await page.waitForFunction((name) => {
      return Array.from(document.querySelectorAll('.node-overlay')).some((ov) =>
        ov.textContent.trim().startsWith(name)
        && ov.querySelector('button.more-actions-trigger'));
    }, OWN_FN, {timeout: 90000, polling: 200});
    await page.evaluate((name) => {
      const ov = Array.from(document.querySelectorAll('.node-overlay')).find((o) =>
        o.textContent.trim().startsWith(name)
        && o.querySelector('button.more-actions-trigger'));
      ov.querySelector('button.more-actions-trigger')
        .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    }, OWN_FN);
    await page.waitForSelector('.row-actions-popover [data-action="namespace-move"]',
      {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('.row-actions-popover [data-action="namespace-move"]')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
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
