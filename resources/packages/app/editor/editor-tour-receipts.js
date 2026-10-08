// Tutorial creation receipts survive branch reloads and lost mutation replies.
// graph-first-exception: local lesson ownership/persistence; mutations remain
// the ordinary editor actions. A matching name is never a creation receipt.

function _tourReceiptBranch() {
  const name = _tourSessionBranch();
  return _tourState?.created?.find(row => row.type === 'branch' && row.name === name && row.id)?.id
    || (_tourState?.sandboxBranch === name ? _tourState.sandboxBranchId : null) || null;
}

function _tourReceiptTicket(type, name, fields = {}) {
  const step = _tourStep();
  if (!_tourState || !_tourPrincipalMatches(_tourState)
      || step?.creates?.type !== type || step.creates.name !== name
      || (step.creates.version && step.creates.version !== fields.version)) return null;
  const token = 'receipt-' + Date.now() + '-' + Math.random().toString(36).slice(2);
  const entry = {type, name, ...fields, receipt: 'pending', token};
  _tourState.created.push(entry);
  _tourSaveState();
  return {token, lessonId: _tourState.lessonId, principal: {..._tourState.principal}};
}

function _tourReceiptEntry(ticket) {
  if (!ticket || !_tourState || _tourState.lessonId !== ticket.lessonId
      || !_tourPrincipalMatches(_tourState) || !_tourPrincipalMatches(ticket)) return null;
  return _tourState.created.find(row => row.token === ticket.token) || null;
}

function gdTourBeginEntityCreation(type, name, fields) {
  return _tourReceiptTicket(type, name, fields);
}

function gdTourRecordEntityCreation(ticket, response) {
  const entry = _tourReceiptEntry(ticket);
  const id = response?.headers?.get('X-Graphden-Created-Id');
  if (!entry || !response?.ok || !id) return false;
  Object.assign(entry, {id, receipt: 'created'});
  _tourSaveState();
  return true;
}

async function _tourReadBranchBase(baseRef) {
  const response = await authFetch(API.api_branches);
  if (!response.ok) throw new Error('Branch creation context unavailable');
  const body = await response.json();
  const rows = Array.isArray(body) ? body : body?.branches;
  const base = rows?.find(row => row.id === baseRef || row.name === baseRef);
  if (!base?.id || !base.name) throw new Error('Base branch unavailable');
  return base;
}

async function gdTourBeginBranchCreation(name, baseRef) {
  const state = _tourState;
  const step = _tourStep();
  if (!state || !_tourPrincipalMatches(state) || step?.creates?.type !== 'branch'
      || step.creates.name !== name) return null;
  const base = await _tourReadBranchBase(baseRef);
  if (_tourState !== state || !_tourPrincipalMatches(state)) throw new Error('Branch creation context changed');
  const id = window.crypto.randomUUID();
  const ticket = _tourReceiptTicket('branch', name, {id, 'base-branch-id': base.id,
    'base-branch-name': base.name});
  if (!ticket) throw new Error('Branch creation step changed');
  return {...ticket, branchId: id, baseBranchId: base.id};
}

function gdTourRejectBranchCreation(ticket, response, body) {
  const entry = _tourReceiptEntry(ticket);
  if (entry?.type !== 'branch' || entry.receipt !== 'pending'
      || !((response?.status >= 400 && response.status < 500) || (response?.ok && body?.ok === false))) return;
  // A definite pre-write rejection gives no ownership; a transport/500 failure
  // can follow a commit, so its exact pending UUID must remain recoverable.
  _tourState.created = _tourState.created.filter(row => row !== entry);
  _tourSaveState();
}

function gdTourRecordBranchReceipt(ticket, branch) {
  const entry = _tourReceiptEntry(ticket);
  if (entry?.type !== 'branch' || !branch?.id || !branch['base-branch-id']
      || entry.id !== branch.id || entry['base-branch-id'] !== branch['base-branch-id']
      || entry.name !== branch.name) return false;
  Object.assign(entry, {id: branch.id, 'base-branch-id': branch['base-branch-id'], receipt: 'created'});
  _tourSaveState();
  return true;
}

function gdTourBeginPackagePublish(command) {
  const source = command['ns-root'];
  const namespace = source ? _tourState?.created?.find(row => row.type === 'ns'
    && row.name === source && row.id && row.receipt === 'created') : null;
  if (source && !namespace) return null;
  return _tourReceiptTicket('package-version', command.name, {version: command.version,
    ...(source ? {'ns-root': source, 'source-namespace-id': namespace.id} : {})});
}

function gdTourRecordPackagePublish(ticket, body) {
  const entry = _tourReceiptEntry(ticket);
  if (!entry || body?.ok !== true || !body.id || !body['content-hash']
      || body.name !== entry.name || body.version !== entry.version) return false;
  Object.assign(entry, {id: body.id, 'content-hash': body['content-hash'], receipt: 'created'});
  _tourSaveState();
  return true;
}

function _tourOwnedPublishedVersion(name, version, id, hash) {
  return _tourState?.created?.find(row => row.type === 'package-version'
    && row.name === name && row.version === version && row.id === id
    && row['content-hash'] === hash && row.receipt === 'created') || null;
}

function gdTourBeginPackageInstall(command) {
  if (!_tourState || !_tourPrincipalMatches(_tourState)) return null;
  const branchId = _tourReceiptBranch();
  // Only the tutorial's own fresh branch can grant pin cleanup ownership.
  if (!branchId || !_tourState.created.some(row => row.type === 'package-version'
    && row.name === command.name && row.version === command.version && row.receipt === 'created')) return null;
  const token = 'install-' + Date.now() + '-' + Math.random().toString(36).slice(2);
  _tourState.created.push({type: 'package-install', name: command.name, version: command.version,
    'branch-id': branchId, 'branch-name': _tourSessionBranch(), receipt: 'pending', token});
  _tourSaveState();
  return {token, lessonId: _tourState.lessonId, principal: {..._tourState.principal}};
}

function gdTourRecordPackageInstall(ticket, body) {
  const entry = _tourReceiptEntry(ticket);
  const pin = body?.pin;
  const version = body?.version || body?.to;
  if (!entry || body?.ok !== true || pin?.['branch-id'] !== entry['branch-id']
      || pin['package-name'] !== entry.name || pin.version !== version || !pin.id
      || version !== entry.version || !_tourOwnedPublishedVersion(entry.name, version,
        body['package-version-id'], body['content-hash'])) return false;
  const namespaces = body['created-namespaces'];
  if (!Array.isArray(namespaces) || namespaces.some(row => !row?.id || !row.name
      || !Object.hasOwn(row, 'parent-id'))) return false;
  Object.assign(entry, {id: pin.id, receipt: 'created',
    'package-version-id': body['package-version-id'], 'content-hash': body['content-hash']});
  for (const row of namespaces) {
    if (!_tourState.created.some(existing => existing.type === 'ns' && existing.id === row.id)) {
      _tourState.created.push({type: 'ns', ...row, receipt: 'created',
        'branch-id': entry['branch-id'], materialized: true,
        'package-name': entry.name, version: entry.version});
    }
  }
  // Pin updates preserve their UUID; retain one current receipt, alongside all
  // independently owned releases and namespace creations.
  _tourState.created = _tourState.created.filter(row => row === entry || row.type !== 'package-install'
    || row.id !== entry.id || row['branch-id'] !== entry['branch-id']);
  _tourSaveState();
  return true;
}

function _tourPackageRequest(event) {
  const config = event.detail?.requestConfig;
  if (!config) return null;
  const path = config.path || event.detail?.pathInfo?.requestPath || '';
  const url = new URL(path, location.href);
  if (![API.api_packages_panel_install, API.api_packages_panel_update].includes(url.pathname)) return null;
  const parameters = config.parameters || {};
  return {name: url.searchParams.get('name') || parameters.name,
    version: url.searchParams.get('version') || parameters.version};
}

if (typeof document !== 'undefined') {
  document.addEventListener('htmx:beforeRequest', event => {
    try {
      const command = _tourPackageRequest(event);
      if (command && event.detail?.xhr) event.detail.xhr.gdTourReceipt = gdTourBeginPackageInstall(command);
    } catch (_) { /* An unrelated/invalid request cannot grant ownership. */ }
  });
  document.addEventListener('htmx:afterRequest', event => {
    const xhr = event.detail?.xhr;
    if (!xhr?.gdTourReceipt || xhr.status < 200 || xhr.status >= 300) return;
    try {
      const encoded = xhr.getResponseHeader('X-Graphden-Package-Receipt');
      const recorded = gdTourRecordPackageInstall(xhr.gdTourReceipt,
        JSON.parse(decodeURIComponent(encoded.replace(/\+/g, ' '))));
      if (recorded && typeof initGraph === 'function') void initGraph().catch(() => {});
    } catch (_) { /* Keep the pending receipt visible for explicit recovery. */ }
  });
}

Object.assign(window, {gdTourBeginEntityCreation, gdTourRecordEntityCreation,
  gdTourBeginBranchCreation, gdTourRecordBranchReceipt, gdTourRejectBranchCreation,
  gdTourBeginPackagePublish, gdTourRecordPackagePublish,
  gdTourBeginPackageInstall, gdTourRecordPackageInstall});

// Checks read the persisted exact receipts. Installed state is refreshed from
// the branch-scoped API; a typed version input or an earlier success is not a pin.
let _tourPinCheck = null;
function _tourPackagePinPasses(check) {
  const entry = _tourState?.created?.find(row => row.type === 'package-install'
    && row.name === check.name && row.version === check.version && row.receipt === 'created'
    && row['branch-name'] === _tourSessionBranch());
  if (!entry || !_tourPrincipalMatches(_tourState)) return false;
  const key = JSON.stringify([_tourState.lessonId, _tourState.step, entry.id, entry.version,
    entry['branch-id'], _tourState.principal]);
  if (_tourPinCheck?.key === key && Date.now() - _tourPinCheck.at < 1000) return _tourPinCheck.allowed;
  if (_tourPinCheck?.key === key && _tourPinCheck.pending) return false;
  const snapshot = {_state: _tourState, key, at: Date.now(), pending: true, allowed: false};
  _tourPinCheck = snapshot;
  authFetch(API.api_packages_installed, {headers: {'X-Graphden-Branch': entry['branch-id']}})
    .then(async response => {
      if (!response.ok) throw new Error('Installed packages unavailable');
      const rows = await response.json();
      if (_tourPinCheck !== snapshot || _tourState !== snapshot._state || !_tourPrincipalMatches(_tourState)
          || _tourSessionBranch() !== entry['branch-name']) return;
      snapshot.allowed = Array.isArray(rows) && rows.some(row => row.id === entry.id
        && row['branch-id'] === entry['branch-id'] && row['package-name'] === entry.name && row.version === entry.version);
    }).catch(() => { snapshot.allowed = false; })
    .finally(() => { snapshot.pending = false; snapshot.at = Date.now(); });
  return false;
}

function _tourPackageReferencePasses(check) {
  const owner = _tourState?.created?.find(row => row.type === 'fn' && row.name === check.owner
    && row.id && row.receipt === 'created');
  const namespaces = _tourState?.created?.filter(row => row.type === 'ns' && row.materialized
    && row['package-name'] === check.name && row.version === check.version && row.receipt === 'created') || [];
  if (!owner || !_tourPrincipalMatches(_tourState) || !namespaces.length || typeof lookups === 'undefined' || !lookups) return false;
  return (lookups.bindingsByFn?.get(owner.id) || []).some(binding => {
    const slot = lookups.slotMap?.get(binding['slot-id']);
    const referenced = lookups.fnMap?.get(binding['ref-fn-id']);
    return binding['fn-id'] === owner.id && slot?.name === check.slot && referenced?.name === check.fn
      && namespaces.some(namespace => namespace.id === referenced['namespace-id']);
  });
}

function _tourOwnedEntityPasses(check) {
  const entry = _tourState?.created?.find(row => row.type === check.type && row.name === check.name
    && row.id && row.receipt === 'created');
  if (!entry || !_tourPrincipalMatches(_tourState)) return false;
  return check.type === 'ns' ? !!graphData?.namespaces?.some(row => row.id === entry.id && row.name === entry.name)
    : !!lookups?.fnMap?.get(entry.id) && (!check['selected?'] || selectedFnId === entry.id);
}

function _tourPackagePublishedPasses(check) {
  return !!_tourState?.created?.some(row => row.type === 'package-version'
    && row.name === check.name && row.version === check.version && row.id && row['content-hash']
    && row.receipt === 'created') && _tourPrincipalMatches(_tourState);
}
