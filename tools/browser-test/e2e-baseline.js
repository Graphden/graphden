#!/usr/bin/env node
// Per-file duration baseline for run-edit-tests.sh's DEGRADED verdict.
//
//   node e2e-baseline.js <gate log>... > e2e-baseline.tsv
//
// Reads the runner's own output (the gate logs under .git/wtq/logs/ carry it
// verbatim): a `─── <file> ───` header, then the `[ Ns  executor=…]` line the
// file ends on. Only first-attempt passes count — a retried file's seconds
// span every attempt. Prints `<median seconds>\t<file>` per file, slowest
// first. A file absent from the logs has no line, and the runner judges it
// against the absolute THRASH_FILE_SECS until a refresh picks it up.
//
// Refresh after a change that moves a file's duration for good (a split, a
// lesson added to a walk), from the GREEN gate runs since that change landed:
// a baseline taken from starved runs would raise every limit and blind the
// verdict it feeds.

const fs = require('node:fs');

const logs = process.argv.slice(2);
if (!logs.length) {
  console.error('usage: node e2e-baseline.js <gate log>... > e2e-baseline.tsv');
  process.exit(2);
}

const per = new Map();
for (const log of logs) {
  const lines = fs.readFileSync(log, 'utf8').replace(/\r/g, '\n').split('\n');
  let cur = null;
  for (const line of lines) {
    const h = line.match(/^─── (edit-\S+\.test\.js) ───/);
    if (h) { cur = h[1]; continue; }
    const t = line.match(/^\s+\[\s*(\d+)s\s+executor=\S*?(\s+attempts=\d+)?\]/);
    if (t && cur) {
      if (!t[2]) {
        if (!per.has(cur)) per.set(cur, []);
        per.get(cur).push(Number(t[1]));
      }
      cur = null;
    }
  }
}

const median = (xs) => {
  const s = xs.slice().sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : Math.round((s[m - 1] + s[m]) / 2);
};

const rows = Array.from(per, ([file, xs]) => [median(xs), file, xs.length])
  .sort((a, b) => b[0] - a[0]);
console.log('# median seconds per file over ' + logs.length
            + ' log(s) — regenerate: node e2e-baseline.js <gate logs> > e2e-baseline.tsv');
for (const [secs, file] of rows) console.log(secs + '\t' + file);
