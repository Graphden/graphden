'use strict';
const {randomBytes} = require('node:crypto');
const {assert} = require('./edit-test-helpers');
const {waitTourTitle, clickTourButton, filterAndSelect, extendViaRowActions,
  bindPlaceholderOn, finishAndDelete} = require('./tutorial-tour-helpers');
const {rootAction, apps, closePopover, mint, openPreview, exactFnPresent} = require('./tutorial-handler-preview-helpers');

async function walkLesson30(page, base, {label = 'native-app-' + randomBytes(6).toString('hex'),
  onHandler = () => {}, onAppAttempt = () => {}} = {}) {
  const name = 'tutorial-page';
  // Use the same chooser as readers: lesson 30 needs its owned sandbox,
  // while a direct tutorial URL intentionally starts on the current branch.
  await page.goto(base + '/?branch=main');
  await page.waitForFunction(() => typeof window.openTutorialMenu === 'function');
  await page.evaluate(() => window.openTutorialMenu());
  await page.locator('[data-lesson-id="30"] .gd-tour-btn-primary').dispatchEvent('click');
  await waitTourTitle(page, 'Serving the graph to the public', 150000);
  await clickTourButton(page, 'Next');
  await waitTourTitle(page, 'Something to serve');
  await filterAndSelect(page, 'html-ok-response', 'html-ok-response');
  await waitTourTitle(page, 'Make it yours');
  await extendViaRowActions(page, name, 'html-ok-response');
  await waitTourTitle(page, 'tutorial-page is open');
  await waitTourTitle(page, 'Answer like a web server');
  const creation = await page.evaluate(fnName => {
    const row = _tourState.created.find(entry => entry.type === 'fn' && entry.name === fnName
      && entry.id === selectedFnId && entry.receipt === 'created');
    return row ? {...row, branch: getCurrentBranchName(), principal: {..._tourState.principal},
      sandbox: {id: _tourState.sandboxBranchId, name: _tourState.sandboxBranch,
        'base-branch-id': _tourState.sandboxBaseBranchId}} : null;
  }, name);
  assert(creation?.id && creation.sandbox.id && creation.branch !== 'main',
    'lesson 30 records its typed handler on its exact owned branch');
  onHandler(creation);
  assert(!await exactFnPresent(page, creation.id, 'main'), 'lesson handler is not silently written to main');
  await bindPlaceholderOn(page, name, 'body', 'literal', '<h1>Hello from my app</h1>');
  await waitTourTitle(page, 'Open its Apps');
  await rootAction(page, name, 'apps');
  await waitTourTitle(page, 'Publish it');
  let appId;
  const record = request => {
    if (request.method() !== 'POST' || new URL(request.url()).pathname !== '/partials/fn-apps/create') return;
    const form = new URLSearchParams(request.postData());
    if (form.get('label') === label && form.get('handler-fn-id') === creation.id) {
      appId = form.get('create-id');
      onAppAttempt(appId);
    }
  };
  page.on('request', record);
  try {
    await page.locator('.fn-apps-popover.visible .app-create-form [name="label"]').fill(label);
    await page.locator('.fn-apps-popover.visible .app-create-btn').click();
    await waitTourTitle(page, 'Preview this lesson branch', 60000);
  } finally { page.off('request', record); }
  assert(appId && await page.evaluate(id => _tourState.created.some(entry =>
    entry.type === 'app-route' && entry.id === id && entry.receipt === 'created'), appId),
  'lesson 30 advances only for its exact server-confirmed app UUID');
  assert((await apps(page)).some(row => row.id === appId && row.label === label
    && row['handler-fn-id'] === creation.id), 'ordinary Apps creation preserves the selected handler identity');
  await closePopover(page);
  await mint(page, name);
  await waitTourTitle(page, 'Read your page');
  const external = await openPreview(page, {htmx: false});
  try {
    assert(await external.getByRole('heading', {name: 'Hello from my app', exact: true}).isVisible(),
      'real HTTPS response serves this lesson branch HTML');
  } finally { await external.close(); }
  await closePopover(page);
  await clickTourButton(page, 'Next');
  await waitTourTitle(page, 'Ordinary publication follows a merge');
  await finishAndDelete(page);
  assert(!(await apps(page)).some(row => row.id === appId), 'lesson 30 Finish removes the exact app route');
  assert(await page.evaluate(() => getCurrentBranchName()) === 'main', 'lesson 30 cleanup returns to main');
  assert(!await exactFnPresent(page, creation.id), 'lesson 30 handler has no surviving main copy');
  const removed = await page.evaluate(async id => {
    const response = await authFetch(window.API.api_branches);
    if (!response.ok) return false;
    const body = await response.json();
    const rows = Array.isArray(body) ? body : body.branches;
    return Array.isArray(rows) && !rows.some(row => row.id === id);
  }, creation.sandbox.id);
  assert(removed, 'lesson 30 removes its exact owned branch');
  return {appId, creation};
}

module.exports = {walkLesson30};
