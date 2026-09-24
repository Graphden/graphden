'use strict';

// editor-trace-view.js — the call-tree side panel. Pinned: while it loads,
// and when the load fails, the panel still carries a visible × (the server's
// header with its ✕ only arrives with the tree), and × closes it.
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-trace-view.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

function boot(reply) {
  const doc = createDocument();
  let release;
  const ctx = vm.createContext({
    console, Promise,
    document: doc,
    returnFocusTo() {},
    focusIntoDialog() {},
    ensurePopoverClose: (el, onClose) => {
      const b = doc.createElement('button');
      b.className = 'gd-pop-x';
      b.addEventListener('click', onClose);
      el.insertBefore(b, el.firstChild);
      return b;
    },
    authFetch: () => new Promise((r) => { release = () => r(reply()); }),
  });
  ctx.window = ctx;
  vm.runInContext(SRC, ctx);
  return { ctx, doc, release: () => release() };
}

(async () => {
  console.log(' loading and failed states carry a × that closes the panel');
  const t = boot(() => ({ ok: false, status: 500 }));
  const p = t.ctx.openTraceView('e1');
  const panel = t.doc.querySelector('.trace-view-panel');
  assert(!!panel?.querySelector('.gd-pop-x'), '× while loading');
  t.release();
  await p;
  await flush();
  assert(panel.textContent.includes('HTTP 500'), 'error shown, got "' + panel.textContent + '"');
  const x = panel.querySelector('.gd-pop-x');
  assert(!!x, '× on the error');
  x?.click();
  assert(!t.doc.querySelector('.trace-view-panel'), '× closes the panel');

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
