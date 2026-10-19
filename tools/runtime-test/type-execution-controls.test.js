'use strict';

// Contract authoring must replace only the selected binding-local leaf.
// Real storage narrowing is covered by crud/entities_test; this tests the
// production editor controls and exact JSON proposal without a live stack.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');

(async () => {
  const document = createDocument();
  const submissions = [];
  const ctx = vm.createContext({document, console, JSON, Set, Map,
    addEventListener() {}, setTimeout() {},
    resolveOneHop: value => value === 'Callbacks' ? ['list', callback] : value,
    API: {api_bindings_binding_id_tighten_fn_effects: id => '/bindings/' + id},
    fetch: async (url, opts) => { submissions.push({url, body: JSON.parse(opts.body)}); return {ok: true}; },
  });
  ctx.window = ctx;
  for (const filename of ['editor-type-format.js', 'editor-overlay-type-expand.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor', filename), 'utf8'), ctx);
  }
  const callback = ['fn', {value: 'int'}, 'int', ['io', 'db'], ['execution-var', 'E']];
  const root = ['map', 'keyword', ['list', callback]];
  const before = JSON.stringify(root);
  const controls = ctx.makeExecutionRow(callback[4]);
  assert.equal(controls.select.value, 'current');
  assert.equal(controls.changed(), false);
  const checks = {io: {checked: true, addEventListener() {}}, db: {checked: true, addEventListener() {}}};
  const button = ctx.makeTightenButton({argSelects: {}, checkboxes: checks,
    currentEff: new Set(['io', 'db']), currentType: callback, rootType: root,
    typePath: [2, 1], execution: controls, bindingId: 'local-binding',
    errEl: document.createElement('div')});
  assert.equal(button.disabled, true, 'unchanged effects do not create a proposal');
  controls.select.value = 'browser';
  controls.select.dispatch('change');
  assert.equal(button.disabled, false);
  await button.listeners.click[0]({stopPropagation() {}});
  assert.deepEqual(submissions, [{url: '/bindings/local-binding', body: {
    type: ['map', 'keyword', ['list', ['fn', {value: 'int'}, 'int', ['io', 'db'], ['browser-main']]]],
  }}]);
  assert.equal(JSON.stringify(root), before, 'shared source contract is immutable');
  const replaced = ctx.replaceContractType('Callbacks', [1], ['fn', {}, 'text', [], ['browser-main']]);
  assert.deepEqual(JSON.parse(JSON.stringify(replaced)), ['list', ['fn', {}, 'text', [], ['browser-main']]]);
  assert.throws(() => ctx.replaceContractType(root, [9], callback), /reload/);
  const browser = ctx.makeExecutionRow(['browser-main']);
  assert.equal(browser.select.value, 'browser');
  assert.equal(browser.select.children.find(option => option.value === 'jvm').disabled, true);
  assert.equal(browser.select.children.find(option => option.value === 'unknown').disabled, true);
  assert.equal(browser.select.children.find(option => option.value === 'both').disabled, false);
  assert.equal(ctx.formatExecutionConstraint([]), 'no proven runtime');
  assert.equal(ctx.formatExecutionConstraint('unknown'), 'execution unknown');
  assert.equal(ctx.formatExecutionConstraint(['execution-var', 'E']), 'inferred E');
  const alerts = [];
  ctx.alert = text => alerts.push(text);
  for (const execution of [['browser-main'], ['execution-var', 'E']]) {
    const callable = ['fn', {value: 'int'}, 'text', ['io'], execution];
    const shared = ['map', 'keyword', callable];
    ctx.prompt = () => 'renamed';
    await ctx.promptRenameFnTypeArg('rename-local', callable, 'value', shared, [2]);
    const submission = submissions.at(-1);
    assert.deepEqual(submission, {url: '/bindings/rename-local', body: {
      type: ['map', 'keyword', ['fn', {renamed: 'int'}, 'text', ['io'], execution]],
    }});
    assert.deepEqual(shared, ['map', 'keyword', callable], 'rename leaves the shared contract unchanged');
  }
  ctx.prompt = () => 'taken';
  const prior = submissions.length;
  await ctx.promptRenameFnTypeArg('rename-local', ['fn', {value: 'int', taken: 'text'}, 'text', ['io'], ['browser-main']],
    'value', callback, []);
  assert.equal(submissions.length, prior, 'duplicate parameter name cannot overwrite another parameter');
  assert.match(alerts[0], /already has that name/);
  for (const filename of ['editor-literal-types.js', 'editor-overlay-arg.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor', filename), 'utf8'), ctx);
  }
  ctx.lookups = {fnMap: new Map([['child', {id: 'child', name: 'local'}]]),
    slotMap: new Map([['renamed', {name: 'renamed-handlers'}]])};
  ctx.richTypes = {local: {'fn-id': 'child', 'type-hints': [
    {'arg-name': 'renamed-handlers', path: ['value'], message: 'Connect a value, or encode-view explicitly.'},
    {'arg-name': 'other', path: [], message: 'Unrelated binding.'},
  ]}};
  assert.equal(ctx.bindingTypeHints({'fn-id': 'child', 'slot-id': 'renamed'}).length, 1,
    'consumed renamed binding keeps only its own diagnostic');
  ctx.richTypes.local['fn-id'] = 'sibling-with-same-name';
  assert.equal(ctx.bindingTypeHints({'fn-id': 'child', 'slot-id': 'renamed'}).length, 0,
    'a same-name sibling cannot supply diagnostic provenance');
  const inheritedMap = ['map', 'keyword', callback];
  ctx.lookups = {fnMap: new Map([['child', {id: 'child', name: 'child'}],
    ['parent', {id: 'parent', name: 'parent'}]]),
    bindingMap: new Map(), slotMap: new Map([['source', {name: 'map', 'type-fn-id': 'omitted-anonymous'}]])};
  ctx.getInheritanceChain = () => ['child', 'parent'];
  ctx.getEffectiveSlotName = id => id === 'child' ? 'events' : 'handlers';
  ctx.richTypes = {child: {'fn-id': 'child', args: {events: inheritedMap}},
    parent: {'fn-id': 'parent', args: {handlers: inheritedMap}}};
  const renamedArg = {'fn-id': 'child', 'slot-id': 'source', name: 'events'};
  assert.equal(ctx.expectedSlotType(renamedArg), inheritedMap,
    'inherited renamed slot recovers its rich contract when anonymous row is not loaded');
  ctx.richTypes.child['fn-id'] = 'unrelated-sibling';
  assert.equal(ctx.expectedSlotType(renamedArg), inheritedMap,
    'parent declaration uses its own effective slot name when child entry belongs to a sibling');
  ctx.richTypes.parent['fn-id'] = 'another-sibling';
  assert.equal(ctx.expectedSlotType(renamedArg), null, 'same-name siblings cannot supply a contract');
  ctx.richTypes.child['fn-id'] = 'child';
  ctx.lookups.bindingMap.set('local', {'type-override-fn-id': 'missing-explicit'});
  assert.equal(ctx.expectedSlotType({...renamedArg, 'binding-id': 'local'}), null,
    'missing explicit override cannot silently fall back to a broader declaration');
  ctx.lookups.fnMap.set('producer', {id: 'producer', name: 'producer', 'return-type-fn-id': 'missing-return'});
  ctx.lookups.bindingMap.set('ref', {'ref-fn-id': 'producer'});
  assert.equal(ctx.expectedSlotType({...renamedArg, 'binding-id': 'ref'}), null,
    'missing ref return type cannot silently fall back to the declaration');
  ctx.getEffectiveSlotName = () => 'unrelated-slot';
  assert.equal(ctx.expectedSlotType(renamedArg), null, 'another slot cannot supply the rich declaration');
  const list = ['list', callback];
  assert.equal(ctx.computeSlotType({constraint: list}), list, 'anonymous list rows retain their full callable contract');
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-data.js'), 'utf8'), ctx);
  for (const order of [['old', 'new'], ['new', 'old']]) {
    ctx.lookups = {fnMap: new Map([['base', {id: 'base'}], ['parent', {id: 'parent', 'parent-ids': ['base']}],
      ['grandchild', {id: 'grandchild', 'parent-ids': ['parent']}]]),
      fnSlotsByFn: new Map([['base', [{'slot-id': 'root'}]], ['parent', order.map(id => ({'slot-id': id}))]]),
      slotMap: new Map([['root', {id: 'root', name: 'root'}],
        ['old', {id: 'old', name: 'events', 'source-slot-id': 'root'}],
        ['new', {id: 'new', name: 'renamed-again', 'source-slot-id': 'old'}]])};
    assert.equal(ctx.getEffectiveSlotName('parent', 'root'), 'renamed-again');
    assert.equal(ctx.getEffectiveSlotName('grandchild', 'root'), 'renamed-again');
  }
  console.log('✓ execution authoring preserves nested contracts, effects and local identity');
})().catch(error => {console.error(error); process.exit(1);});
