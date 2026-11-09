// editor-tour-session.js — restore a lesson's context without claiming a branch.
// graph-first-exception: browser persistence and branch-switch reload lifecycle.
// `sandboxBranch` is only the sandbox THIS session created; `activeBranch` is
// where its last step ran. Legacy `branch` was inferred from names, so it can
// restore context but cannot grant rollback ownership to a tutorial-* branch.

function _tourSessionBranch() {
  return typeof getCurrentBranchName === 'function'
    ? getCurrentBranchName() : (new URLSearchParams(location.search).get('branch') || 'main');
}

// Capture the actual successful creation before branch switching reloads the
// page. A name-only on-branch check cannot establish ownership of an old branch.
function gdTourRecordBranchCreation(branch) {
  const step = _tourStep();
  if (!_tourState || !_tourPrincipalMatches(_tourState)
      || step?.creates?.type !== 'branch' || step.creates.name !== branch?.name
      || !branch.id || !branch['base-branch-id']) return;
  if (_tourState.created.some((row) => row.type === 'branch' && row.name === branch.name)) return;
  _tourState.created.push({type: 'branch', id: branch.id, name: branch.name,
    'base-branch-id': branch['base-branch-id'], receipt: 'created'});
  _tourSaveState();
}
window.gdTourRecordBranchCreation = gdTourRecordBranchCreation;

// The view writer accepts this fresh UUID separately from an update identity.
// Persist it before POST; a lost response must never trigger name-only cleanup.
function _tourTrackGraphViewCreation(command) {
  const step = _tourStep();
  if (!_tourState || step?.creates?.type !== 'fn' || step.creates.name !== command.name
      || !_tourPrincipalMatches(_tourState) || _tourState.activeBranch !== _tourSessionBranch()
      || !command['create-id'] || _tourState.created.some(row => row.id === command['create-id'])) return;
  _tourState.created.push({type: 'fn', id: command['create-id'], name: command.name,
    'namespace-id': command['namespace-id']});
  _tourState.activeBranch = _tourSessionBranch();
  _tourSaveState();
}

function _tourRejectGraphViewCreation(command) {
  if (!_tourState || !command['create-id']) return;
  _tourState.created = _tourState.created.filter(row => row.id !== command['create-id']);
  _tourSaveState();
}

async function _tourExpectedBranch(saved, current = () => true) {
  const lesson = (_tourLessons?.lessons || []).find((l) => l.id === saved.lessonId);
  const check = lesson?.steps?.[saved.step]?.check;
  if (check?.kind === 'on-branch' && check.name === 'main') return 'main';
  if (!['on-branch', 'created-branch'].includes(check?.kind)) return null;
  const receipt = _tourBranchReceipt(saved, check.name);
  if (!receipt || receipt.receipt === 'pending'
      || !await _tourConfirmPrincipal(saved, current)) return null;
  const status = await _tourBranchStatus(saved, receipt.name);
  return current() && _tourPrincipalMatches(saved) && status === 'available'
    ? receipt.name : null;
}

function _tourOwnedBranch(saved) {
  return saved?.sandboxBranch && saved.sandboxBranchId && saved.sandboxBaseBranchId
    ? saved.sandboxBranch : null;
}

function _tourCleanupBranch(saved) {
  return _tourOwnedBranch(saved) || saved?.cleanupBranch || saved?.activeBranch
    || saved?.branch || _tourSessionBranch();
}

function _tourSessionPrincipal() {
  return {
    accountId: window.gdAccount?.id || null,
    orgId: typeof graphdenCurrentOrg === 'undefined' ? null : graphdenCurrentOrg,
  };
}

function _tourPrincipalMatches(saved, current = _tourSessionPrincipal()) {
  if (!saved?.principal) return !current.accountId && !current.orgId;
  return saved.principal.accountId === current.accountId && saved.principal.orgId === current.orgId;
}

async function _tourConfirmPrincipal(saved, current = () => true) {
  let confirmed = _tourSessionPrincipal();
  try {
    if (window.gdAccountsReady && await window.gdAccountsReady) {
      // The auth probe helper swallows errors and can retain a stale account.
      // Cleanup needs a successful fresh read of the actual cookie principal.
      const response = await fetch('/auth/me', {headers: {Accept: 'application/json'}});
      const body = await response.json();
      if (response.ok && body?.account?.id) confirmed.accountId = body.account.id;
      else if (response.status === 401 && body?.error === 'unauthenticated'
               && !saved?.principal?.accountId && !confirmed.accountId) {
        // Demo/bearer tenants can have a confirmed org with no cookie account.
        confirmed.accountId = null;
      } else throw new Error('Account unavailable');
    }
    if (window.gdOrgsAvailable || confirmed.orgId || saved?.principal?.orgId) {
      // X-Graphden-Org is the data-scope UUID, also captured by branch-context
      // at boot. /auth/me has no org id; the scoped tree read proves it afresh.
      const response = await authFetch(API.api_graph_entities + '?scope=tree');
      if (!response.ok) throw new Error('Organization unavailable');
      const org = response.headers?.get('X-Graphden-Org');
      if (org === null || org === undefined) throw new Error('Organization unavailable');
      confirmed.orgId = org.trim() || null;
    }
  } catch (_) {
    confirmed = null;
  }
  if (!current()) return false;
  if (confirmed && _tourPrincipalMatches(saved, confirmed)) return true;
  _tourDialog({
    title: 'Tutorial context changed',
    body: 'This lesson’s account or organization cannot be confirmed. Cleanup'
      + ' is paused to protect the current workspace. Return to the original'
      + ' account and organization, or keep the items and close the session.',
    primary: ['Cancel', () => _tourTeardown(true)],
    quiet: [_tourCopy('cleanup-keep', 'Keep & close'), () => _tourTeardown()],
  });
  return false;
}

function _tourBranchReceipt(saved, branch) {
  if (saved?.restartPending?.name === branch && saved.restartPending.id
      && saved.restartPending['base-branch-id']) return saved.restartPending;
  if (saved?.sandboxBranch === branch && saved.sandboxBranchId && saved.sandboxBaseBranchId) {
    return {id: saved.sandboxBranchId, name: branch,
      'base-branch-id': saved.sandboxBaseBranchId};
  }
  return saved?.created?.find(row => row.type === 'branch' && row.name === branch
    && row.id && row['base-branch-id'] && row.receipt === 'created') || null;
}

// A successful, parseable listing can prove absence. Network failures, 403s,
// and malformed replies cannot; never turn them into a destructive recovery.
async function _tourBranchStatus(saved, branch) {
  if (branch === 'main') return 'available';
  if (!(window.API && API.api_branches)) return 'unavailable';
  try {
    const response = await authFetch(API.api_branches);
    if (!response.ok) return 'unavailable';
    const payload = await response.json();
    const rows = Array.isArray(payload) ? payload : payload?.branches;
    if (!Array.isArray(rows)) return 'unavailable';
    const receipt = _tourBranchReceipt(saved, branch);
    const found = receipt
      ? rows.some(row => row.id === receipt.id && row.name === receipt.name
        && row['base-branch-id'] === receipt['base-branch-id'])
      : rows.some(row => row.name === branch);
    return found ? 'available' : 'missing';
  } catch (_) { return 'unavailable'; }
}

async function _tourCheckSavedBranches(saved, ticket, activeBranch) {
  const current = () => _tourStartCurrent(ticket) && !_tourState
    && _tourPrincipalMatches(saved);
  const watched = [...new Set([saved.recoveryBranch, saved.sandboxBranch, activeBranch]
    .filter(name => name && name !== 'main'))];
  if (watched.length && !await _tourConfirmPrincipal(saved, current)) return false;
  for (const name of watched) {
    const status = await _tourBranchStatus(saved, name);
    if (!current()) return false;
    if (status === 'missing') {
      _tourMissingBranch(saved, name);
      return false;
    }
    if (status !== 'available') {
      _tourUnavailableContext(name);
      return false;
    }
  }
  return true;
}

function _tourUnavailableContext(branch) {
  _tourDialog({
    title: 'Tutorial branch unavailable',
    body: 'Branch “' + branch + '” is unavailable. Cleanup is paused to protect'
      + ' work on the current branch. Keep the items or try recovery again later.',
    primary: ['Cancel', () => _tourTeardown(true)],
    quiet: [_tourCopy('cleanup-keep', 'Keep & close'), () => _tourTeardown()],
  });
}

function _tourMissingBranch(saved, branch) {
  // Replace the state object so every in-flight check that captured the old
  // generation refuses to publish a result after this dialog appears.
  const state = {...saved, recoveryBranch: branch};
  _tourStartGeneration++;
  _tourState = state;
  _tourSaveState();
  if (_tourTimer) { clearInterval(_tourTimer); _tourTimer = null; }
  _tourStopFollowing();
  document.removeEventListener('keydown', _tourOnKey);
  _tourDialog({
    title: 'Tutorial branch was deleted',
    body: 'Branch “' + branch + '” no longer exists. This lesson is paused.'
      + ' Restart it on a new branch, or exit and keep any remaining items for review.',
    primary: ['Restart lesson', () => _tourRestartMissing(state)],
    quiet: ['Exit lesson', () => {
      if (_tourState === state) _tourTeardown();
    }],
  });
}

async function _tourRestartMissing(state) {
  if (_tourState !== state) return false;
  const ticket = _tourStartTicket();
  const current = () => _tourState === state && _tourStartCurrent(ticket)
    && _tourPrincipalMatches(state);
  if (!await _tourConfirmPrincipal(state, current) || !current()) return false;
  const oldStatus = await _tourBranchStatus(state, state.recoveryBranch);
  if (!current()) return false;
  if (oldStatus !== 'missing') {
    _tourUnavailableContext(state.recoveryBranch);
    return false;
  }
  if (_tourSessionBranch() !== 'main') {
    if (typeof switchToBranch === 'function') switchToBranch(null, {clearSelection: true});
    return false;
  }
  const lesson = ((await _tourFetchLessons())?.lessons || []).find(l => l.id === state.lessonId);
  if (!current() || !lesson) return false;
  let pending = state.restartPending;
  if (pending) {
    const pendingStatus = await _tourBranchStatus(state, pending.name);
    if (!current()) return false;
    if (pendingStatus === 'unavailable') return false;
    if (pendingStatus === 'missing') {
      delete state.restartPending;
      _tourSaveState();
      pending = null;
    }
  }
  if (!pending) {
    let base;
    try { base = await _tourReadBranchBase('main'); }
    catch (_) { return false; }
    if (!current() || !base?.id) return false;
    pending = {type: 'branch', name: 'tutorial-' + state.lessonId + '-'
      + Math.random().toString(36).slice(2, 6), id: crypto.randomUUID(),
    'base-branch-id': base.id, receipt: 'pending'};
    state.restartPending = pending;
    _tourSaveState();
    try {
      const response = await authFetch(API.api_branches, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({id: pending.id, name: pending.name,
          'base-branch-id': pending['base-branch-id']}),
      });
      const body = await response.json().catch(() => null);
      if (!current()) return false;
      if (response.ok && body?.ok === true && body.branch?.id === pending.id
          && body.branch.name === pending.name
          && body.branch['base-branch-id'] === pending['base-branch-id']) {
        pending.receipt = 'created';
      } else if (response.status >= 400 && response.status < 500) {
        delete state.restartPending;
        _tourSaveState();
        return false;
      }
    } catch (_) { /* Reconcile the exact generated ID below. */ }
  }
  if (pending.receipt !== 'created') {
    const pendingStatus = await _tourBranchStatus(state, pending.name);
    if (!current() || pendingStatus !== 'available') return false;
  }
  if (!current() || !await _tourConfirmPrincipal(state, current)) return false;
  _tourState = {lessonId: state.lessonId, lessonVersion: lesson.version || 1,
    step: 0, created: [{...pending, receipt: 'created'}],
    sandboxBranch: pending.name, sandboxBranchId: pending.id,
    sandboxBaseBranchId: pending['base-branch-id'], activeBranch: pending.name,
    principal: state.principal};
  _tourSaveState();
  switchToBranch(pending.name);
  return true;
}

async function _tourConfirmCleanupContext(branch) {
  if (!await _tourConfirmPrincipal(_tourState)) return false;
  // Revoking a public URL is identity/owner-scoped, so it is safe even if
  // either lesson branch disappeared. Graph cleanup still requires its
  // original branch and retains the existing fail-closed boundary below.
  if (typeof _tourDeleteHttpPublications === 'function') {
    await _tourDeleteHttpPublications(_tourState?.created || []);
  }
  const status = await _tourBranchStatus(_tourState, branch);
  if (branch === _tourSessionBranch() && status === 'available') {
    // The response may have refreshed the org header while it was in flight.
    return _tourConfirmPrincipal(_tourState);
  }
  if (status === 'missing') _tourMissingBranch(_tourState, branch);
  else _tourUnavailableContext(branch);
  return false;
}

// Returning to Build on another branch must not silently run the saved
// lesson's checks there. Lessons is the explicit route back to that context.
async function _tourRestoreSession(saved, cleanup = false, nextLessonId = null,
                                   ticket = _tourStartTicket()) {
  if (!saved?.lessonId || !_tourStartCurrent(ticket)) return false;
  _tourState = { ...saved };
  const state = _tourState;
  if (cleanup) _tourState.phase = 'cleanup';
  if (nextLessonId) _tourState.nextLessonId = nextLessonId;
  _tourSaveState();
  if (!await _tourConfirmPrincipal(state,
    () => _tourState === state && _tourStartCurrent(ticket))) return false;
  if (_tourState !== state || !_tourStartCurrent(ticket)) return false;
  const ending = cleanup || saved.phase === 'cleanup';
  if (ending && typeof _tourDeleteHttpPublications === 'function') {
    await _tourDeleteHttpPublications(_tourState.created || []);
    if (_tourState !== state || !_tourStartCurrent(ticket)) return false;
  }
  const branch = ending ? _tourCleanupBranch(saved)
    : saved.activeBranch || saved.sandboxBranch || saved.branch || 'main';
  if (branch !== _tourSessionBranch() && typeof switchToBranch === 'function') {
    const status = await _tourBranchStatus(saved, branch);
    if (_tourState !== state || !_tourStartCurrent(ticket)
        || !_tourPrincipalMatches(state)) return false;
    if (status !== 'available') {
      if (status === 'missing') _tourMissingBranch(state, branch);
      else _tourUnavailableContext(branch);
      return false;
    }
    switchToBranch(branch, { clearSelection: true });
    return true;
  }
  const lesson = ((await _tourFetchLessons())?.lessons || []).find((l) => l.id === saved.lessonId);
  if (_tourState !== state || !_tourStartCurrent(ticket)) return false;
  if (_tourState.phase === 'cleanup' || (lesson && saved.step >= lesson.steps.length)) {
    await _tourEnd(_tourState.nextLessonId);
    return true;
  }
  return startTutorial(saved.lessonId, saved.step, saved.created, saved, ticket);
}

// Choosing another lesson is a decision about the pending lesson too. Reuse
// its existing cleanup/keep dialog instead of replacing its creation ledger.
async function _tourChooseLesson(lessonId) {
  const saved = _tourState || _tourLoadState();
  if (saved?.lessonId) return _tourRestoreSession(saved, true, lessonId);
  return startTutorialIsolated(lessonId);
}

function _tourKeepAndContinue(lessonId, branch) {
  _tourTeardown();
  if (!lessonId) return;
  if (branch && typeof switchToBranch === 'function') {
    _tourQueueNext(lessonId);
    switchToBranch(null, { clearSelection: true });
  } else {
    startTutorialIsolated(lessonId);
  }
}


// Org-mode entry: run the lesson on its OWN branch — create
// tutorial-<lesson>-<suffix> off main, switch (the reload resumes the
// saved tour state on the branch), and the end-of-tour dialog offers
// branch deletion = full rollback. Falls back to a plain in-place tour
// when branch creation is unavailable (401/403/older deploys).
async function startTutorialIsolated(lessonId, ticket = _tourStartTicket()) {
  const lessons = await _tourFetchLessons();
  if (!_tourStartCurrent(ticket)) return false;
  if (!lessons) {
    if (typeof gdToast === 'function') gdToast('Tutorial unavailable on this deployment');
    return false;
  }
  const canBranch = window.API && API.api_branches
    && typeof switchToBranch === 'function';
  const onMain = canBranch && _tourSessionBranch() === 'main';
  // A lesson that MANAGES branches itself (lesson 23) opts out of the
  // scratch-branch isolation — double-wrapping broke its own "main
  // never saw it" beat and leaked the scratch branch.
  const lesson = (lessons.lessons || []).find((l) => l.id === lessonId);
  if (lesson?.requires === 'services'
      && !await _tourPrepareServiceLesson(lesson, () => _tourStartCurrent(ticket))) return false;
  if (lesson?.['in-place']) return startTutorial(lessonId, null, null, null, ticket);
  if (!canBranch || !onMain) return startTutorial(lessonId, null, null, null, ticket);
  const branch = 'tutorial-' + lessonId + '-'
    + Math.random().toString(36).slice(2, 6);
  const principal = _tourSessionPrincipal();
  let base;
  let id;
  try {
    base = await _tourReadBranchBase('main');
    if (!_tourStartCurrent(ticket)) return false;
    if (!_tourPrincipalMatches({principal})) throw new Error('Tutorial principal changed');
    id = window.crypto.randomUUID();
  } catch (_) {
    if (!_tourStartCurrent(ticket)) return false;
    if (typeof gdToast === 'function') gdToast('Starting in place (no branch)');
    return startTutorial(lessonId, null, null, null, ticket);
  }
  // Save exact create-only ownership BEFORE sending the mutation. A reload or
  // ambiguous response restores cleanup on main, without claiming a name.
  const pending = {type: 'branch', name: branch, id, 'base-branch-id': base.id, receipt: 'pending'};
  const state = {lessonId, step: 0, created: [pending], activeBranch: 'main',
    cleanupBranch: 'main', phase: 'cleanup', principal};
  _tourState = state;
  _tourSaveState();
  try {
    const response = await authFetch(API.api_branches, {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({id, name: branch, 'base-branch-id': base.id}),
    });
    const body = await response.json().catch(() => null);
    if (_tourState !== state || !_tourStartCurrent(ticket)
        || !_tourPrincipalMatches(state)) return false;
    if ((response.status >= 400 && response.status < 500) || (response.ok && body?.ok === false)) {
      _tourState.created = [];
      _tourSaveState();
      if (typeof gdToast === 'function') gdToast('Starting in place (no branch)');
      return startTutorial(lessonId, null, null, null, ticket);
    }
    const row = body?.branch;
    if (!response.ok || body?.ok !== true || row?.id !== id
        || row.name !== branch || row['base-branch-id'] !== base.id) throw new Error('Unconfirmed branch creation');
    pending.receipt = 'created';
  } catch (_) {
    if (_tourState === state && _tourStartCurrent(ticket)) {
      if (typeof gdToast === 'function') gdToast('Branch creation could not be confirmed. Cleanup remains available in Lessons.');
      await _tourEnd();
    }
    return false;
  }
  Object.assign(state, {sandboxBranch: branch, sandboxBranchId: id,
    sandboxBaseBranchId: base.id, activeBranch: branch});
  delete state.phase;
  _tourSaveState();
  if (/^#@/.test(location.hash)) {
    try { history.replaceState(null, '', location.pathname + location.search); } catch (_) {}
  }
  switchToBranch(branch);
  return true;
}

// The public URL is a separately revocable artifact. Stage its server-create
// UUID before POST; never recover it by function name after a lost response.
function _tourTrackHttpPublication(id, fn) {
  const step = _tourStep();
  if (!_tourState || step?.creates?.type !== 'http-publication'
      || step.creates.name !== fn.name || !id) return;
  if (_tourState.created.some(created => created.type === 'http-publication' && created.id === id)) return;
  if (!_tourState.sessionId) _tourState.sessionId = crypto.randomUUID();
  _tourState.created.push({type: 'http-publication', id, name: fn.name, 'fn-id': fn.id,
    'namespace-id': fn['namespace-id'] ?? null, sessionId: _tourState.sessionId,
    branch: _tourSessionBranch(), principal: _tourSessionPrincipal()});
  _tourSaveState();
}
