// Lesson 39: real worker retries, exact-message requeue, repair and ACK.
// Run: GRAPHDEN_REQUIRE_QUEUE_SERVICES=1 node tools/browser-test/edit-tutorial-tour-queues.test.js
// Required self-host/dedicated verification never skips. Shared-cloud plans
// may explicitly skip; failed availability reads and missing controls fail.
const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');
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
    const selector = page.locator('.service-popover.visible .service-popover-branch-select');
    if (await selector.count()) await selector.selectOption(branch.id);
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
    await page.goto(BASE + '/?tutorial=39');
    await waitTourTitle(page, 'A real background worker', 120000);
    assert(await clickTourButton(page, 'Next'), 'queue lesson introduction');
    await waitTourTitle(page, 'Open random-uuid');
    await filterAndSelect(page, 'core.random-uuid', 'random-uuid');
    await waitTourTitle(page, 'Choose an unused queue name');
    await openRun(page, 'random-uuid', false);
    const raw = '.execute-popover.visible .execute-result-raw pre';
    await page.waitForFunction(selector => {
      try { return /^[0-9a-f-]{36}$/i.test(JSON.parse(document.querySelector(selector)?.textContent)); }
      catch (_) { return false; }
    }, raw, {timeout: 30000});
    const queue = JSON.parse(await page.locator(raw).textContent());
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
    await waitTourTitle(page, 'Leave the message input unused');
    assert(await clickTourButton(page, 'Next'), 'retain the inherited parser default');

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
    await exact.dispatchEvent('click');
    await waitTourTitle(page, 'Open tutorial-queue-handler');
    const pending = await messageState(page, message.id);
    assert(pending.id === message.id && pending.state.replace(/^:/, '') === 'pending' && pending.attempts === 0,
      'Requeue retains the same identity and resets attempts while the worker is stopped');
    await backToGraph(page);
    await filterAndSelect(page, 'tutorial-queue-handler', 'tutorial-queue-handler');
    await waitTourTitle(page, 'Repair the handler');
    await editBoundValue(page, '{}');
    await waitTourTitle(page, 'Open tutorial-queue-worker');
    await filterAndSelect(page, 'tutorial-queue-worker', 'tutorial-queue-worker');
    await waitTourTitle(page, 'Restart the same service');
    await serviceSettings(page, true, service.id);
    await waitTourTitle(page, 'Stop the worker', 90000);
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
