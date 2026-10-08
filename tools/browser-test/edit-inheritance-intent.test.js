// Real editor intent menu + atomic API: preserve own composition and source,
// UUID navigation, both variation kinds, all-consumer semantics and cleanup.
const { chromium } = require('playwright');
const { assert, newContext, api, getEntities, nodeApi, deleteFnByName } = require('./edit-test-helpers');

const suffix = '-' + process.pid + '-' + Date.now().toString(36);
const nsName = 'intent' + suffix;
const names = { P: 'source' + suffix, F: 'child' + suffix, Q: 'source-consumer' + suffix,
  R: 'child-consumer' + suffix, O: 'owner' + suffix, O2: 'owner-consumer' + suffix,
  copy: '_parent-copy' + suffix, refCopy: '_reference-copy' + suffix };
const base = process.env.GRAPHDEN_URL || 'http://localhost:9002';

async function makeFn(page, name, parentId, namespaceId) {
  await api(page, 'POST', '/api/entities/fn', new URLSearchParams({
    name, 'namespace-id': namespaceId, 'parent-ids': parentId,
  }).toString());
  const result = await api(page, 'GET', '/api/graph/entities?scope=search&q=' + encodeURIComponent(name));
  const fn = result.fns?.find(row => row.name === name && row['namespace-id'] === namespaceId);
  assert(fn, 'created ' + name);
  return fn;
}

async function binding(page, fnId, slotId, fields) {
  await api(page, 'POST', '/api/entities/binding', new URLSearchParams({
    'fn-id': fnId, 'slot-id': slotId, ...fields,
  }).toString());
}

async function openIntent(page, fnName, depth = 1) {
  await page.click('.node-overlay[data-fn-name="' + fnName + '"] .ancestor-line[data-level="' + depth + '"] button.more-actions-trigger');
  await page.click('.row-actions-popover [data-action="inheritance-intent"]');
  await page.waitForSelector('.inheritance-intent-popover [data-preview]');
}

(async () => {
  const { browser, page } = await newContext(chromium, { boot: false });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  let namespaceId;
  try {
    await api(page, 'POST', '/api/entities/ns', 'name=' + encodeURIComponent(nsName));
    const tree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    namespaceId = tree.namespaces?.find(row => row.name === nsName && !row['parent-id'])?.id;
    assert(namespaceId, 'owned namespace created');
    const subs = await getEntities(page, 'subs');
    const baseFn = subs.fns.find(row => row.name === 'subs' && !(row['parent-ids'] || []).length);
    assert(baseFn, 'subs identity resolved');
    const slot = name => subs.slots.find(row => row.name === name)?.id;
    const P = await makeFn(page, names.P, baseFn.id, namespaceId);
    await binding(page, P.id, slot('end'), { terminal: 'true' });
    const F = await makeFn(page, names.F, P.id, namespaceId);
    await binding(page, F.id, slot('string'), { value: '"graphden"' });
    await binding(page, F.id, slot('start'), { value: '5' });
    const Q = await makeFn(page, names.Q, P.id, namespaceId);
    const R = await makeFn(page, names.R, F.id, namespaceId);
    const before = await getEntities(page, F.id);
    const ownBefore = before.bindings.filter(row => row['fn-id'] === F.id);

    await page.goto(base + '/#' + nsName + '.' + names.F);
    await page.waitForSelector('.node-overlay[data-fn-name="' + names.F + '"]');
    await openIntent(page, names.F);
    assert(/all its consumers in this branch/.test(await page.textContent('.inheritance-intent-scope')),
      'menu explains function identity scope');
    await page.click('[data-action="inheritance-source"]');
    await page.waitForFunction(id => selectedFnId === id, P.id);
    await page.waitForSelector('.node-overlay[data-fn-name="' + names.P + '"]');
    assert(true, 'Go to source navigates by source UUID');
    await page.evaluate(id => navigateInheritanceSource(id), F.id);
    await page.waitForSelector('.node-overlay[data-fn-name="' + names.F + '"]');
    await openIntent(page, names.F);
    await page.keyboard.press('Escape');
    await page.waitForSelector('.inheritance-intent-popover', { state: 'detached' });
    assert(await page.evaluate(() => document.activeElement?.classList.contains('more-actions-trigger')),
      'Escape restores the row trigger focus');

    await page.route('**/partials/inheritance-intent?*', route => route.fulfill({
      status: 503, contentType: 'text/plain', body: 'Preview temporarily unavailable.',
    }));
    await page.click('.node-overlay[data-fn-name="' + names.F + '"] .ancestor-line[data-level="1"] button.more-actions-trigger');
    await page.click('.row-actions-popover [data-action="inheritance-intent"]');
    await page.waitForFunction(() => document.querySelector('.inheritance-intent-popover')
      ?.textContent.includes('Preview temporarily unavailable.'));
    await page.click('.inheritance-intent-popover [aria-label="Close inheritance actions"]');
    await page.waitForSelector('.inheritance-intent-popover', { state: 'detached' });
    await page.unroute('**/partials/inheritance-intent?*');
    assert(true, 'failed preview retains a visible working Close control');

    await openIntent(page, names.F);
    await page.evaluate(() => inheritanceIntentAnchor.remove());
    await page.waitForSelector('.inheritance-intent-popover', { state: 'detached' });
    assert(true, 'removing the anchor dismisses the owned popup');
    await page.evaluate(() => initGraph());
    await openIntent(page, names.F);
    await page.setViewportSize({ width: 390, height: 240 });
    const bounds = await page.locator('.inheritance-intent-popover').boundingBox();
    assert(bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= 390
      && bounds.y + bounds.height <= 240, 'intent stays inside narrow viewport');
    await page.screenshot({ path: '/tmp/graphden-inheritance-intent-narrow.png' });
    await page.setViewportSize({ width: 1400, height: 900 });
    await page.fill('[data-variation-name]', names.copy);
    await page.click('[data-action="inheritance-variation"]');
    await page.waitForSelector('.node-overlay[data-fn-name="' + names.copy + '"]');
    const after = await getEntities(page, F.id);
    const copy = after.fns.find(row => row.name === names.copy);
    assert(copy && copy.id !== P.id && copy['namespace-id'] === namespaceId, 'named sibling has new identity');
    assert(JSON.stringify(copy['parent-ids']) === JSON.stringify(P['parent-ids']), 'copy is sibling of source');
    assert(after.fns.find(row => row.id === F.id)['parent-ids'][0] === copy.id, 'target points to sibling');
    assert(JSON.stringify(after.bindings.filter(row => row['fn-id'] === F.id)) === JSON.stringify(ownBefore),
      'target own composition preserved');
    const copiedSeal = after.bindings.find(row => row['fn-id'] === copy.id && row['slot-id'] === slot('end'));
    assert(copiedSeal?.terminal, 'source own seal copied');
    await api(page, 'PUT', '/api/entities/binding/' + copiedSeal.id, 'terminal=false');
    await binding(page, F.id, slot('end'), { value: '7' });
    const runF = await api(page, 'POST', '/api/execute', { 'fn-id': F.id, args: {} });
    const runR = await api(page, 'POST', '/api/execute', { 'fn-id': R.id, args: {} });
    assert(runF.result === 'de' && runR.result === 'de', 'second consumer of target sees changed identity');
    const original = await getEntities(page, Q.id);
    assert(original.fns.find(row => row.id === Q.id)['parent-ids'][0] === P.id
      && original.bindings.find(row => row['fn-id'] === P.id && row['slot-id'] === slot('end')).terminal,
    'original source and its second consumer stay unchanged');

    const constGraph = await getEntities(page, 'const');
    const constFn = constGraph.fns.find(row => row.name === 'const' && !(row['parent-ids'] || []).length);
    const valueSlot = constGraph.slots.find(row => row.name === 'value');
    const O = await makeFn(page, names.O, constFn.id, namespaceId);
    await binding(page, O.id, valueSlot.id, { 'ref-fn-id': P.id });
    const O2 = await makeFn(page, names.O2, O.id, namespaceId);
    await page.goto(base + '/#' + nsName + '.' + names.O);
    await page.waitForSelector('.node-overlay[data-fn-name="' + names.P + '"]');
    await openIntent(page, names.P, 0);
    await page.fill('[data-variation-name]', names.refCopy);
    await page.click('[data-action="inheritance-variation"]');
    await page.waitForSelector('.node-overlay[data-fn-name="' + names.refCopy + '"]');
    const ownerAfter = await getEntities(page, O2.id);
    const refCopy = ownerAfter.fns.find(row => row.name === names.refCopy);
    assert(ownerAfter.bindings.find(row => row['fn-id'] === O.id && row['slot-id'] === valueSlot.id)['ref-fn-id'] === refCopy.id,
      'own-ref changes exact owner binding, visible to owner consumer');
    assert((await getEntities(page, P.id)).bindings.some(row => row['fn-id'] === P.id && row.terminal),
      'own-ref preserves original source seal');

    const descriptor = { type: 'fn', id: refCopy.id, name: refCopy.name, 'namespace-id': namespaceId };
    assert(await page.evaluate(row => _tourFnIdForCreation(row), descriptor) === refCopy.id,
      'cleanup resolves existing row by exact subtree UUID on real API');
    const missing = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=00000000-0000-4000-8000-000000000000');
    assert(Array.isArray(missing.fns) && missing.fns.length === 0, 'missing subtree root returns valid empty graph');
    assert(await page.evaluate(row => _tourFnIdForCreation(row), { ...descriptor,
      id: '00000000-0000-4000-8000-000000000000' }) === null, 'missing identity does not fall back to same name');
    assert(errors.length === 0, 'no browser exceptions');
    console.log('PASS inheritance intent');
  } finally {
    for (const name of [names.O2, names.O, names.R, names.F, names.Q, names.refCopy, names.copy, names.P]) {
      await deleteFnByName(page, name);
    }
    if (namespaceId) {
      const response = await nodeApi('DELETE', '/api/entities/ns/' + namespaceId);
      assert(response.ok, 'fixture namespace cleaned');
    }
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
