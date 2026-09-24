'use strict';

// editor-branch-diff.js — the Suggestions section's "Δ what it changes"
// preview. Pinned: an error answer is reported as unavailable, not as
// "No differences." (an error body has no `groups` either), and the next
// expand asks again.
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-branch-diff.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

(async () => {
  const doc = createDocument();
  let viewReply = { ok: false, status: 500, json: async () => ({ ok: false, error: 'diff failed' }) };
  const views = [];
  const ctx = vm.createContext({
    console, Promise, JSON,
    document: doc,
    installTabTrap() {}, installPopoverDismiss() {},
    API: { api_branches: '/api/branches',
           api_branches_ref_diff_view: (ref) => '/api/branches/' + ref + '/diff-view' },
  });
  ctx.window = ctx;
  ctx.authFetch = async (url) => {
    if (url === '/api/branches') {
      return { ok: true, json: async () => ({ branches: [
        { id: 'b1', name: 'feature' },
        { id: 'b2', name: 'sugg', 'base-branch-id': 'b1', 'review-state': 'proposed' }] }) };
    }
    views.push(url);
    return viewReply;
  };
  vm.runInContext(SRC, ctx);

  console.log(' an error answer is "unavailable", and the next expand retries');
  const body = doc.createElement('div');
  doc.body.appendChild(body);
  await ctx.renderDiffSuggestions(body, 'feature', 'feature');
  const details = body.querySelector('details.bd-sugg-preview');
  assert(!!details, 'preview mounted');
  details.open = true;
  details.dispatch('toggle');
  await flush();
  const pv = details.children[1];
  assert(/unavailable/.test(pv.textContent) && !/No differences/.test(pv.textContent),
    'got "' + pv.textContent + '"');
  viewReply = { ok: true, status: 200, json: async () => ({ groups: [] }) };
  details.dispatch('toggle');
  await flush();
  assert(views.length === 2 && pv.textContent === 'No differences.', 'retried and rendered, got "' + pv.textContent + '"');

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
