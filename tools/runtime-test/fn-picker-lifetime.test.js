'use strict';

// Capture focus before unmounting a renderer-owned option or toggle button.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-fn-picker.js'), 'utf8');
let returned = null;
const body = {};
const focused = {};
const anchor = {};
const ctx = vm.createContext({
  console, installTabTrap() {},
  document: {activeElement: focused, removeEventListener() {}},
  returnFocusTo(value) { returned = value; },
  body, focused, anchor,
});
vm.runInContext(script, ctx);
vm.runInContext(`
  fnPickerEl = {contains: (node) => node === focused, remove() {}};
  fnPickerAnchor = anchor;
  fnPickerGraphDispose = () => { document.activeElement = body; };
  closeFnPicker();
`, ctx);
assert.equal(returned, anchor);
assert.equal(vm.runInContext('fnPickerEl', ctx), null);
returned = null;
vm.runInContext(`
  fnPickerEl = {contains: () => false, remove() {}};
  fnPickerAnchor = anchor;
  closeFnPicker();
`, ctx);
assert.equal(returned, null);
console.log('PASS picker focus restoration survives graph disposal');
