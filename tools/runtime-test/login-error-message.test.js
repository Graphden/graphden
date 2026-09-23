// auth-pages/login.js — the server-side auth redirects land on
// `/login?error=<code>` (OAuth / Telegram callbacks, the verify link). The
// page used to ignore the parameter, so a refused social sign-in reloaded a
// blank form. It now says why in the form's alert — in particular the
// `email_has_password` refusal (a social identity is never auto-linked into
// an account that signs in with a password; docs/ACCOUNTS.md).
//
// Run:  node tools/runtime-test/login-error-message.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SRC = fs.readFileSync(
  path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'auth-pages', 'login.js'), 'utf8');

function render(search) {
  const els = {};
  const el = (id) => {
    if (!els[id]) {
      els[id] = { id, textContent: '', className: '', style: {}, classList: { toggle() {} },
                  setAttribute() {}, focus() {} };
    }
    return els[id];
  };
  const document = { readyState: 'complete', getElementById: el, querySelector: () => null,
                     addEventListener() {} };
  const ctx = vm.createContext({ URL, URLSearchParams, document,
                                 location: { search, origin: 'https://app.example' } });
  vm.runInContext(SRC, ctx);
  return el('msg');
}

const cases = [
  ['?error=email_has_password&provider=github',
   'An account with this email already exists. Sign in with your password, then connect GitHub in Settings.',
   'the pre-hijack refusal names the provider and the way forward'],
  ['?error=email_has_password&provider=google', /connect Google in Settings/, 'Google'],
  ['?error=oauth_state', /expired/, 'another existing code is now shown too'],
  ['?error=unknown-code', '', 'an unknown code shows nothing'],
  ['', '', 'no error → no message'],
];

let failures = 0;
for (const [search, want, why] of cases) {
  const msg = render(search);
  const ok = want instanceof RegExp ? want.test(msg.textContent) : msg.textContent === want;
  const errClass = want === '' || /\berr\b/.test(msg.className);
  if (!(ok && errClass)) failures += 1;
  console.log(((ok && errClass) ? ' ✓ ' : ' ✗ ') + JSON.stringify(search) + ' → '
    + JSON.stringify(msg.textContent) + ' [' + msg.className + '] — ' + why);
}
console.log(failures === 0 ? 'PASS: ' + cases.length + ' cases' : 'FAIL: ' + failures + ' of ' + cases.length);
process.exit(failures === 0 ? 0 : 1);
