'use strict';
const assert = require('node:assert/strict');

// A lazy catalogue view receives a root receipt only after its native host
// finishes checked paint. Closing it resumes the original lesson owner.
async function verifyTourCatalogRootSelection(page, selection) {
  await page.evaluate(() => openTutorialMenu());
  try {
    await page.waitForFunction(selection =>
      gdUIComponentRuntimeIdentity('tutorial-catalog') === selection
      && document.querySelector('.gd-tour-lesson-row .gd-tour-btn'), selection);
    await page.locator('.gd-tour-filter').fill('function');
    assert(await page.evaluate(selection =>
      gdEditorRoot.componentIdentity('tutorial-catalog') === selection,
    selection), 'local catalogue filtering preserves the checked root paint receipt');
    await page.locator('.gd-tour-filter').fill('');
    await page.locator('.gd-tour-filter').press('Escape');
    assert(await page.evaluate(() => gdEditorRoot.componentIdentity('tutorial-catalog') === null),
      'closing the last catalogue host releases its root receipt');
  } finally {
    await page.evaluate(() => { gdTourCatalogClose?.(); });
  }
}

async function verifyTourCatalogRootLifetime(page, selection) {
  await page.evaluate(() => {
    const anchor = document.createElement('button');
    anchor.textContent = 'Catalogue lifetime proof';
    document.body.appendChild(anchor);
    anchor.focus();
    window.__gdCatalogLifetime = {anchor};
  });
  try {
    await page.evaluate(() => openTutorialMenu());
    await page.waitForFunction(selection => gdEditorRoot.componentIdentity('tutorial-catalog') === selection, selection);
    const retired = await page.evaluate(() => {
      const proof = window.__gdCatalogLifetime;
      proof.row = document.querySelector('.gd-tour-lesson-row button.gd-tour-btn-primary:not([disabled])');
      proof.scopes = [...document.querySelectorAll('#gd-tour-pop [data-gd-ui-style]')];
      proof.reloaded = gdEditorRoot.reload();
      return !document.querySelector('.gd-tour-lesson-list')
        && document.activeElement === proof.anchor
        && proof.scopes.length > 0 && proof.scopes.every(host => !host.dataset.gdUiStyle)
        && gdEditorRoot.componentIdentity('tutorial-catalog') === null;
    });
    assert(retired, 'real root reload immediately retires catalogue DOM, styles, focus and render receipt');
    await page.evaluate(async () => {
      const proof = window.__gdCatalogLifetime;
      if (!proof.row || typeof window._tourChooseLesson !== 'function') throw new Error('Native lesson action unavailable');
      proof.originalChoose = window._tourChooseLesson;
      proof.chosen = 0;
      window._tourChooseLesson = () => { proof.chosen++; };
      proof.row.click();
      await proof.reloaded;
    });
    assert(await page.evaluate(() => window.__gdCatalogLifetime.chosen === 0
      && !document.querySelector('.gd-tour-lesson-list')
      && gdEditorRoot.componentIdentity('tutorial-catalog') === null),
    'detached old lesson callback cannot choose a lesson, revive the catalogue or certify a selection');
  } finally {
    await page.evaluate(() => {
      gdTourCatalogClose?.();
      if (window.__gdCatalogLifetime?.originalChoose) window._tourChooseLesson = window.__gdCatalogLifetime.originalChoose;
      window.__gdCatalogLifetime?.anchor.remove();
      delete window.__gdCatalogLifetime;
    });
  }
}

module.exports = {verifyTourCatalogRootSelection, verifyTourCatalogRootLifetime};
