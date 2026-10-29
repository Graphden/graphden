'use strict';

// Real shared adapter: retained controls, whole-batch fallback and retired
// callbacks. Layout/CSS hit rectangles are covered by the browser family.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');

function boot(narrow = true) {
  const document = createDocument();
  const listeners = new Map();
  const frames = new Map();
  const renders = [];
  const scopes = new Set();
  const geometries = [];
  let globalLayouts = 0;
  let nextFrame = 0;
  let selected;
  const output = text => new Map([['tree', text], ['title', text], ['styles', ['scoped']]]);
  const builtin = {run(role, {inputs}) {
    return role === 'initial' ? inputs.get('value') : output(inputs.get('label'));
  }};
  const ctx = vm.createContext({document, Map, Set, WeakMap, WeakSet, AbortController,
    console, queueMicrotask,
    requestAnimationFrame(fn) { frames.set(++nextFrame, fn); return nextFrame; },
    cancelAnimationFrame(id) { frames.delete(id); },
    addEventListener(name, fn) {
      if (!listeners.has(name)) listeners.set(name, []);
      listeners.get(name).push(fn);
    },
    GraphdenBrowser: {keyword: name => name, createRuntime: () => builtin},
    GraphdenRenderer: {
      vnode: tree => tree,
      mount(host) { return {
        render(tree) { renders.push(tree); host.textContent = tree; },
        dispose() { host.textContent = ''; },
      }; },
    },
    GraphdenStyles: {
      normalize: styles => styles,
      acquire() { const scope = {id: 'scope-' + scopes.size}; scopes.add(scope); return scope; },
      release(scope) { scopes.delete(scope); },
    },
    gdLoadUIComponentRuntime: async () => selected,
    gv: {ready: () => true}, graph: {nodes: new Map()},
    reflowFromMeasuredHeights() { globalLayouts++; return false; },
    updateOverlayPositions() {},
    narrow, geometries,
  });
  ctx.window = ctx;
  vm.runInContext(fs.readFileSync(path.join(__dirname,
    '../../resources/packages/app/editor/editor-passive-canvas.js'), 'utf8'), ctx);
  vm.runInContext(`component = gdCreatePassiveCanvas({identity: 'test', prefix: 'test',
    classes: new Set(), plan: () => ({}),
    projection: (_surface, facts) => new Map([['label', facts.label]]),
    failureText: () => 'unavailable',
    geometry: narrow ? owners => geometries.push(owners) : undefined,
  });`, ctx);
  function mount() {
    const owner = document.createElement('div');
    const control = document.createElement('button');
    const host = document.createElement('span');
    owner.appendChild(control);
    owner.appendChild(host);
    document.body.appendChild(owner);
    control.focus = () => { document.activeElement = control; };
    ctx.owner = owner; ctx.host = host;
    const body = vm.runInContext("component.mount(owner, host, 'label', {label: 'original'})", ctx);
    return {owner, host, control, body};
  }
  return {ctx, document, renders, scopes, geometries, mount, output,
    select: runtime => { selected = runtime; },
    event: name => { for (const fn of listeners.get(name) || []) fn(); },
    frame() { const current = [...frames.values()]; frames.clear(); current.forEach(fn => fn()); },
    globalLayouts: () => globalLayouts,
  };
}

(async () => {
  const t = boot();
  const {owner, host, control, body} = t.mount();
  control.focus();
  assert.equal(host.textContent, 'original');
  assert(body.update({label: 'updated'}));
  assert.equal(host.textContent, 'updated');
  assert.equal(owner.children[0], control);
  assert.equal(t.document.activeElement, control);
  t.frame();
  assert.equal(t.geometries.length, 1);
  assert.equal(t.geometries[0][0], owner);
  assert.equal(t.globalLayouts(), 0, 'narrow geometry cannot trigger whole-canvas layout');

  body.update({label: 'pending'});
  t.ctx.component.dispose(owner);
  const count = t.renders.length;
  body.refresh();
  assert.equal(body.update({label: 'retired'}), false);
  t.frame();
  assert.equal(t.renders.length, count);
  assert.equal(t.geometries.length, 1, 'dispose cancels queued geometry');
  assert.equal(t.scopes.size, 0);
  assert.equal(host.dataset.gdUiStyle, undefined);

  const nested = boot();
  const parent = nested.mount();
  const child = nested.mount();
  const outside = nested.mount();
  parent.owner.appendChild(child.owner);
  nested.ctx.component.disposeWithin(parent.owner);
  assert.equal(child.body.update({label: 'late nested'}), false);
  assert.equal(parent.body.update({label: 'late parent'}), false);
  assert.equal(child.host.dataset.gdUiStyle, undefined);
  assert.equal(nested.scopes.size, 1, 'another retained owner keeps its shared stylesheet');
  assert(outside.body.update({label: 'still mounted'}));
  outside.body.dispose();
  assert.equal(nested.scopes.size, 0, 'individual final-body disposal releases owner and shared styles');

  const auth = boot();
  const old = auth.mount();
  auth.event('gd-auth-changed');
  old.body.refresh();
  assert.equal(old.body.update({label: 'old private facts'}), false);
  assert.equal(old.host.textContent, '');
  assert.equal(auth.scopes.size, 0);

  const batch = boot();
  const calls = [];
  batch.select({run(role, {inputs}) {
    const value = inputs.get('value');
    calls.push(value);
    if (role === 'initial' && value === 3) throw new Error('late refusal');
    return role === 'initial' ? value * 10 : batch.output('personal');
  }});
  await batch.ctx.component.reload();
  const staged = batch.ctx.component.initialBatch(run => [1, 2, 3].map(value =>
    run(new Map([['value', value]]), result => { assert(Number.isInteger(result)); return result; })));
  assert.deepEqual(staged, [1, 2, 3], 'fallback reruns all original facts, discarding earlier personal outputs');
  assert.deepEqual(calls, [1, 2, 3]);
  const revision = batch.ctx.component.revision();
  batch.ctx.component.invalidate();
  assert(batch.ctx.component.revision() > revision, 'cache revision retires synchronously before microtasks');

  const oldPersonal = boot();
  oldPersonal.select({hasEntry: entry => entry !== 'initial', run: () => oldPersonal.output('personal')});
  await oldPersonal.ctx.component.reload();
  const personalView = oldPersonal.mount();
  assert.equal(personalView.host.textContent, 'personal');
  const builtinPolicy = oldPersonal.ctx.component.initialBatch(run => run(new Map([['value', 7]]), value => value));
  assert.equal(builtinPolicy, 7, 'missing personal initial uses builtin policy');
  personalView.body.refresh();
  assert.equal(personalView.host.textContent, 'personal', 'policy fallback keeps the personal view selected');

  const late = boot();
  let completeLoad;
  late.select(new Promise(resolve => { completeLoad = resolve; }));
  const loading = late.ctx.component.reload();
  late.event('gd-auth-changed');
  completeLoad({run: () => late.output('old account')});
  await loading;
  assert.equal(late.mount().host.textContent, 'original', 'late aborted load cannot select an old-account runtime');

  const legacy = boot(false);
  const retained = legacy.mount();
  retained.body.update({label: 'legacy'});
  legacy.frame();
  assert.equal(legacy.globalLayouts(), 1, 'existing default geometry remains intact');

  const detached = boot();
  const removed = detached.mount();
  removed.body.update({label: 'pending'});
  removed.owner.remove();
  detached.frame();
  assert.equal(detached.geometries.length, 0, 'queued geometry ignores disconnected owners');
  console.log('PASS passive canvas: retained owner, retirement, auth, atomic fallback and local geometry');
})().catch(error => { console.error(error); process.exitCode = 1; });
