'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {selectCreatedLeaf, editOwnValue, waitForPersonalMenuValue, waitForPersonalMenuHome} = require('../browser-test/tutorial-ui-components-helpers');

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

test('a saved menu value waits for the authorized plan rather than the old ready runtime', async () => {
  let hover = '#old';
  let persisted = '#fed7aa';
  const context = vm.createContext({Map, args: null,
    graphData: {bindings: [{'fn-id': 'own-hover', 'slot-id': 'own-slot', get value() { return persisted; }}]},
    lookups: {slotMap: new Map([['own-slot', {name: 'value'}]])},
    window: {
      gdUIComponentRuntimeIdentity: () => 'own-configuration',
      GraphdenBrowser: {keyword: value => value},
      gdGraphThemeBase: () => ({'--bg': '#fff7ed'}),
      gdShellMenuGraph: {ready: true, state: new Map(), runtime: {
        run: () => new Map([['menu-tokens', new Map([['--gd-account-menu-hover', hover]])]]),
      }},
    },
  });
  await waitForPersonalMenuValue({waitForFunction: async (predicate, args) => {
    context.args = args;
    const check = () => vm.runInContext('(' + predicate.toString() + ')(args)', context);
    assert.equal(check(), false, 'persisted binding plus old ready plan is insufficient');
    hover = '#fed7aa';
    persisted = '#old';
    assert.equal(check(), false, 'a matching plan still requires the exact saved binding');
    persisted = '#fed7aa';
    assert.equal(check(), true, 'opening can follow the current authorized plan');
  }}, 'own-hover', 'own-configuration', '#fed7aa');
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

test('Home waits for both its exact persisted list item and the reloaded update decision', async () => {
  let fresh = false;
  const data = {bindings: [{'fn-id': 'own-map', 'slot-id': 'own-slot', id: 'own-binding'}], 'list-items': []};
  const state = new Map([['active', 0]]);
  const context = vm.createContext({Map, args: null, graphData: data,
    lookups: {slotMap: new Map([['own-slot', {name: 'vals'}]])},
    window: {
      gdUIComponentRuntimeIdentity: () => 'own-configuration',
      GraphdenBrowser: {keyword: value => value},
      gdShellMenuGraph: {ready: true, state, runtime: {run(entry, args) {
        if (entry === 'initial') return state;
        assert.equal(entry, 'update');
        assert.equal(args.context.get('items').length, 3);
        const home = args.event.get('kind') === 'keydown' && args.event.get('key') === 'Home';
        return new Map([['active', home && fresh ? 2 : 0]]);
      }}},
    },
  });
  await waitForPersonalMenuHome({waitForFunction: async (predicate, args) => {
    context.args = args;
    const check = () => vm.runInContext('(' + predicate.toString() + ')(args)', context);
    assert.equal(check(), false, 'the current DOM cannot substitute for a persisted item');
    data['list-items'].push({'binding-id': 'own-binding', position: 2, value: 'last'});
    assert.equal(check(), false, 'the old ready update plan still maps Home to first');
    fresh = true;
    assert.equal(check(), true);
    assert.equal(state.get('active'), 0, 'readiness inspection never changes mounted state');
  }}, 'own-map', 'own-configuration');
});
