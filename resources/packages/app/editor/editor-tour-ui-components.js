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

function _tourEditorConfigurationContext(receipt) {
  const scope = receipt?.request?.scope;
  const org = typeof graphdenCurrentOrg === 'string' && graphdenCurrentOrg ? graphdenCurrentOrg : 'public';
  return scope && _tourState && _tourPrincipalMatches(_tourState) && _tourPrincipalMatches(receipt)
    && scope['principal-id'] === window.gdPrefOwner && scope.org === org
    && receipt['branch-name'] === getCurrentBranchName();
}

function _tourEditorReceiptMatches(original, result) {
  const stable = value => Array.isArray(value) ? value.map(stable)
    : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
  return Object.keys(original).every(key => JSON.stringify(stable(original[key])) === JSON.stringify(stable(result?.[key])));
}

function gdTourStageEditorConfiguration(request, original) {
  if (_tourState?.lessonId !== '25' || _tourState.phase === 'cleanup' || !original.created) return null;
  const receipt = JSON.parse(JSON.stringify({request, original, principal: _tourState.principal,
    'branch-name': getCurrentBranchName(), token: crypto.randomUUID(), receipt: 'pending',
    previousRoots: window.gdUIComponentsRoots?.()}));
  if (!_tourEditorConfigurationContext(receipt)) throw new Error('Tutorial configuration context changed.');
  const fields = {creation: 'editor-configuration', token: receipt.token, receipt: 'pending',
    'branch-id': request.scope['branch-id'], 'branch-name': receipt['branch-name']};
  for (const row of original['function-receipts']) _tourState.created.push({type: 'fn', ...row, ...fields});
  _tourState.editorConfigurations ||= [];
  _tourState.editorConfigurations.push(receipt);
  _tourSaveState();
  return {token: receipt.token, lessonId: _tourState.lessonId, principal: {...receipt.principal}};
}

function _tourEditorConfigurationReceipt(ticket) {
  if (!ticket || !_tourState || _tourState.lessonId !== ticket.lessonId || !_tourPrincipalMatches(ticket)) return null;
  const receipt = _tourState.editorConfigurations?.find(row => row.token === ticket.token);
  return _tourEditorConfigurationContext(receipt) ? receipt : null;
}

function gdTourConfirmEditorConfiguration(ticket, result) {
  const receipt = _tourEditorConfigurationReceipt(ticket);
  if (!receipt || !_tourEditorReceiptMatches(receipt.original, result)) return false;
  receipt.receipt = 'created';
  for (const row of _tourState.created) if (row.token === receipt.token) row.receipt = 'created';
  _tourSaveState();
  return true;
}

function gdTourRejectEditorConfiguration(ticket) {
  const receipt = _tourEditorConfigurationReceipt(ticket);
  if (receipt?.receipt !== 'pending') return;
  _tourState.created = _tourState.created.filter(row => row.token !== receipt.token);
  _tourState.editorConfigurations = _tourState.editorConfigurations.filter(row => row !== receipt);
  _tourSaveState();
}

function _tourEditorUndoInput(original) {
  return {'current-id': original.id, 'expected-configuration-revision': original['configuration-revision'],
    'cohort-revision': original['cohort-revision'], previous: original.previous, functions: original['function-receipts']};
}

async function _tourEditorConfigurationRequest(route, body) {
  const response = await authFetch(route, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  const result = await response.json();
  if (!response.ok || !result.ok) {
    const error = new Error('The tutorial configuration needs review before cleanup.');
    error.definite = true;
    throw error;
  }
  return result;
}

async function _tourEditorUndoConfirmed(receipt) {
  if (!_tourEditorConfigurationContext(receipt)) return false;
  const expected = receipt.undoAttempt;
  const result = await _tourEditorConfigurationRequest(API.api_ui_configuration_reconcile_undo, expected);
  return _tourEditorConfigurationContext(receipt) && result.confirmed === true && result.undone === true
    && result.id === (expected.receipt.previous?.id || receipt.original['restore-id'])
    && result['restore-revision'] === expected['restore-revision'];
}

async function gdTourUndoEditorConfigurations(created, options = {}) {
  const state = _tourState;
  if (!state?.editorConfigurations?.length) return [];
  for (const receipt of [...state.editorConfigurations].reverse()) {
    if (receipt.receipt === 'removed') continue;
    const rows = created.filter(row => row.token === receipt.token && row.creation === 'editor-configuration');
    if (options.token && options.token !== receipt.token) return rows;
    if (!_tourEditorConfigurationContext(receipt)) return rows;
    const input = options.review?.request || _tourEditorUndoInput(receipt.original);
    try {
      if (receipt.receipt === 'pending') {
        const confirmed = await _tourEditorConfigurationRequest(API.api_ui_configuration_reconcile, input);
        if (_tourState !== state || !_tourEditorConfigurationContext(receipt) || !confirmed.confirmed
          || confirmed['cohort-revision'] !== receipt.original['cohort-revision']
          || confirmed['configuration-revision'] !== receipt.original['configuration-revision']) return rows;
        receipt.receipt = 'created';
        for (const row of rows) row.receipt = 'created';
        _tourSaveState();
      }
      if (receipt.undoAttempt) {
        if (!await _tourEditorUndoConfirmed(receipt)) return rows;
      } else {
        receipt.undoAttempt = JSON.parse(JSON.stringify({receipt: input, scope: receipt.request.scope,
          'restore-revision': options.review?.['restore-revision'] || receipt.original['restore-revision']}));
        _tourSaveState();
        try {
          const result = await _tourEditorConfigurationRequest(API.api_ui_configuration_undo, input);
          if (JSON.stringify([...(result.deleted || [])].sort()) !== JSON.stringify([...receipt.original.functions].sort())) return rows;
        } catch (error) {
          if (error.definite) { delete receipt.undoAttempt; _tourSaveState(); return rows; }
          if (!await _tourEditorUndoConfirmed(receipt)) return rows;
        }
      }
      if (_tourState !== state || !_tourEditorConfigurationContext(receipt)) return rows;
      receipt.receipt = 'removed';
      for (const row of rows) row.receipt = 'removed';
      _tourSaveState();
      window.gdRefreshUIConfiguration?.();
      if (options.token) return [];
    } catch (_) { return rows; }
  }
  return [];
}

async function gdTourReviewEditorConfigurationCleanup(nextLessonId) {
  const state = _tourState;
  const receipt = [...(state?.editorConfigurations || [])].reverse().find(row => row.receipt !== 'removed');
  if (!receipt) return false;
  try {
    if (!_tourEditorConfigurationContext(receipt)) throw new Error('Context changed');
    if (receipt.undoAttempt) {
      const failed = await gdTourUndoEditorConfigurations(state.created, {token: receipt.token});
      if (failed.length) throw new Error('Undo outcome uncertain');
      await _tourEnd(nextLessonId);
      return true;
    }
    if (receipt.receipt === 'pending') {
      const confirmed = await _tourEditorConfigurationRequest(API.api_ui_configuration_reconcile,
        _tourEditorUndoInput(receipt.original));
      if (_tourState !== state || !_tourEditorConfigurationContext(receipt) || !confirmed.confirmed
        || confirmed['cohort-revision'] !== receipt.original['cohort-revision']
        || confirmed['configuration-revision'] !== receipt.original['configuration-revision']) throw new Error('Apply outcome uncertain');
      receipt.receipt = 'created';
      for (const row of state.created) if (row.token === receipt.token) row.receipt = 'created';
      _tourSaveState();
    }
    const review = await _tourEditorConfigurationRequest(API.api_ui_configuration_cleanup_preview,
      {receipt: _tourEditorUndoInput(receipt.original), scope: receipt.request.scope,
        'restore-revision': receipt.original['restore-revision']});
    if (_tourState !== state || !_tourEditorConfigurationContext(receipt)
      || review.kind !== 'editor-configuration-cleanup-review'
      || review.request?.['current-id'] !== receipt.original.id
      || !_tourEditorReceiptMatches({previous: receipt.original.previous}, review.request)
      || JSON.stringify(review.delete?.map(row => row.id).sort()) !== JSON.stringify([...receipt.original.functions].sort())) {
      throw new Error('Review does not match receipt');
    }
    _tourDialog({title: 'Delete these tutorial UI graphs?',
      body: 'Delete these exact tutorial graphs, including your edits: '
        + review.delete.map(row => JSON.stringify(row.name || row.id)).join(', ')
        + '. Restore ' + JSON.stringify(review.restore.name) + '. Other graphs are kept.',
      primary: ['Delete these graphs & restore', async () => {
        if (_tourState !== state || !_tourEditorConfigurationContext(receipt)) return;
        const failed = await gdTourUndoEditorConfigurations(state.created, {token: receipt.token, review});
        if (failed.length) {
          _tourReport(false, 'Kept tutorial UI graphs: the reviewed state changed or deletion could not be confirmed. Review cleanup again.');
          return;
        }
        await _tourEnd(nextLessonId);
      }],
      quiet: ['Keep & close', () => _tourKeepAndContinue(nextLessonId)],
    });
  } catch (_) {
    _tourReport(false, 'Kept tutorial UI graphs: their current state could not be reviewed. Return through Lessons to retry cleanup.');
  }
  return true;
}

async function gdTourRestoreUIComponentPreferences(created, failed) {
  if (!_tourState?.uiComponentManifests?.length) return [];
  const state = _tourState;
  const receipts = state.uiComponentManifests.filter(_tourUIComponentContext);
  const removable = id => created.some(row => row.type === 'fn' && row.id === id
    && row.creation === 'create-only-manifest') && !failed.some(row => row.type === 'fn' && row.id === id);
  const rejected = [];
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  // Abort the selected theme reader before a components write can refresh it.
  for (const key of ['theme', 'components']) {
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
  const activation = _tourState?.editorConfigurations?.find(receipt => receipt.receipt === 'created'
    && _tourEditorConfigurationContext(receipt) && receipt.original.id === selected?.['fn-id']);
  return _tourState?.uiComponentManifests?.find(receipt => receipt.receipt === 'created'
    && _tourUIComponentContext(receipt) && (receipt.manifest.roots['configuration-id'] === selected?.['fn-id']
      || receipt.manifest.roots['configuration-id'] === activation?.request?.command?.['selection-id']));
}

function _tourUIComponentFn(receipt, group, name) {
  const ns = receipt.manifest.namespaces.find(row => row.name === group);
  const row = receipt.manifest.functions.find(fn => fn.name === name && fn['namespace-id'] === ns?.id);
  const actual = row && lookups.fnMap.get(row.id);
  return actual?.name === row?.name && actual?.['namespace-id'] === row?.['namespace-id'] ? actual : null;
}

function gdTourObserveExistingUIComponent(request, result, beforeRoots) {
  if (_tourState?.lessonId !== '25' || request.operation !== 'personalize'
    || request.command?.component !== 'account-menu' || result.created !== false || !result['entry-id']) return;
  const observation = JSON.parse(JSON.stringify({request, original: result, previousRoots: beforeRoots,
    principal: _tourState.principal, 'branch-name': getCurrentBranchName()}));
  if (!_tourEditorConfigurationContext(observation)) return;
  _tourState.existingUIComponent = observation;
  _tourSaveState();
}

function _tourExistingPersonalComponent() {
  const observation = _tourState?.existingUIComponent;
  return _tourEditorConfigurationContext(observation)
    && observation.original['entry-id'] === window.gdUIComponentsRoots?.()['menu-id'] ? observation : null;
}

function gdTourUIComponentStep(step) {
  if (!_tourExistingPersonalComponent()) return step;
  if (step?.check?.action === 'personal-theme') return {...step, title: 'Inspect your existing theme input',
    body: 'This is your existing menu; the lesson did not create it and will not delete it. Inspect its theme input and shared dependencies. Keep your current values. Click Next to continue without changing them.',
    target: null, targets: [], check: {kind: 'manual'}};
  if (step?.title === 'Shared helpers stay shared') return {...step,
    body: 'Your existing menu retains its own inputs and shared dependencies. Filled ancestor bindings remain sealed. The lesson has not changed your graph or created a cleanup receipt.'};
  if (step?.title === 'The boundary and cleanup') return {...step,
    body: 'You inspected your existing graphs. The lesson created no graphs to delete and keeps your configuration. Graphs author presentation; the host retains DOM, account actions, search and storage. Click Finish.'};
  return step;
}

function _tourPersonalComponentReceipt(component) {
  const root = component === 'account-menu' ? 'menu-id' : component === 'fn-picker' ? 'picker-id' : null;
  const entry = root && window.gdUIComponentsRoots?.()[root];
  return _tourState?.editorConfigurations?.find(receipt => receipt.receipt === 'created'
    && _tourEditorConfigurationContext(receipt) && receipt.request.command?.component === component
    && receipt.original['entry-id'] === entry);
}

function _tourPersonalComponentCheck(check) {
  if (check.action === 'retained-open') {
    const menu = _tourPersonalComponentReceipt('account-menu') || _tourExistingPersonalComponent();
    const previous = menu?.previousRoots?.[check.root];
    return !!previous && previous === window.gdUIComponentsRoots?.()[check.root] && selectedFnId === previous;
  }
  const receipt = _tourPersonalComponentReceipt(check.component)
    || (check.component === 'account-menu' ? _tourExistingPersonalComponent() : null);
  if (!receipt || !lookups) return false;
  const entry = receipt.original['entry-id'];
  if (check.action === 'personalized') return receipt.original.id === window.gdUIComponentsSelection?.()?.['fn-id']
    && window.gdUIComponentRuntimeIdentity?.(check.component) === receipt.original.id;
  if (check.action === 'personal-open') return selectedFnId === entry;
  if (check.action === 'personal-theme') {
    const binding = (graphData.bindings || []).find(row => row['fn-id'] === entry
      && row.value?.accent === check.accent && row.value?.['canvas-background'] === check.background);
    return !!binding && window.gdThemeTokenValue('--gd-flow') === check.accent
      && window.gdThemeTokenValue('--bg') === check.background;
  }
  return false;
}

function gdTourUIComponentCheck(check) {
  if (['personalized', 'personal-open', 'personal-theme', 'retained-open'].includes(check.action)) {
    return _tourPersonalComponentCheck(check);
  }
  const receipt = _tourUIComponentActive();
  if (!receipt || !lookups) return false;
  const activeRoot = window.gdUIComponentsSelection?.()?.['fn-id'];
  if (check.action === 'created') return window.gdShellMenuGraph?.ready && window.gdFnPickerGraph?.ready
    && window.gdUIComponentRuntimeIdentity?.('account-menu') === activeRoot
    && window.gdUIComponentRuntimeIdentity?.('fn-picker') === activeRoot
    && (!receipt.manifest.roots['recents-id']
      || window.gdUIComponentRuntimeIdentity?.('recents') === activeRoot)
    && (!receipt.manifest.roots['inspector-id']
      || window.gdUIComponentRuntimeIdentity?.('inspector') === activeRoot);
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
