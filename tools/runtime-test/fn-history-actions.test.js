'use strict';

// Both history hosts mount the same server partial and bind navigation/restore.
// Restore revalidates current fields before writing. No browser or JVM.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const doc = createDocument();
const processed = [];
const switched = [];
const requests = [];
let needed = false;
let refreshed = 0;
const ctx = vm.createContext({ console, document: doc, URLSearchParams,
  API: { api_fns_fn_id_versions: (id) => '/versions/' + id,
    api_entities_type_id: (_type, id) => '/fn/' + id },
  switchToBranch: (branch) => switched.push(branch),
  getCurrentBranchName: () => 'main',
  confirm: () => true,
  alert: (message) => { throw new Error(message); },
  loadGraphData: () => { refreshed += 1; },
});
ctx.window = ctx;
ctx.htmx = { process: (host) => processed.push(host) };
ctx.authFetch = async (url, options) => {
  requests.push({ url, options });
  return { ok: true, json: async () => ({ versions: [{ id: 'older', 'restore-needed?': needed,
    description: 'historic', 'branch-name': 'feature', 'created-at': '2026-10-08 09:00' }] }) };
};
vm.runInContext(fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-fn-versions.js'), 'utf8'), ctx);

(async () => {
  const host = doc.createElement('div');
  // mini-dom does not parse HTML; these are the server partial's action hooks.
  const switchButton = doc.createElement('button');
  switchButton.setAttribute('data-switch-to-branch', 'feature');
  const restore = doc.createElement('button');
  restore.className = 'fn-versions-restore';
  restore.setAttribute('data-fn-version-id', 'older');
  const current = doc.createElement('button');
  current.className = 'fn-versions-restore';
  current.disabled = true;
  host.appendChild(switchButton); host.appendChild(restore); host.appendChild(current);
  ctx.mountFnVersionsContent(host, 'server partial', { id: 'owner', name: 'Owner' });
  assert.equal(host.innerHTML, 'server partial');
  assert.deepEqual(processed, [host]);
  const click = (button) => button.dispatchEvent({ type: 'click', stopPropagation() {} });
  click(switchButton);
  assert.deepEqual(switched, ['feature'], 'Switch is wired in the shared host');
  click(current);
  assert.equal(requests.length, 0, 'Current-state restore is not wired');
  click(restore);
  for (let i = 0; i < 5; i += 1) await Promise.resolve();
  assert.equal(requests.length, 1, 'A historic row matching current fields performs no PUT');
  needed = true;
  await ctx.restoreFnVersion({ id: 'owner', name: 'Owner' }, 'older');
  const put = requests.find((request) => request.options?.method === 'PUT');
  assert.equal(put.url, '/fn/owner');
  assert.equal(put.options.body, 'description=historic', 'Only historic function fields are written');
  assert.equal(refreshed, 1, 'Successful restore refreshes live graph data');
  let finishSlow;
  let calls = 0;
  ctx.authFetch = () => ++calls === 1
    ? new Promise((resolve) => { finishSlow = resolve; })
    : Promise.resolve({ok: true, text: async () => 'newer history'});
  const fn = {id: 'owner', name: 'Owner'};
  const slow = ctx.showFnVersionsPopover(fn, null);
  await ctx.showFnVersionsPopover(fn, null);
  finishSlow({ok: false, status: 503});
  await slow;
  assert.equal(doc.getElementById('fn-versions-popover').innerHTML, 'newer history',
    'A late error from the same function cannot replace a newer history request');
  console.log('shared function history actions: PASS');
})().catch((error) => { console.error(error); process.exitCode = 1; });
