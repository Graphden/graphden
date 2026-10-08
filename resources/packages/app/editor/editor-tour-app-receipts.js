// Tutorial Apps use ordinary HTMX forms and exact create-only receipts.
// graph-first-exception: browser request/receipt and ordered cleanup lifecycle.

async function _tourReadApps() {
  if (!window.API?.api_orgs_apps) throw new Error('Apps unavailable');
  const response = await authFetch(window.API.api_orgs_apps);
  if (!response.ok) throw new Error('Apps unavailable');
  const rows = await response.json();
  if (!Array.isArray(rows)) throw new Error('Invalid app list');
  return rows;
}

function _tourAppTupleMatches(entry, row) {
  return row?.id === entry.id && row.label === entry.label
    && row['handler-fn-id'] === entry['handler-fn-id'];
}

function gdTourBeginAppCreation(form) {
  const step = typeof _tourStep === 'function' ? _tourStep() : null;
  if (step?.creates?.type !== 'app-route' || !_tourPrincipalMatches(_tourState)) return null;
  const handler = _tourState.created.find(row => row.type === 'fn'
    && row.name === step.creates.name && row.id && row.receipt === 'created');
  const handlerId = form.querySelector('[name="handler-fn-id"]')?.value;
  const label = form.querySelector('[name="label"]')?.value?.trim();
  if (!handler || handler.id !== handlerId || !label || !_tourReceiptBranch()) return null;
  const previous = _tourReceiptEntry(form.gdTourAppTicket);
  if (previous?.receipt === 'pending' && previous.label === label
      && previous['handler-fn-id'] === handlerId) return form.gdTourAppTicket;
  const id = window.crypto.randomUUID();
  const ticket = _tourReceiptTicket('app-route', handler.name, {
    id, label, 'handler-fn-id': handlerId, 'branch-id': _tourReceiptBranch(),
    'branch-name': _tourSessionBranch(), creation: 'create-only-app-route',
    principal: {..._tourState.principal},
  });
  return ticket ? {...ticket, id} : null;
}

async function gdTourRecordAppCreation(ticket, xhr) {
  const entry = _tourReceiptEntry(ticket);
  if (entry?.type !== 'app-route') return;
  if (xhr.status >= 400 && xhr.status < 500) {
    // A definite create-only refusal establishes no ownership of that UUID.
    _tourState.created = _tourState.created.filter(row => row !== entry);
    _tourSaveState();
    return;
  }
  if (xhr.status < 200 || xhr.status >= 300
      || xhr.getResponseHeader('X-Graphden-Created-Id') !== entry.id) return;
  try {
    const rows = await _tourReadApps();
    if (_tourReceiptEntry(ticket) !== entry
        || !_tourAppTupleMatches(entry, rows.find(row => row.id === entry.id))) return;
    entry.receipt = 'created';
    _tourSaveState();
    if (typeof refreshAppRoutesCache === 'function') await refreshAppRoutesCache();
  } catch (_) { /* Retain the exact pending attempt for cleanup/retry. */ }
}

function gdTourAppCreationPasses(check) {
  if (!_tourPrincipalMatches(_tourState) || typeof getAppRoutesForFnId !== 'function') return false;
  return _tourState.created.some(entry => entry.type === 'app-route'
    && entry.name === check.name && entry.receipt === 'created'
    && entry['branch-id'] === _tourReceiptBranch()
    && getAppRoutesForFnId(entry['handler-fn-id']).some(row => _tourAppTupleMatches(entry, row)));
}

async function _tourAppForReceipt(entry) {
  if (!entry.id || !entry.label || !entry['handler-fn-id'] || !entry['branch-id']
      || !entry.principal || !_tourPrincipalMatches(entry)
      || (entry.receipt !== 'created' && !(entry.receipt === 'pending'
        && entry.creation === 'create-only-app-route'))) throw new Error('App receipt unavailable');
  const row = (await _tourReadApps()).find(candidate => candidate.id === entry.id);
  if (row && !_tourAppTupleMatches(entry, row)) throw new Error('App identity changed');
  return row || null;
}

async function _tourDeleteAppRoutes(created) {
  const failed = [];
  for (const entry of created.filter(row => row.type === 'app-route' && row.receipt !== 'removed')) {
    try {
      const app = await _tourAppForReceipt(entry);
      if (app) {
        if (!window.API?.api_orgs_apps_delete) throw new Error('App removal unavailable');
        const response = await authFetch(window.API.api_orgs_apps_delete, {
          method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'},
          body: new URLSearchParams({id: entry.id}).toString(),
        });
        if (!response.ok) throw new Error('App removal refused');
        await response.json();
        if (await _tourAppForReceipt(entry)) throw new Error('App removal unconfirmed');
      }
      entry.receipt = 'removed';
    } catch (_) { failed.push(entry); }
  }
  return failed;
}
