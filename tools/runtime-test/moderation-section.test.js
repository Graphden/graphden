// editor-moderation.js — the Platform → Moderation section mounts only for a
// signed-in platform-admin on an instance whose registry package serves the
// moderation route; then it is a lazy `hx-get` of the queue partial. Runs
// under node's vm; no browser, no stack.
//
// Run:  node tools/runtime-test/moderation-section.test.js
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

function load({ authed, caps, api }) {
  const document = {
    createElement: (tag) => ({ tag, className: '', innerHTML: '' }),
    addEventListener: () => {},
  };
  const window = {
    graphdenHasCap: (c) => caps.includes(c),
    API: api,
  };
  const ctx = vm.createContext({ console, document, window, isAuthenticated: () => authed });
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-moderation.js'), 'utf8'), ctx,
                  { filename: 'editor-moderation.js' });
  return ctx;
}

const WITH_ROUTE = { api_marketplace: '/api/marketplace', api_marketplace_moderation: '/api/marketplace/moderation' };

console.log(' the section is gated: signed in, platform-admin, the moderation route present');
assert(load({ authed: false, caps: ['platform-admin'], api: WITH_ROUTE }).buildModerationSection() === null, 'signed out → no section');
assert(load({ authed: true, caps: ['manage-users'], api: WITH_ROUTE }).buildModerationSection() === null, 'no platform-admin → no section');
assert(load({ authed: true, caps: [], api: WITH_ROUTE }).buildModerationSection() === null, 'single-tenant (no capability header) → no section');
assert(load({ authed: true, caps: ['platform-admin'], api: { api_marketplace: '/api/marketplace' } }).buildModerationSection() === null,
       'registry without the moderation route → no section');

console.log(' with everything present it lazy-loads the queue partial');
{
  const el = load({ authed: true, caps: ['platform-admin'], api: WITH_ROUTE }).buildModerationSection();
  assert(el && el.className === 'sidebar-moderation', 'a .sidebar-moderation container');
  assert(/hx-get="\/partials\/moderation-queue"/.test(el.innerHTML), 'hx-get of the queue partial');
  assert(/hx-trigger="load"/.test(el.innerHTML), 'loaded on mount');
  assert(/Approve lists it; Reject keeps it/.test(el.innerHTML), 'the hint explains the two decisions');
}

console.log(passes + ' passed, ' + failures + ' failed');
process.exit(failures ? 1 : 0);
