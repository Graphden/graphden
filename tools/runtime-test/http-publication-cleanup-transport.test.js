'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const {trackPublications} = require('../browser-test/tutorial-http-helpers');
for (const outcome of ['absent', 'retained', 'refused']) {
  test('publication cleanup retains creation scope and checks ' + outcome, async () => {
    const page = new EventEmitter();
    page.evaluate = () => {throw new Error('navigation destroyed the realm');};
    const calls = [];
    let disposed = false;
    const cleanup = trackPublications(page, {requestFactory: async options => {
      assert.deepEqual(options.storageState, {cookies: [], origins: []});
      assert.deepEqual(options.extraHTTPHeaders, {authorization: 'creation-token', 'x-graphden-org': 'creation-org', 'x-graphden-branch': 'creation-branch'});
      return {delete: async url => {calls.push(url); return {ok: () => true, json: async () => ({ok: outcome !== 'refused'})};},
        get: async url => {calls.push(url); return {ok: () => true, json: async () => ({ok: true, publications: outcome === 'retained' ? [{id: 'owned-id'}] : []})};},
        dispose: async () => {disposed = true;}};
    }});
    page.emit('request', {method: () => 'POST', url: () => 'https://creation.example/api/http-host',
      postDataJSON: () => ({'create-id': 'owned-id'}), allHeaders: async () => ({authorization: 'creation-token',
        'x-graphden-org': 'creation-org', 'x-graphden-branch': 'creation-branch', host: 'do-not-replay', 'content-length': '99'})});
    const priorExitCode = process.exitCode;
    if (outcome === 'absent') await cleanup();
    else await assert.rejects(cleanup());
    assert.equal(disposed, true);
    assert.equal(calls[0], 'https://creation.example/api/http-host/owned-id');
    assert.equal(page.listenerCount('request'), 0);
    // The browser helper marks deliberate negative assertions in process state.
    process.exitCode = priorExitCode;
  });
}
