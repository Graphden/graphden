// Sandbox tests for graphden-actions-builtin.js — exercises
// the `navigate`, `submit-form`, and `custom` built-in action
// handlers in a Node vm context with a minimal DOM + fetch mock.
//
// Run:  node tools/runtime-test/actions-builtin.test.js
// Exit: 0 on pass, 1 on first failure.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const runtimeDir = path.join(__dirname, '..', '..',
                             'resources', 'packages', 'web', 'runtime');
const runtimeSrc = fs.readFileSync(
  path.join(runtimeDir, 'graphden-runtime.js'), 'utf8');
const actionsSrc = fs.readFileSync(
  path.join(runtimeDir, 'graphden-actions-builtin.js'), 'utf8');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes++; return; }
  failures++;
  console.error('  ✗ ' + msg);
}

function test(name, fn) {
  console.log(' ' + name);
  try { fn(); }
  catch (e) { failures++; console.error('  ✗ threw: ' + e.message); }
}

// =============================================================================
// DOM mock — extended to cover the FormData / closest('form') /
// querySelector surface the handlers touch.
// =============================================================================

function makeElement(tag, attrs) {
  attrs = attrs || {};
  const el = {
    tagName: tag.toUpperCase(),
    children: [],
    listeners: {},
    attributes: { ...attrs },
    dataset: {},
    style: {},
    className: '',
    textContent: '',
    innerHTML: '',
    title: '',
    parentNode: null,
    addEventListener(ev, fn) { (this.listeners[ev] ||= []).push(fn); },
    getAttribute(k) { return this.attributes[k] ?? null; },
    setAttribute(k, v) { this.attributes[k] = v; },
    closest(sel) {
      let cur = this;
      while (cur) {
        if (sel === 'form' && cur.tagName === 'FORM') return cur;
        const m = sel.match(/^\[data-([\w-]+)(?:="([^"]+)")?\]$/);
        if (m) {
          const [, attr, want] = m;
          const camel = attr.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
          if (cur.dataset?.[camel] !== undefined
              && (want === undefined || cur.dataset[camel] === want)) return cur;
        }
        cur = cur.parentNode;
      }
      return null;
    },
    appendChild(child) {
      child.parentNode = this;
      this.children.push(child);
      return child;
    },
    contains(node) {
      let cur = node;
      while (cur) { if (cur === this) return true; cur = cur.parentNode; }
      return false;
    },
    querySelector() { return null; },
  };
  if (attrs['data-action']) el.dataset.action = attrs['data-action'];
  if (attrs['data-href']) el.dataset.href = attrs['data-href'];
  if (attrs['data-target']) el.dataset.target = attrs['data-target'];
  if (attrs['data-custom-handler']) el.dataset.customHandler = attrs['data-custom-handler'];
  return el;
}

function loadActions(overrides) {
  overrides = overrides || {};
  const locationStub = { href: 'about:blank', pathname: '/' };
  const ctx = {
    document: {
      createElement: (tag) => makeElement(tag),
      querySelector: overrides.querySelector || (() => null),
    },
    window: { location: locationStub },
    location: locationStub,
    // FormData stub: minimal iterable yielding 0 entries — the
    // handler we test only does `for (const [k, v] of formData)`
    // to copy fields into URLSearchParams; empty iteration is
    // enough to assert "POSTed to the right URL with the right
    // method". Real field-roundtrip lives in browser e2e.
    FormData: function FormData() {
      this[Symbol.iterator] = function* () {};
    },
    URLSearchParams,
    Symbol,
    fetch: overrides.fetch || (() => { throw new Error('no fetch'); }),
    console,
  };
  vm.createContext(ctx);
  vm.runInContext(runtimeSrc, ctx);
  vm.runInContext(actionsSrc, ctx);
  return ctx;
}

// =============================================================================
// navigate — sets window.location.href when data-href present
// =============================================================================

console.log('editor-actions-builtin — sandbox tests');

test('navigate sets window.location.href from data-href', () => {
  const ctx = loadActions();
  const btn = makeElement('button', { 'data-action': 'navigate',
                                       'data-href': '/about' });
  let prevented = false;
  ctx.getActionHandler('navigate')(btn, { preventDefault() { prevented = true; } });
  assert(ctx.window.location.href === '/about', 'href updated');
  assert(prevented, 'preventDefault called');
});

test('navigate no-op when data-href missing', () => {
  const ctx = loadActions();
  const btn = makeElement('button', { 'data-action': 'navigate' });
  ctx.window.location.href = 'about:blank';
  ctx.getActionHandler('navigate')(btn, { preventDefault() {} });
  assert(ctx.window.location.href === 'about:blank',
         'no nav without href');
});

// =============================================================================
// submit-form — POSTs to form action and swaps response
// =============================================================================

(async () => {
  // Happy path with explicit target selector.
  console.log(' submit-form happy path (target selector)');
  let fetchedUrl = null;
  let fetchedMethod = null;
  const ctx = loadActions({
    fetch: async (url, opts) => {
      fetchedUrl = url; fetchedMethod = opts.method;
      return { ok: true, status: 200, text: async () => '<p>Thanks!</p>' };
    },
  });
  const form = makeElement('form');
  form.attributes.action = '/contact';
  form.attributes.method = 'POST';
  const btn = makeElement('button',
                          { 'data-action': 'submit-form',
                            'data-target': '#contact-result' });
  form.appendChild(btn);
  const target = makeElement('div', { id: 'contact-result' });
  ctx.document.querySelector = (sel) => sel === '#contact-result' ? target : null;
  await ctx.getActionHandler('submit-form')(btn,
    { preventDefault() {}, stopPropagation() {} });
  assert(fetchedUrl === '/contact', `fetched ${fetchedUrl} — expected /contact`);
  assert(fetchedMethod === 'POST', 'POST method');
  assert(target.innerHTML.includes('Thanks!'), 'response swapped into target');
})();

(async () => {
  // No target selector → response replaces the form's innerHTML.
  console.log(' submit-form swaps into form when no target');
  const ctx = loadActions({
    fetch: async () => ({ ok: true, text: async () => '<p>ok</p>' }),
  });
  const form = makeElement('form');
  form.attributes.action = '/x';
  const btn = makeElement('button', { 'data-action': 'submit-form' });
  form.appendChild(btn);
  await ctx.getActionHandler('submit-form')(btn,
    { preventDefault() {}, stopPropagation() {} });
  assert(form.innerHTML.includes('ok'),
         'response swapped back into the form when no data-target');
})();

(async () => {
  // No <form> ancestor → no fetch, silent no-op.
  console.log(' submit-form no-op when not inside a form');
  let fetched = false;
  const ctx = loadActions({
    fetch: async () => { fetched = true; return { ok: true, text: async () => '' }; },
  });
  const btn = makeElement('button', { 'data-action': 'submit-form' });
  await ctx.getActionHandler('submit-form')(btn,
    { preventDefault() {}, stopPropagation() {} });
  assert(!fetched, 'no fetch fired without a form ancestor');
})();

(async () => {
  // Non-OK response → the server's rendered error body is SWAPPED in
  // (audit-7: silently-inert buttons hid every validation rejection
  // from user pages); a blank body gets a minimal inline notice.
  console.log(' submit-form non-OK response surfaces the error');
  const ctx = loadActions({
    fetch: async () => ({ ok: false, status: 422,
                          text: async () => '<p class="error">Name taken.</p>' }),
  });
  const form = makeElement('form');
  form.attributes.action = '/x';
  const btn = makeElement('button', { 'data-action': 'submit-form' });
  form.appendChild(btn);
  await ctx.getActionHandler('submit-form')(btn,
    { preventDefault() {}, stopPropagation() {} });
  assert(form.innerHTML.includes('Name taken.'),
         'error body swapped into the form on non-OK');

  const ctx2 = loadActions({
    fetch: async () => ({ ok: false, status: 500, text: async () => '' }),
  });
  const form2 = makeElement('form');
  form2.attributes.action = '/x';
  const btn2 = makeElement('button', { 'data-action': 'submit-form' });
  form2.appendChild(btn2);
  await ctx2.getActionHandler('submit-form')(btn2,
    { preventDefault() {}, stopPropagation() {} });
  assert(form2.innerHTML.includes('HTTP 500'),
         'blank error body gets a minimal inline notice');
})();

// =============================================================================
// custom — escape hatch: data-custom-handler body evaluated as JS
// =============================================================================

test('custom handler runs the body with btn / event / host in scope', () => {
  const ctx = loadActions();
  // Body sets a property on btn we can observe.
  const btn = makeElement('button', {
    'data-action': 'custom',
    'data-custom-handler': "btn.title = 'clicked: ' + event.detail;",
  });
  const host = makeElement('div');
  host.appendChild(btn);
  ctx.getActionHandler('custom')(btn, { detail: 'ok' }, host);
  assert(btn.title === 'clicked: ok',
         `body executed with btn + event in scope (title="${btn.title}")`);
});

test('custom handler: missing body is a silent no-op', () => {
  const ctx = loadActions();
  const btn = makeElement('button', { 'data-action': 'custom' });
  // No data-custom-handler → handler must not throw.
  let threw = false;
  try { ctx.getActionHandler('custom')(btn, {}, btn); }
  catch (_) { threw = true; }
  assert(!threw, 'missing handler body is silently ignored');
});

test('custom handler: parse error is caught, no throw', () => {
  const ctx = loadActions();
  const btn = makeElement('button', {
    'data-action': 'custom',
    'data-custom-handler': '%%not valid JS%%',
  });
  let threw = false;
  try { ctx.getActionHandler('custom')(btn, {}, btn); }
  catch (_) { threw = true; }
  assert(!threw, 'parse error is caught + logged, click is a no-op');
});

test('custom handler: runtime error is caught, no throw', () => {
  const ctx = loadActions();
  const btn = makeElement('button', {
    'data-action': 'custom',
    'data-custom-handler': "throw new Error('boom');",
  });
  let threw = false;
  try { ctx.getActionHandler('custom')(btn, {}, btn); }
  catch (_) { threw = true; }
  assert(!threw, 'runtime error caught + logged, click is a no-op');
});


setTimeout(() => {
  console.log(`\nResults: ${passes} pass, ${failures} fail`);
  process.exit(failures > 0 ? 1 : 0);
}, 100);
