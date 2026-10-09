'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {EventEmitter} = require('node:events');
const sentinel = 'SYNTHETIC_DIAGNOSTIC_SECRET_97';
const diagnosticSource = fs.readFileSync(require.resolve('../browser-test/safe-diagnostics'), 'utf8');
const helperSource = fs.readFileSync(require.resolve('../browser-test/edit-test-helpers'), 'utf8');
function fixture() {
  const lines = [];
  const box = {module: {exports: {}}, URL, console: {log: (...args) => lines.push(args.join(' '))},
    setTimeout: () => ({unref() {}}), clearTimeout() {}};
  vm.runInNewContext(diagnosticSource, box);
  return {safe: box.module.exports, lines};
}
function helper(name, end, globals) {
  const start = helperSource.indexOf('async function ' + name + '(');
  return vm.runInNewContext(helperSource.slice(start, helperSource.indexOf(end, start)) + '\n' + name, globals);
}
test('actual page listeners preserve error counts and HTTP metadata without arbitrary text', async () => {
  const {safe, lines} = fixture();
  const page = new EventEmitter();
  const frame = {url: () => 'https://user:' + sentinel + '@host/api/branches?q=' + sentinel + '#' + sentinel};
  page.mainFrame = () => frame;
  const counts = safe.installDiagnostics(page);
  let externalErrors = 0;
  page.on('pageerror', () => externalErrors++);
  page.emit('pageerror', Object.assign(new Error(sentinel, {cause: new Error(sentinel)}), {name: sentinel}));
  page.emit('crash');
  page.emit('console', {type: () => 'error', text: () => sentinel});
  page.emit('console', {type: () => 'warning', text: () => sentinel});
  const req = {method: () => 'POST', url: frame.url, failure: () => ({errorText: sentinel})};
  page.emit('requestfailed', req);
  page.emit('request', req);
  const res = {request: () => req, url: frame.url, status: () => 403, ok: () => false,
    text: () => { throw new Error('must not read response body ' + sentinel); }};
  await Promise.all(page.listeners('response').map(fn => fn(res)));
  page.emit('framenavigated', frame);
  page.emit('close');
  assert.equal(externalErrors, 1, 'listeners and strict failure observers are not removed');
  assert.deepEqual(JSON.parse(JSON.stringify(counts)), {pageErrors: 1, crashes: 1, consoleErrors: 1,
    consoleWarnings: 1, requestFailures: 1, httpFailures: 1});
  assert.match(lines.join('\n'), /\[op\] POST \/api\/branches → 403 in \d+ms/);
  assert.match(lines.join('\n'), /\[pageerror\]/);
  assert.equal(lines.join('\n').includes(sentinel), false);
});
test('deleteOrThrow failure still throws and writes stderr, without body/name/query or cause', async () => {
  const {safe} = fixture();
  const stderr = [];
  let code = 409;
  const remove = helper('deleteOrThrow', '// Delete the test', {
    httpFailure: safe.httpFailure,
    process: {stderr: {write: s => stderr.push(s)}},
    nodeApi: async () => ({ok: false, status: code,
      text: () => { throw new Error('must not read ' + sentinel); }}),
  });
  for (const status of [409, sentinel]) {
    code = status;
    await assert.rejects(remove('/api/entities/fn/' + sentinel + '?secret=' + sentinel, sentinel), error => {
      assert.equal(String(error.stack).includes(sentinel), false);
      assert.equal(error.cause, undefined);
      assert.match(error.message, /DELETE \/api\/entities\/:resource: HTTP (409|unknown)/);
      return true;
    });
  }
  assert.equal(stderr.length, 2);
  assert.equal(stderr.join('').includes(sentinel), false);
  code = 404;
  await remove('/api/entities/fn/x', sentinel);
  assert.equal(stderr.length, 2, 'already absent stays a successful cleanup');
});
test('nodeApi transport failures retain failure semantics without original message or cause', async () => {
  const {safe} = fixture();
  const request = helper('nodeApi', 'async function nodeApiJson', {
    ...safe, BASE: 'https://unit.invalid', requestAuthHeaders: () => ({}), Buffer, AbortController,
    setTimeout: () => 0, clearTimeout() {},
    coreHttpRequest: async () => { throw new TypeError(sentinel, {cause: new Error(sentinel)}); },
  });
  await assert.rejects(request('POST', '/api/secrets?token=' + sentinel), error => {
    assert.match(error.message, /POST \/api\/secrets: HTTP unknown \(TypeError\)/);
    assert.equal(String(error.stack).includes(sentinel), false);
    assert.equal(error.cause, undefined);
    return true;
  });
});
test('JSON parse errors returned by nodeApiJson cannot echo the response payload', async () => {
  const {safe} = fixture();
  const request = helper('nodeApiJson', 'async function deleteFnByName', {
    ...safe, nodeApi: async () => ({ok: true, status: 200,
      json: async () => { throw new SyntaxError(sentinel); }}),
  });
  await assert.rejects(request('GET', '/api/graph/entities?q=' + sentinel), error => {
    assert.equal(error.stack.includes(sentinel), false);
    assert.match(error.message, /HTTP 200 \(SyntaxError\)/);
    return true;
  });
});
test('spotlight descriptions redact secret-region descendants without dropping ordinary graph text', () => {
  const src = fs.readFileSync(require.resolve('../browser-test/tutorial-tour-helpers'), 'utf8');
  const start = src.indexOf('    const desc = (el) =>');
  const describe = vm.runInNewContext(src.slice(start, src.indexOf('    const rr =', start)) + '\ndesc',
    {secretRegions: '.test-secret-region'});
  const owner = {dataset: {}, textContent: sentinel};
  const node = inside => ({tagName: 'SPAN', id: '', className: '', textContent: inside ? sentinel : 'ordinary graph label',
    closest: selector => selector === '.node-overlay' ? owner : selector === '.test-secret-region' && inside ? {} : null,
    querySelector: () => null});
  assert.equal(JSON.stringify(describe(node(true))).includes(sentinel), false);
  assert.equal(describe(node(false)).text, 'ordinary graph label');
  assert.equal(describe(node(false)).card, null, 'card identity never falls back to potentially secret owner text');
});
