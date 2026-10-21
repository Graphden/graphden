'use strict';

// A picker selection receipt exists only while a real picker host has painted
// that root's view. Export/readiness alone must never certify rendering.
async function verifyPickerRootSelection(page, selection, candidate, background = null) {
  await page.waitForFunction(() => window.gdFnPickerGraph?.ready && window.gdEditorRoot?.componentNow('fn-picker'));
  await page.evaluate(candidate => openFnPicker({anchorEl: document.body,
    candidates: [{id: candidate, name: 'root-picker-proof', qualified: 'root-picker-proof', ns: null, effects: []}],
    onPick() {}}), candidate);
  try {
    await page.waitForFunction(selection => gdEditorRoot.componentIdentity('fn-picker') === selection
      && document.querySelector('.fn-picker-row[data-picker-key]'), selection);
    if (background !== null) await page.waitForFunction(background =>
      getComputedStyle(document.querySelector('.fn-picker-row[data-picker-key]')).backgroundColor === background, background);
    await page.locator('.fn-picker-search').focus();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.fn-picker-popover'));
  } finally { await page.evaluate(() => closeFnPicker()); }
}

module.exports = {verifyPickerRootSelection};
