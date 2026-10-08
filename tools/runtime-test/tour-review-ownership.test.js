'use strict';

// Review steps require a saved anchored comment. Branch cleanup uses the
// creation UUID retained across reloads, never a replacement with its name.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const editor = path.join(__dirname, '../../resources/packages/app/editor');
const saved = [];
const requests = [];
const document = createDocument();
let rows = [];
let accessible = true;
const ctx = vm.createContext({console, document, URLSearchParams,
  location: {search: '?branch=tutorial-feature'},
  localStorage: {setItem: (_key, value) => saved.push(JSON.parse(value))},
  API: {api_branches: '/branches', api_branches_ref: (id) => '/branches/' + id},
  lookups: {fnMap: new Map([['owner', {id: 'owner', name: 'review-demo'}]])},
  authFetch: async (url, options) => {
    requests.push({url, method: options?.method || 'GET'});
    return {ok: accessible, json: async () => url === '/branches' ? {branches: rows} : {ok: true}};
  },
});
ctx.window = ctx;
for (const file of ['editor-tour.js', 'editor-tour-session.js', 'editor-tour-checks.js', 'editor-tour-cleanup.js']) {
  vm.runInContext(fs.readFileSync(path.join(editor, file), 'utf8'), ctx);
}
vm.runInContext(`
  _tourLessons = {lessons: [{id: '24', steps: [{creates: {type: 'branch', name: 'tutorial-feature'}}]}]};
  _tourState = {lessonId: '24', step: 0, created: [], cleanupBranch: 'main',
    principal: {accountId: null, orgId: null}};
`, ctx);

(async () => {
  const check = {kind: 'created-branch', name: 'tutorial-feature'};
  assert.equal(ctx._tourCheckPasses(check), false, 'A preexisting branch cannot claim tutorial creation');
  ctx.gdTourRecordBranchCreation({id: 'foreign', name: 'different', 'base-branch-id': 'base'});
  ctx.gdTourRecordBranchCreation({id: 'root', name: 'tutorial-feature', 'base-branch-id': null});
  assert.equal(saved.length, 0);
  const created = {id: 'created-id', name: 'tutorial-feature', 'base-branch-id': 'owned-base'};
  ctx.gdTourRecordBranchCreation(created);
  ctx.gdTourRecordBranchCreation(created);
  assert.equal(saved.length, 1, 'The actual response is persisted once before reload');
  assert.equal(saved[0].cleanupBranch, 'main');
  assert.equal(ctx._tourCheckPasses(check), true);
  ctx.restored = JSON.parse(JSON.stringify(saved[0]));
  vm.runInContext('_tourState = restored;', ctx);
  assert.equal(ctx._tourCheckPasses(check), true, 'Reload retains creation ownership');

  rows = [{...created, id: 'replacement'}];
  assert.equal(await ctx._tourDeleteOwnedBranch(saved[0].created[0]), true);
  assert.equal(requests.some((request) => request.method === 'DELETE'), false,
    'A same-named replacement is never deleted');
  rows = [{...created, name: 'renamed'}];
  assert.equal(await ctx._tourDeleteOwnedBranch(saved[0].created[0]), false);
  rows = [{...created, 'base-branch-id': 'different-base'}];
  assert.equal(await ctx._tourDeleteOwnedBranch(saved[0].created[0]), false);
  rows = [created];
  accessible = false;
  assert.equal(await ctx._tourDeleteOwnedBranch(saved[0].created[0]), false);
  accessible = true;
  assert.equal(await ctx._tourDeleteOwnedBranch(saved[0].created[0]), true);
  assert.equal(requests.at(-1).url, '/branches/created-id');
  assert.equal(requests.at(-1).method, 'DELETE');

  const thread = document.createElement('div');
  thread.className = 'branch-diff-anchor-thread';
  thread.dataset.anchorName = 'fn'; thread.dataset.anchorId = 'owner';
  document.body.appendChild(thread);
  const comment = document.createElement('div');
  comment.className = 'branch-comment';
  let visible = true;
  comment.getBoundingClientRect = () => ({width: visible ? 100 : 0, height: visible ? 20 : 0});
  const body = document.createElement('span');
  body.className = 'branch-comment-body'; body.textContent = 'Saved reply';
  comment.appendChild(body); thread.appendChild(comment);
  const replyCheck = {kind: 'review-comment', name: 'review-demo', text: 'Saved reply'};
  assert.equal(ctx._tourCheckPasses(replyCheck), false, 'An unsaved draft has no server comment identity');
  comment.dataset.commentId = 'server-comment-id';
  assert.equal(ctx._tourCheckPasses(replyCheck), true);
  thread.dataset.anchorId = 'other-owner';
  assert.equal(ctx._tourCheckPasses(replyCheck), false, 'A reply on another function cannot complete this step');
  thread.dataset.anchorId = 'owner'; visible = false;
  assert.equal(ctx._tourCheckPasses(replyCheck), false, 'An invisible stale thread is not the reader’s answer');
  console.log('tour review: saved reply and branch ownership PASS');
})().catch((error) => {console.error(error); process.exitCode = 1;});
