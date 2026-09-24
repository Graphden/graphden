'use strict';

// editor-sidebar.js — the Explorer's server search. Pinned:
//   * a failed query says so instead of "Searching…" forever;
//   * after a write the Explorer repaints (`repaintExplorer` →
//     `requerySearch`), an active search is asked again — a renamed or
//     deleted row used to linger from the pre-write answer.
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-sidebar.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

function boot(searchFns) {
  const doc = createDocument();
  const list = doc.createElement('div');
  list.id = 'entity-list';
  doc.body.appendChild(list);
  const timers = [];
  const ctx = vm.createContext({
    console, Promise,
    document: doc,
    graphData: { namespaces: [], fns: [] },
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
    clearTimeout() {},
    searchFns,
    syncKindFilterBar() {}, primeServiceCacheOnce() {}, primeAppsCacheOnce() {},
    primeSecretsOnce() {}, primeTestStatusesOnce() {}, primeProblemsOnce() {},
  });
  ctx.window = ctx;
  // innerHTML as plain text: the render's transient states are strings.
  Object.defineProperty(list, 'innerHTML', { get() { return this._html || ''; }, set(v) { this._html = v; } });
  vm.runInContext(SRC, ctx);
  return { ctx, list, timers };
}

(async () => {
  console.log(' a failed search is shown as failed');
  {
    const t = boot(() => Promise.reject(new Error('HTTP 502')));
    const origErr = console.error;
    console.error = () => {};
    t.ctx.onSearchInput('adder');
    t.timers.shift()();
    await flush();
    console.error = origErr;
    assert(/Search failed/.test(t.list.innerHTML), 'got "' + t.list.innerHTML + '"');
  }

  console.log(' requerySearch asks the server again for the active query');
  {
    const queries = [];
    const t = boot((q) => { queries.push(q); return new Promise(() => {}); });
    t.ctx.onSearchInput('adder');
    t.timers.shift()();
    await flush();
    t.ctx.requerySearch();
    assert(queries.join() === 'adder,adder', 'asked twice, got ' + queries.join());
    const idle = boot((q) => { queries.push(q); return new Promise(() => {}); });
    idle.ctx.requerySearch();
    assert(queries.length === 2, 'no query without an active search');
  }

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
