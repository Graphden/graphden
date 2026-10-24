const {checkedAction} = require('./checked-action');

// Ordinary lesson entries share this readiness and completion boundary.
// Startup and stale-owner regressions intentionally exercise earlier states.
async function openCatalog(page, timeoutMs = 30000) {
  await checkedAction(page, {
    name: 'open-catalog', timeoutMs,
    ready: () => page.waitForFunction(
      () => !!window.gdEditorRoot?.componentNow('tutorial-catalog'),
      null, {timeout: timeoutMs}),
    gesture: () => page.evaluate(() => window.openTutorialMenu()),
    settled: () => page.waitForSelector('.gd-tour-lesson-list', {timeout: timeoutMs})
  });
}

module.exports = {openCatalog};
