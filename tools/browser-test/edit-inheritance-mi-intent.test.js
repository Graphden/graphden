// A local variation on one MI cell preserves the other direct parent.
const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, nodeApi, deleteFnByName} =
  require('./edit-test-helpers');

const suffix = '-' + process.pid + '-' + Date.now().toString(36);
const namespaceName = 'inheritance-mi' + suffix;
const names = {
  source: 'source' + suffix, left: 'left' + suffix, right: 'right' + suffix,
  target: 'target' + suffix, copy: '_left-copy' + suffix,
  deepTarget: 'deep-target' + suffix, deepCopy: '_source-copy' + suffix,
};
const base = process.env.GRAPHDEN_URL || 'http://localhost:9002';

async function createFn(page, name, parents, namespaceId) {
  await api(page, 'POST', '/api/entities/fn', new URLSearchParams({
    name, 'namespace-id': namespaceId, 'parent-ids': parents.join(','),
  }).toString());
  const graph = await getEntities(page, name);
  const row = graph.fns.find((fn) => fn.name === name && fn['namespace-id'] === namespaceId);
  assert(row, 'created ' + name);
  return row;
}

async function waitForParents(id, expected) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    const graph = await api(null, 'GET', '/api/graph/entities?scope=subtree&root-id=' + id);
    const row = graph.fns.find((fn) => fn.id === id);
    if (row && JSON.stringify(row['parent-ids']) === JSON.stringify(expected)) return true;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return false;
}

async function waitForFn(name) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    const graph = await api(null, 'GET', '/api/graph/entities?scope=search&q=' + encodeURIComponent(name));
    const row = graph.fns.find((fn) => fn.name === name);
    if (row) return row;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return null;
}

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (dialog) => dialog.accept());
  let namespaceId;
  let middleCloneName;
  try {
    await api(page, 'POST', '/api/entities/ns', 'name=' + encodeURIComponent(namespaceName));
    const graph = await api(page, 'GET', '/api/graph/entities?scope=tree');
    namespaceId = graph.namespaces.find((ns) => ns.name === namespaceName)?.id;
    assert(namespaceId, 'fixture namespace created');
    const constFn = (await getEntities(page, 'const')).fns.find((fn) => fn.name === 'const');
    assert(constFn, 'const parent resolved');
    const source = await createFn(page, names.source, [constFn.id], namespaceId);
    const left = await createFn(page, names.left, [source.id], namespaceId);
    const right = await createFn(page, names.right, [constFn.id], namespaceId);
    const target = await createFn(page, names.target, [left.id, right.id], namespaceId);
    const deepTarget = await createFn(page, names.deepTarget, [left.id, right.id], namespaceId);

    await page.goto(base + '/#' + namespaceName + '.' + names.target);
    const row = page.locator('.node-overlay[data-fn-name="' + names.target
      + '"] .ancestor-line[data-level="1"]');
    await row.waitFor();
    const cell = row.locator(':scope > span').filter({hasText: names.left});
    const trigger = cell.locator('button.more-actions-trigger');
    await trigger.click();
    const action = page.locator('.row-actions-popover [data-action="inheritance-intent"]');
    await action.waitFor();
    const command = JSON.parse(await action.getAttribute('data-inheritance-command'));
    assert(command['source-fn-id'] === left.id && command['parent-edge-index'] === 0,
      'MI cell names the exact selected parent edge');
    await action.click();
    const intent = page.locator('.inheritance-intent-popover [data-preview]');
    await intent.waitFor();
    const preview = JSON.parse(await intent.getAttribute('data-preview'));
    assert(preview.allowed, 'selected MI edge has an allowed preview: ' + preview.reason);
    const candidateResponse = page.waitForResponse((response) =>
      response.url().includes('/api/inheritance/preview')
      && response.request().method() === 'POST', {timeout: 15000});
    await page.click('[data-action="inheritance-ancestors"]');
    const listed = await (await candidateResponse).json();
    const alternate = listed.candidates.find((candidate) => candidate.id === constFn.id);
    assert(alternate?.request?.['parent-ids']?.[1] === right.id,
      'ancestor choice preserves the other MI parent');
    await page.locator('.fn-picker-popover').waitFor();
    await page.keyboard.press('Escape');
    await page.locator('.fn-picker-popover').waitFor({state: 'detached'});
    await trigger.click();
    await page.click('.row-actions-popover [data-action="inheritance-intent"]');
    await intent.waitFor();
    await page.fill('[data-variation-name]', names.copy);
    const applyResponse = page.waitForResponse((response) =>
      response.url().includes('/api/inheritance/apply')
      && response.request().method() === 'POST', {timeout: 20000});
    await page.click('[data-action="inheritance-variation"]');
    const applied = await applyResponse;
    const result = await applied.json();
    assert(applied.ok() && result.ok, 'MI variation committed: ' + JSON.stringify(result));
    const copy = await waitForFn(names.copy);
    assert(copy, 'local variation created');
    assert(await waitForParents(target.id, [copy.id, right.id]),
      'only the selected MI edge changed');
    const after = await getEntities(page, left.id);
    assert(JSON.stringify(after.fns.find((fn) => fn.id === left.id)['parent-ids'])
      === JSON.stringify(left['parent-ids']), 'source kept its own parents');

    await page.goto(base + '/#' + namespaceName + '.' + names.deepTarget);
    const deepRow = page.locator('.node-overlay[data-fn-name="' + names.deepTarget
      + '"] .ancestor-line[data-level="2"]');
    await deepRow.waitFor();
    assert(await page.evaluate(([targetId, ancestorId]) =>
      uniqueInheritancePath(targetId, ancestorId), [deepTarget.id, constFn.id]) === null,
    'diamond ancestor needs an explicit route choice');
    const deepCell = deepRow.locator(':scope > span').filter({hasText: names.source});
    await deepCell.locator('button.more-actions-trigger').click();
    const deepAction = page.locator('.row-actions-popover [data-action="inheritance-intent"]');
    await deepAction.waitFor();
    const deepCommand = JSON.parse(await deepAction.getAttribute('data-inheritance-command'));
    assert(JSON.stringify(deepCommand['occurrence-path'])
      === JSON.stringify([deepTarget.id, left.id, source.id]),
    'deep MI action names the unique occurrence path');
    assert(deepCommand['expected-parent-id'] === left.id && deepCommand['parent-edge-index'] === 0,
      'deep action names the first selected edge');
    const deepPartialResponse = page.waitForResponse((response) =>
      response.url().includes('/partials/inheritance-intent'), {timeout: 20000});
    await deepAction.click();
    assert((await deepPartialResponse).ok(), 'deep path preview loaded');
    await intent.waitFor();
    await page.waitForTimeout(2200);
    assert(await page.locator('.inheritance-intent-popover [data-preview]').count() === 1,
      'deep preview survives its card redraw');
    assert(await page.locator('.inheritance-intent-popover [data-action="inheritance-ancestors"]').count() === 0,
      'deep path does not offer direct ancestor replacement');
    await page.fill('[data-variation-name]', names.deepCopy);
    const deepApply = page.waitForResponse((response) =>
      response.url().includes('/api/inheritance/apply')
      && response.request().method() === 'POST', {timeout: 20000});
    await page.click('[data-action="inheritance-variation"]');
    const deepResult = await (await deepApply).json();
    assert(deepResult.ok, 'deep path variation committed: ' + JSON.stringify(deepResult));
    const deepCopy = await waitForFn(names.deepCopy);
    assert(deepCopy, 'selected deep source copied');
    const deepGraph = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + deepTarget.id);
    const deepParents = deepGraph.fns.find((fn) => fn.id === deepTarget.id)['parent-ids'];
    const middleClone = deepGraph.fns.find((fn) => fn.id === deepParents[0]);
    middleCloneName = middleClone?.name;
    assert(middleClone && JSON.stringify(middleClone['parent-ids'])
      === JSON.stringify([deepCopy.id]), 'only the chosen chain was copied');
    assert(deepParents[1] === right.id, 'other MI parent preserved on the deep target');
    assert(await waitForParents(target.id, [copy.id, right.id]), 'first target remained unchanged');
    const batchPreview = await api(page, 'POST', '/api/inheritance/preview', {
      action: 'batch', commands: [
        {action: 'reparent', 'target-fn-id': target.id, 'parent-ids': [right.id]},
        {action: 'reparent', 'target-fn-id': deepTarget.id, 'parent-ids': [right.id]},
      ],
    });
    assert(batchPreview.allowed, 'batch wire preview accepts two independent targets');
    assert(batchPreview['write-set']?.fn?.length === 2,
      'batch wire preview exposes the shared function write set');
    const batchApplied = await api(page, 'POST', '/api/inheritance/apply', {
      ...batchPreview.request,
      'expected-state': batchPreview['expected-state'],
      'accepted-orphan-binding-ids': batchPreview['orphan-binding-ids'],
    });
    assert(batchApplied.ok && batchApplied.committed,
      'batch wire apply commits both targets atomically');
    assert(await waitForParents(target.id, [right.id])
      && await waitForParents(deepTarget.id, [right.id]),
    'both target identities reflect the one batch commit');
    console.log('PASS inheritance MI intent');
  } finally {
    await page.close();
    for (const name of [names.deepTarget, names.target, middleCloneName,
      names.deepCopy, names.copy, names.left, names.right, names.source]) {
      if (!name) continue;
      await deleteFnByName(page, name);
    }
    if (namespaceId) {
      const response = await nodeApi('DELETE', '/api/entities/ns/' + namespaceId);
      assert(response.ok, 'fixture namespace cleaned');
    }
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
