// editor-marketplace.js — the two pure shell behaviours of the Marketplace
// surface: the share dialog's refusal wording (`gdMarketRefusalText`, the
// publish route's codes as sentences) and the URL mirror
// (`gdMarketSyncHash`: `#@marketplace/<name>` while an item is open,
// `#@marketplace` on the listing, nothing when the surface is not up).
// Runs under node's vm; no browser, no stack.
//
// Run:  node tools/runtime-test/marketplace-shell.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

let failures = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}

// A document stub: the surface attribute on body, one market root whose
// `[data-marketplace]` child carries (or not) the open item.
function load(state) {
  const root = { dataset: {} };
  if (state.item) root.dataset.marketplaceItem = state.item;
  const document = {
    addEventListener: () => {},
    body: { getAttribute: (k) => (k === 'data-surface' ? state.surface : null) },
    querySelector: (sel) => (sel === '#gd-market-root [data-marketplace]' && state.mounted ? root : null),
    getElementById: () => null,
  };
  const replaced = [];
  const window = {
    location: { hash: state.hash || '' },
    history: { replaceState: (_s, _t, url) => { replaced.push(url); window.location.hash = url; } },
    API: { api_marketplace: '/api/marketplace' },
  };
  const ctx = vm.createContext({ console, document, window, fetch: () => Promise.reject(new Error('no network')) });
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-marketplace.js'), 'utf8'), ctx,
                  { filename: 'editor-marketplace.js' });
  return { ctx, window, replaced };
}

console.log(' refusal codes become sentences; an unknown code passes through');
{
  const { window } = load({ surface: 'build' });
  assert(/first come, first served/.test(window.gdMarketRefusalText('name-taken')), 'name-taken is worded');
  assert(/immutable/.test(window.gdMarketRefusalText('version-exists')), 'version-exists is worded');
  assert(/category/.test(window.gdMarketRefusalText('bad-category')), 'bad-category is worded');
  assert(window.gdMarketRefusalText('something-new') === 'something-new', 'an unknown code is shown as is');
}

console.log(' the hash mirrors the open item while the surface is up');
{
  const { window, replaced } = load({ surface: 'market', mounted: true, item: 'acme.theme', hash: '#@marketplace' });
  window.gdMarketSyncHash();
  assert(replaced.length === 1 && replaced[0] === '#@marketplace/acme.theme', 'item → #@marketplace/<name> (got ' + JSON.stringify(replaced) + ')');
  window.gdMarketSyncHash();
  assert(replaced.length === 1, 'no re-push when the hash already matches');
}
{
  const { window, replaced } = load({ surface: 'market', mounted: true, item: 'a/b c', hash: '' });
  window.gdMarketSyncHash();
  assert(replaced[0] === '#@marketplace/' + encodeURIComponent('a/b c'), 'the name is URI-encoded');
}
{
  const { window, replaced } = load({ surface: 'market', mounted: true, hash: '#@marketplace/acme.theme' });
  window.gdMarketSyncHash();
  assert(replaced[0] === '#@marketplace', 'back on the listing → #@marketplace');
}
{
  const { window, replaced } = load({ surface: 'settings', mounted: true, item: 'acme.theme', hash: '#@settings' });
  window.gdMarketSyncHash();
  assert(replaced.length === 0, 'another surface up → the hash is left alone');
}


// The listing race: a root whose innerHTML is real, a shell whose surface
// switch mounts the default listing, and a fetch whose responses resolve in
// the order the test picks.
function loadRace() {
  const root = {
    innerHTML: '',
    dataset: {},
    querySelector: (sel) => (sel === '[data-marketplace]' && /data-marketplace/.test(root.innerHTML) ? root : null),
  };
  const pending = [];
  const fetch = (url) => new Promise((resolve) => {
    pending.push({ url, resolve: (html) => resolve({ ok: true, text: () => Promise.resolve(html) }) });
  });
  const document = {
    addEventListener: () => {},
    body: { getAttribute: () => 'market' },
    querySelector: (sel) => (sel === '#gd-market-root [data-marketplace]' && /data-marketplace/.test(root.innerHTML) ? root : null),
    getElementById: (id) => (id === 'gd-market-root' ? root : null),
  };
  const window = {
    location: { hash: '' },
    history: { replaceState: (_s, _t, url) => { window.location.hash = url; } },
    API: { api_marketplace: '/api/marketplace' },
    gdShellSurface: () => window.gdRenderMarket(),
  };
  const ctx = vm.createContext({ console, document, window, fetch });
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-marketplace.js'), 'utf8'), ctx,
                  { filename: 'editor-marketplace.js' });
  return { window, root, pending };
}
const settle = () => new Promise((r) => setImmediate(r));

(async () => {
  console.log(' opening on a kind fetches that kind only — the shell mount does not race it');
  {
    const { window, root, pending } = loadRace();
    window.gdMarketOpen({ kind: 'theme' });
    assert(/mk-loading/.test(root.innerHTML), 'the placeholder is mounted before the shell looks');
    assert(pending.length === 1 && /kind=theme$/.test(pending[0].url),
           'exactly one listing fetch, for the kind asked (got ' + JSON.stringify(pending.map((p) => p.url)) + ')');
    pending[0].resolve('<div data-marketplace="1">themes</div>');
    await settle();
    assert(root.innerHTML === '<div data-marketplace="1">themes</div>', 'the Themes listing lands');
  }

  console.log(' the newest listing request wins, whatever order the answers come in');
  {
    const { window, root, pending } = loadRace();
    window.gdRenderMarket();                   // the shell mounts the default listing…
    window.gdMarketOpen({ kind: 'theme' });    // …and the reader switches to Themes
    assert(pending.length === 2, 'two requests in flight');
    pending[1].resolve('<div data-marketplace="1">themes</div>');
    await settle();
    pending[0].resolve('<div data-marketplace="1">fns</div>');   // the slower default answers last
    await settle();
    assert(root.innerHTML === '<div data-marketplace="1">themes</div>',
           'the stale default listing does not overwrite the Themes tab (got ' + root.innerHTML + ')');
  }
  {
    const { window, root, pending } = loadRace();
    window.gdMarketOpen({ kind: 'theme' });
    window.gdMarketOpen({ name: 'acme.theme' });
    pending[0].resolve('<div data-marketplace="1">themes</div>');
    await settle();
    assert(root.innerHTML === '<div data-marketplace="1" class="mk-root mk-loading">Loading the marketplace…</div>',
           'an older listing answering first leaves the placeholder for the newer request');
    pending[1].resolve('<div data-marketplace="1" data-marketplace-item="acme.theme">item</div>');
    await settle();
    assert(/acme\.theme/.test(root.innerHTML), 'the item lands');
  }

  console.log(' a second visit to a mounted surface does not refetch');
  {
    const { window, root, pending } = loadRace();
    window.gdRenderMarket();
    pending[0].resolve('<div data-marketplace="1">fns</div>');
    await settle();
    window.gdRenderMarket();
    assert(pending.length === 1, 'the mounted listing is kept');
    assert(root.innerHTML === '<div data-marketplace="1">fns</div>', 'and shown as is');
  }

  console.log(passes + ' passed, ' + failures + ' failed');
  process.exit(failures ? 1 : 0);
})();
