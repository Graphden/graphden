'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require.resolve('../browser-test/edit-tutorial-tour.test.js'), 'utf8');
const start = source.indexOf('.map((l) => [...l.childNodes]') + '.map('.length;
const end = source.indexOf('),\n      handlerCard:', start);
const label = vm.runInNewContext('(' + source.slice(start, end) + ')', {Node: {TEXT_NODE: 3}});
test('ancestry labels exclude native Run and menu controls', () => {
  const rows = ['health', 'get-route', 'route', 'list'].map(text => ({childNodes: [
    {nodeType: 3, textContent: text}, {nodeType: 1, textContent: '▶ Run'}, {nodeType: 1, textContent: '⋯'},
  ]}));
  assert.deepEqual(rows.map(label), ['health', 'get-route', 'route', 'list']);
  rows[0].childNodes[0].textContent = 'other';
  assert.notEqual(rows.map(label).join(','), 'health,get-route,route,list');
});
