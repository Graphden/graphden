const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const helperRequire = createRequire(require.resolve('../browser-test/edit-test-helpers.js'));
const source = name => fs.readFileSync(path.join(__dirname, '../browser-test', name), 'utf8');

async function authMode(env) {
  const probes = [];
  const storage = new Map([['graphden.auth.password', 'old-static-fixture']]);
  const sandbox = vm.createContext({module: {exports: {}}, process: {env}, console,
    URL, Buffer, AbortSignal, AbortController, setTimeout, clearTimeout, require: helperRequire,
    localStorage: {setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k)},
    fetch: async (url, options) => { probes.push({url, options}); return {ok: true}; },
  });
  vm.runInContext(source('edit-test-helpers.js'), sandbox);
  const cookies = [];
  const page = {on() {}, setDefaultTimeout() {}, setDefaultNavigationTimeout() {}};
  const chromium = {launch: async () => ({newContext: async () => ({
    addCookies: async rows => cookies.push(...rows),
    addInitScript: async (fn, args) => fn(args), newPage: async () => page,
  })})};
  await sandbox.module.exports.newContext(chromium, {boot: false});
  const requests = [];
  // Capture the actual nodeApi transport boundary, independently of the
  // browser setup and the global-fetch readiness probes.
  sandbox.coreHttpRequest = async (...args) => { requests.push(args); return {ok: true}; };
  await sandbox.module.exports.nodeApi('GET', '/api/graph/entities?scope=tree');
  await sandbox.module.exports.nodeApi('GET', '/api/branches', undefined, {'X-Graphden-Branch': 'fixture'});
  return {probes, storage, cookies, requests};
}

async function tokenGate(env) {
  const logs = [];
  let opened = false;
  let navigated = false;
  const page = {removeAllListeners() {}, on() {}, context: () => ({tracing: {stop: async () => {}}}),
    goto: async url => { assert.equal(url, 'http://fixture/'); navigated = true; },
    waitForFunction: async () => { assert.equal(navigated, true); }, evaluate: async () => null,
    getByRole: () => ({isVisible: async () => false})};
  const sandbox = vm.createContext({process: {env}, console: {
    log: message => logs.push(message), error: message => logs.push(message)},
    require: name => {
      if (name === 'playwright') return {chromium: {}};
      if (name === './tutorial-tour-helpers') return {};
      if (name === './edit-test-helpers') return {
        BASE: 'http://fixture', assert: (ok, message) => { if (!ok) throw new Error(message); },
        newContext: async () => { opened = true; return {page, browser: {close: async () => {}}}; },
      };
      throw new Error('unexpected fixture dependency');
    },
  });
  await vm.runInContext(source('edit-tutorial-token-lifecycle.test.js'), sandbox);
  return {opened, navigated, logs, exitCode: sandbox.process.exitCode};
}

(async () => {
  const legacy = await authMode({AUTH_TOKEN: 'static-fixture'});
  assert.equal(legacy.cookies.length, 0);
  assert.equal(legacy.storage.get('graphden.auth.password'), 'static-fixture');
  assert.equal(legacy.probes[1].options.headers.Authorization, 'Bearer static-fixture');
  assert.equal(legacy.requests[0][2].Authorization, 'Bearer static-fixture');
  assert.equal(legacy.requests[0][2].Cookie, undefined);

  const account = await authMode({AUTH_TOKEN: 'must-not-shadow', GRAPHDEN_SESSION_COOKIE: 'session-fixture'});
  assert.equal(account.cookies[0].name, 'gd_session');
  assert.equal(account.cookies[0].value, 'session-fixture');
  assert.equal(account.storage.has('graphden.auth.password'), false);
  assert.equal(account.probes[1].options.headers.Cookie, 'gd_session=session-fixture');
  assert.equal(account.probes[1].options.headers.Authorization, undefined);
  for (const request of account.requests) {
    assert.equal(request[2].Cookie, 'gd_session=session-fixture');
    assert.equal(request[2].Authorization, undefined);
    assert.equal(request[2].Connection, 'close');
  }
  assert.equal(account.requests[1][2]['X-Graphden-Branch'], 'fixture');

  const absent = await tokenGate({GRAPHDEN_REQUIRE_TOKENS: '1'});
  assert.equal(absent.opened, false, 'required cloud gate never falls back to a static token');
  assert.equal(absent.exitCode, 1);
  const unsupported = await tokenGate({GRAPHDEN_REQUIRE_TOKENS: '1', GRAPHDEN_SESSION_COOKIE: 'session-fixture'});
  assert.equal(unsupported.navigated, true, 'capability checks run on the editor origin');
  assert.equal(unsupported.exitCode, 1, 'missing required token listing fails');
  assert.ok(!unsupported.logs.some(line => line.includes('SKIP')));
  const optional = await tokenGate({});
  assert.equal(optional.navigated, true);
  assert.equal(optional.exitCode, undefined);
  assert.ok(optional.logs.some(line => line.includes('SKIP')));
  console.log('PASS browser account-cookie auth and required token gate');
})().catch(error => { console.error(error); process.exitCode = 1; });
