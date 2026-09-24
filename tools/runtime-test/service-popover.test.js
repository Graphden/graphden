'use strict';

// editor-service-popover.js — the ⚙ Service settings dialog. Pinned:
//   * every open re-fetches the partial: its body carries live status and
//     the "Running copies" heartbeats, which a per-fn HTML cache froze at the
//     first open for the rest of the session;
//   * focus moves INTO the dialog on open (it traps Tab), on the form and on
//     the load-error body alike;
//   * a load error still gets a visible ×, and × hands focus back to ⚙.
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument, MiniElement } = require('./mini-dom');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-service-popover.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

function boot() {
  const doc = createDocument();
  const seen = { fetches: 0, focusedInto: 0, returned: [], xButtons: 0 };
  let reply = () => ({ ok: true, status: 200, text: async () => 'FORM heartbeat-' + seen.fetches });
  const ctx = vm.createContext({
    console, Promise, URLSearchParams,
    document: doc,
    API: {},
    installPopoverDismiss() {},
    anchorBelowClamped() {},
    gdEscapeHtml: (s) => s,
    focusIntoDialog: () => { seen.focusedInto += 1; return true; },
    returnFocusTo: (el) => { seen.returned.push(el); return true; },
    ensurePopoverClose: (el, onClose) => {
      seen.xButtons += 1;
      const b = doc.createElement('button');
      b.className = 'gd-pop-x';
      b.addEventListener('click', () => onClose());
      el.insertBefore(b, el.firstChild);
      return b;
    },
    authFetch: async (url) => {
      if (url.startsWith('/partials/service-popover')) { seen.fetches += 1; return reply(); }
      return { ok: false, status: 404 };
    },
  });
  ctx.window = ctx;
  // mini-dom does not parse HTML: the partial's body is a marker string, and
  // `FORM …` becomes a header × plus a text node carrying the rest.
  Object.defineProperty(MiniElement.prototype, 'innerHTML', {
    configurable: true,
    get() { return this.textContent; },
    set(v) {
      this.textContent = '';
      if (String(v).startsWith('FORM')) {
        const x = doc.createElement('button');
        x.className = 'service-popover-close';
        this.appendChild(x);
        this.appendChild(doc.createTextNode(String(v)));
      }
    },
  });
  vm.runInContext(SRC, ctx);
  const anchor = doc.createElement('button');
  doc.body.appendChild(anchor);
  return { ctx, doc, seen, anchor, setReply: (f) => { reply = f; } };
}

const popover = (doc) => doc.querySelector('.service-popover');

(async () => {
  console.log(' every open re-fetches the live body; focus enters each time');
  {
    const t = boot();
    const fn = { id: 'f1', name: 'svc' };
    await t.ctx.showServicePopover(fn, t.anchor);
    assert(popover(t.doc).textContent.includes('heartbeat-1'), 'first body shown');
    t.ctx.hideServicePopover();
    await t.ctx.showServicePopover(fn, t.anchor);
    assert(t.seen.fetches === 2, 'fetched on each open, got ' + t.seen.fetches);
    assert(popover(t.doc).textContent.includes('heartbeat-2'), 'second open shows the fresh heartbeat');
    assert(t.seen.focusedInto === 2, 'focus moved into the dialog on both opens, got ' + t.seen.focusedInto);
  }

  console.log(' × on the form returns focus to the ⚙ trigger');
  {
    const t = boot();
    await t.ctx.showServicePopover({ id: 'f1', name: 'svc' }, t.anchor);
    popover(t.doc).querySelector('.service-popover-close').click();
    assert(!popover(t.doc).classList.contains('visible'), 'closed');
    assert(t.seen.returned[0] === t.anchor, 'focus handed back to the anchor');
  }

  console.log(' a load error still has a visible × and takes focus');
  {
    const t = boot();
    t.setReply(() => ({ ok: false, status: 500, text: async () => '' }));
    await t.ctx.showServicePopover({ id: 'f1', name: 'svc' }, t.anchor);
    const el = popover(t.doc);
    assert(el.textContent.includes('HTTP 500'), 'error shown');
    const x = el.querySelector('.gd-pop-x');
    assert(!!x, 'error body carries a ×');
    assert(t.seen.focusedInto === 1, 'focus moved into the error dialog');
    x?.click();
    assert(!el.classList.contains('visible') && t.seen.returned[0] === t.anchor, '× closes and returns focus');
  }

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
