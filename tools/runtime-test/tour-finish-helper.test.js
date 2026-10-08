'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require.resolve('../browser-test/tutorial-tour-helpers.js'), 'utf8');
const body = source.slice(source.indexOf('async function finishAndDelete('), source.indexOf('// Bind the currently-shown placeholder', source.indexOf('async function finishAndDelete(')));
for (const title of ['Clean up tutorial items?', 'Delete the tutorial branch?']) {
  test('Finish accepts exact cleanup title: ' + title, async () => {
    const events = [];
    let closed = false;
    const sandbox = {assert: assert.ok, clickTourButton: async (_page, label) => { events.push(label); if (label === 'Delete them') closed = true; return true; }, document: {
      querySelector: selector => selector.endsWith('.gd-tour-title') ? {textContent: title} : closed ? null : {},
      querySelectorAll: () => [{textContent: title.startsWith('Delete') ? 'Delete branch & return' : 'Delete them'}],
    }};
    const finish = vm.runInNewContext(body + '\nfinishAndDelete', sandbox);
    await finish({waitForFunction: async fn => assert.equal(fn(), true)});
    assert.deepEqual(events, ['Finish', 'Delete them']);
    sandbox.document.querySelector = () => ({textContent: 'Lesson finished'});
    await assert.rejects(finish({waitForFunction: async fn => assert.equal(fn(), true)}));
  });
}
