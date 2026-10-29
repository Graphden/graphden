'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const plan = require('../../resources/packages/app/ui-preview/builtin-plans.json').plans.canvasBindings;
const runtime = graph.createRuntime(plan, {operationLimit: 10000});
const record = facts => new Map(Object.entries(facts).map(([name, value]) => [graph.keyword(name), value]));
const ctx = vm.createContext({console, Map, Set, WeakMap, Object, Number, window: {}});
Object.assign(ctx.window, {
  GraphdenBrowser: graph,
  GraphdenBuiltinPlans: {plans: {canvasBindings: plan}},
  gdCheckCanvasFlowStyles() {},
  addEventListener() {},
  gdCreatePassiveCanvas() {
    return {initialBatch(build) {
      return build((facts, validate) => validate(runtime.run('initial', {inputs: record(facts)})));
    }};
  },
});
vm.runInContext(fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-canvas-bindings-graph.js'), 'utf8'), ctx);

const policy = facts => ctx.window.gdCanvasBindingsGraph.flagPolicy(facts);
const base = {declared: false, 'names-count': 0, own: false, inherited: false};
assert.equal(policy({...base, surface: 'lambda'}).mode, 'derived');
assert.equal(policy({...base, surface: 'lambda', declared: true}).mode, 'none');
assert.equal(policy({...base, surface: 'lambda', declared: true, 'names-count': 2}).mode, 'named');
assert.deepEqual({...policy({...base, surface: 'branch-local'})},
  {mode: '', checked: false, disabled: false});
assert.deepEqual({...policy({...base, surface: 'branch-local', own: true})},
  {mode: '', checked: true, disabled: false});
assert.deepEqual({...policy({...base, surface: 'branch-local', inherited: true})},
  {mode: '', checked: true, disabled: true});
assert.throws(() => policy({...base, surface: 'branch-local', 'names-count': -1}), /Invalid flag facts/);
console.log('PASS graph flag policy: lambda modes, branch-local inherited lock and bounded input');
