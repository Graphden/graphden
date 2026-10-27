// A current, owned server refusal must replace the editor root and retry the
// typed query. The first response is controlled; the replacement is real HTTP.
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const {newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');
const {observeRootServerCalls, withoutRecoveredServerErrors, rootServerDiagnostic} = require('./root-server-call-test-helpers');

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const calls = await observeRootServerCalls(page);
  const errors = [];
  const responses = [];
  let refused = false;
  page.on('response', response => {
    if (new URL(response.url()).pathname === '/api/ui/server-call')
      responses.push(response.json().then(body => ({status: response.status(), code: body?.code})));
  });
  page.on('pageerror', error => errors.push({kind: 'pageerror', text: error.message}));
  page.on('console', message => {
    if (message.type() === 'error') errors.push({kind: 'console', text: message.text(), url: message.location().url});
  });
  await page.route('**/api/ui/server-call', route => {
    if (refused) return route.continue();
    refused = true;
    return route.fulfill({status: 422, contentType: 'application/json',
      body: JSON.stringify({ok: false, code: 'server-call-stale', reason: 'Server call is unavailable.'})});
  });
  try {
    await page.goto(BASE);
    await calls.settle();
    assert(refused, 'current account query received the controlled refusal');
    const results = await Promise.all(responses);
    assert.deepEqual(results[0], {status: 422, code: 'server-call-stale'});
    assert(results.some((result, index) => index > 0 && result.status === 200), 'replacement query reached the real server');
    const requests = calls.events.filter(event => event.kind === 'request');
    const first = requests[0], replacement = requests.find(event => event.root > first.root && event.owned);
    assert(first.owned && replacement, 'owned request acquired a newer root');
    assert(calls.events.some(event => event.kind === 'changing' && event.root === replacement.root), 'old root was disposed');
    assert(calls.events.some(event => event.kind === 'installed' && event.root === replacement.root), 'new root was installed');
    assert(!calls.events.some(event => event.kind === 'state' && event.root === first.root && event.phase === 'error'),
      'stale refusal never became a current consumer error');
    assert(calls.events.some(event => event.kind === 'state' && event.root === replacement.root
      && event.phase === 'ready' && event.owned && event.hostReady), 'replacement query became ready');
    assert.deepEqual(withoutRecoveredServerErrors(errors, calls.events), []);
    console.log('Current server refusal replaced the root and retried the real typed query PASS');
  } catch (error) {
    console.log('bounded HTTP diagnostics', JSON.stringify(await Promise.all(responses)));
    console.log('bounded server call diagnostics', JSON.stringify(rootServerDiagnostic(error, calls.events)));
    throw error;
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
