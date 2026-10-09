'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require.resolve('../browser-test/edit-execute-trace.test.js'), 'utf8');
const start = source.indexOf('// Native disclosure regression: both server initial states are valid.');
const body = source.slice(start, source.indexOf('// End native disclosure regression.', start));
const check = vm.runInNewContext('(async (page, details, initiallyOpen) => {' + body + '\n})', {assert: assert.ok});
for (const initiallyOpen of [true, false]) {
  test('native trace disclosure starts ' + (initiallyOpen ? 'open' : 'closed'), async () => {
    let open = initiallyOpen;
    let focused = false;
    const keys = [];
    const details = {waitFor: async () => {}, evaluate: async fn => fn({open}),
      locator: selector => { assert.equal(selector, 'summary'); return {focus: async () => { focused = true; }}; }};
    const page = {keyboard: {press: async key => { assert.ok(focused); keys.push(key); open = !open; }}};
    await check(page, details, initiallyOpen);
    assert.deepEqual(keys, initiallyOpen ? ['Space', 'Enter', 'Space'] : ['Enter', 'Space']);
    assert.equal(open, false);
    await assert.rejects(check(page, details, true));
  });
}
