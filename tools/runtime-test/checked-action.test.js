const assert = require('node:assert/strict');
const {checkedAction} = require('../browser-test/checked-action');

const goodResponse = {ok: () => true, status: () => 200,
  url: () => 'http://localhost/api/entities/binding'};
const request = (response = goodResponse) => ({
  method: () => 'POST', url: () => 'http://localhost/api/entities/binding',
  response: async () => response, failure: () => null
});
const page = (outcome) => ({waitForRequest: async (matches) => {
  if (outcome === 'missing') throw new Error('request timeout');
  const value = outcome || request();
  assert(matches(value));
  return value;
}});
const action = {name: 'save-binding', ready: async () => {}, gesture: async () => {},
  request: {method: 'POST', path: '/api/entities/binding'},
  settled: async () => {}, timeoutMs: 10};

async function fails(phase, overrides = {}, browser = page()) {
  await assert.rejects(
    checkedAction(browser, {...action, ...overrides}),
    (error) => error.phase === phase && error.action === 'save-binding');
}

(async () => {
  assert.equal(await checkedAction(page(), action), goodResponse);
  await fails('element-absent', {ready: async () => { throw new Error('no Save'); }});
  await fails('click-failed', {gesture: async () => { throw new Error('intercepted'); }});
  await fails('request-not-sent', {}, page('missing'));
  await fails('response-timeout', {}, page({...request(),
    response: () => new Promise(() => {})}));
  await fails('response-missing', {}, page(request(null)));
  await fails('http-error', {}, page(request({ok: () => false, status: () => 500,
    url: () => 'http://localhost/api/entities/binding'})));
  await fails('ui-not-settled', {settled: async () => { throw new Error('popover still open'); }});
  const created = {...request(), url: () => 'http://localhost/api/entities/fn'};
  const rebound = {...request(), method: () => 'PUT',
    url: () => 'http://localhost/api/entities/binding/123'};
  const requests = [
    {name: 'create', method: 'POST', path: '/api/entities/fn'},
    {name: 'rebind', method: ['POST', 'PUT'], path: /^\/api\/entities\/binding\/[^/]+$/}
  ];
  const browser = {waitForRequest: async (matches) => [created, rebound].find(matches)};
  assert.deepEqual(await checkedAction(browser, {...action, request: undefined, requests}),
    [goodResponse, goodResponse]);
  await assert.rejects(checkedAction({waitForRequest: async (matches) => {
    if (matches(created)) return created;
    throw new Error('rebind absent');
  }}, {...action, request: undefined, requests}),
  (error) => error.phase === 'request-not-sent' && /rebind/.test(error.message));
  console.log('checked browser action phases passed');
})().catch((error) => { console.error(error); process.exitCode = 1; });
