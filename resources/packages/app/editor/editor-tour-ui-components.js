// Exact fixed-template receipts and live lesson 25 predicates. The editor's
// ordinary graph actions perform the mutations; this ledger owns no entities.
function _tourUIComponentContext(receipt) {
  const branch = typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : null;
  const org = typeof graphdenCurrentOrg === 'string' && graphdenCurrentOrg ? graphdenCurrentOrg : 'public';
  return receipt && _tourState && _tourPrincipalMatches(_tourState)
    && _tourPrincipalMatches(receipt) && receipt.request.owner === window.gdPrefOwner
    && receipt.request.org === org
    && (receipt['branch-name'] === branch || receipt.request['branch-id'] === branch);
}

function gdTourStageUIComponentsManifest(request, manifest) {
  if (_tourState?.lessonId !== '25' || !_tourPrincipalMatches(_tourState)
      || _tourStep()?.check?.kind !== 'ui-component' || _tourStep().check.action !== 'created') return null;
  const receipt = JSON.parse(JSON.stringify({request, manifest, principal: _tourState.principal,
    'branch-name': getCurrentBranchName(), token: crypto.randomUUID(), receipt: 'pending',
    previousPreferences: {components: window.gdPrefRead('components'), theme: window.gdPrefRead('theme')}}));
  if (!_tourUIComponentContext(receipt)) throw new Error('Tutorial creation context changed.');
  const fields = {creation: 'create-only-manifest', 'manifest-root-id': request['root-id'],
    'branch-id': request['branch-id'], 'branch-name': receipt['branch-name'],
    token: receipt.token, receipt: 'pending'};
  for (const row of manifest.namespaces) _tourState.created.push({type: 'ns',
    id: row.id, name: row.name, 'parent-id': row['parent-id'], ...fields});
  for (const row of manifest.functions) _tourState.created.push({type: 'fn', ...row, ...fields,
    'cleanup-order-root-id': manifest.roots['configuration-id']});
  _tourState.uiComponentManifests ||= [];
  _tourState.uiComponentManifests.push(receipt);
  _tourSaveState();
  return {token: receipt.token, lessonId: _tourState.lessonId, principal: {...receipt.principal}};
}

function _tourUIComponentReceipt(ticket) {
  if (!ticket || !_tourState || _tourState.lessonId !== ticket.lessonId || !_tourPrincipalMatches(ticket)) return null;
  const receipt = _tourState.uiComponentManifests?.find(row => row.token === ticket.token);
  return _tourUIComponentContext(receipt) ? receipt : null;
}

function gdTourConfirmUIComponentsManifest(ticket, manifest) {
  const receipt = _tourUIComponentReceipt(ticket);
  if (!receipt || JSON.stringify(receipt.manifest) !== JSON.stringify(manifest)) return false;
  receipt.receipt = 'created';
  for (const row of _tourState.created) if (row.token === receipt.token) row.receipt = 'created';
  _tourSaveState();
  return true;
}

function gdTourRejectUIComponentsManifest(ticket) {
  const receipt = _tourUIComponentReceipt(ticket);
  if (receipt?.receipt !== 'pending') return;
  _tourState.created = _tourState.created.filter(row => row.token !== receipt.token);
  _tourState.uiComponentManifests = _tourState.uiComponentManifests.filter(row => row !== receipt);
  _tourSaveState();
}

async function gdTourRestoreUIComponentPreferences(created, failed) {
  if (!_tourState?.uiComponentManifests?.length) return [];
  const state = _tourState;
  const receipts = state.uiComponentManifests.filter(_tourUIComponentContext);
  const removable = id => created.some(row => row.type === 'fn' && row.id === id
    && row.creation === 'create-only-manifest') && !failed.some(row => row.type === 'fn' && row.id === id);
  const rejected = [];
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  for (const key of ['components', 'theme']) {
    const identity = value => key === 'components' ? value?.['fn-id'] : value?.graph?.['fn-id'];
    const rootKey = key === 'components' ? 'configuration-id' : 'theme-id';
    const current = window.gdPrefRead(key);
    let receipt = receipts.find(row => row.manifest.roots[rootKey] === identity(current) && removable(identity(current)));
    if (!receipt) receipt = receipts.find(row => row.restorations?.[key]
      && same(current, row.restorations[key].value) && removable(row.manifest.roots[rootKey]));
    if (!receipt) continue;
    let value = receipt.restorations?.[key]?.value ?? receipt.previousPreferences[key];
    const visited = new Set();
    while (identity(value) && removable(identity(value)) && !visited.has(identity(value))) {
      visited.add(identity(value));
      const earlier = receipts.find(row => row.manifest.roots[rootKey] === identity(value));
      if (!earlier) break;
      value = earlier.previousPreferences[key];
    }
    const descriptor = {type: 'preference', name: key === 'components' ? 'UI graphs' : 'Theme',
      creation: 'component-preference-restoration', 'manifest-root-id': receipt.request['root-id'], key};
    receipt.restorations ||= {};
    receipt.restorations[key] = {value};
    if (!state.created.some(row => row.creation === descriptor.creation && row.key === key)) state.created.push(descriptor);
    _tourSaveState();
    const saved = await window.gdPrefWrite(key, value);
    if (_tourState !== state || !_tourUIComponentContext(receipt)) throw new Error('Tutorial preference context changed.');
    if (!saved) rejected.push(descriptor);
    else {
      delete receipt.restorations[key];
      state.created = state.created.filter(row => !(row.creation === descriptor.creation && row.key === key));
      _tourSaveState();
    }
  }
  return rejected;
}

function _tourUIComponentActive() {
  const selected = window.gdUIComponentsSelection?.();
  return _tourState?.uiComponentManifests?.find(receipt => receipt.receipt === 'created'
    && _tourUIComponentContext(receipt) && receipt.manifest.roots['configuration-id'] === selected?.['fn-id']);
}

function _tourUIComponentFn(receipt, group, name) {
  const ns = receipt.manifest.namespaces.find(row => row.name === group);
  const row = receipt.manifest.functions.find(fn => fn.name === name && fn['namespace-id'] === ns?.id);
  const actual = row && lookups.fnMap.get(row.id);
  return actual?.name === row?.name && actual?.['namespace-id'] === row?.['namespace-id'] ? actual : null;
}

function gdTourUIComponentCheck(check) {
  const receipt = _tourUIComponentActive();
  if (!receipt || !lookups) return false;
  if (check.action === 'created') return window.gdShellMenuGraph?.ready && window.gdFnPickerGraph?.ready
    && window.gdUIComponentRuntimeIdentity?.('account-menu') === receipt.manifest.roots['configuration-id']
    && window.gdUIComponentRuntimeIdentity?.('fn-picker') === receipt.manifest.roots['configuration-id'];
  if (check.action === 'open') return selectedFnId === receipt.manifest.roots[check.root];
  const fn = _tourUIComponentFn(receipt, check.group, check.name);
  if (!fn) return false;
  const binding = (graphData.bindings || []).find(row => row['fn-id'] === fn.id
    && lookups.slotMap.get(row['slot-id'])?.name === check.slot);
  if (check.action === 'literal') return binding?.value === check.value
    && (!check.token || window.gdThemeTokenValue(check.token) === check.value);
  if (check.action === 'menu-local') {
    const menu = document.querySelector('.auth-menu');
    return binding?.value === check.value && _tourDomVisible('.auth-menu')
      && menu.style.getPropertyValue('--gd-account-menu-hover') === check.value
      && window.gdThemeTokenValue('--bg') === check.canvas;
  }
  if (check.action === 'home-last') {
    const items = (graphData['list-items'] || []).filter(row => row['binding-id'] === binding?.id);
    const changed = items.some(row => row.position === 2 && row.value === 'last');
    const menu = document.querySelector('.auth-menu');
    const targets = menu ? [...menu.querySelectorAll('[role="menuitem"]')] : [];
    const state = window.gdShellMenuGraph?.state;
    return changed && targets.length > 1 && _tourDomVisible('.auth-menu')
      && state?.get(window.GraphdenBrowser.keyword('active')) === targets.length - 1
      && document.activeElement === targets[targets.length - 1];
  }
  return false;
}
