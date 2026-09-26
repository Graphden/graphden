// A non-enterable secret arg must tell the user to bind it on the card.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

async function noteFor(widget) {
  const notes = [];
  const host = {
    dataset: { slotId: 'slot', slotName: 'token' },
    classList: { add() {} },
    appendChild(node) { notes.push(node.textContent); },
    querySelector(selector) {
      return selector === '[data-form-widget="secret-binding"]'
        && widget === 'secret-binding' ? {} : null;
    },
  };
  const ctx = vm.createContext({
    window: {},
    document: { createElement: () => ({}), addEventListener() {} },
    fetchValueForm: async () => ({ ok: true, widget }),
    renderValueForm() {},
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-execute.js'), 'utf8'), ctx);
  await ctx.mountArgFormHost({ id: 'fn' }, host);
  return notes.join(' ');
}

(async () => {
  assert.match(await noteFor('secret-binding'), /Secret.*bind it on the card/);
  assert.doesNotMatch(await noteFor('secret-binding'), /argument is a function/);
  assert.match(await noteFor('function'), /argument is a function/);
})().catch((error) => { console.error(error); process.exitCode = 1; });
