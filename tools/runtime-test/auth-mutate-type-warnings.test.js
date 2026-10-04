// editor-auth.js — a persisted type warning must be visible on the same write
// that creates it, while preserving the response body for the caller.
//
// Run:  node tools/runtime-test/auth-mutate-type-warnings.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor', 'editor-auth.js'), 'utf8');

const messages = [];
async function runWithToast(response) {
  const context = vm.createContext({
    URLSearchParams,
    fetch: async () => response,
    localStorage: { getItem: () => null },
    document: { body: { addEventListener() {} } },
    window: {},
    gdToast: (...args) => messages.push(args),
  });
  context.window = context;
  vm.runInContext(source, context);
  const result = await context.authMutate('POST', '/api/entities/binding', { value: '1' });
  return result;
}

function jsonResponse(body) {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    clone: () => ({ json: async () => body }),
    json: async () => body,
  };
}

function safeRepairHint(warning) {
  const context = vm.createContext({
    document: { body: { addEventListener() {} } },
    window: {},
  });
  context.window = context;
  vm.runInContext(source, context);
  return context.safeTypeRepairHint(warning);
}

(async () => {
  const warning = jsonResponse({ 'created': 'id', 'type-warnings': [{
    message: 'private diagnostic text; literal=TOP_SECRET_SENTINEL',
    binding: 'TOP_SECRET_SENTINEL', expected: ':int', actual: ':text',
  }] });
  const preserved = await runWithToast(warning);
  assert.equal(preserved, warning, 'the original Response is returned');
  assert.equal(messages.length, 1, 'a successful write with warnings shows feedback');
  assert.match(messages[0][0], /type warning/);
  assert.match(messages[0][0], /this function/);
  assert.match(messages[0][0], /Expected integer, but got text/);
  assert.match(messages[0][0], /:parse-int/);
  assert.doesNotMatch(messages[0][0], /private diagnostic text|TOP_SECRET_SENTINEL/,
    'the toast uses safe type labels, never message or binding contents');
  assert.equal(safeRepairHint({ expected: ':int', actual: ':text' }),
    'Try :parse-int before saving.',
    'the detailed explainer can reuse the save toast repair hint');
  assert.equal(safeRepairHint({ expected: ':text', actual: 'TOP_SECRET_SENTINEL' }), '',
    'unknown type names never become repair instructions');
  assert.equal(safeRepairHint({ expected: ':number', actual: ':text', message: 'TOP_SECRET_SENTINEL' }),
    'Try :parse-number before saving.',
    'repair selection ignores arbitrary diagnostic text');

  messages.length = 0;
  await runWithToast(jsonResponse({ created: 'id', 'type-warnings': [{
    expected: ['refine', 'int', ['>=', 1]], actual: 'int', binding: -1,
  }] }));
  assert.match(messages[0][0], /does not meet the required constraints/);
  assert.doesNotMatch(messages[0][0], /-1/, 'refinement feedback does not reveal the rejected value');

  messages.length = 0;
  await runWithToast(jsonResponse({ created: 'id', 'dependent-type-warning-count': 2 }));
  assert.equal(messages.length, 1, 'dependent diagnostics are surfaced when the edited function remains valid');
  assert.match(messages[0][0], /2 dependent functions/);

  messages.length = 0;
  await runWithToast(jsonResponse({ created: 'id' }));
  assert.equal(messages.length, 0, 'a clean write does not show a warning');

  console.log('✓ authMutate surfaces type warnings without consuming or disclosing the diagnostic body');
})().catch((error) => { console.error(error); process.exitCode = 1; });
