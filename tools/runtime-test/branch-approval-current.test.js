'use strict';

// Use the production status renderer and action together: a stale recorded
// approval must POST a new stamp, while a current uncounted one can withdraw.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const document = createDocument();
const popover = document.createElement('div');
document.body.appendChild(popover);
const row = document.createElement('div');
row.className = 'branch-row'; row.dataset.branchId = 'source-uuid';
popover.appendChild(row);
const button = document.createElement('button');
button.className = 'branch-row-approve'; button.dataset.approveBranch = 'proposal';
button.insertAdjacentElement = (_position, badge) => row.appendChild(badge);
row.appendChild(button);
let status;
const writes = [];
const ctx = vm.createContext({console, document, API: {
  api_branches_ref_approvals: (id) => '/branches/' + id + '/approvals',
  api_branches_ref_approve: (id) => '/branches/' + id + '/approve',
}, authFetch: async (url, options) => {
  if (options) writes.push({url, method: options.method});
  return {ok: true, json: async () => options ? {ok: true} : status};
}});
ctx.window = ctx;
vm.runInContext(fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-branches.js'), 'utf8'), ctx);
vm.runInContext('openBranchPopover = () => {};', ctx);

(async () => {
  status = {mine: true, 'mine-current': true, required: 1, have: 1, satisfied: true};
  await ctx.populateReviewStatus(popover);
  assert.equal(button.dataset.approved, '1');
  status = {mine: true, 'mine-current': false, required: 1, have: 0, satisfied: false};
  await ctx.populateReviewStatus(popover);
  assert.equal(button.getAttribute('data-approved'), null, 'Old mine cannot override a stale current verdict');
  assert.equal(button.getAttribute('aria-pressed'), 'false');
  await ctx.approveProposal(button);
  assert.deepEqual(writes.at(-1), {url: '/branches/source-uuid/approve', method: 'POST'});

  status = {mine: true, 'mine-current': true, required: 1, have: 0, satisfied: false};
  await ctx.populateReviewStatus(popover);
  assert.equal(button.dataset.approved, '1', 'Current author approval remains withdrawable when not counted');
  await ctx.approveProposal(button);
  assert.equal(writes.at(-1).method, 'DELETE');

  status = {mine: true, required: 1, have: 1, satisfied: true};
  await ctx.populateReviewStatus(popover);
  assert.equal(button.dataset.approved, '1', 'Older API responses retain the legacy toggle');
  console.log('branch approval current stamp: renderer and POST/DELETE PASS');
})().catch((error) => {console.error(error); process.exitCode = 1;});
