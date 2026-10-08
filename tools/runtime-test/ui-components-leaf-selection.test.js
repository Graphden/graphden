'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {selectCreatedLeaf, editOwnValue} = require('../browser-test/tutorial-ui-components-helpers');

test('leaf selection clicks one visible exact identity despite duplicate search and tree rows', async () => {
  const own = '11111111-1111-1111-1111-111111111111';
  const other = '22222222-2222-2222-2222-222222222222';
  const name = 'theme-canvas-color';
  const rows = [
    {id: own, visible: false}, // a hidden presentation must not win
    {id: own, visible: true}, // pinned exact match
    {id: other, visible: true}, // same label from a different manifest
    {id: own, visible: true}, // namespace tree presentation
  ];
  let selected;
  let opened = false;
  const page = {
    locator(selector) {
      if (selector.startsWith('input[')) return {fill: async value => assert.equal(value, name)};
      if (selector.startsWith('#entity-list')) {
        const id = selector.match(/data-fn-id="([^"]+)"/)[1];
        const matches = rows.filter(row => row.id === id && (!selector.includes(':visible') || row.visible));
        const click = async choices => {
          assert.equal(choices.length, 1, 'ordinary Playwright click requires one exact row');
          assert(choices[0].visible, 'ordinary click cannot use a hidden row');
          selected = choices[0].id;
        };
        return {click: () => click(matches), first: () => ({click: () => click(matches.slice(0, 1))})};
      }
      assert.equal(selector, '.node-overlay[data-fn-name="' + name + '"]');
      return {locator: child => {
        assert.equal(child, '.ancestor-line');
        return {first: () => ({click: async () => { opened = true; }})};
      }};
    },
    waitForFunction: async (_predicate, id) => assert.equal(selected, id, 'selection retains the manifest UUID'),
  };
  const manifest = {namespaces: [{id: 'theme-namespace', name: 'theme'}],
    functions: [{id: other, name, 'namespace-id': 'other-namespace'},
      {id: own, name, 'namespace-id': 'theme-namespace'}]};
  assert.equal(await selectCreatedLeaf(page, manifest, 'theme', name), own);
  assert.equal(selected, own);
  assert.equal(opened, true);
});

test('color assertions wait for the asynchronous typed control before editing', async () => {
  let mounted = false;
  let edited;
  let saved = false;
  const events = [];
  const input = {
    waitFor: async options => {
      assert.equal(options.state, 'visible');
      events.push('await-form');
      await new Promise(setImmediate);
      mounted = true;
    },
    fill: async value => { assert(mounted); edited = value; events.push('edit'); },
  };
  const popover = {
    locator: selector => selector === 'input[type="color"]' ? {count: async () => {
      events.push('check-widget');
      return mounted ? 1 : 0;
    }} : {first: () => input},
    getByRole: () => ({click: async () => { saved = true; }}),
    waitFor: async options => { assert.equal(options.state, 'detached'); assert(saved); },
  };
  const page = {
    waitForFunction: async () => {},
    evaluate: async () => '.exact-owned-value',
    locator: selector => selector === '.arg-value-edit-popover' ? popover : {click: async () => {}},
  };
  await editOwnValue(page, 'owned-id', 'value', '#fff7ed');
  assert.deepEqual(events, ['await-form', 'check-widget', 'edit']);
  assert.equal(edited, '#fff7ed');
  assert.equal(saved, true);
});
