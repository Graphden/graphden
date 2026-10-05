// Preparation must reject tenancy/auth before importing or creating a branch.
const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const {execFile} = require('node:child_process');
const {promisify} = require('node:util');

async function refusesBeforeWriting({headers = {}, status = 200, body, message, mode = []}) {
  const requests = [];
  const server = http.createServer((request, response) => {
    requests.push([request.method, request.url]);
    response.writeHead(status, {'Content-Type': 'application/json', ...headers});
    response.end(JSON.stringify(body));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const url = 'http://127.0.0.1:' + server.address().port;
    await assert.rejects(promisify(execFile)('bb', ['-cp', 'src',
      'tools/ui_preview/prepare.clj', ...mode, url, 'review-fixture', 'user.ui-preview'],
    {cwd: path.resolve(__dirname, '../..'), env: {...process.env, AUTH_TOKEN: 'fixture-token'}, timeout: 15000}),
    (error) => error.code === 1 && message.test(error.stderr));
    assert.deepEqual(requests, [['GET', '/api/branches']]);
  } finally { await new Promise((resolve) => server.close(resolve)); }
}

async function preparesAccountMenu({rejectExport = false} = {}) {
  const requests = [];
  let imported = '';
  const exporterId = await promisify(execFile)('bb', ['-cp', 'src', '-e',
    '(require \'[graphden.packages.records.ids :as ids]) (print (str (ids/fn-id "app.ui-preview" :browser-plan-export)))'],
  {cwd: path.resolve(__dirname, '../..'), timeout: 15000});
  const server = http.createServer(async (request, response) => {
    const uri = new URL(request.url, 'http://localhost');
    requests.push([request.method, uri.pathname]);
    let body;
    let status = 200;
    if (uri.pathname === '/api/branches') body = {branches: []};
    else if (uri.pathname === '/api/graph/entities') body = {fns: [{id: exporterId.stdout}]};
    else if (request.method === 'POST' && uri.pathname === '/api/import/graph') {
      for await (const chunk of request) imported += chunk;
      body = {imported: true};
    } else if (uri.pathname === '/ui-preview/plan') {
      assert.equal(uri.searchParams.get('branch'), 'review-fixture');
      status = rejectExport ? 422 : 200;
      body = rejectExport ? {reason: 'visibility-denied'} : {entries: Object.fromEntries(
        ['initial', 'update', 'view'].map((name) => [name, uri.searchParams.get(name)]))};
    } else if (request.method === 'DELETE' && uri.pathname === '/api/branches/review-fixture') body = {ok: true};
    else { status = 500; body = {reason: 'unexpected-request'}; }
    response.writeHead(status, {'Content-Type': 'application/json'});
    response.end(JSON.stringify(body));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const url = 'http://127.0.0.1:' + server.address().port;
    const run = promisify(execFile)('bb', ['-cp', 'src', 'tools/ui_preview/prepare.clj',
      '--account-menu', url, 'review-fixture', 'user.account-menu'],
    {cwd: path.resolve(__dirname, '../..'), timeout: 15000});
    if (rejectExport) await assert.rejects(run, (error) => error.code === 1 && /visibility-denied/.test(error.stderr));
    else {
      const prepared = JSON.parse((await run).stdout);
      const editor = new URL(prepared.url);
      assert.equal(editor.pathname, '/');
      assert.equal(editor.searchParams.get('branch'), prepared.branch);
      for (const name of ['initial', 'update', 'view']) assert.equal(editor.searchParams.get('ui-' + name), prepared.entries[name]);
      assert.equal(editor.hash, '#' + prepared.namespace + '.theme-canvas-background');
      assert.equal(prepared['plan-id'], undefined);
    }
    assert.match(imported, /:name :account-menu-view/);
    assert.doesNotMatch(imported, /:name :browser-plan\b/);
    assert.deepEqual(requests, [['GET', '/api/branches'], ['GET', '/api/graph/entities'],
      ['POST', '/api/import/graph'], ['GET', '/ui-preview/plan'],
      ...(rejectExport ? [['DELETE', '/api/branches/review-fixture']] : [])]);
  } finally { await new Promise((resolve) => server.close(resolve)); }
}

(async () => {
  await refusesBeforeWriting({headers: {'X-Graphden-Capabilities': ''}, body: {branches: []},
    message: /limited to self-hosted/});
  await refusesBeforeWriting({status: 401, body: {reason: 'auth-required'},
    message: /request failed/});
  await refusesBeforeWriting({body: {branches: [{name: 'review-fixture'}]},
    message: /existing preview edits are never overwritten/});
  await refusesBeforeWriting({headers: {'X-Graphden-Capabilities': ''}, body: {branches: []},
    message: /limited to self-hosted/, mode: ['--account-menu']});
  await preparesAccountMenu();
  await preparesAccountMenu({rejectExport: true});
  console.log('PASS: prepare guards, actual editor URL, read-only export and exact failed-copy cleanup');
})().catch((error) => { console.error(error); process.exitCode = 1; });
