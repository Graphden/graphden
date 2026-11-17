'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const directory = path.join(__dirname, '../../resources/packages/app/editor');
const flush = () => new Promise(resolve => setImmediate(resolve));
function fixture(root = false) {
  const document = createDocument();
  document.readyState = 'loading';
  const create = document.createElement;
  document.createElement = tag => {
    const el = create(tag);
    el.toggleAttribute = (key, on) => on ? el.setAttribute(key, '') : el.removeAttribute(key);
    el.append = (...children) => children.forEach(child => el.appendChild(child));
    el.getClientRects = () => {
      for (let node = el; node; node = node.parentNode) if (node.hidden) return [];
      return el.isConnected ? [{}] : [];
    };
    return el;
  };
  const app = document.createElement('div'); app.id = 'app';
  const main = document.createElement('div'); main.id = 'main-container';
  const auth = document.createElement('div'); auth.id = 'auth-mount';
  app.append(auth, main); document.body.appendChild(app);
  const listeners = new Map(), requests = [], announcements = [];
  const context = vm.createContext({document, Map, Set, WeakMap, Object, queueMicrotask, AbortController,
    performance, DOMException, Event, CustomEvent: class {},
    gdAnnounce: text => announcements.push(text),
    gdPrefOwner: 'owner', gdPrefRead: () => null,
    API: {api_ui_root_plan: '/api/ui/root/plan'},
    GraphdenBrowser: {keyword: key => key, isCallable: value => typeof value === 'function',
      createRuntime: () => ({run: () => new Map([['legacy', true]])})},
    authFetch: (_url, options) => new Promise((resolve, reject) => requests.push({resolve, reject, options})),
    addEventListener(event, callback) {
      if (!listeners.has(event)) listeners.set(event, []);
      listeners.get(event).push(callback);
    }, dispatchEvent() {},
  });
  context.window = context;
  for (const file of ['editor-ui-loading.js', ...(root ? ['editor-ui-policy.js', 'editor-root.js'] : [])])
    vm.runInContext(fs.readFileSync(path.join(directory, file), 'utf8'), context, {filename: file});
  const response = () => ({ok: true, json: async () => ({ok: true, 'argument-name': null,
    'root-id': '00000000-0000-0000-0000-000000000001', plan: {}})});
  return {context, document, app, main, auth, requests, response,
    announcements, activity: context.gdUILoading,
    status: component => document.getElementById('gd-ui-loading-' + component),
    event: name => { for (const callback of listeners.get(name) || []) callback(); }};
}

test('loading annotates its local host without replacing or blocking working controls', async () => {
  const f = fixture();
  const host = f.document.createElement('span'), button = f.document.createElement('button');
  button.textContent = 'Edit'; host.appendChild(button); f.main.appendChild(host);
  host.setAttribute('aria-describedby', 'existing'); host.setAttribute('aria-busy', 'true');
  f.document.activeElement = button;
  f.activity.bind('canvas-values', host);
  const progress = f.activity.begin('canvas-values', () => {});
  await flush();
  assert.equal(host.getAttribute('data-ui-loading'), '');
  assert.equal(host.getAttribute('aria-describedby'), 'existing gd-ui-loading-canvas-values');
  assert.equal(f.status('canvas-values').getAttribute('aria-busy'), 'true');
  assert.match(f.announcements.at(-1), /Values and types — loading/);
  assert.equal(host.children[0], button);
  assert.equal(f.document.activeElement, button);
  assert.equal(button.disabled, undefined);
  assert.equal(host.inert, undefined);
  progress.finish(); await flush();
  assert.equal(host.getAttribute('data-ui-loading'), null);
  assert.equal(host.getAttribute('aria-busy'), 'true', 'another owner\'s graph refresh fence is untouched');
  assert.equal(host.getAttribute('aria-describedby'), 'existing');
  assert.equal(f.document.querySelector('.gd-ui-loading').hidden, true);
});

test('a hidden popover does not display an unrelated loading row', async () => {
  const f = fixture();
  const host = f.document.createElement('span'); host.hidden = true; f.main.appendChild(host);
  f.activity.bind('canvas-bindings', host);
  f.activity.begin('canvas-bindings', () => {});
  await flush();
  assert.equal(f.status('canvas-bindings').hidden, true);
  assert.equal(f.document.querySelector('.gd-ui-loading').hidden, true);
  host.hidden = false;
  f.activity.bind('canvas-bindings', host); await flush();
  assert.equal(f.status('canvas-bindings').hidden, false);
  assert.match(f.status('canvas-bindings').textContent, /Bindings — loading/);
});

test('errors stop busy, retain builtin content and retry the same component without replacing its status node', async () => {
  const f = fixture();
  const host = f.document.createElement('span'); f.main.appendChild(host); host.textContent = 'Working builtin';
  f.activity.bind('explorer', host);
  let progress;
  const retry = () => { progress = f.activity.begin('explorer', retry); };
  progress = f.activity.begin('explorer', retry);
  f.activity.error('explorer', 'could not be loaded. Built-in controls are available.');
  progress.finish(); await flush();
  const row = f.status('explorer');
  assert.equal(row.getAttribute('aria-busy'), 'false');
  assert.equal(row.dataset.phase, 'error');
  assert.equal(host.getAttribute('data-ui-loading'), null);
  assert.equal(host.getAttribute('data-ui-load-error'), '');
  assert.equal(host.textContent, 'Working builtin');
  row.querySelector('button').click(); await flush();
  assert.equal(f.status('explorer'), row);
  assert.equal(row.getAttribute('aria-busy'), 'true');
  assert.equal(row.querySelector('button').disabled, true);
  progress.finish(); await flush();
  assert.equal(f.status('explorer'), null);
});

test('superseded completions cannot clear a replacement; reset and cancellation cannot leave stale busy', async () => {
  const f = fixture();
  const first = f.activity.begin('editor', () => {});
  const second = f.activity.begin('editor', () => {});
  first.cancel(); first.finish(); await flush();
  assert.equal(f.status('editor').getAttribute('aria-busy'), 'true');
  f.activity.clear(true); await flush();
  assert.equal(f.status('editor').getAttribute('aria-busy'), 'true', 'initial component preference refresh preserves the independently pending root');
  second.cancel(); await flush(); assert.equal(f.status('editor'), null);
  const old = f.activity.begin('canvas', () => {});
  f.activity.clear(); old.finish(); await flush();
  assert.equal(f.status('canvas'), null);
});

test('real root lifetime keeps loading through held authorization and clears on success, denial, retry and dispose', async () => {
  const f = fixture(true);
  let unlock;
  f.context.gdAccountsReady = new Promise(resolve => { unlock = resolve; });
  const load = f.context.gdEditorRoot.reload(); await flush();
  assert.equal(f.status('editor').getAttribute('aria-busy'), 'true');
  assert.equal(f.requests.length, 0);
  unlock(); await flush();
  f.requests[0].resolve(f.response()); await load; await flush();
  assert.equal(f.status('editor'), null);
  const failed = f.context.gdEditorRoot.reload(); await flush();
  f.requests[1].resolve({ok: false, status: 403, json: async () => ({ok: false})});
  await failed; await flush();
  assert.equal(f.status('editor').dataset.phase, 'error');
  assert.equal(f.status('editor').getAttribute('aria-busy'), 'false');
  f.status('editor').querySelector('button').click(); await flush();
  assert.equal(f.status('editor').dataset.phase, 'loading');
  const pending = f.requests[2];
  f.context.gdEditorRoot.dispose(); await flush();
  assert.equal(pending.options.signal.aborted, true);
  assert.equal(f.status('editor'), null);
  pending.resolve(f.response()); await flush();
  assert.equal(f.status('editor'), null, 'late response cannot restore disposed activity');
});


test('a late root reply cannot erase loading for its replacement account', async () => {
  const f = fixture(true);
  const first = f.context.gdEditorRoot.reload();
  f.context.gdPrefOwner = 'another-account';
  const second = f.context.gdEditorRoot.reload();
  f.requests[0].resolve(f.response());
  await first; await flush();
  assert.equal(f.status('editor').dataset.phase, 'loading');
  assert.equal(f.requests[0].options.signal.aborted, true);
  f.requests[1].resolve(f.response());
  await second; await flush();
  assert.equal(f.status('editor'), null);
});
