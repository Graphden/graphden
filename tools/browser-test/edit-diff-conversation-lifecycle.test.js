// Exercise the shipped conversation owner with controlled request completion.
// A late initial read must preserve an already focused, unsent composer.
const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');
(async () => {
  const {browser, page} = await newContext(chromium);
  try {
    const result = await page.evaluate(async () => {
      const root = document.createElement('div');
      const anchor = document.createElement('div');
      anchor.className = 'branch-diff-entry';
      anchor.dataset.anchorName = 'fn'; anchor.dataset.anchorId = 'fixture-id';
      const button = document.createElement('button');
      button.className = 'branch-diff-comment-btn'; button.textContent = 'Comment';
      anchor.appendChild(button); root.appendChild(anchor); document.body.appendChild(root);
      const original = window.authFetch;
      let complete;
      const trace = [];
      window.authFetch = async () => {
        trace.push('GET started');
        return new Promise((resolve) => { complete = () => {
          trace.push('GET completed');
          resolve({json: async () => ({ok: true, comments: []})});
        }; });
      };
      try {
        window.gdDiffAttachThreads(root, 'fixture-source', 'fixture-source', {anchoredOnly: true});
        button.click();
        const input = root.querySelector('textarea');
        input.value = 'Unsubmitted draft'; input.focus();
        const send = root.querySelector('.branch-comment-send');
        trace.push('draft entered');
        complete();
        // Observe the next paint after the real fetch/json continuations.
        await new Promise((resolve) => requestAnimationFrame(resolve));
        trace.push('owner rendered');
        return {trace, connected: input.isConnected, sameInput: root.querySelector('textarea') === input,
          sameSend: root.querySelector('.branch-comment-send') === send,
          draft: input.value, focused: document.activeElement === input};
      } finally {window.authFetch = original; root.remove();}
    });
    console.log(JSON.stringify(result));
    assert(result.connected && result.sameInput && result.sameSend,
      'late initial comments response preserves composer DOM identity');
    assert(result.draft === 'Unsubmitted draft' && result.focused,
      'late initial comments response preserves draft and focus');
  } finally {await browser.close();}
})().catch((error) => {console.error(error); process.exitCode = 1;});
