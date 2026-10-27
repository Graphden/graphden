'use strict';
const assert = require('node:assert/strict');
const browser = require('../../resources/packages/app/ui-preview/browser-runtime.js');
globalThis.GraphdenBrowser = {...browser, invoke: (query, input) => query(input)};
const server = require('../../resources/packages/app/ui-preview/browser-server.js');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return {promise, resolve}; };
(async () => {
  const requests = [], states = [];
  let scope = 'session/root/branch/acl-first';
  const lifetime = server.createLifetime({scope: () => scope, onState: state => states.push(state),
    transport(body, options) { const d = deferred(); requests.push({body, options, ...d}); return d.promise; }});
  const query = input => server.call({token: 'opaque'}, input);
  const input = new Map([[browser.keyword('query'), '']]);
  const first = lifetime.invoke(query, input);
  const second = lifetime.invoke(query, input);
  assert(requests[0].options.signal.aborted);
  const reply = (index, text) => requests[index].resolve({ok: true, json: async () => ({ok: true, value: browser.encode(text)})});
  reply(0, 'stale'); reply(1, 'current');
  await Promise.all([first, second]);
  assert.deepEqual(states.map(s => s.phase), ['loading', 'loading', 'ready']);
  assert.equal(states.at(-1).value, 'current');
  assert.equal(requests[0].body.descriptor.token, 'opaque');
  assert(browser.equal(browser.decode(requests[0].body.input), input));

  const third = lifetime.invoke(query, input);
  const parsing = deferred();
  requests[2].resolve({ok: true, json: () => parsing.promise});
  await Promise.resolve(); await Promise.resolve();
  scope = 'session/root/branch/acl-second';
  parsing.resolve({ok: true, value: browser.encode('stale parsed response')});
  await third;
  assert.equal(states.at(-1).phase, 'loading');

  const fourth = lifetime.invoke(query, input);
  lifetime.cancel();
  reply(3, 'cancelled'); await fourth;
  assert(requests[3].options.signal.aborted);
  assert.equal(states.at(-1).phase, 'cancelled');

  const denied = lifetime.invoke(query, input);
  requests[4].resolve({ok: false, json: async () => ({ok: false})});
  await denied;
  assert.equal(states.at(-1).phase, 'error');
  lifetime.dispose();
  await assert.rejects(lifetime.invoke(query, input), /disposed/);

  let staleCount = 0;
  const staleStates = [];
  const staleLifetime = server.createLifetime({scope: () => scope,
    transport: async () => ({status: 422, ok: false,
      json: async () => ({ok: false, code: 'server-call-stale'})}),
    onState: state => staleStates.push(state),
    onStale() { staleCount++; staleLifetime.dispose(); }});
  await staleLifetime.invoke(query, input);
  assert.equal(staleCount, 1);
  assert.deepEqual(staleStates.map(state => state.phase), ['loading']);
  console.log('browser server lifetime: PASS');
})().catch(error => { console.error(error); process.exitCode = 1; });
