// Lesson 39: real worker retries, exact-message requeue, repair and ACK.
// Run: GRAPHDEN_REQUIRE_QUEUE_SERVICES=1 node tools/browser-test/edit-tutorial-tour-queues.test.js
// Required self-host/dedicated verification never skips. Shared-cloud plans
// may explicitly skip; failed availability reads and missing controls fail.
const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');
const {readQueueError} = require('./queue-error-diagnostic');
const {waitTourTitle, clickTourButton, filterAndSelect, extendViaRowActions,
  bindNamedPlaceholder, openRowActionsFor, editBoundValue, openOperateSection,
  tourWhere} = require('./tutorial-tour-helpers');

const REQUIRED = process.env.GRAPHDEN_REQUIRE_QUEUE_SERVICES === '1';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function receipt(page, type) {
  return page.evaluate(kind => {
    const row = [...(_tourState?.created || [])].reverse().find(item => item.type === kind);
    return row ? JSON.parse(JSON.stringify(row)) : null;
  }, type);
}

async function openRun(page, name, persist) {
  await openRowActionsFor(page, name, 30000, {root: true});
  await page.locator('.row-actions-popover [data-action="run-fn"]').dispatchEvent('click');
  await page.waitForSelector('.execute-popover.visible .execute-run-btn', {timeout: 30000});
  await page.evaluate(saved => {
    const pop = document.querySelector('.execute-popover.visible');
    const history = pop.querySelector('.execute-persist-checkbox');
    if (saved && history && !history.checked) history.click();
    const acknowledge = pop.querySelector('.execute-confirm-checkbox');
    if (acknowledge && !acknowledge.checked) acknowledge.click();
  }, persist);
  if (persist) assert(await page.locator('.execute-popover.visible .execute-persist-checkbox').isChecked(),
    'publisher Run retains the exact execution identity');
  await page.locator('.execute-popover.visible .execute-run-btn').dispatchEvent('click');
}

async function serviceSettings(page, enabled, existingId) {
  await openRowActionsFor(page, 'tutorial-queue-worker', 30000, {root: true});
  await page.locator('.row-actions-popover [data-action="service-settings"]').dispatchEvent('click');
  await page.waitForSelector('.service-popover.visible .service-popover-save-btn', {timeout: 30000});
  const actual = await page.locator('.service-popover.visible .service-popover-save-btn')
    .getAttribute('data-existing-service-id');
  assert(actual === (existingId || ''), 'service dialog addresses the exact owned service');
  if (!existingId) {
    const branch = await receipt(page, 'branch');
    assert(UUID.test(branch?.id || '') && branch.receipt === 'created', 'lesson owns an exact scratch branch');
    const dialogBranch = await page.evaluate(() => {
      const pop = document.querySelector('.service-popover.visible');
      const selector = pop.querySelector('.service-popover-branch-select');
      const note = [...pop.querySelectorAll('.service-popover-note')]
        .find(row => row.textContent.trim().startsWith('Branch: '));
      return {id: selector?.value, name: selector ? selector.selectedOptions[0]?.textContent.trim()
        : note?.textContent.trim().slice('Branch: '.length), readonly: !selector && !!note};
    });
    assert(dialogBranch.name === branch.name, 'service dialog retains the exact scratch branch label');
    assert(dialogBranch.readonly || dialogBranch.id === branch.id,
      'service dialog retains its captured branch UUID');
  }
  const toggle = page.locator('.service-popover.visible .service-popover-enabled');
  if (enabled) await toggle.check(); else await toggle.uncheck();
  await page.locator('.service-popover.visible .service-popover-save-btn').dispatchEvent('click');
  await page.waitForFunction(() => !document.querySelector('.service-popover.visible'), null,
    {timeout: 60000});
}

async function messageState(page, id) {
  return page.evaluate(async messageId => {
    const response = await authFetch('/partials/queues/message?message-id=' + encodeURIComponent(messageId));
    if (!response.ok) throw new Error('Exact message read refused');
    return response.json();
  }, id);
}

async function backToGraph(page) {
  await page.locator('#gd-brand-home').dispatchEvent('click');
}

async function cleanup(page, finish) {
  if (finish) assert(await clickTourButton(page, 'Finish'), 'finish the queue lesson');
  else await page.evaluate(async () => { if (_tourState) await _tourEnd(); });
  if (!finish && await page.evaluate(() => !_tourState)) return;
  await page.waitForSelector('#gd-tour-pop .gd-tour-btn', {timeout: 30000});
  const remove = page.locator('#gd-tour-pop .gd-tour-btn')
    .filter({hasText: /^(Delete them|Delete branch & return)$/});
  await remove.dispatchEvent('click');
  await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'), null, {timeout: 60000});
}

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', dialog => { void dialog.accept(); });
  let errors = 0;
  page.on('pageerror', () => { errors++; });
  let finished = false;
  try {
    // Read the actual plan; primeTenantPlan's tolerant failure fallback must
    // never turn an unavailable cloud endpoint into an apparent self-host.
    const plan = await page.evaluate(async () => {
      if (!window.graphdenTenancyActive?.()) return null;
      const response = await authFetch(API.api_orgs_quota);
      if (!response.ok) throw new Error('Service availability read refused');
      return (await response.json())?.plan || null;
    });
    if (plan && plan !== 'dedicated') {
      assert(!REQUIRED, 'required service gate has a dedicated executor');
      finished = true;
      console.log('SKIP lesson 39: shared-cloud plan has no persistent worker');
      return;
    }
    await page.goto(BASE + '/');
    await page.evaluate(() => window.openTutorialMenu());
    await page.locator('[data-lesson-id="39"] .gd-tour-btn-primary').dispatchEvent('click');
    await waitTourTitle(page, 'A real background worker', 120000);
    assert(await clickTourButton(page, 'Next'), 'queue lesson introduction');
    await waitTourTitle(page, 'Open random-uuid');
    await filterAndSelect(page, 'core.system.random-uuid', 'random-uuid');
    await waitTourTitle(page, 'Choose an unused queue name');
    const uuidFnId = await page.evaluate(() => selectedFnId);
    assert(UUID.test(uuidFnId || ''), 'UUID Run source has an exact selected identity');
    await openRun(page, 'random-uuid', false);
    const scalar = '.execute-popover.visible .execute-result-scalar';
    await page.waitForFunction(({selector, fnId}) => {
      const value = document.querySelector(selector);
      return value?.closest('.execute-result-host')?.gdExecutionFnId === fnId
        && /^[0-9a-f-]{36}$/i.test(value.textContent.trim());
    }, {selector: scalar, fnId: uuidFnId}, {timeout: 30000});
    const queue = (await page.locator(scalar).textContent()).trim();
    assert(UUID.test(queue), 'queue channel is the actual random-uuid Run result');
    assert(await clickTourButton(page, 'Next'), 'record the unused channel');

    await waitTourTitle(page, 'Open parse-json');
    await filterAndSelect(page, 'parse-json', 'parse-json');
    await waitTourTitle(page, 'Create tutorial-queue-handler');
    await extendViaRowActions(page, 'tutorial-queue-handler', 'parse-json');
    await waitTourTitle(page, 'Open tutorial-queue-handler');
    await filterAndSelect(page, 'tutorial-queue-handler', 'tutorial-queue-handler');
    await waitTourTitle(page, 'Bind :string');
    await bindNamedPlaceholder(page, 'string', 'literal', 'not JSON');
    await waitTourTitle(page, 'Bind :keywordize');
    await bindNamedPlaceholder(page, 'keywordize', 'literal', 'true');

    await waitTourTitle(page, 'Open pg-queue-consumer');
    await filterAndSelect(page, 'pg-queue-consumer', 'pg-queue-consumer');
    await waitTourTitle(page, 'Create tutorial-queue-worker');
    await extendViaRowActions(page, 'tutorial-queue-worker', 'pg-queue-consumer');
    await waitTourTitle(page, 'Open tutorial-queue-worker');
    await filterAndSelect(page, 'tutorial-queue-worker', 'tutorial-queue-worker');
    await waitTourTitle(page, 'Bind :queue');
    await bindNamedPlaceholder(page, 'queue', 'literal', queue);
    await waitTourTitle(page, 'Bind :handler');
    await bindNamedPlaceholder(page, 'handler', 'fn-ref', 'tutorial-queue-handler');
    await waitTourTitle(page, 'Start the worker');
    await serviceSettings(page, true);
    await waitTourTitle(page, 'Open queue-publish', 90000);
    const service = await receipt(page, 'service');
    assert(UUID.test(service?.id || '') && service.receipt === 'created', 'exact service creation receipt');

    await filterAndSelect(page, 'queue-publish', 'queue-publish');
    await waitTourTitle(page, 'Create tutorial-queue-publish');
    await extendViaRowActions(page, 'tutorial-queue-publish', 'queue-publish');
    await waitTourTitle(page, 'Open tutorial-queue-publish');
    await filterAndSelect(page, 'tutorial-queue-publish', 'tutorial-queue-publish');
    await waitTourTitle(page, 'Bind :queue');
    await bindNamedPlaceholder(page, 'queue', 'literal', queue);
    await waitTourTitle(page, 'Bind :payload');
    await bindNamedPlaceholder(page, 'payload', 'literal', '"original"');
    await waitTourTitle(page, 'Publish once and observe dead letter');
    await openRun(page, 'tutorial-queue-publish', true);
    await waitTourTitle(page, 'Open tutorial-queue-worker', 90000);
    const message = await receipt(page, 'queue-message');
    assert(UUID.test(message?.id || '') && UUID.test(message['execution-id'] || ''), 'persisted Run owns one exact message UUID');
    const dead = await messageState(page, message.id);
    assert(dead.id === message.id && dead.state.replace(/^:/, '') === 'dead' && dead.attempts === 5,
      'the exact message exhausted five real worker attempts');

    await filterAndSelect(page, 'tutorial-queue-worker', 'tutorial-queue-worker');
    await waitTourTitle(page, 'Stop before requeue');
    await serviceSettings(page, false, service.id);
    await waitTourTitle(page, 'Requeue the exact dead letter', 90000);
    assert((await page.evaluate(id => readServiceInstances(id), service.id)).count === 0,
      'no registered worker can take the requeued message');
    await openOperateSection(page, 'queues');
    const exact = page.locator('.gd-queues-panel button[hx-post="/partials/queues/requeue?message-id=' + message.id + '"]');
    await exact.waitFor({state: 'visible', timeout: 30000});
    assert((await exact.locator('xpath=ancestor::tr').textContent()).includes(queue), 'dead-letter control belongs to this fresh queue');
    const requeue = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/partials/queues/requeue'
      && new URL(response.url()).searchParams.get('message-id') === message.id,
    {timeout: 30000});
    await exact.click();
    assert((await requeue).status() === 200, 'the ordinary Requeue control returned an exact POST receipt');
    const pending = await messageState(page, message.id);
    assert(pending.id === message.id && pending.state.replace(/^:/, '') === 'pending' && pending.attempts === 0,
      'Requeue retains the same identity and resets attempts while the worker is stopped');
    try {
      await waitTourTitle(page, 'Open tutorial-queue-handler');
    } catch (error) {
      console.error('Exact pending gate flags: ' + JSON.stringify(await page.evaluate(id => {
        const entry = _tourState?.created.find(row => row.type === 'queue-message' && row.id === id);
        return {observedDead: !!entry?.observedDead, observedRequeue: !!entry?.observedRequeue,
          principalMatches: !!_tourState && _tourPrincipalMatches(_tourState),
          probePending: !!_tourServiceProbe?.pending, probePassed: !!_tourServiceProbe?.passed,
          probeOwnState: _tourServiceProbe?.state === _tourState, step: _tourState?.step};
      }, message.id)));
      throw error;
    }
    await backToGraph(page);
    await filterAndSelect(page, 'tutorial-queue-handler', 'tutorial-queue-handler');
    await waitTourTitle(page, 'Repair the handler');
    await editBoundValue(page, '{}');
    await waitTourTitle(page, 'Open tutorial-queue-worker');
    await filterAndSelect(page, 'tutorial-queue-worker', 'tutorial-queue-worker');
    await waitTourTitle(page, 'Restart the same service');
    await serviceSettings(page, true, service.id);
    try {
      if (process.env.GRAPHDEN_QUEUE_DIAGNOSTIC_DB_CONTAINER) {
        const deadline = Date.now() + 90000;
        while (Date.now() < deadline) {
          const diagnostic = readQueueError(message.id, queue);
          if (diagnostic.absent) break;
          if (diagnostic.errorPresent) {
            console.error('Exact repaired queue failure: ' + JSON.stringify(diagnostic));
            throw new Error('Repaired handler failed its first observed attempt');
          }
          await page.waitForTimeout(200);
        }
      }
      await waitTourTitle(page, 'Stop the worker', 90000);
    } catch (error) {
      console.error('Exact ACK gate flags: ' + JSON.stringify(await page.evaluate(async ({serviceId, messageId, executionId}) => {
        const entry = _tourState.created.find(row => row.type === 'service' && row.id === serviceId);
        const handler = _tourState.created.find(row => row.type === 'fn' && row.name === 'tutorial-queue-handler');
        const read = await authFetch('/partials/queues/message?message-id=' + encodeURIComponent(messageId));
        const row = read.ok ? await read.json() : null;
        const cache = await fetchServices();
        const serviceRow = cache?.services?.find(item => item.id === serviceId);
        const runRead = await authFetch(API.api_execute_id(executionId));
        const run = runRead.ok ? await runRead.json() : null;
        const bindings = lookups.bindingsByFn?.get(handler?.id) || [];
        const literal = name => bindings.find(binding => lookups.slotMap?.get(binding['slot-id'])?.name === name);
        const statuses = new Set(['succeeded', 'failed', 'running', 'pending', 'cancelled']);
        const codes = new Set(['execution/free-args', 'validation/type-mismatch', 'execution/type-mismatch', 'parse-json/invalid-json']);
        return {messageReadStatus: read.status, messageExact: row?.id === messageId,
          messageAbsent: !!row && Object.keys(row).length === 0,
          messageState: ['pending', 'dead'].includes(row?.state) ? row.state : 'other-or-absent', attempts: row?.attempts,
          desiredEnabled: !!serviceRow?.['enabled?'], runningCount: (await readServiceInstances(serviceId)).count,
          serviceBranchMatches: serviceRow?.['branch-id'] === entry?.['branch-id'],
          handlerStringRepaired: literal('string')?.value === '{}', handlerKeywordizeTrue: literal('keywordize')?.value === true,
          children: (run?.children || []).map(child => {
            const status = String(child.status).replace(/^:/, '');
            const code = child['error-type'];
            return {handlerMatches: child['fn-id'] === handler?.id,
              status: statuses.has(status) ? status : 'other', errorCode: codes.has(code) ? code : 'other-or-unavailable'};
          })};
      }, {serviceId: service.id, messageId: message.id, executionId: message['execution-id']})));
      throw error;
    }
    assert(Object.keys(await messageState(page, message.id)).length === 0, 'ACK removes the exact requeued message');
    const handled = await page.evaluate(async executionId => {
      const response = await authFetch(API.api_execute_id(executionId));
      if (!response.ok) throw new Error('Original publisher execution unavailable');
      const run = await response.json();
      const handler = _tourState.created.find(row => row.type === 'fn' && row.name === 'tutorial-queue-handler');
      return run.id === executionId && run.children?.some(child => child['fn-id'] === handler?.id
        && String(child.status).replace(/^:/, '') === 'succeeded');
    }, message['execution-id']);
    assert(handled, 'original publisher execution links the exact repaired handler success');
    await serviceSettings(page, false, service.id);
    await waitTourTitle(page, 'Finish and clean up', 90000);
    assert((await page.evaluate(id => readServiceInstances(id), service.id)).count === 0,
      'worker instances are gone before cleanup removes its graph');
    const branch = await receipt(page, 'branch');
    await cleanup(page, true);
    // Branch cleanup restores the main document asynchronously. Do not
    // read its service/branch lists from the document being replaced.
    await page.waitForURL(url => !url.searchParams.has('branch'),
      {waitUntil: 'domcontentloaded', timeout: 60000});
    await page.waitForSelector('#search-input', {timeout: 30000});
    const remaining = await page.evaluate(async ({serviceId, branchId}) => {
      const cache = await fetchServices();
      const response = await authFetch(API.api_branches);
      if (!response.ok || !Array.isArray(cache?.services)) throw new Error('Cleanup verification unavailable');
      const body = await response.json();
      const branches = Array.isArray(body) ? body : body.branches;
      return {service: cache.services.some(row => row.id === serviceId),
        branch: branches.some(row => row.id === branchId)};
    }, {serviceId: service.id, branchId: branch.id});
    assert(!remaining.service && !remaining.branch, 'exact service and scratch branch removed');
    assert(errors === 0, 'no browser exceptions');
    finished = true;
    console.log('PASS lesson 39: real retry/dead, same UUID requeue, graph repair, ACK and exact cleanup');
  } catch (error) {
    process.exitCode = 1;
    console.error('FAIL queue lesson: ' + error.message);
    console.error('Tour at failure: ' + await tourWhere(page).catch(() => 'unavailable'));
  } finally {
    if (!finished) await cleanup(page, false).catch(() => {
      process.exitCode = 1;
      console.error('Queue lesson cleanup incomplete; retain exact recovery metadata');
    });
    if (!finished) {
      const recovery = await page.evaluate(() => (_tourState?.created || []).map(row => ({
        type: row.type, id: row.id, name: row.name, receipt: row.receipt,
        'branch-id': row['branch-id'], 'branch-name': row['branch-name'],
        'base-branch-id': row['base-branch-id'], 'namespace-id': row['namespace-id'],
        'fn-id': row['fn-id'], 'execution-id': row['execution-id'],
      }))).catch(() => []);
      if (recovery.length) console.error('Exact queue lesson recovery: ' + JSON.stringify(recovery));
    }
    await browser.close();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
