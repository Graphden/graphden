'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const {installInspectorGraph} = require('./inspector-graph-fixture');

const editor = path.join(__dirname, '../../resources/packages/app/editor');
function fixture() {
  const document = createDocument();
  const inspector = document.createElement('div');
  inspector.id = 'gd-inspector';
  document.body.appendChild(inspector);
  const group = {'fn-id': 'f', change: 'modified', __effects: 'pure → io',
    'branch-local?': true, entries: [
      {'entity-name': 'binding', 'entity-id': 'b', 'slot-name': 'x', change: 'modified',
        fields: [{field: 'value', target: '1', source: '2'}]},
      {'entity-name': 'fn-slot', 'entity-id': 's', change: 'added-in-source', preview: 'added slot'},
    ]};
  const ctx = vm.createContext({document, Map, Set, Promise, AbortController,
    gdDiffVisibleGroup: () => group,
    _gdDiffMode: {branch: 'other', currentId: 'current', proposalRef: null}});
  ctx.window = ctx;
  // Production loads diff modules before the Inspector graph owner; neither
  // may capture the late owner eagerly at script evaluation time.
  for (const file of ['editor-diff-render.js', 'editor-diff-inspector.js']) {
    vm.runInContext(fs.readFileSync(path.join(editor, file), 'utf8'), ctx);
  }
  installInspectorGraph(ctx, document);
  let acquired = 0; let released = 0;
  ctx.GraphdenStyles.acquire = () => { acquired++; return {id: 'shared'}; };
  ctx.GraphdenStyles.release = sheet => { if (sheet) released++; };
  return {ctx, document, inspector, group, counts: () => ({acquired, released})};
}

test('real Inspector host factory renders selected graph in passive bodies and retains native anchors', () => {
  const f = fixture();
  f.ctx.gdDiffRenderInspectorSection(f.inspector, 'f');
  const panel = f.document.getElementById('gd-diff-insp');
  assert.equal(panel.querySelector('.gd-diff-graph-host').dataset.gdUiStyle, 'shared');
  assert.equal(panel.dataset.gdUiStyle, undefined, 'scoped styles cannot reach native comment controls');
  assert.deepEqual(f.counts(), {acquired: 1, released: 0}, 'one stylesheet for head and all entries');
  assert.match(panel.querySelector('.gd-diff-insp-head').textContent, /vs other.*pure → io.*branch-local/);
  assert.equal(panel.querySelector('.branch-diff-entry[data-anchor-id="b"] .bd-old').textContent, '1');
  assert.equal(panel.querySelector('.branch-diff-entry[data-anchor-id="b"] .bd-new').textContent, '2');
  assert.equal(panel.querySelector('.branch-diff-entry[data-anchor-id="s"] .branch-diff-entry-preview').textContent, 'added slot');
  assert.equal(panel.querySelectorAll('.branch-diff-comment-btn').length, 3);
  assert.equal(panel.querySelectorAll('button').length, 3, 'graph owns no actions');
  const head = panel.querySelector('.gd-diff-insp-head');
  const button = head.querySelector('.branch-diff-comment-btn');
  const row = panel.querySelector('.branch-diff-entry[data-anchor-id="b"]');
  const body = row.querySelector('.gd-diff-graph-host');
  const draft = f.document.createElement('textarea');
  draft.value = 'review in progress';
  head.appendChild(draft);
  f.document.activeElement = draft;
  const chrome = f.document.createElement('div');
  f.inspector.appendChild(chrome);
  f.inspector.dataset.fnId = 'f';
  f.ctx.gdInspectorGraph.render(chrome, {selected: true, name: 'demo', namespace: 'app',
    description: '', parents: [], 'has-return': false},
  {panel: f.document.createElement('div'), load() {}, close() {}, resume() {}});
  assert.equal(f.document.getElementById('gd-diff-insp'), panel,
    'shared runtime publication repaints the comparison without remounting anchors');
  assert.equal(head.querySelector('textarea'), draft);
  const afterChrome = f.counts();
  f.group.entries[0].fields[0].source = '3';
  f.ctx.gdDiffRenderInspectorSection(f.inspector, 'f');
  assert.equal(f.document.getElementById('gd-diff-insp'), panel);
  assert.equal(panel.querySelector('.gd-diff-insp-head'), head);
  assert.equal(head.querySelector('.branch-diff-comment-btn'), button);
  assert.equal(panel.querySelector('.branch-diff-entry[data-anchor-id="b"]'), row);
  assert.equal(row.querySelector('.gd-diff-graph-host'), body);
  assert.equal(row.querySelector('.bd-new').textContent, '3', 'read refresh paints only the body');
  assert.equal(head.querySelector('textarea'), draft, 'comment draft keeps its exact DOM owner');
  assert.equal(f.document.activeElement, draft, 'redraw does not move focus');
  assert.deepEqual(f.counts(), afterChrome, 'compare redraw reuses its shared sheet');
  f.ctx.gdDiffDisposeInspectorPanel(f.document.getElementById('gd-diff-insp'));
  assert.deepEqual(f.counts(), {acquired: afterChrome.acquired, released: afterChrome.released + 1},
    'mode exit releases the compare sheet');
});

test('a different account discards old compare anchors and passive graph owners', () => {
  const f = fixture();
  f.ctx.gdDiffRenderInspectorSection(f.inspector, 'f');
  const old = f.document.getElementById('gd-diff-insp');
  f.ctx.gdPrefOwner = 'another-account';
  f.ctx.gdDiffRenderInspectorSection(f.inspector, 'f');
  assert.notEqual(f.document.getElementById('gd-diff-insp'), old);
  assert.equal(old.isConnected, false);
  assert.deepEqual(f.counts(), {acquired: 2, released: 1});
});

test('removing a compared entry retains its unsent native comment draft', () => {
  const f = fixture();
  f.ctx.gdDiffRenderInspectorSection(f.inspector, 'f');
  const panel = f.document.getElementById('gd-diff-insp');
  const entries = panel.querySelector('.branch-diff-entries');
  const row = panel.querySelector('.branch-diff-entry[data-anchor-id="b"]');
  const next = panel.querySelector('.branch-diff-entry[data-anchor-id="s"]');
  const thread = f.document.createElement('div');
  thread.className = 'branch-diff-anchor-thread';
  thread.dataset.anchorName = 'binding'; thread.dataset.anchorId = 'b';
  const composer = f.document.createElement('div');
  composer.className = 'branch-diff-anchor-compose';
  const draft = f.document.createElement('textarea'); draft.value = 'Unsent note';
  composer.appendChild(draft); thread.appendChild(composer);
  entries.insertBefore(thread, next);
  f.document.activeElement = draft;
  f.group.entries.shift();
  f.ctx.gdDiffRenderInspectorSection(f.inspector, 'f');
  assert.equal(row.isConnected, false);
  assert.equal(thread.isConnected, true);
  assert.equal(panel.querySelector('.branch-diff-anchor-thread textarea'), draft);
  assert.equal(draft.value, 'Unsent note');
  assert.equal(f.document.activeElement, draft);
});

test('a personal passive layout may change its root without replacing native anchors', () => {
  const f = fixture();
  const api = f.ctx.GraphdenBrowser;
  const original = api.createRuntime;
  const keyword = api.keyword;
  api.createRuntime = (...args) => {
    const runtime = original(...args);
    return {run(entry, inputs) {
      const result = runtime.run(entry, inputs);
      if (entry === 'view' && String(inputs.inputs.get(keyword('surface'))).startsWith('compare-')) {
        result.set(keyword('tree'), [keyword('div'), new Map([[keyword('class'), 'personal-layout']]),
          [keyword('span'), 'Personal comparison']]);
      }
      return result;
    }};
  };
  try {
    f.ctx.gdDiffRenderInspectorSection(f.inspector, 'f');
    const panel = f.document.getElementById('gd-diff-insp');
    assert.equal(panel.querySelectorAll('.personal-layout').length, 3);
    assert.equal(panel.querySelectorAll('.branch-diff-comment-btn').length, 3);
    assert.equal(panel.querySelector('.gd-diff-insp-head').getAttribute('data-anchor-id'), 'f');
  } finally { api.createRuntime = original; }
});

test('legacy or invalid personal compare view falls back only inside Inspector section', () => {
  const f = fixture();
  const owner = f.ctx.gdInspectorGraph;
  owner.compareView = () => { throw new Error('legacy chrome-only view'); };
  f.ctx.gdDiffRenderInspectorSection(f.inspector, 'f');
  const panel = f.document.getElementById('gd-diff-insp');
  assert.equal(panel.querySelector('.bd-old').textContent, '1');
  assert.equal(panel.querySelectorAll('.branch-diff-comment-btn').length, 3);
  assert.deepEqual(f.counts(), {acquired: 0, released: 0});
  assert.equal(f.ctx.gdInspectorGraph, owner, 'the selected chrome runtime owner survives section fallback');
});

test('review and suggestion renderer keep their original DOM without the Inspector adapter', () => {
  const f = fixture();
  const review = f.ctx.gdDiffRenderGroup(f.group, {});
  const suggestion = f.ctx.gdDiffRenderGroup(f.group, {comments: false});
  assert.equal(review.querySelector('.branch-diff-entry[data-anchor-id="b"] .bd-old').textContent, '1');
  assert.equal(review.querySelectorAll('.branch-diff-comment-btn').length, 3);
  assert.equal(suggestion.querySelectorAll('.branch-diff-comment-btn').length, 0);
  assert.equal(review.querySelectorAll('.gd-diff-graph-host').length, 0);
});

test('review and suggestion lists share graph entry bodies while native anchors survive runtime replacement', () => {
  const f = fixture();
  const modal = f.document.createElement('div');
  const review = f.document.createElement('div');
  const suggestion = f.document.createElement('div');
  modal.appendChild(review);
  modal.appendChild(suggestion);
  f.document.body.appendChild(modal);
  f.ctx.gdDiffRenderGroups(review, [f.group]);
  f.ctx.gdDiffRenderGroups(suggestion, [f.group], {comments: false});
  assert.equal(review.querySelectorAll('.gd-diff-graph-host').length, 3);
  assert.equal(review.querySelector('.branch-diff-row-head .gd-diff-insp-graph-head .branch-diff-fn-name').textContent,
    '?', 'the reviewed function label comes from the graph head surface');
  assert.equal(review.querySelectorAll('.branch-diff-comment-btn').length, 3);
  assert.equal(suggestion.querySelectorAll('.branch-diff-comment-btn').length, 0);
  assert.equal(review.querySelector('.bd-old').textContent, '1');
  assert.equal(review.querySelector('.bd-new').textContent, '2');
  const row = review.querySelector('.branch-diff-entry[data-anchor-id="b"]');
  const button = row.querySelector('.branch-diff-comment-btn');
  const draft = f.document.createElement('textarea');
  row.appendChild(draft);
  f.document.activeElement = draft;
  const chrome = f.document.createElement('div');
  f.inspector.appendChild(chrome);
  f.ctx.gdInspectorGraph.render(chrome, {selected: true, name: 'demo', namespace: 'app',
    description: '', parents: [], 'has-return': false},
  {panel: f.document.createElement('div'), load() {}, close() {}, resume() {}});
  assert.equal(review.querySelector('.branch-diff-entry[data-anchor-id="b"]'), row);
  assert.equal(row.querySelector('.branch-diff-comment-btn'), button);
  assert.equal(row.querySelector('textarea'), draft);
  assert.equal(f.document.activeElement, draft);
  assert.equal(review.querySelectorAll('.gd-diff-graph-host[data-gd-ui-style]').length, 3);
  const beforeDispose = f.counts();
  f.ctx.gdDiffDisposeReviewWithin(modal);
  assert.equal(f.counts().released, beforeDispose.released + 2,
    'the two review surfaces release their sheets on dialog close');
});
