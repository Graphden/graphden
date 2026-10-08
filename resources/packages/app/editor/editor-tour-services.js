// Lesson-owned service/message identities. The ordinary dialogs and Run API
// perform every mutation; this ledger never infers ownership from queue names.
// graph-first-exception: browser receipts, current-run correlation and cleanup.

function _tourOwnedServiceFn(name, fnId) {
  return _tourState?.created?.find(row => row.type === 'fn' && row.receipt === 'created' && row.name === name && row.id === fnId);
}

function gdTourBeginServiceCreation(fn, branchId) {
  if (_tourStep()?.creates?.type !== 'service' || _tourStep().creates.name !== fn.name) return null;
  if (!_tourOwnedServiceFn(fn.name, fn.id) || !_tourPrincipalMatches(_tourState)
      || !branchId || branchId !== _tourReceiptBranch()) throw new Error('Use this lesson’s exact function and branch.');
  const id = window.crypto.randomUUID();
  const ticket = _tourReceiptTicket('service', fn.name, {id, 'fn-id': fn.id,
    'branch-id': branchId, principal: {..._tourState.principal}});
  if (!ticket) throw new Error('Service creation step changed.');
  return {...ticket, id};
}

function gdTourServiceCreationResult(ticket, response) {
  const entry = _tourReceiptEntry(ticket);
  if (!entry) return;
  if (response?.ok) entry.receipt = 'created';
  else if (response?.status >= 400 && response.status < 500) entry.receipt = 'removed';
  _tourSaveState();
}

function gdTourBeginQueueRun(fnId, persist) {
  const step = _tourStep();
  if (step?.creates?.type !== 'queue-message') return null;
  if (!persist || !_tourOwnedServiceFn(step.creates.name, fnId)) {
    throw new Error('Select this lesson’s publisher and enable Persist before Run.');
  }
  return _tourReceiptTicket('queue-message', step.creates.name, {'fn-id': fnId,
    'branch-id': _tourReceiptBranch(), principal: {..._tourState.principal}});
}

function gdTourQueueRunResult(ticket, response, body) {
  const entry = _tourReceiptEntry(ticket);
  if (!entry) return;
  if (response?.ok && body?.['execution-id']) entry['execution-id'] = body['execution-id'];
  else if (response?.status >= 400 && response.status < 500) entry.receipt = 'removed';
  _tourSaveState();
}

function _tourAssertServiceOwner(entry) {
  if (!entry.principal || !_tourPrincipalMatches(entry)) throw new Error('Lesson owner changed');
}

async function _tourReadQueueMessage(entry) {
  _tourAssertServiceOwner(entry);
  if (!entry.id && entry['execution-id']) {
    const response = await authFetch(API.api_execute_id(entry['execution-id']));
    if (!response.ok) throw new Error('Recorded execution unavailable');
    const run = await response.json();
    _tourAssertServiceOwner(entry);
    if (run.id !== entry['execution-id'] || run['fn-id'] !== entry['fn-id']) throw new Error('Execution identity changed');
    if (String(run.status).replace(/^:/, '') !== 'succeeded') return {pending: true};
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(run.result || '')) throw new Error('Run did not return a message UUID');
    entry.id = run.result;
    entry.receipt = 'created';
    _tourSaveState();
  }
  if (!entry.id) return {pending: true}; // Lost POST reply is never recovered by latest/name.
  const response = await authFetch('/partials/queues/message?message-id=' + encodeURIComponent(entry.id));
  if (!response.ok) throw new Error('Message unavailable');
  const row = await response.json();
  _tourAssertServiceOwner(entry);
  if (row && Object.keys(row).length === 0) return null;
  if (row.id !== entry.id) throw new Error('Message identity changed');
  return row;
}

async function _tourReadOwnedService(entry) {
  _tourAssertServiceOwner(entry);
  const cache = await fetchServices();
  _tourAssertServiceOwner(entry);
  if (!Array.isArray(cache?.services)) throw new Error('Service list unavailable');
  const service = cache.services.find(row => row.id === entry.id);
  if (service && (service['fn-id'] !== entry['fn-id'] || service['branch-id'] !== entry['branch-id'])) {
    throw new Error('Service identity changed');
  }
  return service;
}

let _tourServiceProbe = null;
function gdTourServiceCheck(check) {
  if (!_tourState || !_tourPrincipalMatches(_tourState)) return false;
  const state = _tourState;
  const key = JSON.stringify([state.lessonId, state.step, check]);
  if (_tourServiceProbe?.key === key && _tourServiceProbe.state === state) {
    if (_tourServiceProbe.passed) return true;
    if (_tourServiceProbe.pending || Date.now() < _tourServiceProbe.next) return false;
  }
  const probe = {key, state, pending: true, passed: false};
  _tourServiceProbe = probe;
  void _tourProbeServiceStep(check, state).then(passed => {
    if (_tourServiceProbe === probe && _tourState === state && _tourPrincipalMatches(state)) probe.passed = passed;
  }).catch(() => {}).finally(() => { probe.pending = false; probe.next = Date.now() + 1000; });
  return false;
}

async function _tourProbeServiceStep(check, state) {
  const type = check.kind === 'queue-state' ? 'queue-message' : 'service';
  const entry = [...state.created].reverse().find(row => row.type === type && row.name === check.name
    && row.receipt !== 'removed');
  if (!entry) return false;
  if (type === 'queue-message') {
    const row = await _tourReadQueueMessage(entry);
    if (!row) {
      if (check.state !== 'acked' || !entry.observedDead || !entry.observedRequeue) return false;
      const handler = state.created.find(item => item.type === 'fn' && item.name === check.handler && item.id);
      const response = await authFetch(API.api_execute_id(entry['execution-id']));
      if (!response.ok) return false;
      const run = await response.json();
      return run.id === entry['execution-id'] && run['fn-id'] === entry['fn-id']
        && run.children?.some(child => child['fn-id'] === handler?.id
          && String(child.status).replace(/^:/, '') === 'succeeded');
    }
    const status = String(row.state).replace(/^:/, '');
    if (status === 'dead') entry.observedDead = true;
    if (entry.observedDead && status === 'pending' && row.attempts === 0) entry.observedRequeue = true;
    _tourSaveState();
    return status === check.state && (check.state !== 'pending' || entry.observedRequeue);
  }
  const service = await _tourReadOwnedService(entry);
  const {count} = await readServiceInstances(entry.id);
  return check.state === 'running' ? !!service?.['enabled?'] && count > 0
    : !!service && !service['enabled?'] && count === 0;
}

async function _tourCleanupServices(created) {
  const failed = [];
  for (const entry of created.filter(row => row.type === 'service' && row.receipt !== 'removed')) {
    try {
      if (!entry.id || !entry.principal || !_tourPrincipalMatches(entry)) throw new Error('Service owner changed');
      const service = await _tourReadOwnedService(entry);
      if (service?.['enabled?']) {
        const response = await saveService(entry.id, entry['fn-id'], {enabled: false,
          restartPolicy: service['restart-policy'], cardinality: service.cardinality,
          poolSize: service['pool-size'], branchId: entry['branch-id']});
        if (!response.ok) throw new Error('Stop refused');
        await reconcileServices();
      }
      // One bounded read: an active or unreachable executor retains its graph.
      // Retry cleanup after the reconciler removes the registered instance.
      if ((await readServiceInstances(entry.id)).count !== 0) throw new Error('Service still registered');
      _tourAssertServiceOwner(entry);
      if (service && !(await deleteService(entry.id)).ok) throw new Error('Delete refused');
      entry.receipt = 'removed';
    } catch (_) { failed.push(entry); }
  }
  if (failed.length) return failed;
  for (const entry of created.filter(row => row.type === 'queue-message' && row.receipt !== 'removed')) {
    try {
      if (!entry.principal || !_tourPrincipalMatches(entry)) throw new Error('Message owner changed');
      const row = await _tourReadQueueMessage(entry);
      if (row?.pending) throw new Error('Publish outcome unknown; retain for manual recovery');
      _tourAssertServiceOwner(entry);
      if (row && !(await authFetch(API.api_entities_type_id('queue-message', entry.id), {method: 'DELETE'})).ok) {
        throw new Error('Message delete refused');
      }
      entry.receipt = 'removed';
    } catch (_) { failed.push(entry); }
  }
  _tourSaveState();
  return failed;
}
