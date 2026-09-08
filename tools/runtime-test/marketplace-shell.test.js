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

console.log(passes + ' passed, ' + failures + ' failed');
process.exit(failures ? 1 : 0);
