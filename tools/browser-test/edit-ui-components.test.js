// Native lesson25: Appearance creation, canonical typed values, local/shared
// dependencies, graph-owned keyboard behavior and exact receipt cleanup.
// GRAPHDEN_URL=http://localhost:<port> node tools/browser-test/edit-ui-components.test.js
'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const {unexpectedCleanupErrors} = require('./owned-cleanup-errors');
const {chromium} = require('playwright');
const {newContext} = require('./edit-test-helpers');
const {walkUIComponentsLesson} = require('./tutorial-ui-components-helpers');
(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  const attempts = new Map();
  const deletes = [];
  const batches = [];
  let ownedIds = new Set();
  const stages = new Map([
    ['/api/ui/components/plan', 'component-plan'],
    ['/api/ui/theme/evaluate', 'theme-evaluate'],
    ['/api/ui/components/create/preview', 'create-preview'],
    ['/api/ui/components/create/apply', 'create-apply'],
  ]);
  const themeReasons = new Set(['not-plain-pure', 'evaluation-failed', 'result-unavailable', 'timeout', 'unavailable']);
  const refusalCodes = new Set(['policy-refresh-required', 'tainted-result', 'runtime-effects', 'not-plain-pure', 'graph-changed']);
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/functions/delete-batch') {
      try { batches.push(JSON.parse(request.postData()).functions.map(row => row.id)); }
      catch (_) { batches.push(null); }
    }
  });
  page.on('response', async response => {
    if (response.request().method() === 'DELETE') deletes.push({url: response.url(), status: response.status()});
    const stage = stages.get(new URL(response.url()).pathname);
    if (!stage) return;
    const attempt = (attempts.get(stage) || 0) + 1;
    attempts.set(stage, attempt);
    if (response.status() !== 422) return;
    const body = await response.json().catch(() => null);
    const code = refusalCodes.has(body?.code) ? body.code : 'unclassified';
    const reason = stage === 'theme-evaluate' && themeReasons.has(body?.reason) ? body.reason : 'unclassified';
    console.log(JSON.stringify({diagnostic: 'ui-graph-refusal', stage, attempt, status: 422,
      code, retryable: body?.retryable === true, reason}));
  });
  page.on('pageerror', error => errors.push({kind: 'pageerror', text: error.message}));
  page.on('console', message => { if (message.type() === 'error') errors.push({kind: 'console', text: message.text(), url: message.location().url}); });
  page.on('dialog', dialog => dialog.accept());
  try {
    await walkUIComponentsLesson(page, {cleanupOnly: process.env.GRAPHDEN_COMPONENT_CLEANUP_ONLY === '1',
      onReceipt: async receipt => {
        const path = '/tmp/graphden-native25-ledger-' + process.pid + '.json';
        fs.writeFileSync(path, JSON.stringify(receipt), {mode: 0o600, flag: 'wx'});
        ownedIds = new Set(receipt.manifest.functions.map(row => row.id));
        console.log(JSON.stringify({diagnostic: 'owned-creation-receipt', path}));
      }});
    console.log('PASS lesson 25 walkthrough completed; checking console');
    assert.equal(batches.length, 1, 'the complete receipt uses one guarded batch');
    assert.deepEqual(new Set(batches[0]), ownedIds, 'the batch contains every exact owned function and no others');
    assert(!deletes.some(row => /^\/api\/entities\/fn\//.test(new URL(row.url).pathname)),
      'manifest cleanup does not issue per-function DELETE requests');
    if (unexpectedCleanupErrors(errors, deletes, ownedIds, true).length) throw new Error('Unexpected browser errors');
  } catch (error) {
    const frames = String(error.stack || '').split('\n').flatMap(line => {
      const match = line.match(/\/(tutorial-ui-components-helpers|edit-ui-components|tutorial-tour-helpers)\.js:(\d+):(\d+)\)?$/);
      return match ? [{file: match[1], line: Number(match[2]), column: Number(match[3])}] : [];
    });
    console.error(JSON.stringify({diagnostic: 'ui-graph-walk-failure',
      kind: error.name === 'TimeoutError' ? 'timeout' : 'failure', frames}));
    await page.screenshot({path: '/tmp/graphden-personal-ui-components-failure.png'}).catch(() => {});
    console.error('FAIL personal UI component walkthrough; error details withheld to avoid DOM dumps');
    process.exitCode = 1;
  } finally { await browser.close(); }
})();
