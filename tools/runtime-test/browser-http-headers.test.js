'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const vm = require('node:vm');
const source = fs.readFileSync(require.resolve('../browser-test/edit-test-helpers.js'), 'utf8');
const start = source.indexOf('function coreHttpRequest(');
const fn = vm.runInNewContext(source.slice(start, source.indexOf('async function nodeApi(', start)) + '\ncoreHttpRequest', {require, URL, Headers, Buffer, JSON});
test('core HTTP response preserves canonical creation header and body', async () => {
  const id = '11111111-1111-4111-8111-111111111111';
  const server = http.createServer((_req, res) => {
    res.writeHead(201, {'X-Graphden-Created-Id': id, 'Content-Type': 'application/json'});
    res.end('{"ok":true}');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const response = await fn('POST', 'http://127.0.0.1:' + server.address().port, {}, undefined);
    assert.equal(response.status, 201);
    assert.equal(response.headers.get('X-Graphden-Created-Id'), id);
    assert.equal(response.headers.get('x-graphden-created-id'), id);
    assert.equal(response.headers.get('missing'), null);
    assert.deepEqual(await response.json(), {ok: true});
  } finally { await new Promise(resolve => server.close(resolve)); }
});
