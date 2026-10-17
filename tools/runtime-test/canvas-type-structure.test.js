'use strict';

// Actual generated graph + actual native snapshot/cache transaction. Canonical
// grammar/save helpers are tested separately; these receipts are display only.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {performance} = require('node:perf_hooks');
const {createDocument} = require('./mini-dom');
const root = path.join(__dirname, '../../resources/packages');
function size(value) {
  let result = 0;
  const pending = [value];
  while (pending.length) {
    const item = pending.pop();
    if (typeof item === 'string') result += item.length;
    else if (item instanceof Map) pending.push(...item.values());
    else if (item && typeof item === 'object') pending.push(...Object.values(item));
  }
  return result;
}
function boot() {
  let selected = null;
  const calls = [];
  const failures = [];
  const ctx = vm.createContext({console, Map, Set, WeakMap, WeakSet, AbortController, performance,
    document: createDocument(), queueMicrotask,
    addEventListener() {}, requestAnimationFrame: () => 1, cancelAnimationFrame() {},
    GraphdenRenderer: {},
    gdLoadUIComponentRuntime: async () => selected,
    gdUIComponentFailed: identity => failures.push(identity),
  });
  ctx.window = ctx;
  const load = file => vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), ctx, {filename: file});
  load('app/ui-preview/browser-runtime.js');
  load('app/ui-preview/builtin-plans.js');
  const create = ctx.GraphdenBrowser.createRuntime;
  ctx.GraphdenBrowser.createRuntime = (plan, options) => {
    const runtime = create(plan, options);
    return {run(role, input, budget) {
      const before = budget?.remaining;
      try {
        const value = runtime.run(role, input, budget);
        if (role === 'initial') calls.push({input, value, personal: !!budget,
          operations: budget ? before - budget.remaining : null,
          projected: size(input.inputs), emitted: size(value)});
        return value;
      } catch (error) {
        if (role === 'initial') calls.push({personal: !!budget, error: error.message,
          operations: budget ? before - budget.remaining : null, projected: 0, emitted: 0});
        throw error;
      }
    }};
  };
  load('app/editor/editor-passive-canvas.js');
  load('app/editor/editor-canvas-type-structure-graph.js');
  return {ctx, calls, failures, select(value) { selected = value; }};
}

(async () => {
  const t = boot();
  const graph = t.ctx.gdCanvasTypeStructureGraph;
  const cases = [
    [null, 'any', '', 'any'],
    ['int', 'int', 'integer', 'int'],
    ['a-27', 'any', 'a-27', "'a"],
    [['list', 'int'], 'sequence', 'list of integers', '[int]'],
    [['map', 'keyword', 'text'], 'jsonb', 'map of keyword to text', '{keyword→text}'],
    [['tuple', 'int', 'text'], 'jsonb', 'tuple of (integer, text)', '(int,text)'],
    [['union', 'int', 'null'], 'any', 'either integer or null', 'int|null'],
    [{}, null, 'empty record', '{}'],
    [{name: 'text', age: 'int'}, null, 'record with name (text) and age (integer)', '{name,age}'],
    [['refine', 'int', ['>', 0]], 'int', 'positive integer', 'int'],
    [['refine', 'int', ['and', ['>=', 1024], ['<=', 65535]]], 'int', 'integer where >= 1024 and <= 65535', 'int'],
    [['refine', 'keyword', ['in', ['get', 'post', 3, null]]], 'keyword', 'keyword where one of get, post, 3, null', 'keyword'],
    [['refine', 'text', 'plain atom'], 'text', 'text where "plain atom"', 'text'],
    [['secret', 'text'], 'text', 'secret text', 'text'],
    [['fn', {}, 'bool', []], 'fn', 'function: takes no args, returns true/false; must be pure (no effects allowed)', '()→bool'],
    [['fn', {request: 'int'}, 'bool', ['db', 'time']], 'fn', 'function: takes request (integer), returns true/false; allowed effects: db, time. Args contravariant; return covariant.', '(request:int)→bool'],
  ];
  for (const [raw, flat, human, compact] of cases) {
    const result = graph.format(raw, flat);
    assert.equal(result.human, human);
    assert.equal(result.compact, compact);
  }
  const metrics = [];
  function measure(name, raw) {
    t.calls.length = 0;
    const started = performance.now();
    let result;
    try { result = graph.format(raw, 'any'); }
    catch (error) { error.message = name + ': ' + error.message; throw error; }
    metrics.push({name, nodes: t.calls.length,
      projected: t.calls.reduce((sum, call) => sum + call.projected, 0),
      emitted: t.calls.reduce((sum, call) => sum + call.emitted, 0),
      personalOperations: t.calls.reduce((sum, call) => sum + (call.operations || 0), 0),
      personalCalls: t.calls.filter(call => call.personal).length,
      fallbackCalls: t.calls.filter(call => !call.personal).length,
      output: result.human.length + (result.compact?.length || 0),
      elapsedMs: Math.round((performance.now() - started) * 100) / 100});
    console.log('type display cost', JSON.stringify(metrics[metrics.length - 1]));
    return result;
  }
  let deep = 'int';
  for (let i = 0; i < 1200; i++) deep = ['list', deep];
  assert(measure('deep-1200', deep).human.startsWith('list of list of '));
  measure('wide-128', Object.fromEntries(Array.from({length: 128}, (_, i) => ['field' + i, 'text'])));
  const subtree = ['tuple', 'int', 'text'];
  measure('repeated-64', ['tuple', ...Array(64).fill(subtree)]);
  measure('label-65536', ['marker', 'x'.repeat(65536)]);
  const raw = ['list', 'int'];
  graph.format(raw, 'any');
  const cached = t.calls.length;
  graph.format(raw, 'any');
  assert.equal(t.calls.length, cached, 'same revision reuses display receipt without graph execution');

  const k = t.ctx.GraphdenBrowser.keyword;
  t.select({run(role, {inputs}) {
    assert.equal(role, 'initial');
    return new Map([[k('human'), 'personal'], [k('compact'), 'P'], [k('constraint'), ''], [k('short'), 'P']]);
  }});
  await graph.reload();
  assert.equal(graph.format(raw, 'any').human, 'personal');
  graph.invalidate();
  assert.equal(graph.format(raw, 'any').human, 'list of integers', 'synchronous invalidation retires old cached strings');

  t.select(t.ctx.GraphdenBrowser.createRuntime(t.ctx.GraphdenBuiltinPlans.plans.canvasTypeStructure,
    {operationLimit: 150000}));
  await graph.reload();
  measure('personal-wide-128', Object.fromEntries(Array.from({length: 128}, (_, i) => ['field' + i, 'text'])));
  assert(measure('personal-deep-1200-with-fallback', deep).human.startsWith('list of list of '));
  assert(t.failures.includes('canvas-type-structure'), 'aggregate resource exhaustion retires personal runtime');

  const literal = value => ({kind: 'literal', value: t.ctx.GraphdenBrowser.encode(value)});
  const call = fn => ({kind: 'call', fn, renames: []});
  const fn = (id, primitive, args) => ({id, primitive, args, env: [], aliases: []});
  const arg = (name, expr) => ({name, slot: name, expr});
  const slowPlan = {format: 1, primitiveAbi: 1, entries: {initial: 'entry'},
    inputs: {entry: {accepted: ['inputs'], required: ['inputs'], destinations: []}},
    primitives: [{id: 'if', op: 'if'}, {id: 'lte', op: 'lte'}, {id: 'const', op: 'const'}],
    functions: [fn('entry', 'if', [arg('test', call('compare')), arg('then', call('result')), arg('else', call('result'))]),
      fn('compare', 'lte', [arg('nums', literal(Array(49000).fill(1)))]),
      fn('result', 'const', [arg('value', literal(new Map([[k('human'), 'slow'], [k('compact'), 'S'], [k('constraint'), ''], [k('short'), 'P']])))]),
    ]};
  t.select(t.ctx.GraphdenBrowser.createRuntime(slowPlan, {operationLimit: 150000}));
  await graph.reload();
  let costProbe = 'int';
  for (let i = 0; i < 200; i++) costProbe = ['list', costProbe];
  assert(measure('expensive-personal-with-whole-fallback', costProbe).human.startsWith('list of list of '));
  assert(t.calls.some(call => call.error?.includes('batch time limit')), 'sampled deadline exhaustion is observed');
  console.log('PASS type structure graph cases/cache; UTF-16 string cost', JSON.stringify(metrics));
})().catch(error => { console.error(error); process.exitCode = 1; });
