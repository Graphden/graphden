const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');

const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-http-host.js'), 'utf8');
const id = '66666666-6666-4666-8666-666666666666';

function fixture(fetch) {
  const events = [];
  const fields = new Map();
  const element = {dataset: {}, querySelector(selector) {
    if (!fields.has(selector)) fields.set(selector, {removeAttribute(key) {delete this[key];}});
    return fields.get(selector);
  }};
  const state = {session: 'account:org:branch', fn: {id: 'handler-uuid', name: 'lesson-handler'}, available: true, busy: false};
  const context = vm.createContext({
    window: {gdAccount: {id: 'account'}}, URL, location: {origin: 'https://editor.example.test'},
    graphdenTenancyActive: () => true, serviceSessionKey: () => context.session,
    session: 'account:org:branch', document: {body: {classList: {toggle() {}}}},
    crypto: {randomUUID: () => id}, installPopoverDismiss() {},
    state, element, events,
    _tourTrackHttpPublication(publicationId, fn) {events.push(['stage', publicationId, fn.id]);},
    authFetch(url, options) {events.push(['fetch', url, options]); return fetch(url, options);},
  });
  vm.runInContext(source, context);
  vm.runInContext('httpHostState = state; httpHostEl = element;', context);
  return {context, state, fields, events};
}

test('a lost create response retains its prestaged UUID for exact Stop', async () => {
  let lost = true;
  const f = fixture(async () => {
    if (lost) throw new Error('response lost after commit');
    return {ok: true, json: async () => ({ok: true})};
  });
  await f.context.publishHttpHost(f.state);
  assert.equal(f.events[0][0], 'stage');
  assert.equal(f.events[1][0], 'fetch');
  assert.equal(JSON.parse(f.events[1][2].body)['create-id'], id);
  assert.equal(f.state.publication.id, id);
  assert.equal(f.fields.get('[data-http-host-stop]').disabled, false);
  assert.equal(f.fields.get('[data-http-host-url]').hidden, true);
  lost = false;
  await f.context.stopHttpHost(f.state);
  assert.equal(f.events.at(-1)[1], '/api/http-host/' + id);
  assert.equal(f.state.publication, null);
  assert.equal(f.state.stopped, true);
});

test('a delayed create cannot replace another function dialog', async () => {
  let resolve;
  const f = fixture(() => new Promise(done => {resolve = done;}));
  const running = f.context.publishHttpHost(f.state);
  f.context.replacement = {fn: {id: 'other'}, available: true};
  vm.runInContext('httpHostState = replacement;', f.context);
  resolve({ok: true, json: async () => ({ok: true, publication: {id, url: 'https://example.test/'}})});
  await running;
  assert.equal(f.fields.get('[data-http-host-url]').hidden, true);
  assert.equal(f.fields.get('[data-http-host-url]').href, undefined);
  assert.equal(f.context.replacement.publication, undefined);
});

test('capacity rejection permits retry and never displays a successful URL', async () => {
  const f = fixture(async () => ({ok: false, status: 429, json: async () => ({ok: false})}));
  await f.context.publishHttpHost(f.state);
  assert.equal(f.state.publication, null);
  assert.equal(f.fields.get('[data-http-host-publish]').disabled, false);
  assert.match(f.state.message, /wait.*expire/);
  assert.equal(f.fields.get('[data-http-host-url]').hidden, true);
});

test('failed Stop retains cleanup identity and does not claim revocation', async () => {
  const f = fixture(async () => ({ok: false, status: 403, json: async () => ({ok: false})}));
  f.state.publication = {id};
  await f.context.stopHttpHost(f.state);
  assert.equal(f.state.publication.id, id);
  assert.notEqual(f.state.stopped, true);
});


test('HTML preview uses explicit mode and an isolated two-minute capsule', async () => {
  const f = fixture(async () => ({ok: true, json: async () => ({ok: true, mode: 'handler',
    url: 'https://app.example.test/__preview/handler/test-capsule/'} )}));
  await f.context.mintHandlerPreview(f.state);
  assert.deepEqual(JSON.parse(f.events[0][2].body), {'fn-id': 'handler-uuid', mode: 'handler'});
  assert.equal(f.fields.get('[data-handler-preview-url]').href,
    'https://app.example.test/__preview/handler/test-capsule/');
  assert.equal(f.fields.get('[data-handler-preview-url]').hidden, false);
  assert.match(f.state.message, /two minutes.*expires.*new link/);
});

test('HTML remint removes expired links and rejects editor-origin addresses', async () => {
  const f = fixture(async () => ({ok: true, json: async () => ({ok: true, mode: 'handler',
    url: 'https://editor.example.test/__preview/handler/test-capsule/'} )}));
  const link = f.context.element.querySelector('[data-handler-preview-url]');
  link.href = 'https://app.example.test/__preview/handler/expired-capsule/';
  link.hidden = false;
  await f.context.mintHandlerPreview(f.state);
  assert.equal(link.hidden, true);
  assert.equal(link.href, undefined);
  assert.match(f.state.message, /isolated HTTPS/);
  assert.equal(f.fields.get('[data-handler-preview-mint]').disabled, false);
});

test('HTML capsule callbacks are ignored after branch or account changes', async () => {
  let resolve;
  const f = fixture(() => new Promise(done => {resolve = done;}));
  const running = f.context.mintHandlerPreview(f.state);
  f.context.session = 'other-account:org:other-branch';
  resolve({ok: true, json: async () => ({ok: true, mode: 'handler',
    url: 'https://app.example.test/__preview/handler/test-capsule/'})});
  await running;
  assert.equal(f.fields.get('[data-handler-preview-url]').href, undefined);
  assert.equal(f.fields.get('[data-handler-preview-url]').hidden, true);
});


test('an already stale HTML dialog cannot mint in the new branch', async () => {
  const f = fixture(async () => {throw new Error('must not send');});
  f.context.session = 'account:org:new-branch';
  await f.context.mintHandlerPreview(f.state);
  assert.equal(f.events.length, 0);
});
