'use strict';

// editor-inspector.js renderInspTab / gdInspectorRender ↔ editor-execute.js
// gdMountRunPane — the Run pane's CodeMirror views (code-typed args) keep
// document observers alive until `gdCode.destroyWithin` tears them down. The
// inspector replaces its tab body (and, on a fn switch, the whole column)
// through innerHTML; if that happens BEFORE anyone destroys the views, the
// run pane's own destroyWithin(host) only ever sees the NEW, empty host and
// every view leaks. Pinned in the real call order, with both real modules:
// switching tab, switching fn and re-rendering the Runs tab destroy the
// outgoing views.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument, MiniElement } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');
const INSPECTOR = fs.readFileSync(path.join(EDITOR, 'editor-inspector.js'), 'utf8');
const EXECUTE = fs.readFileSync(path.join(EDITOR, 'editor-execute.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

// Just enough of an HTML parser for the inspector's own markup: nested
// elements with double-quoted attributes; text is dropped.
function parseInto(doc, parent, html) {
  const stack = [parent];
  const TAG = /<(\/?)([a-zA-Z][\w-]*)([^>]*)>/g;
  let m;
  while ((m = TAG.exec(html)) !== null) {
    if (m[1]) { stack.pop(); continue; }
    const el = doc.createElement(m[2]);
    for (const a of m[3].matchAll(/([\w-]+)="([^"]*)"/g)) {
      if (a[1] === 'class') el.className = a[2];
      else el.setAttribute(a[1], a[2]);
    }
    stack[stack.length - 1].appendChild(el);
    stack.push(el);
  }
}

(async () => {
  const doc = createDocument();
  Object.defineProperty(MiniElement.prototype, 'innerHTML', {
    configurable: true,
    get() { return ''; },
    set(v) { this.textContent = ''; parseInto(doc, this, String(v)); },
  });
  const inspector = doc.createElement('div');
  inspector.id = 'gd-inspector';
  doc.body.appendChild(inspector);

  // CodeMirror stand-in: a live view per enhanced textarea until destroyed.
  const live = new Set();
  const gdCode = {
    destroyWithin(root) {
      for (const ta of root.querySelectorAll('textarea[data-cm-enhanced]')) live.delete(ta);
    },
  };
  const hang = () => new Promise(() => {});
  const ctx = vm.createContext({
    console, Promise, JSON, Map, Set, String, Number, Array,
    document: doc,
    lookups: { fnMap: new Map([['A', { id: 'A', name: 'a' }], ['B', { id: 'B', name: 'b' }]]) },
    isAuthenticated: () => true,
    fetchValueForm: hang,
    authFetch: hang,
    fetch: hang,
    gdEscapeHtml: (s) => String(s),
    fnLabel: (fn) => fn.name,
    requestAnimationFrame() {},
    setTimeout() {}, clearTimeout() {},
  });
  ctx.window = ctx;
  ctx.gdCode = gdCode;
  vm.runInContext(EXECUTE, ctx);
  vm.runInContext(INSPECTOR, ctx);

  // Open A's Runs tab, then put a CodeMirror-enhanced arg field in its pane.
  const openRunsWithCodeField = () => {
    ctx.gdInspectorShowRuns('A');
    const pane = doc.querySelector('#gd-insp-run-host .gd-insp-run');
    assert(pane, 'the run pane mounted');
    const ta = doc.createElement('textarea');
    ta.setAttribute('data-cm-enhanced', '1');
    pane.appendChild(ta);
    live.add(ta);
    return ta;
  };

  console.log(' switching tab destroys the run pane\'s views');
  openRunsWithCodeField();
  doc.querySelector('.gd-insp-tab[data-insp-tab="overview"]').click();
  assert(live.size === 0, 'views left alive after a tab switch: ' + live.size);

  console.log(' re-rendering the Runs tab destroys the outgoing views');
  openRunsWithCodeField();
  ctx.gdInspectorShowRuns('A');
  assert(live.size === 0, 'views left alive after a Runs re-render: ' + live.size);

  console.log(' selecting another fn destroys the run pane\'s views');
  openRunsWithCodeField();
  ctx.gdInspectorRender('B');
  assert(live.size === 0, 'views left alive after a fn switch: ' + live.size);

  console.log(' clearing the selection destroys the run pane\'s views');
  openRunsWithCodeField();
  ctx.gdInspectorRender(null);
  assert(live.size === 0, 'views left alive after deselect: ' + live.size);

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
