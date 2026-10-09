'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-overlay-fn-rows.js'), 'utf8');
function element(tag = 'div') {
  return {tag, style: {}, dataset: {}, children: [], listeners: {}, attributes: {},
    appendChild(child) { this.children.push(child); },
    setAttribute(key, value) { this.attributes[key] = value; },
    addEventListener(name, callback) { this.listeners[name] = callback; }};
}
function fixture({signedIn = true, isNavRoot = true, depth = 0, known = true} = {}) {
  const entity = {id: 'root', name: 'selected'}, calls = [], toolbars = [];
  const context = vm.createContext({document: {createElement: element},
    lookups: {fnMap: new Map(known ? [['root', entity]] : [])},
    implementationFnIds: new Set(['root']), isAuthenticated: () => signedIn,
    measureCanvasText: text => text.length * 7, displayLabel: name => name, bindFullNameHover() {}, onPreviewLeave() {},
    clearPreview() {}, attachPreviewHandlers() {}, _singleEditableIncomingArg: () => null,
    createMoreActionsTrigger(options) {
      const trigger = element('button'); trigger.buildContent = options.buildContent; return trigger;
    },
    loadRowActionsContent: (...args) => toolbars.push(args),
    showExecutePopover: (...args) => calls.push(args)});
  vm.runInContext(source, context);
  const level = {depth, fns: [{fnId: 'root', name: 'selected'}], blockIsRoot: true,
    groupMaxDepth: depth};
  const ctx = {nodeId: 'node', originalFnId: 'root', isNavRoot,
    fullDepth: 0, partialFns: new Set(), visibleLevels: [level],
    paint: {setRowBg() {}, fnIsHighlighted: () => false,
      applyPreviewStyle() {}, restoreStyles() {}}};
  return {context, level, ctx, entity, calls, toolbars};
}
{
  const f = fixture({depth: 1});
  f.level.fns = [{fnId: 'a', name: 'parent A'}, {fnId: 'b', name: 'parent B'}];
  const line = element();
  const cells = f.context.renderMiRow(line, f.level, 0, f.ctx);
  assert.equal(cells.size, 2, 'MI render completes without a ReferenceError');
  assert.equal(line.children.length, 2, 'MI row contains only its parent cells');
  for (const cell of line.children) {
    assert.equal(cell.children.length, 1, 'each parent retains its actions trigger');
    assert.equal(cell.children[0].className, undefined, 'MI never gets a root Run button');
    cell.children[0].buildContent(element());
  }
  assert.deepEqual(f.toolbars.map(args => [args[1], args[2], args[3].cardFnId]),
    [['a', 'cell', 'root'], ['b', 'cell', 'root']]);
}
for (const options of [{}, {signedIn: false}, {isNavRoot: false}, {depth: 1}, {known: false}]) {
  const f = fixture(options), line = element();
  f.context.renderSingleFnRow(line, f.level, f.ctx);
  const runs = line.children.filter(child => child.className === 'fn-run-trigger');
  const visible = !Object.keys(options).length;
  assert.equal(runs.length, visible ? 1 : 0, JSON.stringify(options));
  if (visible) {
    const run = runs[0];
    assert.equal(run.attributes['aria-label'], 'Run selected');
    let stops = 0;
    run.listeners.pointerdown({stopPropagation() { stops++; }});
    run.listeners.click({stopPropagation() { stops++; }});
    assert.equal(stops, 2, 'Run does not bubble into row expansion');
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0][0], f.entity, 'Run uses the selected entity');
    assert.equal(f.calls[0][1], run, 'Inspector execution popover anchors to Run');
  }
}
console.log('overlay fn Run trigger: MI rendering and selected-root dispatch passed');

// The sizing contract must reserve the same actual chrome as the rendered row.
{
  const f = fixture();
  f.context.document.body = {classList: {contains: () => false}};
  f.context.DRAG_HANDLE_HEIGHT = 6;
  f.context.expansionState = new Set();
  f.context.getComputedStyle = () => ({fontSize: '16px', getPropertyValue: () => ''});
  vm.runInContext(fs.readFileSync(path.join(__dirname,
    '../../resources/packages/app/editor/editor-layout.js'), 'utf8'), f.context);
  f.context.overlayCssWidth = (_token, fallback) => fallback;
  const data = {id: 'root', originalFnId: 'root', label: 'web-server', type: 'fn', isRoot: true};
  const withRun = f.context.calculateNodeSize(data).width;
  f.context.isAuthenticated = () => false;
  const withoutRun = f.context.calculateNodeSize(data).width;
  assert(withRun > withoutRun, 'Run reserves width only when actually rendered');
  f.context.isAuthenticated = () => true;
  const line = element();
  f.context.renderSingleFnRow(line, f.level, f.ctx);
  assert(withRun >= data.label.length * 7 + parseFloat(line.style.padding.split(' ')[1]) + 8,
    'selected root fits its name and the rendered Run/actions padding');
}

{
  const {createDocument} = require('./mini-dom');
  const context = vm.createContext({document: createDocument(),
    DRAG_HANDLE_HEIGHT: 6, lookups: {fnMap: new Map()}, expansionState: new Set()});
  vm.runInContext(fs.readFileSync(path.join(__dirname,
    '../../resources/packages/app/editor/editor-overlay-arg.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname,
    '../../resources/packages/app/editor/editor-layout.js'), 'utf8'), context);
  Object.assign(context, {resolveArgType: () => 'port', expectedSlotType: () => ['refine', 'int', 'range'],
    namedRefinementPin: () => null, compactTypeChipText: () => 'port',
    refinementConstraintText: () => '1…65535', argRowFromNode: () => ({type: 'port'}),
    displayLiteralLabel: text => ({text}), truncateLabel: text => text});
  const chip = context.createTypeChip({type: 'port'});
  assert.equal(chip.children[1].textContent, '1…65535', 'real chip renders its range line');
  const data = {type: 'arg', label: '8080', argType: 'port'};
  const constrained = context.calculateNodeSize(data).width;
  context.refinementConstraintText = () => null;
  const plain = context.calculateNodeSize(data).width;
  assert(constrained > plain, 'sizing includes the wider rendered refinement line');
  context.overlayCssWidth = () => 32;
  assert(context.calculateNodeSize({...data, sourceChain: [{}]}).width >= plain + 36,
    'touch source-link reserves its actual CSS icon size');
  context.getTypeNarrowingInfo = () => ({baseTypeName: 'long-underlying-type'});
  assert(context.calculateNodeSize(data).width > plain, 'sizing also includes the narrowed base line');
}
