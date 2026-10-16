'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require.resolve('../browser-test/edit-tutorial-tour.test.js'), 'utf8');
const {createDocument} = require('./mini-dom');
const marker = '.map((l) =>';
const start = source.indexOf(marker) + '.map('.length;
const end = source.indexOf('),\n      handlerCard:', start);
assert(source.includes(marker) && end > start, 'the browser fixture exposes its ancestry label reader');
const label = vm.runInNewContext('(' + source.slice(start, end) + ')');
test('nested passive ancestry names exclude native controls and diagnostic siblings', () => {
  const document = createDocument();
  const rows = ['health', 'get-route', 'route', 'list'].map(text => {
    const row = document.createElement('div');
    const host = document.createElement('span');
    host.className = 'gd-canvas-body-host';
    const name = document.createElement('span');
    name.className = 'gd-canvas-name';
    const content = document.createElement('strong');
    content.textContent = ' ' + text + ' ';
    name.appendChild(content);
    host.appendChild(name);
    row.appendChild(host);
    for (const text of ['▶ Run', '⋯']) {
      const control = document.createElement('button');
      control.textContent = text;
      row.appendChild(control);
    }
    const diagnostics = document.createElement('span');
    diagnostics.className = 'gd-canvas-body-host';
    const badge = document.createElement('span');
    badge.className = 'type-error-badge';
    badge.textContent = '⚠';
    diagnostics.appendChild(badge);
    row.appendChild(diagnostics);
    return row;
  });
  assert(rows[0].textContent.includes('▶ Run') && rows[0].textContent.includes('⚠'));
  assert.deepEqual(rows.map(label), ['health', 'get-route', 'route', 'list']);
  rows[0].querySelector('.gd-canvas-name').textContent = 'other';
  assert.notEqual(rows.map(label).join(','), 'health,get-route,route,list');
  rows[0].querySelector('.gd-canvas-name').remove();
  assert.equal(label(rows[0]), '', 'missing names cannot fall back to native control text');
});
