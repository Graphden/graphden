'use strict';
const assert = require('node:assert/strict');
const {recoveredStaleCalls, withoutRecoveredServerErrors, rootServerDiagnostic} = require('../browser-test/root-server-call-test-helpers');
const old = {document: 'same-document', root: 1, lifetime: 1, invocation: 1, owned: true};
const next = {...old, root: 2, lifetime: 2};
const events = [
  {kind: 'installed', document: old.document, root: 1, order: 1},
  {kind: 'request', ...old, order: 2},
  {kind: 'changing', document: old.document, root: 2, order: 3},
  {kind: 'response', ...old, status: 422, code: 'server-call-stale', order: 4},
  {kind: 'installed', document: old.document, root: 2, order: 5},
  {kind: 'request', ...next, order: 6},
  {kind: 'response', ...next, status: 200, ok: true, order: 7},
  {kind: 'state', ...next, phase: 'ready', hostReady: true, order: 8},
];
const failure = {kind: 'console', text: 'Failed to load resource: the server responded with a status of 422', url: 'http://localhost/api/ui/server-call'};
assert.equal(recoveredStaleCalls(events), 1);
assert.deepEqual(withoutRecoveredServerErrors([failure], events), []);
const modify = (index, patch) => events.map((event, i) => i === index ? {...event, ...patch} : event);
for (const rejected of [
  modify(3, {code: null}), modify(3, {code: 'policy-refresh-required'}), modify(3, {status: 403}),
  modify(1, {owned: false}), modify(5, {root: 1}), modify(5, {document: 'other-document'}),
  modify(7, {owned: false}), modify(7, {hostReady: false}), modify(7, {phase: 'error'}),
  modify(7, {invocation: 2}), modify(6, {ok: false}), modify(4, {root: 3}),
  events.filter(event => event.kind !== 'changing'), events.filter(event => event.kind !== 'state'),
  events.filter(event => event.lifetime !== 2),
  [...events.map(event => event.kind === 'changing' ? {...event, order: 4.75} : event),
    {kind: 'state', ...old, phase: 'error', hostReady: true, order: 4.5}],
  [...events, {kind: 'state', ...old, phase: 'error', owned: false, hostReady: false, order: 4.5}],
]) assert.throws(() => recoveredStaleCalls(rejected), /server/);
assert.throws(() => withoutRecoveredServerErrors([], events), /exactly one/);
assert.throws(() => withoutRecoveredServerErrors([failure, failure], events), /exactly one/);
const unrelated = {kind: 'console', text: 'unrelated', url: 'http://localhost/other'};
assert.deepEqual(withoutRecoveredServerErrors([failure, unrelated], events), [unrelated]);
assert.equal(recoveredStaleCalls(events.filter(event => event.status !== 422)), 0);
assert.equal(rootServerDiagnostic(new Error('private body or token'), events).reason, 'other');
assert.equal(rootServerDiagnostic({message: {private: true}}, events).reason, 'other');
assert(!JSON.stringify(rootServerDiagnostic(null, events)).includes('same-document'));
console.log('PASS exact stale server-call supersession receipts; current, unknown, cross-document and unrendered refusals reject');
