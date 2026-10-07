// editor-tour-session.js — restore a lesson's context without claiming a branch.
// graph-first-exception: browser persistence and branch-switch reload lifecycle.
// `sandboxBranch` is only the sandbox THIS session created; `activeBranch` is
// where its last step ran. Legacy `branch` was inferred from names, so it can
// restore context but cannot grant rollback ownership to a tutorial-* branch.

function _tourSessionBranch() {
  return typeof getCurrentBranchName === 'function'
    ? getCurrentBranchName() : (new URLSearchParams(location.search).get('branch') || 'main');
}

function _tourExpectedBranch(saved) {
  const lesson = (_tourLessons?.lessons || []).find((l) => l.id === saved.lessonId);
  const check = lesson?.steps?.[saved.step]?.check;
  return check?.kind === 'on-branch' ? check.name : null;
}

function _tourOwnedBranch(saved) {
  return saved?.sandboxBranch || null;
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

async function _tourConfirmPrincipal(saved) {
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

async function _tourBranchAvailable(branch) {
  if (branch === 'main') return true;
  if (!(window.API && API.api_branches)) return false;
  try {
    const response = await authFetch(API.api_branches);
    if (!response.ok) return false;
    const payload = await response.json();
    const rows = Array.isArray(payload) ? payload : payload?.branches;
    return Array.isArray(rows) && rows.some((row) => row.name === branch);
  } catch (_) { return false; }
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

async function _tourConfirmCleanupContext(branch) {
  if (!await _tourConfirmPrincipal(_tourState)) return false;
  if (branch === _tourSessionBranch() && await _tourBranchAvailable(branch)) {
    // The response may have refreshed the org header while it was in flight.
    return _tourConfirmPrincipal(_tourState);
  }
  _tourUnavailableContext(branch);
  return false;
}

// Returning to Build on another branch must not silently run the saved
// lesson's checks there. Lessons is the explicit route back to that context.
async function _tourRestoreSession(saved, cleanup = false, nextLessonId = null) {
  if (!saved?.lessonId) return false;
  _tourState = { ...saved };
  if (cleanup) _tourState.phase = 'cleanup';
  if (nextLessonId) _tourState.nextLessonId = nextLessonId;
  _tourSaveState();
  if (!await _tourConfirmPrincipal(_tourState)) return false;
  const ending = cleanup || saved.phase === 'cleanup';
  const branch = (ending && _tourOwnedBranch(saved))
    || saved.activeBranch || saved.sandboxBranch || saved.branch || 'main';
  if (branch !== _tourSessionBranch() && typeof switchToBranch === 'function') {
    if (!await _tourBranchAvailable(branch)) {
      _tourUnavailableContext(branch);
      return false;
    }
    switchToBranch(branch, { clearSelection: true });
    return true;
  }
  const lesson = ((await _tourFetchLessons())?.lessons || []).find((l) => l.id === saved.lessonId);
  if (_tourState.phase === 'cleanup' || (lesson && saved.step >= lesson.steps.length)) {
    await _tourEnd(_tourState.nextLessonId);
    return true;
  }
  return startTutorial(saved.lessonId, saved.step, saved.created, saved);
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
