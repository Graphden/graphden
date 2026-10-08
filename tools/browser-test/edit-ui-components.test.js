// Native lesson25: Appearance creation, canonical typed values, local/shared
// dependencies, graph-owned keyboard behavior and exact receipt cleanup.
// GRAPHDEN_URL=http://localhost:<port> node tools/browser-test/edit-ui-components.test.js
'use strict';
const {chromium} = require('playwright');
const {newContext} = require('./edit-test-helpers');
const {walkUIComponentsLesson} = require('./tutorial-ui-components-helpers');
(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  const attempts = new Map();
  const stages = new Map([
    ['/api/ui/components/plan', 'component-plan'],
    ['/api/ui/theme/evaluate', 'theme-evaluate'],
    ['/api/ui/components/create/preview', 'create-preview'],
    ['/api/ui/components/create/apply', 'create-apply'],
  ]);
  const themeReasons = new Set(['not-plain-pure', 'evaluation-failed', 'result-unavailable', 'timeout', 'unavailable']);
  const refusalCodes = new Set(['policy-refresh-required', 'tainted-result', 'runtime-effects', 'not-plain-pure', 'graph-changed']);
  page.on('response', async response => {
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
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('dialog', dialog => dialog.accept());
  try {
    await walkUIComponentsLesson(page);
    console.log('PASS lesson 25 walkthrough completed; checking console');
    if (errors.length) throw new Error('Console errors: ' + errors.join('\n'));
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
