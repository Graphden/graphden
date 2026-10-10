'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const fields = value => new Map(Object.entries(value).map(([key, item]) => [graph.keyword(key), item]));
const field = (value, key) => value.get(graph.keyword(key));
const ctx = vm.createContext({Map, Promise, AbortController, console});
ctx.window = ctx;
ctx.GraphdenBrowser = graph;
ctx.GraphdenRenderer = {vnode(tree) { if (tree === 'bad') throw new Error('bad markup'); }};
ctx.GraphdenStyles = {normalize(styles) { if (styles === 'bad') throw new Error('bad styles'); }};
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/ui-preview/graph-component.js'), 'utf8'), ctx);
function fixture() {
  const paints = [];
  const effects = [];
  const pending = [];
  let component;
  let update = (_state, event) => fields({state: event.state, requests: event.requests || []});
  const runtime = {run(entry, supplied) {
    if (entry === 'initial') return 0;
    if (entry === 'view') return fields({tree: supplied.state === 9 ? 'bad' : 'good', styles: []});
    return update(supplied.state, supplied.event);
  }};
  component = ctx.GraphdenComponent.create(runtime, {
    inputs: () => null,
    validateState(state) { if (!Number.isSafeInteger(state) || state < 0) throw new Error('bad state'); },
    validateView(view) { if (!(view instanceof Map)) throw new Error('bad view'); },
    render: view => paints.push(field(view, 'tree')),
    requests: {
      write: {validate(request) {
        const value = field(request, 'value');
        if (!Number.isSafeInteger(value)) throw new Error('bad request');
        return value;
      }, execute(value) { effects.push(value); if (value === 99) throw new Error('write failed'); if (value === 3) component.dispatch({state: 4}); }},
      later: {validate: () => null, execute(_parameters, {signal}) {
        return new Promise(resolve => pending.push({resolve, signal}));
      }},
    },
  });
  return {component, paints, effects, pending, runtime, update: value => { update = value; }};
}
(async () => {
  const f = fixture();
  for (const event of [
    {state: -1, requests: [fields({kind: 'write', value: 1})]},
    {state: 9, requests: [fields({kind: 'write', value: 1})]},
    {state: 1, requests: [fields({kind: 'write', value: 1}), fields({kind: 'write', value: 'invalid'})]},
    {state: 1, requests: [fields({kind: 'custom', value: 1})]},
  ]) {
    assert.throws(() => f.component.dispatch(event));
    assert.equal(f.component.getState(), 0);
    assert.deepEqual(f.effects, []);
    assert.deepEqual(f.paints, ['good']);
  }
  f.component.dispatch({state: 3, requests: [fields({kind: 'write', value: 3})]});
  assert.equal(f.component.getState(), 4, 'reentrant event runs after state commit');
  assert.deepEqual(f.effects, [3]);
  f.component.dispatch({state: 5, requests: [fields({kind: 'later'})]});
  assert.throws(() => f.component.replaceRuntime({run: () => fields({tree: 'bad', styles: []})}));
  assert.equal(f.pending[0].signal.aborted, false, 'invalid candidate retains old runtime and effects');
  f.component.replaceRuntime(f.runtime);
  assert.equal(f.pending[0].signal.aborted, true);
  f.pending[0].resolve({state: 8});
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(f.component.getState(), 5, 'old runtime async completion discarded');
  f.component.dispatch({state: 6, requests: [fields({kind: 'later'})]});
  f.component.dispose();
  f.pending[1].resolve({state: 7});
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(f.component.getState(), 6, 'disposed async completion discarded');
  assert.equal(f.pending[1].signal.aborted, true);
  const failing = fixture();
  assert.throws(() => failing.component.dispatch({state: 1, requests: [fields({kind: 'write', value: 99}), fields({kind: 'write', value: 2})]}));
  assert.equal(failing.component.getState(), 1, 'effect failure retains committed state');
  assert.deepEqual(failing.effects, [99], 'effect failure skips subsequent requests');
  const r = fixture();
  let fail = false;
  r.component.setRender(() => { if (fail) throw new Error('render failed'); });
  fail = true;
  assert.throws(() => r.component.dispatch({state: 1, requests: [fields({kind: 'write', value: 1})]}));
  assert.equal(r.component.getState(), 0);
  assert.deepEqual(r.effects, []);
})().then(() => console.log('PASS graph component atomic validation, queued events and cancellation'))
  .catch(error => { console.error(error); process.exitCode = 1; });
