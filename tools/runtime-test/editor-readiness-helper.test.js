'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {waitForEditorReady} = require('../browser-test/editor-readiness');

test('strong helper accepts an explicit usable auth wall, never failed metadata', async () => {
  for (const scenario of [
    {name: 'accounts redirect', path: '/login', allow: true, expected: true},
    {name: 'admin sign-in', admin: true, allow: true, expected: true},
    {name: 'admin sign-in is not editing readiness', admin: true, expected: false},
    {name: 'hidden auth form', admin: true, hidden: true, allow: true, expected: false},
    {name: 'inert auth form', admin: true, inert: true, allow: true, expected: false},
    {name: 'failed metadata', allow: true, expected: false},
    {name: 'empty failed shell', ready: true, empty: true, allow: true, expected: false},
    {name: 'ready editor', ready: true, expected: true},
  ]) {
    const popover = {
      classList: {contains: () => !!scenario.hidden}, getClientRects: () => [{}],
      querySelector: () => ({disabled: false, closest: () => scenario.inert ? {} : null}),
    };
    const context = vm.createContext({location: {pathname: scenario.path || '/'},
      isAuthenticated: () => false, gdGraphEditsReady: () => !!scenario.ready,
      graphData: scenario.empty ? null : {},
      document: {getElementById: () => scenario.admin ? popover : null},
    });
    const page = {evaluate: async () => {}, waitForFunction: async (fn, arg) => {
      assert.equal(vm.runInContext('(' + fn.toString() + ')(' + JSON.stringify(arg) + ')', context),
        scenario.expected, scenario.name);
    }};
    await waitForEditorReady(page, {allowAuthWall: !!scenario.allow});
  }
});
