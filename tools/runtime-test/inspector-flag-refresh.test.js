'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-edit-modes-flags.js'), 'utf8');

function row(fnId, action) {
  const classes = new Set();
  const listeners = new Map();
  return {
    dataset: {fnId, action}, classList: {
      add(name) { classes.add(name); },
      contains(name) { return classes.has(name); },
    },
    title: '',
    setAttribute() {},
    addEventListener(name, listener) { listeners.set(name, listener); },
    click() { listeners.get('click')?.({stopPropagation() {}}); },
  };
}

function host(rows) {
  return {querySelectorAll(selector) {
    return selector === '.gd-insp-flag-row[data-action]' ? rows : [];
  }};
}

async function scenario(mode) {
  const fn = {id: 'f1', name: 'one', 'parent-ids': ['base']};
  const opens = [];
  let visible = [];
  let owned = true;
  let inspectorFnId = fn.id;
  let finishRefresh;
  const ctx = vm.createContext({console, Promise, Date,
    _graphAuthEpoch: 0,
    window: {},
    document: {
      querySelectorAll: () => visible,
      querySelector: () => visible[0] || null,
      getElementById: () => ({dataset: {fnId: inspectorFnId}}),
    },
    lookups: {fnMap: new Map([[fn.id, fn]])},
    isAuthenticated: () => true,
    isPackageOwnedFn: () => false,
    graphdenIsFnOwned: () => owned,
    loadGraphData() {
      ctx.lookups.fnMap.clear();
      return new Promise((resolve) => { finishRefresh = resolve; });
    },
    enterExpectsEffectsEditMode(value, anchor) { opens.push({value, anchor}); },
  });
  vm.runInContext(source, ctx);
  const first = row(fn.id, 'expects-effects');
  visible = [first];
  ctx.gdBindInspectorFlagRows(host(visible));
  const refresh = ctx.gdAfterFlagSaved(fn);
  await Promise.resolve();
  first.click();
  assert.equal(opens.length, 0, 'click waits while the graph lookup is empty');
  visible = [];
  owned = mode !== 'revoke';
  if (mode === 'auth-switch') ctx._graphAuthEpoch++;
  if (mode === 'navigation') inspectorFnId = 'f2';
  ctx.lookups.fnMap.set(fn.id, fn);
  finishRefresh();
  await refresh;
  const replacement = row(fn.id, 'expects-effects');
  visible = [replacement];
  ctx.gdBindInspectorFlagRows(host(visible));
  if (mode !== 'normal') {
    assert.equal(opens.length, 0, 'revocation or auth change cancels the queued action');
    owned = true;
    ctx.gdBindInspectorFlagRows(host([row(fn.id, 'expects-effects')]));
    assert.equal(opens.length, 0, 'a later grant does not resurrect the cancelled action');
  } else {
    assert.equal(opens.length, 1, 'the queued action opens once');
    assert.equal(opens[0].value, fn);
    assert.equal(opens[0].anchor, replacement, 'the popover uses the current row');
  }
}

(async () => {
  await scenario('normal');
  await scenario('revoke');
  await scenario('auth-switch');
  await scenario('navigation');
  console.log('PASS Inspector flag refresh replays once and cancels on revocation, auth switch or navigation');
})().catch((error) => { console.error(error); process.exitCode = 1; });
