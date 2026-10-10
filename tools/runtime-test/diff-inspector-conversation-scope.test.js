'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');

function fixture() {
  const document = createDocument();
  const create = document.createElement.bind(document);
  document.createElement = tag => {
    const element = create(tag);
    element.insertAdjacentElement = (_position, child) => {
      const parent = element.parentNode;
      parent.insertBefore(child, parent.children[parent.children.indexOf(element) + 1] || null);
    };
    return element;
  };
  const inspector = document.createElement('div'); document.body.appendChild(inspector);
  const head = document.createElement('div'); head.className = 'gd-insp-head'; inspector.appendChild(head);
  const controllers = [];
  const mode = {currentId: 'current-branch', proposalRef: 'proposal', branch: 'compared'};
  const window = {gdPrefOwner: 'alice'};
  const context = vm.createContext({document, window, _gdDiffMode: mode,
    gdDiffVisibleGroup: id => ({'fn-id': id, change: 'modified'}),
    gdDiffMarkerEl: () => document.createElement('span'),
    gdDiffCommentBtnEl: () => document.createElement('button'),
    gdDiffRenderGroup: () => document.createElement('div'),
    gdDiffAttachThreads: () => {
      const controller = {mounts: 0, captureFocus() {}, mount() { this.mounts++; }};
      controllers.push(controller); return controller;
    },
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname,
    '../../resources/packages/app/editor/editor-diff-inspector.js'), 'utf8'), context);
  return {render: id => context.gdDiffRenderInspectorSection(inspector, id),
    leave: () => context.gdDiffCaptureInspectorFocus(inspector, null), window, mode, controllers};
}

test('only the same selected function, branch pair and account reuse a conversation', () => {
  const f = fixture(); f.render('function'); f.render('function');
  assert.equal(f.controllers.length, 1); assert.equal(f.controllers[0].mounts, 1);
  f.window.gdPrefOwner = 'bob'; f.render('function');
  f.mode.currentId = 'another-current'; f.render('function');
  f.mode.proposalRef = 'another-proposal'; f.render('function');
  f.render('another-function');
  assert.equal(f.controllers.length, 5);
  assert.equal(f.controllers.slice(1).every(controller => controller.mounts === 0), true);
  f.leave(); f.render('another-function');
  assert.equal(f.controllers.length, 6, 'leaving the selection disposes its local draft identity');
});
