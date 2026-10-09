'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require.resolve('../browser-test/tutorial-tour-helpers.js'), 'utf8');
const body = source.slice(source.indexOf('async function finishAndDelete('), source.indexOf('// Bind the currently-shown placeholder', source.indexOf('async function finishAndDelete(')));
for (const title of ['Clean up tutorial items?', 'Delete the tutorial branch?']) {
  test('Finish accepts exact cleanup title: ' + title, async () => {
    const label = title === 'Delete the tutorial branch?' ? 'Delete branch & return' : 'Delete them';
    const events = [];
    let closed = false;
    const sandbox = {assert: assert.ok, clickTourButton: async (_page, label) => { events.push(label); if (label !== 'Finish') closed = true; return true; }, document: {
      querySelector: selector => selector.endsWith('.gd-tour-title') ? {textContent: title} : closed ? null : {},
      querySelectorAll: () => [{textContent: label}],
    }};
    const finish = vm.runInNewContext(body + '\nfinishAndDelete', sandbox);
    let closureChecked = false;
    const page = {evaluate: async fn => fn(), waitForFunction: async fn => {
      assert.equal(fn(), true);
      if (closed) closureChecked = true;
    }};
    await finish(page);
    assert.deepEqual(events, ['Finish', label]);
    assert.equal(closureChecked, true, 'cleanup requires the dialog to close');
    sandbox.document.querySelector = () => ({textContent: 'Lesson finished'});
    await assert.rejects(finish(page));
  });
}
