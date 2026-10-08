'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {handlerPreviewTestOptions} = require('../browser-test/handler-preview-test-options');

test('ordinary browsers retain certificate checks and native DNS', () => {
  assert.deepEqual(handlerPreviewTestOptions({}), {});
  assert.deepEqual(handlerPreviewTestOptions({GRAPHDEN_PREVIEW_TEST_TLS: 'true'}), {});
});

test('explicit loopback candidate uses real HTTPS origin routing only', () => {
  const options = handlerPreviewTestOptions({GRAPHDEN_PREVIEW_TEST_TLS: '1',
    GRAPHDEN_URL: 'http://127.0.0.1:9960'});
  assert.equal(options.ignoreHTTPSErrors, true);
  assert.deepEqual(options.launchArgs, [
    '--host-resolver-rules=MAP *.gdcloud-candidate.localhost 127.0.0.1:9970',
    '--no-proxy-server',
  ]);
});

test('test TLS opt-in cannot weaken remote editor certificate checking', () => {
  assert.throws(() => handlerPreviewTestOptions({GRAPHDEN_PREVIEW_TEST_TLS: '1',
    GRAPHDEN_URL: 'https://graphden.com'}), /loopback/);
  assert.throws(() => handlerPreviewTestOptions({GRAPHDEN_PREVIEW_TEST_TLS: '1',
    GRAPHDEN_URL: 'http://127.0.0.1.example.com:9960'}), /loopback/);
});
