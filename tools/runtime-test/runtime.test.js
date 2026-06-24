// Unit tests for graphden-runtime.js — pure-JS sandbox via
// node vm.
//
// The runtime is loaded into an isolated `vm.Context` with a
// minimal DOM mock (only the bits the runtime touches). No
// browser, no Playwright — runs in Node directly.
//
// Run:  node tools/runtime-test/runtime.test.js
// Exit: 0 on pass, 1 on first failure (prints the failing case).

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const runtimeSource = fs.readFileSync(
  path.join(__dirname, '..', '..', 'resources', 'packages', 'web',
            'runtime', 'graphden-runtime.js'),
  'utf8',
);

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
// Minimal DOM mock — just the surface the runtime touches:
// host.addEventListener / textContent / appendChild / innerHTML +
// closest / dataset / getAttribute on the buttons.
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
    addEventListener(ev, fn) {
      (this.listeners[ev] ||= []).push(fn);
    },
    getAttribute(k) { return this.attributes[k] ?? null; },
    setAttribute(k, v) { this.attributes[k] = v; },
    closest(sel) {
      // Tiny subset: `[data-action]` or `[data-fn-id]`.
      const m = sel.match(/^\[data-([\w-]+)(?:="([^"]+)")?\]$/);
      if (!m) return null;
      const [, attr, want] = m;
      const camel = attr.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
      let cur = this;
      while (cur) {
        if (cur.dataset?.[camel] !== undefined
            && (want === undefined || cur.dataset[camel] === want)) return cur;
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
  if (attrs['data-fn-id']) el.dataset.fnId = attrs['data-fn-id'];
  return el;
}

// =============================================================================
// Sandbox setup — fresh context per test for isolation.
// =============================================================================

function loadRuntime() {
  const ctx = {
    document: {
      createElement: (tag) => makeElement(tag),
    },
    fetch: undefined,
    console,
  };
  vm.createContext(ctx);
  vm.runInContext(runtimeSource, ctx);
  return ctx;
}

// =============================================================================
// TESTS
// =============================================================================

console.log('editor-runtime — sandbox tests');

test('registerActionHandler / getActionHandler round-trip', () => {
  const ctx = loadRuntime();
  const handler = () => {};
  ctx.registerActionHandler('my-action', handler);
  assert(ctx.getActionHandler('my-action') === handler,
         'registered handler retrievable by name');
});

test('registerActionHandler rejects empty action name', () => {
  const ctx = loadRuntime();
  let threw = false;
  try { ctx.registerActionHandler('', () => {}); }
  catch (e) { threw = true; }
  assert(threw, 'empty string rejected');
});

test('registerActionHandler rejects non-function fn', () => {
  const ctx = loadRuntime();
  let threw = false;
  try { ctx.registerActionHandler('x', 'not a fn'); }
  catch (e) { threw = true; }
  assert(threw, 'non-function fn rejected');
});

test('registerActionHandler last-write-wins on re-register', () => {
  const ctx = loadRuntime();
  ctx.registerActionHandler('x', () => 'first');
  ctx.registerActionHandler('x', () => 'second');
  assert(ctx.getActionHandler('x')() === 'second',
         'second registration overrides first');
});

test('clearActionHandlers wipes the registry', () => {
  const ctx = loadRuntime();
  ctx.registerActionHandler('y', () => {});
  ctx.clearActionHandlers();
  assert(ctx.getActionHandler('y') === undefined,
         'cleared');
});

test('bindActionDispatch routes click to registered handler', () => {
  const ctx = loadRuntime();
  let called = false;
  let receivedBtn = null;
  ctx.registerActionHandler('do-it', (btn) => {
    called = true; receivedBtn = btn;
  });
  const host = makeElement('div');
  const btn = makeElement('button', { 'data-action': 'do-it' });
  host.appendChild(btn);
  ctx.bindActionDispatch(host);
  // Simulate a click bubbling up: event.target = btn.
  const event = { target: btn, preventDefault() {}, stopPropagation() {} };
  host.listeners.click.forEach((fn) => fn(event));
  assert(called, 'handler invoked');
  assert(receivedBtn === btn, 'handler received the button as first arg');
});

test('bindActionDispatch skips when no action matches', () => {
  const ctx = loadRuntime();
  let called = false;
  ctx.registerActionHandler('exists', () => { called = true; });
  const host = makeElement('div');
  const btn = makeElement('button', { 'data-action': 'missing' });
  host.appendChild(btn);
  ctx.bindActionDispatch(host);
  host.listeners.click[0]({ target: btn, preventDefault() {}, stopPropagation() {} });
  assert(!called, 'missing-action click invokes nothing');
});

test('bindActionDispatch short-circuits aria-disabled buttons', () => {
  const ctx = loadRuntime();
  let handlerCalled = false;
  ctx.registerActionHandler('blocked', () => { handlerCalled = true; });
  const host = makeElement('div');
  const btn = makeElement('button', {
    'data-action': 'blocked',
    'aria-disabled': 'true',
  });
  btn.title = 'unavailable because reasons';
  host.appendChild(btn);
  ctx.bindActionDispatch(host);
  let prevented = false;
  const event = {
    target: btn,
    preventDefault() { prevented = true; },
    stopPropagation() {},
  };
  host.listeners.click[0](event);
  assert(!handlerCalled, 'handler NOT invoked on aria-disabled btn');
  assert(prevented, 'preventDefault called');
});

test('bindActionDispatch ignores clicks outside any data-action button', () => {
  const ctx = loadRuntime();
  let called = false;
  ctx.registerActionHandler('x', () => { called = true; });
  const host = makeElement('div');
  const other = makeElement('span');  // no data-action
  host.appendChild(other);
  ctx.bindActionDispatch(host);
  host.listeners.click[0]({ target: other, preventDefault() {}, stopPropagation() {} });
  assert(!called, 'non-action click invokes nothing');
});

test('loadPartial: 200 → swap innerHTML + onSwap + bindActionDispatch', async () => {
  const ctx = loadRuntime();
  ctx.fetch = async () => ({
    ok: true,
    text: async () => '<button data-action="run">go</button>',
  });
  const host = makeElement('div');
  // Track that onSwap runs.
  let onSwapHostArg = null;
  await ctx.loadPartial(host, '/x', { onSwap: (h) => { onSwapHostArg = h; } });
  assert(host.innerHTML.includes('data-action="run"'),
         'innerHTML received fetched content');
  assert(onSwapHostArg === host, 'onSwap called with the host');
  // bindActionDispatch was called → host now has a click listener.
  assert(host.listeners.click && host.listeners.click.length === 1,
         'click listener bound post-swap');
});

test('loadPartial: non-OK → error placeholder', async () => {
  const ctx = loadRuntime();
  ctx.fetch = async () => ({ ok: false, text: async () => 'oops' });
  const host = makeElement('div');
  await ctx.loadPartial(host, '/x');
  // The host.children should contain the error span.
  const errSpan = host.children.find((c) => c.className === 'partial-error');
  assert(errSpan, 'error placeholder appended on non-OK');
  assert(errSpan.textContent === 'Failed', 'failure label');
});

test('loadPartial: fetch throws → network placeholder', async () => {
  const ctx = loadRuntime();
  ctx.fetch = async () => { throw new Error('boom'); };
  const host = makeElement('div');
  await ctx.loadPartial(host, '/x');
  const errSpan = host.children.find((c) => c.className === 'partial-error');
  assert(errSpan, 'error placeholder on throw');
  assert(errSpan.textContent === 'Network', 'network label');
});

(async () => {
  // Wait for any pending awaits in the test fns above.
  await new Promise((r) => setTimeout(r, 50));
  console.log(`\nResults: ${passes} pass, ${failures} fail`);
  process.exit(failures > 0 ? 1 : 0);
})();
