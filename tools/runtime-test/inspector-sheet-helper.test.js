'use strict';
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {closeInspectorSheet} = require('../browser-test/edit-test-helpers');

function pageFor({narrow, open, refusal = false, closes = true}) {
  const state = {open, clicks: 0, waits: 0};
  const context = vm.createContext({
    matchMedia: () => ({matches: narrow}),
    document: {body: {classList: {contains: () => state.open}}},
  });
  return {state,
    evaluate: async fn => vm.runInContext('(' + fn.toString() + ')()', context),
    getByRole(role, options) {
      assert.equal(role, 'button'); assert.equal(options.name, 'Close inspector');
      return {async click() {
        state.clicks++;
        if (refusal) throw new Error('close refused');
        if (closes) state.open = false;
      }};
    },
    async waitForFunction(fn) {
      state.waits++;
      assert.equal(vm.runInContext('(' + fn.toString() + ')()', context), true, 'sheet must actually close');
    },
  };
}
(async () => {
  const wide = pageFor({narrow: false, open: true});
  assert.equal(await closeInspectorSheet(wide), false);
  assert.equal(wide.state.clicks, 0, 'wide viewport never clicks a stale visible narrow close action');
  const closed = pageFor({narrow: true, open: false});
  assert.equal(await closeInspectorSheet(closed), false);
  assert.equal(closed.state.clicks, 0, 'closed sheet outside viewport is not a click target');
  const opened = pageFor({narrow: true, open: true});
  assert.equal(await closeInspectorSheet(opened), true);
  assert.equal(opened.state.clicks, 1);
  assert.equal(opened.state.waits, 1);
  await assert.rejects(closeInspectorSheet(pageFor({narrow: true, open: true, refusal: true})), /close refused/);
  await assert.rejects(closeInspectorSheet(pageFor({narrow: true, open: true, closes: false})), /sheet must actually close/);
  console.log('Inspector sheet helper: current viewport/open state, real close and fatal refusals PASS');
})().catch(error => { console.error(error); process.exitCode = 1; });
