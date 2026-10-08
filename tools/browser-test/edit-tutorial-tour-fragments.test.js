// Lesson 14 uses real cookie authentication, isolated HTTPS and server expiry.
// No HAR/video/screenshots: capsule addresses are short-lived credentials.
'use strict';
const {chromium} = require('playwright');
const {randomBytes} = require('node:crypto');
const {writeFileSync} = require('node:fs');
const {assert, newContext, BASE} = require('./edit-test-helpers');
const {handlerPreviewTestOptions} = require('./handler-preview-test-options');
const {
  waitTourTitle, clickTourButton, filterAndSelect, extendViaRowActions,
  bindPlaceholderOn, bindSeqAnchorPlaceholder, appendFnRefViaChip,
  editBoundValue, finishAndDelete,
} = require('./tutorial-tour-helpers');

const handler = 'tutorial-fragment-handler';
const clockBody = 'tutorial-fragment-clock-body';
const clockHandler = 'tutorial-fragment-clock-handler';
const prefix = '<!doctype html><html><head><meta name="htmx-config" content=\'{"selfRequestsOnly":false,"withCredentials":false,"allowEval":false}\'><script src="assets/htmx.min.js"></script></head><body><button hx-get="fragment" hx-select="#fragment" hx-target="#out">Refresh</button><div id="out"><p id="fragment">';
const suffix = '</p></div></body></html>';
const {rootAction, apps, closePopover, mint, remintWithPendingCheck, openPreview,
  refreshFragment, selectedIdentity, exactFnPresent} = require('./tutorial-handler-preview-helpers');
const {walkLesson30} = require('./tutorial-app-helpers');

(async () => {
  assert(!!process.env.GRAPHDEN_SESSION_COOKIE, 'native cloud walk requires a real account cookie');
  assert(!process.env.GRAPHDEN_JS_COVERAGE, 'capsule walk does not record request-bearing artifacts');
  const {browser, page} = await newContext(chromium,
    {...handlerPreviewTestOptions(), boot: false});
  page.on('dialog', dialog => dialog.accept().catch(() => {}));
  const errors = [];
  page.on('pageerror', () => errors.push(true));
  let stage = 'start';
  let appId;
  let branch;
  let external;
  let clockPage;
  let failed = false;
  const createdIds = [];
  try {
    await page.goto(BASE + '/?tutorial=14');
    await waitTourTitle(page, 'A real fragment request', 150000);
    await clickTourButton(page, 'Next');
    await waitTourTitle(page, 'Open html-ok-response');
    await filterAndSelect(page, 'html-ok-response', 'html-ok-response');
    await waitTourTitle(page, 'Create your HTML handler');
    await extendViaRowActions(page, handler, 'html-ok-response');
    await waitTourTitle(page, 'Open your handler');
    await filterAndSelect(page, handler, handler);
    await waitTourTitle(page, 'Bind the page');
    const handlerId = await selectedIdentity(page);
    createdIds.push(handlerId);
    branch = await page.evaluate(() => ({id: _tourState.sandboxBranchId,
      name: _tourState.sandboxBranch, 'base-branch-id': _tourState.sandboxBaseBranchId,
      principal: {..._tourState.principal}, active: getCurrentBranchName()}));
    assert(branch.id && branch.name !== 'main' && branch.name === branch.active,
      'handler is authored on this exact owned lesson branch');
    assert(!await exactFnPresent(page, handlerId, 'main'),
      'the preview handler does not exist on main');
    await bindPlaceholderOn(page, handler, 'body', 'literal', prefix + 'First fragment' + suffix);
    await waitTourTitle(page, "Open your handler's Apps");

    stage = 'ordinary app creation';
    const beforeApps = new Set((await apps(page)).map(row => row.id));
    const label = 'native-fragment-' + randomBytes(6).toString('hex');
    await rootAction(page, handler, 'apps');
    await waitTourTitle(page, "Create the lesson's app host");
    await page.locator('.fn-apps-popover.visible .app-create-form [name="label"]').fill(label);
    await page.locator('.fn-apps-popover.visible .app-create-btn').click();
    await page.waitForFunction(text => Array.from(document.querySelectorAll(
      '.fn-apps-popover.visible .fn-app-row a')).some(a => new URL(a.href).hostname.split('.')[0] === text), label);
    const own = (await apps(page)).filter(row => row.label === label
      && row['handler-fn-id'] === handlerId && !beforeApps.has(row.id));
    assert(own.length === 1, 'ordinary Apps wizard created exactly one fresh handler app');
    appId = own[0].id;
    await waitTourTitle(page, 'Open the preview controls');
    assert(await page.evaluate(id => _tourState.created.some(row => row.type === 'app-route'
      && row.id === id && row.receipt === 'created'), appId), 'lesson records the actual app creation receipt');
    await closePopover(page);

    stage = 'page and live fragment';
    const first = await mint(page, handler);
    await waitTourTitle(page, 'Open the real page');
    external = await openPreview(page);
    assert(await external.locator('#out #fragment').textContent() === 'First fragment',
      'real isolated page contains the first server fragment');
    await refreshFragment(external, 'First fragment');
    await clickTourButton(page, 'Next');
    await waitTourTitle(page, 'Edit the server response');
    await closePopover(page);
    await editBoundValue(page, prefix + 'Second fragment' + suffix);
    await clickTourButton(page, 'Next');
    await waitTourTitle(page, 'Fetch the changed fragment');
    await refreshFragment(external, 'Second fragment');
    await clickTourButton(page, 'Next');
    await waitTourTitle(page, 'If the two minutes expired');

    stage = 'server expiry';
    while (Date.now() < first.started + first.ttl + 1000) {
      await page.waitForTimeout(Math.min(30000, first.started + first.ttl + 1000 - Date.now()));
      console.log('Waiting for real server capsule expiry');
    }
    const denied = await external.reload();
    assert([403, 404, 410].includes(denied.status()), 'expired capsule is denied by the real server');
    const renewed = await mint(page, handler);
    assert(renewed.url !== first.url, 'remint replaces the expired capability');
    await remintWithPendingCheck(page, renewed.url);
    await external.close();
    external = await openPreview(page);
    await refreshFragment(external, 'Second fragment');
    await closePopover(page);

    stage = 'server clock composition';
    await filterAndSelect(page, 'str-join', 'str-join');
    await extendViaRowActions(page, clockBody, 'str-join');
    await filterAndSelect(page, clockBody, clockBody);
    createdIds.push(await selectedIdentity(page));
    await bindSeqAnchorPlaceholder(page, prefix);
    await appendFnRefViaChip(page, 'coll', 'current-time-ms');
    await bindSeqAnchorPlaceholder(page, suffix);
    await filterAndSelect(page, 'html-ok-response', 'html-ok-response');
    await extendViaRowActions(page, clockHandler, 'html-ok-response');
    await filterAndSelect(page, clockHandler, clockHandler);
    createdIds.push(await selectedIdentity(page));
    await bindPlaceholderOn(page, clockHandler, 'body', 'fn-ref', clockBody);
    await mint(page, clockHandler);
    clockPage = await openPreview(page);
    const firstClock = await clockPage.locator('#out #fragment').textContent();
    assert(/^\d{13}$/.test(firstClock), 'named graph composition renders the server clock');
    await clockPage.waitForTimeout(25);
    const tick = clockPage.waitForResponse(response => new URL(response.url()).pathname.endsWith('/fragment'));
    await clockPage.getByRole('button', {name: 'Refresh', exact: true}).click();
    assert((await tick).status() === 200, 'clock makes another real handler execution');
    await clockPage.waitForFunction(old => {
      const value = document.querySelector('#out #fragment')?.textContent;
      return /^\d{13}$/.test(value || '') && Number(value) > Number(old);
    }, firstClock);
    assert(true, 'server clock advances through HTMX without a client clock substitute');
    await clockPage.close();
    clockPage = null;
    await external.close();
    external = null;
    await closePopover(page);

    stage = 'exact cleanup';
    await clickTourButton(page, 'Next');
    await waitTourTitle(page, 'Finish and remove the lesson graph');
    await finishAndDelete(page);
    assert(!(await apps(page)).some(row => row.id === appId), 'Finish removes the exact created app');
    appId = null;
    assert(await page.evaluate(() => getCurrentBranchName()) === 'main', 'cleanup returns to main');
    for (const id of createdIds) assert(!await exactFnPresent(page, id), 'owned lesson identity is absent on main');
    const branches = await page.evaluate(async () => {
      const r = await authFetch(window.API.api_branches);
      if (!r.ok) throw new Error('Cannot verify branch cleanup');
      const body = await r.json();
      return Array.isArray(body) ? body : body.branches;
    });
    assert(Array.isArray(branches) && !branches.some(row => row.id === branch.id), 'exact owned branch is removed');
    stage = 'lesson 30';
    branch = null;
    createdIds.length = 0;
    await walkLesson30(page, BASE, {
      onHandler: creation => {
        branch = {...creation.sandbox, principal: creation.principal};
        createdIds.push(creation.id);
      },
      onAppAttempt: id => { appId = id; },
    });
    appId = null;
    assert(errors.length === 0, 'editor reports no uncaught browser errors');
    console.log('PASS: native lessons 14/30 real HTTPS, HTMX, edit, expiry, clock, Apps and exact cleanup');
  } catch (_) {
    failed = true;
    // Error stacks, DOM and navigation URLs may contain a capsule token.
    console.error('Native lesson 14 failed during ' + stage + '; exact fixture cleanup requires retry');
    if (branch?.id) {
      // Keep only exact fixture identities for retry after the ephemeral
      // browser closes. Never save account cookies or capsule addresses.
      const file = '/tmp/graphden-native14-cleanup-' + process.pid + '.json';
      writeFileSync(file, JSON.stringify({branch, appId, fnIds: createdIds}),
        {mode: 0o600, flag: 'wx'});
      console.error('Exact cleanup identities retained at ' + file);
    }
  } finally {
    if (clockPage) await clockPage.close().catch(() => {});
    if (external) await external.close().catch(() => {});
    // Never adopt/delete an app or a branch by name on failure. Preserve the
    // session's exact ownership ledger for the editor's cleanup retry.
    await browser.close();
  }
  process.exitCode = failed ? 1 : 0;
})();
