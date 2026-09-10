#!/usr/bin/env node
// Per-module JS line coverage of the editor frontend from the e2e suite.
//
//   GRAPHDEN_JS_COVERAGE=/tmp/jscov ./run-edit-tests.sh      # dumps
//   node js-coverage-report.js /tmp/jscov [--json out.json]  # report
//
// The editor ships as ONE concatenated bundle (`:editor-js-asset` —
// modules joined with "\n\n", the `__BUILD_HASH__` placeholder in
// editor-state.js substituted), so a dump's V8 ranges are offsets into
// that bundle. Each module is located in the bundle text by its own
// source (piecewise around the placeholder), the block-coverage ranges
// are flattened innermost-wins into a per-byte count, and a LINE is
// covered when any non-blank byte on it ran in any dump. Comment-only
// and blank lines are excluded from the denominator — the same
// vocabulary as cloverage's line %, so the two tables read alike.
// Nothing here is a gate: it is the snapshot docs/TESTS.md § Frontend
// records, re-taken by hand when the suite or the editor moves.
'use strict';
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../resources/packages');
const MODULE_DIRS = ['app/editor', 'web/runtime'];
const PLACEHOLDER = '__BUILD_HASH__';

function modules() {
  const out = [];
  for (const d of MODULE_DIRS) {
    const dir = path.join(root, d);
    for (const f of fs.readdirSync(dir).sort()) {
      if (!f.endsWith('.js')) continue;
      out.push({ name: `${d}/${f}`, text: fs.readFileSync(path.join(dir, f), 'utf8') });
    }
  }
  return out;
}

// Locate `text` inside `source`; the placeholder may have been replaced
// by a hash of a different length, so match the pieces around it in order.
function locate(source, text) {
  const pieces = text.split(PLACEHOLDER);
  let from = 0;
  let start = -1;
  let end = -1;
  for (const piece of pieces) {
    if (!piece) continue;
    const idx = source.indexOf(piece, from);
    if (idx < 0) return null;
    if (start < 0) start = idx;
    end = idx + piece.length;
    from = end;
  }
  return start < 0 ? null : { start, end };
}

// Innermost-wins flattening of V8 block ranges into per-byte counts.
function byteCounts(entry) {
  const len = entry.source.length;
  const counts = new Int32Array(len).fill(-1);
  const ranges = [];
  for (const fn of entry.functions) for (const r of fn.ranges) ranges.push(r);
  // Outer ranges first (longer), inner later overwrite.
  ranges.sort((a, b) => (a.startOffset - b.startOffset) || ((b.endOffset - b.startOffset) - (a.endOffset - a.startOffset)));
  for (const r of ranges) counts.fill(r.count, r.startOffset, Math.min(r.endOffset, len));
  return counts;
}

function lineStats(text, covered /* Uint8Array over text */) {
  let total = 0;
  let hit = 0;
  let pos = 0;
  let inBlock = false;
  for (const line of text.split('\n')) {
    const len = line.length;
    const trimmed = line.trim();
    let code = trimmed.length > 0;
    if (inBlock) { code = false; if (trimmed.includes('*/')) inBlock = false; }
    else if (trimmed.startsWith('//')) code = false;
    else if (trimmed.startsWith('/*')) { code = false; if (!trimmed.includes('*/')) inBlock = true; }
    if (code) {
      total += 1;
      let any = false;
      for (let i = 0; i < len; i += 1) {
        if (line[i] !== ' ' && line[i] !== '\t' && covered[pos + i]) { any = true; break; }
      }
      if (any) hit += 1;
    }
    pos += len + 1;
  }
  return { total, hit };
}

function main() {
  const [dir, ...rest] = process.argv.slice(2);
  if (!dir) { console.error('usage: js-coverage-report.js <dump-dir> [--json out]'); process.exit(2); }
  const jsonOut = rest[0] === '--json' ? rest[1] : null;
  const mods = modules();
  const covered = new Map(mods.map((m) => [m.name, new Uint8Array(m.text.length)]));
  const sources = new Map();   // hash → bundle text
  for (const f of fs.readdirSync(dir)) {
    if (f.startsWith('src-') && f.endsWith('.js')) {
      sources.set(f.slice(4, -3), fs.readFileSync(path.join(dir, f), 'utf8'));
    }
  }
  // Where each module sits inside each bundle: computed once per bundle.
  const layout = new Map();
  for (const [hash, src] of sources) {
    const locs = [];
    for (const m of mods) {
      const loc = locate(src, m.text);
      if (loc) locs.push({ name: m.name, ...loc, len: m.text.length });
    }
    layout.set(hash, locs);
  }
  let dumps = 0;
  let matched = 0;
  for (const f of fs.readdirSync(dir)) {
    if (!f.startsWith('ranges-') || !f.endsWith('.json')) continue;
    dumps += 1;
    for (const entry of JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'))) {
      const src = sources.get(entry.source);
      const locs = layout.get(entry.source);
      if (!src || !locs || !locs.length) continue;
      const counts = byteCounts({ source: src, functions: entry.functions });
      matched += 1;
      for (const loc of locs) {
        const arr = covered.get(loc.name);
        const n = Math.min(loc.len, loc.end - loc.start);
        for (let i = 0; i < n; i += 1) if (counts[loc.start + i] > 0) arr[i] = 1;
      }
    }
  }
  const rows = mods.map((m) => ({ module: m.name, ...lineStats(m.text, covered.get(m.name)) }))
    .map((r) => ({ ...r, pct: r.total ? (100 * r.hit) / r.total : 0 }))
    .sort((a, b) => a.pct - b.pct);
  const tot = rows.reduce((acc, r) => ({ total: acc.total + r.total, hit: acc.hit + r.hit }), { total: 0, hit: 0 });
  console.log(`dumps: ${dumps}, bundles: ${sources.size}, bundle entries matched: ${matched}\n`);
  console.log('| Module | Lines | Covered | % |');
  console.log('|---|---:|---:|---:|');
  for (const r of rows) console.log(`| ${r.module} | ${r.total} | ${r.hit} | ${r.pct.toFixed(1)} |`);
  console.log(`| **all** | ${tot.total} | ${tot.hit} | ${(tot.total ? (100 * tot.hit) / tot.total : 0).toFixed(1)} |`);
  if (jsonOut) fs.writeFileSync(jsonOut, JSON.stringify({ dumps, rows, total: tot }, null, 2));
}

main();
