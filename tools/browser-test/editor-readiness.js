// Early Explorer rows certify navigation only. Tests about editing must use
// the stronger fence, including after their own page.goto/reload. Auth-wall
// tests can keep the default bootstrap's tolerated login outcome explicitly.
async function waitForEditorReady(page, {allowAuthWall = false, timeout = 60000} = {}) {
  try {
    await page.evaluate(async () => { if (window.gdAccountsReady) await window.gdAccountsReady; });
  } catch (error) {
    if (!allowAuthWall || !/Execution context was destroyed|Cannot find context/.test(error.message)) throw error;
  }
  await page.waitForFunction(allowWall => {
    if (allowWall && /^\/(?:login|register)(?:\/|$)/.test(location.pathname)) return true;
    return typeof gdGraphEditsReady === 'function' && gdGraphEditsReady()
      && ((typeof graphData !== 'undefined' && !!graphData) || allowWall);
  }, allowAuthWall, {timeout});
}

module.exports = {waitForEditorReady};
