'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {EventEmitter} = require('node:events');
const sentinel = 'SYNTHETIC_AUTH_PASSWORD_97';
function harness() {
  const lines = [];
  const proc = {env: {}, exitCode: 0};
  const console = {log: (...args) => lines.push(args.join(' ')), error: (...args) => lines.push(args.join(' '))};
  const context = {module: {exports: {}}, URL, process: proc, console, setTimeout, clearTimeout};
  vm.runInNewContext(fs.readFileSync(require.resolve('../browser-test/safe-diagnostics'), 'utf8'), context);
  return {safe: context.module.exports, lines, proc, console};
}
test('actual auth-login catch, pageerror and dialog handlers never echo credential text', async () => {
  const {safe, lines, proc, console} = harness();
  const page = new EventEmitter();
  let closed = false;
  let evaluates = 0;
  const poisoned = new Error(sentinel, {cause: new Error(sentinel)});
  Object.assign(page, {goto: async () => {}, waitForSelector: async () => {}, waitForFunction: async () => {},
    evaluate: async () => { if (++evaluates > 1) throw poisoned; }});
  const chromium = {launch: async () => ({newContext: async () => ({newPage: async () => page}),
    close: async () => { closed = true; }})};
  const requireMock = name => name === 'playwright' ? {chromium}
    : name === './safe-diagnostics' ? safe
      : {AUTH: sentinel, BASE: 'http://unit.invalid', assert: condition => { if (!condition) throw poisoned; }};
  await vm.runInNewContext(fs.readFileSync(require.resolve('../browser-test/edit-auth-login.test'), 'utf8'),
    {require: requireMock, process: proc, console, fetch: async () => ({status: 401})});
  page.emit('pageerror', poisoned);
  let accepted = false;
  page.emit('dialog', {message: () => { throw new Error('dialog message must not be inspected'); },
    accept: () => { accepted = true; }});
  assert.equal(proc.exitCode, 1);
  assert.equal(closed, true);
  assert.equal(accepted, true);
  assert.match(lines.join('\n'), /test failed/);
  assert.match(lines.join('\n'), /\[pageerror\]/);
  assert.equal(lines.join('\n').includes(sentinel), false);
});
test('actual cloud membership terminal catch discards original Playwright stack and nested cause', async () => {
  const {safe, lines, proc, console} = harness();
  proc.env = {GRAPHDEN_URL: 'http://127.0.0.1:9900', GRAPHDEN_ORG_EMAIL: 'owner@unit.invalid',
    GRAPHDEN_MEMBER_EMAIL: 'member@unit.invalid', GRAPHDEN_ORG_PASSWORD: sentinel};
  const chromium = {launch: async () => { throw new TypeError(sentinel, {cause: new Error(sentinel)}); }};
  await vm.runInNewContext(fs.readFileSync(require.resolve('../browser-test/cloud-membership-authz-e2e'), 'utf8'),
    {URL, console, process: proc, require: name => name === 'playwright' ? {chromium}
      : name === './safe-diagnostics' ? safe : require(name)});
  assert.equal(proc.exitCode, 1);
  assert.match(lines.join('\n'), /test failed: TypeError/);
  assert.equal(lines.join('\n').includes(sentinel), false);
});
