const assert = require('node:assert/strict');
const {resetDescriptionTransient} = require('../browser-test/tutorial-tour-helpers');
(async () => {
  const prior = global.document;
  try {
    for (const visible of [false, true]) {
      let escapes = 0;
      let settles = 0;
      let cancelled = false;
      global.document = {querySelector: () => null, querySelectorAll: selector =>
        selector === '.description-tooltip-btn' ? [] : [{getBoundingClientRect: () =>
          ({width: visible ? 100 : 0, height: visible ? 100 : 0})}]};
      const page = {evaluate: async fn => fn(), keyboard: {press: async key => {
        assert.equal(key, 'Escape');
        escapes++;
        if (!visible) cancelled = true;
      }}, waitForTimeout: async () => { settles++; }};
      await resetDescriptionTransient(page);
      assert.equal(cancelled, false, 'no-hover description reset must not cancel the tour');
      assert.equal(escapes, visible ? 1 : 0, 'dismiss exactly one visible popup');
      assert.equal(settles, visible ? 1 : 0, 'settle only a requested dismissal');
    }
  } finally { global.document = prior; }
  console.log('PASS description helper: no-hover tour preserved; real popup dismissed once');
})().catch(error => {console.error(error.message); process.exitCode = 1;});
