'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require.resolve('../browser-test/edit-tutorial-tour-branches.test.js'), 'utf8');
const start = source.indexOf('const valueHandle = await page.waitForFunction(') + 'const valueHandle = await page.waitForFunction('.length;
const predicate = source.slice(start, source.indexOf(', {id: fnId, branch: sourceBranch}', start));
test('later edit finds sibling arg overlay by exact owner and branch', () => {
  const other = {closest: () => ({dataset: {nodeId: 'other-arg'}})};
  const owned = {closest: () => ({dataset: {nodeId: 'owned-arg'}})};
  const ctx = {selectedFnId: 'owned', getCurrentBranchName: () => 'source',
    document: {querySelectorAll: () => [other, owned]},
    graph: {nodes: new Map([['other-arg', {data: {'fn-id': 'other'}}], ['owned-arg', {data: {'fn-id': 'owned'}}]])},
    argRowFromNode: data => data};
  vm.createContext(ctx);
  const pick = vm.runInContext('(' + predicate + ')', ctx);
  assert.equal(pick({id: 'owned', branch: {name: 'source'}}), owned);
  assert.equal(pick({id: 'owned', branch: {name: 'target'}}), null);
  ctx.selectedFnId = 'other';
  assert.equal(pick({id: 'owned', branch: {name: 'source'}}), null);
  ctx.selectedFnId = 'owned';
  ctx.graph.nodes.delete('owned-arg');
  assert.equal(pick({id: 'owned', branch: {name: 'source'}}), undefined);
});
