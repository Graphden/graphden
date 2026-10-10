const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');

function fixture(anchoredOnly = true) {
  const document = createDocument();
  const create = document.createElement.bind(document);
  document.createElement = (tag) => {
    const element = create(tag);
    element.focus = () => { document.activeElement = element; };
    return element;
  };
  const root = create('div'); document.body.appendChild(root);
  const anchor = create('div'); anchor.className = 'branch-diff-entry';
  anchor.dataset.anchorName = 'fn'; anchor.dataset.anchorId = 'fixture';
  anchor.insertAdjacentElement = (_position, element) => {
    const index = root.children.indexOf(anchor);
    root.insertBefore(element, root.children[index + 1] || null);
  };
  Object.defineProperty(anchor, 'nextElementSibling', {get: () =>
    root.children[root.children.indexOf(anchor) + 1] || null});
  root.appendChild(anchor);
  const button = create('button'); button.className = 'branch-diff-comment-btn';
  anchor.appendChild(button);
  const pending = [];
  const context = vm.createContext({document, console, CSS: {escape: (value) => value},
    API: {api_branches_ref_comments: (id) => '/comments/' + id}, alert: () => {}});
  context.window = context;
  context.authFetch = (url, options) => new Promise((resolve) => {
    pending.push({url, options, complete: (data) => resolve({json: async () => data})});
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname,
    '../../resources/packages/app/editor/editor-branch-diff.js'), 'utf8'), context);
  context.gdDiffAttachThreads(root, 'source', 'source', {anchoredOnly});
  button.click();
  const input = root.querySelector('textarea'); input.value = 'Keep my draft';
  return {root, button, input, pending, document};
}
const drain = () => new Promise((resolve) => setImmediate(resolve));

test('late initial read retains exact composer and includes newly read comments', async () => {
  const {root, input, pending, document} = fixture();
  const send = root.querySelector('.branch-comment-send');
  pending[0].complete({ok: true, comments: [{id: 'comment', body: 'Saved comment',
    'entity-name': 'fn', 'entity-id': 'fixture'}]});
  await drain();
  assert.equal(root.querySelector('textarea'), input);
  assert.equal(root.querySelector('.branch-comment-send'), send);
  assert.equal(input.value, 'Keep my draft');
  assert.equal(document.activeElement, input);
  assert.equal(root.querySelector('.branch-comment-body').textContent, 'Saved comment');
});

test('successful send closes only the submitted composer; failed send retains it', async () => {
  const {root, input, pending} = fixture();
  pending[0].complete({ok: true, comments: []}); await drain();
  const send = root.querySelector('.branch-comment-send'); send.click();
  assert.equal(pending[1].options.method, 'POST');
  pending[1].complete({ok: false, message: 'Refused'}); await drain();
  assert.equal(root.querySelector('textarea'), input);
  assert.equal(input.value, 'Keep my draft'); assert.equal(send.disabled, false);
  send.click(); pending[2].complete({ok: true}); await drain();
  assert.equal(root.querySelector('textarea'), null);
  assert.equal(pending[3].options, undefined);
  pending[3].complete({ok: true, comments: [{body: 'Keep my draft',
    'entity-name': 'fn', 'entity-id': 'fixture'}]}); await drain();
  assert.equal(root.querySelector('.branch-comment-body').textContent, 'Keep my draft');
});

test('refreshing anchored comments also retains the unrelated general draft', async () => {
  const {root, pending, document} = fixture(false);
  pending[0].complete({ok: true, comments: []}); await drain();
  const general = root.querySelector('.branch-comment-form textarea');
  general.value = 'Unsubmitted general note';
  root.querySelector('.branch-diff-anchor-thread .branch-comment-send').click();
  general.focus();
  pending[1].complete({ok: true}); await drain();
  pending[2].complete({ok: true, comments: []}); await drain();
  assert.equal(root.querySelector('.branch-comment-form textarea'), general);
  assert.equal(general.value, 'Unsubmitted general note');
  assert.equal(document.activeElement, general);
});

test('late read retains the disabled composer while its POST is pending', async () => {
  const {root, input, pending} = fixture();
  const send = root.querySelector('.branch-comment-send'); send.click();
  assert.equal(send.disabled, true);
  pending[0].complete({ok: true, comments: []}); await drain();
  assert.equal(root.querySelector('.branch-comment-send'), send);
  assert.equal(send.disabled, true);
  send.click(); assert.equal(pending.length, 2, 'refresh cannot enable a duplicate POST');
  pending[1].complete({ok: false, message: 'Refused'}); await drain();
  assert.equal(input.value, 'Keep my draft'); assert.equal(send.disabled, false);
});
