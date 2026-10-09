'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require.resolve('../browser-test/tutorial-tour-helpers.js'), 'utf8');
const start = source.indexOf('async function filterAndSelect(');
const body = source.slice(start, source.indexOf('// `expectOwner`', start));
test('selection waits for its exact navigation-root canvas, without an animation gate', async () => {
  const id = '11111111-1111-4111-8111-111111111111';
  const row = {dataset: {fnId: id}, querySelector: () => ({textContent: 'same-name'}), hasAttribute: () => false};
  let painted = 'old-root';
  let reads = 0;
  const world = {URL, location: {href: 'http://editor/?branch=owned'},
    graphData: {fns: [{id}]}, selectedFnId: 'previous-selection',
    document: {querySelectorAll: () => [row], querySelector: selector => {
      if (!selector.includes(id)) return null;
      reads++;
      return painted === id ? {} : null;
    }},
    selectFnByName: async identity => {
      assert.equal(identity, 'fn:' + id, 'selection uses the visible row UUID, not an ambiguous label');
      world.selectedFnId = id;
    },
  };
  const select = vm.runInNewContext(body + '\nfilterAndSelect', world);
  const page = {url: () => world.location.href, fill: async () => {}, evaluate: async (fn, arg) => fn(arg),
    waitForFunction: async (fn, arg) => {
      if (fn(arg)) return;
      assert.equal(world.selectedFnId, id);
      assert.equal(painted, 'old-root', 'selection metadata alone cannot authorize the stale canvas');
      painted = id;
      assert.equal(fn(arg), true, 'the exact selected navigation root completes readiness');
    }};
  await select(page, 'qualified.same-name', 'same-name');
  assert.equal(reads, 2, 'stale canvas was rejected before the matching paint');
});
