// Native lesson25: Appearance creation, canonical typed values, local/shared
// dependencies, graph-owned keyboard behavior and exact receipt cleanup.
// GRAPHDEN_URL=http://localhost:<port> node tools/browser-test/edit-ui-components.test.js
'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const {unexpectedSelectiveCopyErrors, components} = require('./selective-copy-errors');
const {unexpectedThemeRetryErrors} = require('./theme-retry-errors');
const {preparePickerOnlySelection, restorePickerOnlySelection} = require('./partial-ui-components-fixture');
const {unexpectedCleanupErrors} = require('./owned-cleanup-errors');
const {chromium} = require('playwright');
const {newContext} = require('./edit-test-helpers');
const {walkUIComponentsLesson} = require('./tutorial-ui-components-helpers');
(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  let priorFixture;
  try {
    await preparePickerOnlySelection(page, fixture => {
      priorFixture = fixture;
      fs.writeFileSync('/tmp/graphden-native25-prior-ledger-' + process.pid + '.json', JSON.stringify(fixture), {mode: 0o600});
    });
    const boot = await page.evaluate(() => {
      const scripts = [...document.scripts].map(script => script.src);
      return {limit: window.GraphdenAtomicFunctionBatchMax,
        routes: scripts.findIndex(src => src.includes('/assets/api-routes.js')),
        editor: scripts.findIndex(src => src.includes('/assets/editor.js'))};
    });
    assert.equal(boot.limit, 2048, 'the browser receives the server-owned atomic cohort budget');
    assert(boot.routes >= 0 && boot.editor > boot.routes,
      'the generated API/budget script loads before its editor consumers');
  } catch (error) {
    try { await restorePickerOnlySelection(page, priorFixture); }
    finally { await browser.close(); }
    throw error;
  }
  const errors = [];
  const planReplies = [];
  const themeReplies = [];
  const pendingReplies = [];
  const requestSequences = new WeakMap();
  let sequence = 0;
  const attempts = new Map();
  const deletes = [];
  const batches = [];
  const undos = [];
  let cleanupCommitted = false;
  let ownedIds = new Set();
  const stages = new Map([
    ['/api/ui/configuration/plan', 'component-plan'],
    ['/api/ui/theme/evaluate', 'theme-evaluate'],
    ['/api/ui/components/create/preview', 'create-preview'],
    ['/api/ui/components/create/apply', 'create-apply'],
  ]);
  const themeReasons = new Set(['not-plain-pure', 'evaluation-failed', 'result-unavailable', 'timeout', 'unavailable']);
  const refusalCodes = new Set(['policy-refresh-required', 'component-missing', 'tainted-result', 'runtime-effects', 'not-plain-pure', 'graph-changed']);
  page.on('request', request => {
    requestSequences.set(request, ++sequence);
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/ui/configuration/undo') {
      try { undos.push(request.postDataJSON().functions.map(row => row.id)); }
      catch (_) { undos.push(null); }
    }
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/functions/delete-batch') {
      try { batches.push(JSON.parse(request.postData()).functions.map(row => row.id)); }
      catch (_) { batches.push(null); }
    }
  });
  const captureResponse = async response => {
    if (response.request().method() === 'DELETE') deletes.push({url: response.url(), status: response.status()});
    if (new URL(response.url()).pathname === '/api/ui/configuration/undo' && response.ok()) cleanupCommitted = true;
    const stage = stages.get(new URL(response.url()).pathname);
    if (!stage) return;
    const attempt = (attempts.get(stage) || 0) + 1;
    attempts.set(stage, attempt);
    const body = await response.json().catch(() => null);
    if (stage === 'component-plan') {
      const command = response.request().postDataJSON();
      planReplies.push({kind: 'plan', url: response.url(), component: command?.component,
        sequence: requestSequences.get(response.request()), status: response.status(),
        code: body?.code, fallback: body?.fallback, retryable: body?.retryable === true, ok: body?.ok === true});
    }
    if (stage === 'theme-evaluate') {
      const command = response.request().postDataJSON();
      themeReplies.push({url: response.url(), sequence: requestSequences.get(response.request()),
        identity: typeof command?.['fn-id'] === 'string' && typeof command?.org === 'string'
          && typeof command?.owner === 'string' && typeof response.request().headers()['x-graphden-branch'] === 'string'
          ? JSON.stringify([command['fn-id'], command.org, command.owner,
            response.request().headers()['x-graphden-branch']]) : null,
        status: response.status(), code: body?.code, reason: body?.reason,
        retryable: body?.retryable === true, ok: body?.ok === true, payload: !!body?.payload});
    }
    if (stage.startsWith('create-') && !body?.ok) {
      console.log(JSON.stringify({diagnostic: 'ui-creation-refusal', stage, attempt,
        status: response.status(), ok: body?.ok === true,
        committed: typeof body?.committed === 'boolean' ? body.committed : null}));
    }
    if (response.status() !== 422) return;
    const code = refusalCodes.has(body?.code) ? body.code : 'unclassified';
    const reason = stage === 'theme-evaluate' && themeReasons.has(body?.reason) ? body.reason : 'unclassified';
    const component = stage === 'component-plan' ? response.request().postDataJSON()?.component : undefined;
    console.log(JSON.stringify({diagnostic: 'ui-graph-refusal', stage, attempt, status: 422,
      code, retryable: body?.retryable === true, reason,
      component: components.has(component) ? component : undefined,
      fallback: body?.fallback === 'builtin' ? 'builtin' : undefined}));
  };
  page.on('response', response => pendingReplies.push(captureResponse(response)));
  page.on('pageerror', error => errors.push({kind: 'pageerror', text: error.message}));
  page.on('console', message => { if (message.type() === 'error') errors.push({kind: 'console', text: message.text(), url: message.location().url}); });
  page.on('dialog', dialog => dialog.accept());
  const forcePolicyRetry = process.env.GRAPHDEN_POLICY_RETRY_PROOF === '1';
  const forcedPolicyRetries = new Set();
  const forcedCleanupPolicyRetries = new Set();
  const proofComponents = new Set(['account-menu', 'tutorial-catalog']);
  await page.route(url => url.pathname.endsWith('/ui/configuration/plan'), async route => {
    const request = route.request();
    const component = request.method() === 'POST' ? request.postDataJSON()?.component : undefined;
    const forced = cleanupCommitted ? forcedCleanupPolicyRetries : forcedPolicyRetries;
    if ((cleanupCommitted || forcePolicyRetry) && proofComponents.has(component) && !forced.has(component)) {
      forced.add(component);
      await route.fulfill({status: 422, contentType: 'application/json',
        body: JSON.stringify({ok: false, code: 'policy-refresh-required', retryable: true})});
    } else await route.continue();
  });
  try {
    await walkUIComponentsLesson(page, {cleanupOnly: process.env.GRAPHDEN_COMPONENT_CLEANUP_ONLY === '1',
      onStagedReceipt: async receipt => {
        const path = '/tmp/graphden-native25-staged-ledger-' + process.pid + '.json';
        fs.writeFileSync(path, JSON.stringify(receipt), {mode: 0o600, flag: 'wx'});
        console.log(JSON.stringify({diagnostic: 'staged-creation-receipt', path}));
      },
      onReceipt: async receipt => {
        const path = '/tmp/graphden-native25-ledger-' + process.pid + '.json';
        fs.writeFileSync(path, JSON.stringify(receipt), {mode: 0o600, flag: 'wx'});
        ownedIds = new Set(receipt.manifest.functions.map(row => row.id));
        console.log(JSON.stringify({diagnostic: 'owned-creation-receipt', path}));
      }});
    console.log('PASS lesson 25 walkthrough completed; checking console');
    assert.equal(undos.length, 1, 'the inherited component uses one atomic reviewed undo');
    assert.deepEqual(new Set(undos[0]), ownedIds, 'undo contains every exact lesson function and no others');
    assert.equal(batches.length, 0, 'the lesson does not need a separate deletion transaction');
    assert(!deletes.some(row => /^\/api\/entities\/fn\//.test(new URL(row.url).pathname)),
      'manifest cleanup does not issue per-function DELETE requests');
    await Promise.all(pendingReplies);
    assert.deepEqual(forcedCleanupPolicyRetries, proofComponents,
      'both loaded components recover an exact policy refusal after the undo commit');
    if (forcePolicyRetry) {
      assert.deepEqual(forcedPolicyRetries, proofComponents,
        'the targeted proof forced both known component policy refusals');
      for (const component of proofComponents) {
        assert(planReplies.some(reply => reply.component === component && reply.status === 422 && reply.retryable),
          'the harness observed the ' + component + ' policy refusal');
      }
      assert(errors.filter(error => error.kind === 'console' && error.text.includes('status of 422')).length >= proofComponents.size,
        'the browser emitted both network-console errors under test');
    }
    const behaviorVerified = process.env.GRAPHDEN_COMPONENT_CLEANUP_ONLY !== '1';
    const unexpected = unexpectedCleanupErrors(unexpectedThemeRetryErrors(
      unexpectedSelectiveCopyErrors(errors, planReplies, behaviorVerified), themeReplies, behaviorVerified), deletes, ownedIds, true);
    if (unexpected.length) {
      console.error(JSON.stringify({diagnostic: 'unexpected-ui-console', errors: unexpected.map(error => ({
        kind: error.kind, stage: stages.get(new URL(error.url || 'http://unknown').pathname) || 'other',
        networkStatus: Number(error.text?.match(/^Failed to load resource: the server responded with a status of (\d+)/)?.[1]) || null,
      }))}));
      throw new Error('Unexpected browser errors');
    }
  } catch (error) {
    const frames = String(error.stack || '').split('\n').flatMap(line => {
      const match = line.match(/\/(tutorial-ui-components-helpers|edit-ui-components(?:\.test)?|tutorial-tour-helpers)\.js:(\d+):(\d+)\)?$/);
      return match ? [{file: match[1], line: Number(match[2]), column: Number(match[3])}] : [];
    });
    console.error(JSON.stringify({diagnostic: 'ui-graph-walk-failure',
      kind: error.name === 'TimeoutError' ? 'timeout' : 'failure', frames}));
    await page.screenshot({path: '/tmp/graphden-personal-ui-components-failure.png'}).catch(() => {});
    console.error('FAIL personal UI component walkthrough; error details withheld to avoid DOM dumps');
    process.exitCode = 1;
  } finally {
    try { await restorePickerOnlySelection(page, priorFixture); }
    finally { await browser.close(); }
  }
})().catch(() => { console.error('FAIL personal UI fixture; details withheld'); process.exitCode = 1; });
