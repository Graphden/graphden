// Editor modules stay under the size cap.
//
// The graphden-ui skill has said "a file over 800 lines is a code smell —
// split along a concern boundary before it crosses" since the frontend was
// split into modules, and nothing enforced it: by 2026-09-13 eight modules
// were over, the largest at 1700 lines, and the rule was being kept only by
// whoever happened to remember it. This is the other half of the rule —
// the same shape as `test/graphden/large_forms_guard_test.clj` for Clojure
// forms: the cap fails the build, and a file that must stay over it gets a
// line in ACKNOWLEDGED with the reason, so the exception is decided while
// the file is being written instead of at the next audit.
//
// Covers the editor bundle AND the platform-shared runtime (bundled into both
// the editor and /assets/graphden-runtime.js).
//
// Run:  node module-size.test.js

const fs = require('node:fs');
const path = require('node:path');

const CAP = 800;
const ROOT = path.join(__dirname, '..', '..', 'resources', 'packages');
const DIRS = ['app/editor', 'web/runtime'];

// file basename → why it is allowed over the cap. Empty on purpose: every
// module fit under the cap when this guard landed. An entry here is a claim
// that a reader is better off with the file whole — say what holds it together.
const ACKNOWLEDGED = {};

let fails = 0;
let checked = 0;
const stale = new Set(Object.keys(ACKNOWLEDGED));

for (const dir of DIRS) {
  const abs = path.join(ROOT, dir);
  for (const name of fs.readdirSync(abs).filter((f) => f.endsWith('.js')).sort()) {
    const lines = fs.readFileSync(path.join(abs, name), 'utf8').split('\n').length;
    checked += 1;
    if (lines <= CAP) {
      if (ACKNOWLEDGED[name]) {
        // The file shrank under the cap — the exception has outlived its reason.
        console.error(`  ✗ ${dir}/${name}: ${lines} lines — under the cap, remove its ACKNOWLEDGED entry`);
        fails += 1;
      }
      stale.delete(name);
      continue;
    }
    stale.delete(name);
    if (ACKNOWLEDGED[name]) {
      console.log(`  · ${dir}/${name}: ${lines} lines, acknowledged — ${ACKNOWLEDGED[name]}`);
      continue;
    }
    console.error(`  ✗ ${dir}/${name}: ${lines} lines > ${CAP} — split it along a concern seam `
      + '(see docs/EDITOR_MODULES.md), or acknowledge it here with the reason');
    fails += 1;
  }
}
for (const name of stale) {
  console.error(`  ✗ ACKNOWLEDGED names ${name}, which does not exist`);
  fails += 1;
}

if (fails) {
  console.error(`module-size: ${fails} problem(s) over ${checked} files`);
  process.exit(1);
}
console.log(`module-size: ${checked} files, every one ≤ ${CAP} lines — PASS`);
