'use strict';

// editor-edit-modes.js — the inline edit popover skeleton
// (`openInlineEditPopover` / `closeInlineEdit`). Pinned:
//   * a `doSave` that THROWS (network drop) is a failed save: Save and Cancel
//     come back and the error says so — they used to stay disabled, silent;
//   * closing the popover destroys the CodeMirror views inside it (a
//     code-typed value form), which otherwise keep document observers alive;
//   * the value form's "{} raw" toggle does the same when it swaps the raw
//     textarea back out (editor-value-form.js).
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const ROOT = path.join(__dirname, '..', '..', 'resources', 'packages');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };

function boot(files) {
  const doc = createDocument();
  const destroyed = [];
  const ctx = vm.createContext({
    console, Promise,
    document: doc,
    Event: class { constructor(type, o) { this.type = type; Object.assign(this, o || {}); } },
    installTabTrap() {},
    installPopoverDismiss() {},
    returnFocusTo() {},
    focusIntoDialog() {},
    pointerEventInTour: () => false,
    setTimeout: () => 0,
    innerWidth: 1200,
  });
  ctx.window = ctx;
  ctx.gdCode = {
    enhanceWithin(root) {
      for (const ta of root.querySelectorAll('textarea')) ta.dataset.cmEnhanced = '1';
    },
    destroyWithin(root) { destroyed.push(root); },
    viewOf: () => null,
  };
  for (const f of files) {
    vm.runInContext(fs.readFileSync(path.join(ROOT, ...f), 'utf8'), ctx, { filename: f[f.length - 1] });
  }
  return { ctx, doc, destroyed };
}

(async () => {
  const EDIT = [['app', 'editor', 'editor-edit-modes.js']];

  console.log(' a throwing doSave re-enables Save / Cancel and says why');
  {
    const t = boot(EDIT);
    const anchor = t.doc.createElement('button');
    anchor.getBoundingClientRect = () => ({ bottom: 10, left: 10 });
    t.ctx.openInlineEditPopover({
      anchorEl: anchor,
      makeControl: (root) => root.appendChild(t.doc.createElement('input')),
      doSave: async () => { throw new Error('Failed to fetch'); },
    });
    const pop = t.doc.querySelector('.arg-value-edit-popover');
    const [cancel, save] = pop.querySelectorAll('.arg-value-edit-btn');
    save.click();
    await flush();
    assert(save.disabled === false && cancel.disabled === false, 'buttons re-enabled');
    const err = pop.querySelector('.arg-value-edit-error');
    assert(err.classList.contains('visible') && err.textContent.includes('Failed to fetch'),
      'error visible, got "' + err.textContent + '"');
  }

  console.log(' closing the popover destroys its CodeMirror views');
  {
    const t = boot(EDIT);
    const anchor = t.doc.createElement('button');
    anchor.getBoundingClientRect = () => ({ bottom: 10, left: 10 });
    t.ctx.openInlineEditPopover({
      anchorEl: anchor,
      makeControl: (root) => root.appendChild(t.doc.createElement('textarea')),
      doSave: async () => true,
    });
    const pop = t.doc.querySelector('.arg-value-edit-popover');
    t.ctx.closeInlineEdit();
    assert(t.destroyed.includes(pop), 'destroyWithin(popover) before removal');
    assert(!pop.isConnected, 'popover removed');
  }

  console.log(' raw toggle back to the form destroys the raw textarea\'s view');
  {
    const t = boot([['web', 'runtime', 'graphden-edn.js'], ['web', 'runtime', 'graphden-forms.js'],
                    ['app', 'editor', 'editor-value-form.js']]);
    const host = t.doc.createElement('div');
    const root = t.doc.createElement('div');
    root.setAttribute('data-form-root', '');
    root.setAttribute('data-field-kind', 'record');
    const field = t.doc.createElement('input');
    field.setAttribute('data-form-field', '');
    field.setAttribute('data-field-kind', 'int');
    field.setAttribute('data-path', '["n"]');
    field.value = '';
    root.appendChild(field);
    host.appendChild(root);
    t.doc.body.appendChild(host);
    t.ctx.installRawToggle(host, null);
    const btn = host.querySelector('.value-form-raw-toggle');
    btn.click();   // → raw
    assert(t.destroyed.length === 0, 'nothing destroyed entering raw mode');
    btn.click();   // → back to the typed form
    assert(t.destroyed.includes(root), 'destroyWithin(root) before the raw textarea is dropped');
  }

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
