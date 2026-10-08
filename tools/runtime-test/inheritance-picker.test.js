'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');
const file = path.join(__dirname, '../../resources/packages/app/editor/editor-fn-picker.js');
const source = fs.readFileSync(file, 'utf8');

async function exercise(graph) {
  const doc = createDocument();
  const createElement = doc.createElement.bind(doc);
  doc.createElement = tag => {
    const element = createElement(tag);
    element.scrollIntoView = () => {};
    element.focus = () => { doc.activeElement = element; };
    element.contains = node => node === element || element.children.includes(node);
    return element;
  };
  doc.addEventListener = () => {};
  doc.removeEventListener = () => {};
  const anchor = doc.createElement('button');
  anchor.getBoundingClientRect = () => ({ left: 20, bottom: 40 });
  doc.body.appendChild(anchor);
  let picked = null;
  let graphRows = [];
  const ctx = vm.createContext({
    console, Set, Map, Promise, document: doc,
    window: { innerWidth: 1000, innerHeight: 800 },
    graphData: { fns: [{ id: 'wrong-id', name: 'same-name' }] },
    installTabTrap() {}, returnFocusTo() {}, onViewportChanged() {},
    observePopoverAnchor() { return () => {}; },
    compactTypeChipText() { return ''; }, buildEffectsBadges() { return null; },
    pickerTierOf() { throw new Error('Inheritance must not classify binding arity.'); },
    pickerTierLabel() { throw new Error('Inheritance must not print Ready.'); },
    pickerTierTitle() { throw new Error('Inheritance must not claim no free arguments.'); },
    clearTimeout, setTimeout,
    resolveFnByName() { throw new Error('UUID candidates must not resolve by name.'); },
    searchFns() { throw new Error('Fixed ancestors must not query the whole graph.'); },
    candidates: [
      { id: 'allowed-id', name: 'same-name', qualified: 'same-name', ns: null, compatible: true, effects: [] },
      { id: 'blocked-id', name: 'blocked', qualified: 'blocked', ns: null, compatible: false,
        reason: 'Would remove a sealed slot.', effects: [] },
    ],
    options: { anchorEl: anchor, label: 'Choose ancestor',
      onPick(fn, candidate) { picked = { fn, candidate }; } },
  });
  if (graph) {
    ctx.window.gdFnPickerGraph = { ready: true, mount(list, format) {
      return { supports() { return true; }, dispose() {}, render(arranged) {
        graphRows = arranged.groups[0].rows.map((c, idx) => {
          const rowEl = doc.createElement('div');
          rowEl.id = 'option-' + idx;
          list.appendChild(rowEl);
          return { c, rowEl, model: format(c, false) };
        });
        return graphRows;
      } };
    } };
  }
  vm.runInContext(source, ctx);
  vm.runInContext('options.candidates = candidates; openFnPicker(options)', ctx);
  if (graph) {
    assert.equal(graphRows[0].model.fit, 'exact');
    assert.equal(graphRows[0].model['fit-title'], '');
    assert.equal(graphRows[1].model.title, 'Would remove a sealed slot.');
    assert.equal(graphRows[1].model.disabled, true);
  } else {
    assert.equal(doc.querySelectorAll('.fn-picker-row-fit').length, 0);
    assert.equal(doc.querySelectorAll('.fn-picker-row')[1].title, 'Would remove a sealed slot.');
    assert.equal(doc.querySelectorAll('.fn-picker-row')[1].getAttribute('aria-disabled'), 'true');
  }
  const input = doc.querySelector('input');
  input.dispatchEvent({ type: 'keydown', key: 'ArrowDown', preventDefault() {} });
  input.dispatchEvent({ type: 'keydown', key: 'Enter', preventDefault() {} });
  await Promise.resolve();
  assert.equal(picked, null);
  assert.equal(doc.querySelector('.fn-picker-status').textContent, 'Would remove a sealed slot.');
  input.dispatchEvent({ type: 'keydown', key: 'ArrowUp', preventDefault() {} });
  input.dispatchEvent({ type: 'keydown', key: 'Enter', preventDefault() {} });
  await Promise.resolve();
  assert.equal(picked.fn.id, 'allowed-id');
  assert.equal(picked.candidate.id, 'allowed-id');
}

(async () => {
  await exercise(false);
  await exercise(true);
  console.log('✓ inheritance picker: graph + fallback, blocked reason, UUID selection, no binding-fit badges');
})().catch(error => { console.error(error); process.exitCode = 1; });
