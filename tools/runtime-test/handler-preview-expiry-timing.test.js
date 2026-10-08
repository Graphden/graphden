'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {mint} = require('../browser-test/tutorial-handler-preview-helpers');

test('slow mint requests cannot shorten the real server expiry wait', async () => {
  const realNow = Date.now;
  let now = 1000;
  // The capability is synthetic and never leaves this fixture.
  const response = {
    ok: () => true,
    request: () => ({headerValue: async () => 'tutorial-owned'}),
    json: async () => {
      now = 11000; // authorization/compilation took ten seconds
      return {ok: true, mode: 'handler', 'expires-in-ms': 120000};
    },
  };
  const page = {
    waitForSelector: async () => {},
    waitForResponse: async () => response,
    evaluate: async () => 'tutorial-owned',
    locator: () => ({
      click: async () => {},
      getAttribute: async () => 'https://synthetic.invalid/__preview/handler/synthetic/',
    }),
  };
  try {
    Date.now = () => now;
    const result = await mint(page);
    assert.equal(result.started, 11000, 'TTL begins after the successful response, rather than before click');
    assert.equal(result.ttl, 120000);
    assert.equal(result.started + result.ttl + 1000, 132000,
      'expiry navigation waits the full advertised TTL and margin after receipt');
  } finally {
    Date.now = realNow;
  }
});
