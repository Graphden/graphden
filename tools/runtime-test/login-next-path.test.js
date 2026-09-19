// auth-pages/login.js `nextPath` — the post-sign-in redirect target read
// from `?next=`. An open redirect is how a phishing mail borrows a trusted
// domain, so only a path on THIS origin survives: no scheme, no host, no
// protocol-relative `//`, no backslash tricks; anything else is `/`.
// CodeQL js/client-side-unvalidated-url-redirection + js/xss flagged the
// regex-only guard (2026-09-19); the URL-parser + origin comparison is the
// shape it recognises, and this pins the behaviour either way.
//
// Run:  node tools/runtime-test/login-next-path.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SRC = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'auth-pages', 'login.js');
const fnLine = fs.readFileSync(SRC, 'utf8').split('\n').find((l) => l.startsWith('function nextPath'));
if (!fnLine) { console.error('nextPath not found in login.js'); process.exit(1); }

const ORIGIN = 'https://app.graphden.dev';
const cases = [
  ['/join/abc?x=1#h', '/join/abc?x=1#h', 'a same-origin path keeps query + hash'],
  ['/', '/', 'root'],
  ['/a/../b', '/b', 'dot segments resolve on this origin'],
  ['//evil.com/x', '/', 'protocol-relative host is dropped'],
  ['/\\evil.com', '/', 'backslash (browser turns it into //) is dropped'],
  ['javascript:alert(1)', '/', 'a scheme is dropped'],
  ['https://evil.com/a', '/', 'an absolute foreign URL is dropped'],
  ['https://app.graphden.dev/a', '/', 'even our own absolute URL is dropped (paths only)'],
  ['%2F%2Fevil.com', '/', 'percent-encoded // decodes to a host and is dropped'],
  ['', '/', 'empty → root'],
  [null, '/', 'absent → root'],
];

let failures = 0;
for (const [next, want, why] of cases) {
  const search = next === null ? '' : '?next=' + encodeURIComponent(next);
  const ctx = vm.createContext({ URL, URLSearchParams, location: { search, origin: ORIGIN } });
  vm.runInContext(fnLine + '; globalThis.out = nextPath();', ctx);
  const ok = ctx.out === want;
  if (!ok) failures += 1;
  console.log((ok ? ' ✓ ' : ' ✗ ') + JSON.stringify(next) + ' → ' + JSON.stringify(ctx.out)
    + (ok ? '' : ' (want ' + JSON.stringify(want) + ')') + ' — ' + why);
}
console.log(failures === 0 ? 'PASS: ' + cases.length + ' cases' : 'FAIL: ' + failures + ' of ' + cases.length);
process.exit(failures === 0 ? 0 : 1);
