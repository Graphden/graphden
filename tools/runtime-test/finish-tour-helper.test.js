const assert = require('node:assert/strict');
const {finishAndDelete} = require('../browser-test/tutorial-tour-helpers');
(async () => {
  const prior = global.document;
  try {
    for (const [title, label] of [['Clean up tutorial items?', 'Delete them'],
      ['Delete the tutorial branch?', 'Delete branch & return']]) {
      let phase = 'running';
      const clicked = [];
      const button = text => ({textContent: text, click: () => {
        clicked.push(text);
        phase = text === 'Finish' ? 'cleanup' : 'closed';
      }});
      global.document = {
        querySelector: selector => phase === 'closed' ? null
          : selector.includes('.gd-tour-title') ? {textContent: title} : {},
        querySelectorAll: () => [button(phase === 'running' ? 'Finish' : label)],
      };
      let closureChecked = false;
      const page = {evaluate: async (fn, arg) => fn(arg),
        waitForFunction: async (fn, arg) => {
          assert.equal(fn(arg), true, 'the actual dialog/button/closure condition must hold');
          if (phase === 'closed') closureChecked = true;
        }};
      await finishAndDelete(page);
      assert.deepEqual(clicked, ['Finish', label]);
      assert.equal(closureChecked, true, 'cleanup still requires the dialog to close');
    }
  } finally {global.document = prior;}
  console.log('PASS finish helper: exact item/branch action and dialog closure');
})().catch(error => {console.error(error.message); process.exitCode = 1;});
