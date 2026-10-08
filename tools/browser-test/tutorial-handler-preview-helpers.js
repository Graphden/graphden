'use strict';
const {assert, BASE} = require('./edit-test-helpers');
const {openRowActionsFor} = require('./tutorial-tour-helpers');

const linkSelector = '.http-host-popover.visible [data-handler-preview-url]';
const mintSelector = '.http-host-popover.visible [data-handler-preview-mint]';

async function rootAction(page, name, action) {
  await openRowActionsFor(page, name, 90000, {root: true});
  await page.locator('.row-actions-popover [data-action="' + action + '"]').click();
}

async function apps(page) {
  return page.evaluate(async () => {
    const response = await authFetch(window.API.api_orgs_apps);
    if (!response.ok) throw new Error('Cannot read own app identities');
    const rows = await response.json();
    if (!Array.isArray(rows)) throw new Error('Invalid app list');
    return rows;
  });
}

async function closePopover(page) {
  await page.keyboard.press('Escape');
}

async function mint(page, name) {
  if (name) await rootAction(page, name, 'http-host');
  await page.waitForSelector(mintSelector + ':not([disabled])');
  const started = Date.now();
  const replyPromise = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/preview-token'
    && response.request().method() === 'POST');
  await page.locator(mintSelector).click();
  const response = await replyPromise;
  const body = await response.json();
  assert(response.ok() && body.ok === true && body.mode === 'handler',
    'real handler preview minted');
  assert(body['expires-in-ms'] === 120000, 'server reports the two-minute lifetime');
  const branch = await page.evaluate(() => getCurrentBranchName());
  assert(branch !== 'main' && await response.request().headerValue('X-Graphden-Branch') === branch,
    'mint request explicitly captures the current lesson branch');
  await page.waitForSelector(linkSelector + ':not([hidden])');
  const url = await page.locator(linkSelector).getAttribute('href');
  const parsed = new URL(url);
  assert(parsed.protocol === 'https:' && parsed.origin !== new URL(BASE).origin
    && parsed.pathname.startsWith('/__preview/handler/'),
  'preview uses a separate HTTPS capsule origin');
  return {url, started, ttl: body['expires-in-ms']};
}

async function remintWithPendingCheck(page, oldUrl) {
  // Delay only the editor's outgoing mint request, then forward it unchanged.
  // Both capsule mint and every app request still hit the compiled server.
  let release;
  const waiting = new Promise(resolve => { release = resolve; });
  const pause = async route => { await waiting; await route.continue(); };
  await page.route('**/api/preview-token', pause);
  const reached = page.waitForRequest(request =>
    new URL(request.url()).pathname === '/api/preview-token', {timeout: 30000});
  const pending = mint(page);
  try {
    await reached;
    assert(await page.locator(linkSelector).getAttribute('href') === null
      && !await page.locator(linkSelector).isVisible(),
    'pending remint clears the previous clickable capability');
    assert(await page.locator(mintSelector).isDisabled(), 'pending mint prevents duplicate submission');
  } finally {
    release();
    await page.unroute('**/api/preview-token', pause);
  }
  const fresh = await pending;
  assert(fresh.url !== oldUrl, 'successful remint exposes a new capability');
  return fresh;
}

async function openPreview(page, {htmx = true} = {}) {
  const url = await page.locator(linkSelector).getAttribute('href');
  const [external, response] = await Promise.all([
    page.waitForEvent('popup'),
    page.context().waitForEvent('response', {predicate: value => value.url() === url
      && value.request().isNavigationRequest(), timeout: 30000}),
    page.locator(linkSelector).click(),
  ]);
  assert(response.status() === 200, 'real isolated HTML navigation succeeds');
  assert(response.headers()['content-security-policy']?.includes('sandbox allow-scripts allow-forms')
    && !response.headers()['set-cookie'], 'preview keeps the server sandbox and cookie policy');
  await external.waitForLoadState('domcontentloaded');
  if (htmx) await external.waitForFunction(() => typeof window.htmx !== 'undefined');
  return external;
}

async function refreshFragment(external, expected) {
  const before = await external.evaluate(() => performance.timeOrigin);
  const responsePromise = external.waitForResponse(response =>
    new URL(response.url()).pathname.endsWith('/fragment'));
  await external.getByRole('button', {name: 'Refresh', exact: true}).click();
  const response = await responsePromise;
  assert(response.status() === 200, 'actual HTMX fragment request succeeds');
  const headers = await response.request().allHeaders();
  assert(headers.origin === 'null' && headers['hx-request'] === 'true'
    && !headers.cookie && !headers.authorization,
  'sandbox HTMX request carries no editor credentials');
  const responseHeaders = response.headers();
  assert(responseHeaders['access-control-allow-origin'] === 'null'
    && responseHeaders['cache-control'] === 'no-store',
  'server returns sandbox CORS and no-store policy');
  await external.waitForFunction(text =>
    document.querySelector('#out #fragment')?.textContent === text, expected);
  assert(await external.evaluate(() => performance.timeOrigin) === before,
    'fragment update preserves the loaded document');
}

async function selectedIdentity(page) {
  return page.evaluate(() => selectedFnId);
}

async function exactFnPresent(page, id, branch) {
  return page.evaluate(async ({uuid, branchName}) => {
    const options = branchName ? {headers: {'X-Graphden-Branch': branchName}} : undefined;
    const response = await authFetch('/api/graph/entities?scope=subtree&root-id=' + uuid, options);
    if (!response.ok) throw new Error('Cannot verify exact lesson identity');
    const body = await response.json();
    if (!Array.isArray(body.fns)) throw new Error('Invalid exact subtree response');
    return body.fns.some(row => row.id === uuid);
  }, {uuid: id, branchName: branch});
}

module.exports = {rootAction, apps, closePopover, mint, remintWithPendingCheck,
  openPreview, refreshFragment, selectedIdentity, exactFnPresent};
