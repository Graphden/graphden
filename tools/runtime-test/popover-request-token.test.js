'use strict';

// editor-mismatch-explainer.js / editor-provenance-popover.js — a slow
// partial must not open its popover after the reader pressed Escape, closed
// it, or opened another one meanwhile (the response used to win whenever it
// arrived). Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument, MiniElement } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

Object.defineProperty(MiniElement.prototype, 'innerHTML', {
  configurable: true,
  get() { return this._html || ''; },
  set(v) {
    this._html = v;
    this.textContent = '';
    // the provenance probe looks for a resolution tier
    if (/tier/.test(v)) {
      const t = new MiniElement('div');
      t.className = 'type-inline-resolution-tier';
      this.appendChild(t);
    }
  },
});

function boot() {
  const doc = createDocument();
  const pending = [];
  const ctx = vm.createContext({
    console, Promise, URLSearchParams,
    document: doc,
    installPopoverDismiss() {},
    anchorBelowClamped() {},
    authFetch: () => new Promise((r) => pending.push(r)),
  });
  ctx.window = ctx;
  for (const f of ['editor-mismatch-explainer.js', 'editor-provenance-popover.js']) {
    vm.runInContext(fs.readFileSync(path.join(EDITOR, f), 'utf8'), ctx, { filename: f });
  }
  const answer = (i, html) => pending[i]({ ok: true, text: async () => html });
  const escape = () => doc.dispatch('keydown', { key: 'Escape' });
  return { ctx, doc, pending, answer, escape };
}

const visible = (doc, cls) => !!doc.querySelector('.' + cls)?.classList.contains('visible');

(async () => {
  const arg = { 'binding-id': 'b1' };
  for (const [fn, cls, html] of [['showMismatchExplainer', 'mismatch-explainer', '<p>why</p>'],
                                 ['showProvenancePopover', 'provenance-popover', '<p>tier</p>']]) {
    console.log(' ' + fn + ': Escape during the fetch → the answer is dropped');
    {
      const t = boot();
      const anchor = t.doc.createElement('button');
      const p = t.ctx[fn](arg, anchor);
      await flush();
      t.escape();
      t.answer(0, html);
      await p;
      assert(!visible(t.doc, cls), 'stays closed after Escape');
    }
    console.log(' ' + fn + ': an older answer landing last is dropped, the newer one shows');
    {
      const t = boot();
      const anchor = t.doc.createElement('button');
      const p1 = t.ctx[fn](arg, anchor);
      const p2 = t.ctx[fn]({ 'binding-id': 'b2' }, anchor);
      await flush();
      t.answer(1, html);
      await p2;
      assert(visible(t.doc, cls), 'the newer one opens');
      const el = t.doc.querySelector('.' + cls);
      const before = el._html;
      t.answer(0, html.replace('<p>', '<p>OLD '));
      await p1;
      assert(el._html === before, 'the older answer does not replace it');
    }
  }

  for (const [fn, cls] of [['showMismatchExplainer', 'mismatch-explainer'],
                           ['showProvenancePopover', 'provenance-popover']]) {
    console.log(' ' + fn + ': repeat clicks on the same badge join the request in flight');
    const t = boot();
    const anchor = t.doc.createElement('button');
    const p1 = t.ctx[fn](arg, anchor);
    const p2 = t.ctx[fn](arg, anchor);
    await flush();
    assert(t.pending.length === 1, 'one request, got ' + t.pending.length);
    t.answer(0, '<p>tier</p>');
    await p1; await p2;
    assert(visible(t.doc, cls), 'the answer opens the popover');
  }

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
