// Editor Branches — MERGE, and what happens when it does not go through.
//
// `mergeBranchInto` submits the
// merge and reads the response in two halves (a fetch REJECTION means the
// merge committed and the target's post-commit service restart severed the
// response — `waitForServerBack` polls /health before reloading); a
// `:reason :merge-conflict` response opens the conflict-resolution modal
// (`showMergeConflictsModal`, server-rendered at POST /partials/merge-conflicts,
// `aria-modal` made true by `installTabTrap` + `setSiblingsInert`), and
// `submitConflictResolutions` re-submits the same merge with the chosen side
// per conflicting entity. Unit-tested under node in
// tools/runtime-test/merge-branch-into.test.js.

// ============================================================================
// MERGE — with conflict-resolution modal
// ============================================================================

async function mergeBranchInto(sourceName, targetName, conflictResolutions, targetRef) {
  // `targetRef` — an id-safe /api path ref for the TARGET (a name with
  // "/" can't ride the :ref segment). Callers with a row in hand pass
  // the row's id; absent → the name (pre-redesign behaviour).
  targetRef = targetRef || targetName;
  if (!sourceName || !targetName) return;
  if (!conflictResolutions
      && !confirm('Merge "' + sourceName + '" INTO "' + targetName + '"?'
                  + (targetName === DEFAULT_BRANCH
                     ? ' This affects main — every viewer will see these changes.'
                     : ''))) {
    return;
  }
  const errBox = document.getElementById('branch-popover-error');
  const setError = (msg) => {
    if (errBox) { errBox.textContent = msg; errBox.classList.remove('hidden'); }
  };

  // Separate the FETCH from response-processing on purpose. Only the fetch
  // itself REJECTING (no response arrived) is the "committed-merge, target
  // restarting" case — the merge endpoint drops the connection solely in
  // its post-commit step (restarting the target's services; when the target
  // runs the very web-server serving this request, e.g. merging into main,
  // the rebind severs the response). A response that DID arrive — even a
  // 500, a proxy 502/504 HTML page, or an empty-bodied 401 — means the merge
  // did NOT commit and must be shown as an error, never as "restarting".
  let resp;
  try {
    resp = await window.authFetch(
      API.api_branches_ref_merge(targetRef),
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: sourceName,
          'conflict-resolutions': conflictResolutions || undefined,
        }),
      });
  } catch (_netErr) {
    // Fetch rejected — no response. Post-commit restart severed it; the
    // merge already committed. Wait out the rebind, then reload to the real
    // post-merge state instead of crying "Failed to fetch".
    setError('Merge submitted — ' + targetName + ' is restarting, verifying…');
    if (await waitForServerBack(30000)) { closeBranchPopover(); location.reload(); return; }
    setError('Merge sent, but ' + targetName + ' has not come back yet — '
             + 'reload in a moment to confirm.');
    return;
  }

  // A response arrived — parse defensively (an error page / empty body is
  // not JSON) and handle by status. Check 401 BEFORE parsing.
  if (resp.status === 401) { setError('Sign in to merge'); return; }
  const body = await resp.json().catch(() => ({}));
  if (body?.ok === false && body?.reason === 'merge-conflict') {
    // Drop the popover so the modal has full attention.
    closeBranchPopover();
    showMergeConflictsModal(body, sourceName, targetName, targetRef);
    return;
  }
  if (!resp.ok || body?.ok === false) {
    setError(body?.error || ('HTTP ' + resp.status));
    return;
  }
  // Surface the audit-log when the resolver kept entries scoped
  // to their origin branch (sticky-local fns — see
  // graphden.versioning.branch-local). The alert is intentionally
  // synchronous + simple: the user just clicked "Merge", we want
  // them to KNOW these entries didn't propagate before the page
  // reloads. The diff's 📍 badge already showed them ahead
  // of time; this is the post-merge confirmation.
  // A stacked / cross-base merge carries the branches the target lacked
  // in first (server-side, `merge-transitively!`); say which ones landed.
  const first = body?.['merged-first'] || [];
  if (first.length > 0) {
    alert('Merged ' + first.map((s) => s.name).join(', ') + ' into ' + targetName
          + ' first, then ' + sourceName + ' — everything it inherited is carried.');
  }
  const skipped = body?.skipped?.['branch-local'] || [];
  if (skipped.length > 0) {
    const names = skipped.map((s) => ':' + (s['fn-name'] || s['entity-id']))
                         .join(', ');
    alert(skipped.length + ' branch-local fn'
          + (skipped.length === 1 ? '' : 's')
          + ' did NOT propagate to ' + targetName + ': ' + names
          + '. (Marked with 📍 in the diff.)');
  }
  // Success — drop everything and reload so caches refresh and the
  // editor picks up the new resolved view on the current branch.
  closeBranchPopover();
  location.reload();
}



// Poll /health until it answers OK or the deadline passes. Used after a
// merge whose response was severed by the target's post-commit service
// restart — the merge already committed; this just waits out the rebind.
// /health is a fixed, deployment-invariant infra route (not a
// graph-composed API route in window.API), so a same-origin relative
// path is correct here.
async function waitForServerBack(deadlineMs) {
  const deadline = Date.now() + deadlineMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch('/health', { cache: 'no-store' });
      if (r.ok) return true;
    } catch (_) { /* still down — keep polling */ }
    await new Promise((res) => setTimeout(res, 1000));
  }
  return false;
}

let _conflictsModal = null;
// Where the keyboard was before the modal took over (the merge button in
// the branch popover), so closing hands it back.
let _conflictsTrigger = null;

function ensureConflictsModal() {
  if (_conflictsModal) return _conflictsModal;
  const el = document.createElement('div');
  el.id = 'merge-conflicts-modal';
  el.className = 'merge-conflicts-modal hidden';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-label', 'Resolve merge conflicts');
  document.body.appendChild(el);
  _conflictsModal = el;
  // This modal shipped with no Escape handler at all — the only ways out
  // were the Cancel button and clicking the overlay.
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || el.classList.contains('hidden')) return;
    e.preventDefault();   // consumed — see graphden-popover.js
    closeConflictsModal();
  });
  // Make the declared aria-modal="true" true.
  installTabTrap({
    getEl: () => _conflictsModal,
    isVisible: () => !!_conflictsModal && !_conflictsModal.classList.contains('hidden'),
  });
  return el;
}

function closeConflictsModal() {
  if (!_conflictsModal) return;
  const hadFocus = _conflictsModal.contains(document.activeElement);
  _conflictsModal.classList.add('hidden');
  setSiblingsInert(_conflictsModal, false);
  if (hadFocus) returnFocusTo(_conflictsTrigger);
  _conflictsTrigger = null;
}

// The resolution card (header + help + batch toolbar + per-conflict
// rows + previews + actions) is server-rendered hiccup at
// `POST /partials/merge-conflicts` — we POST the `{conflicts, source,
// target}` the failed merge just returned and the server renders it (it
// owns all markup + escaping, mirroring the other editor partials). JS
// here owns only the modal chrome (the full-viewport overlay div, which
// must be a flex sibling of the card so it can't come from the
// single-root partial) and the radio/apply lifecycle. `body` is the
// merge response — its `conflicts` array is what we render.
async function showMergeConflictsModal(body, sourceName, targetName, targetRef) {
  const modal = ensureConflictsModal();
  _conflictsTrigger = document.activeElement;
  // Build fully, THEN reveal. The modal is read the instant it becomes
  // visible (a user tabbing in — and the e2e — expect the rows to be
  // there), so `.hidden` stays on until the server-rendered card is
  // mounted; dropping it before the `await` below would expose an empty
  // shell during the fetch.
  modal.innerHTML = '';
  const overlay = document.createElement('div');
  overlay.className = 'merge-conflicts-overlay';
  overlay.addEventListener('click', closeConflictsModal);
  modal.appendChild(overlay);

  let cardHtml;
  try {
    const resp = await window.authFetch('/partials/merge-conflicts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conflicts: body?.conflicts || [],
        source: sourceName,
        target: targetName,
      }),
    });
    cardHtml = resp.ok
      ? await resp.text()
      : '<div class="merge-conflicts-card"><div class="merge-conflicts-header">'
        + 'Failed to load conflicts (HTTP ' + resp.status + ')</div></div>';
  } catch (_err) {
    cardHtml = '<div class="merge-conflicts-card"><div class="merge-conflicts-header">'
      + 'Failed to load conflicts</div></div>';
  }
  const wrap = document.createElement('div');
  wrap.innerHTML = cardHtml;
  const card = wrap.firstElementChild;
  if (card) modal.appendChild(card);

  const cancel = modal.querySelector('#merge-conflicts-cancel');
  if (cancel) cancel.addEventListener('click', closeConflictsModal);
  const submit = modal.querySelector('#merge-conflicts-submit');
  if (submit) {
    submit.addEventListener('click',
      () => submitConflictResolutions(sourceName, targetName, targetRef));
  }
  const pickAll = (choice) => {
    modal.querySelectorAll('.merge-conflict-row input[type="radio"]')
      .forEach((r) => {
        if (r.value === choice) {
          r.checked = true;
          r.dispatchEvent(new Event('change', {bubbles: true}));
        }
      });
  };
  const pickSrc = modal.querySelector('#merge-conflicts-pick-all-source');
  if (pickSrc) pickSrc.addEventListener('click', () => pickAll('source'));
  const pickTgt = modal.querySelector('#merge-conflicts-pick-all-target');
  if (pickTgt) pickTgt.addEventListener('click', () => pickAll('target'));

  modal.classList.remove('hidden');
  setSiblingsInert(modal, true);
  focusIntoDialog(modal);
}

// Read each rendered row's `data-entity-*` + checked radio into the
// `:conflict-resolutions` payload. The server owns the row markup, so the
// JS↔partial contract is the two data-attrs + the radio `value`.


async function submitConflictResolutions(sourceName, targetName, targetRef) {
  targetRef = targetRef || targetName;
  const rows = Array.from(document.querySelectorAll(
    '.merge-conflicts-modal .merge-conflict-row'));
  const resolutions = rows.map((row) => {
    const chosen = row.querySelector('input[type="radio"]:checked');
    return {
      'entity-name': row.getAttribute('data-entity-name'),
      'entity-id': row.getAttribute('data-entity-id'),
      choice: chosen?.value || 'source',
    };
  });
  const errBox = document.getElementById('merge-conflicts-error');
  const setError = (msg) => {
    if (errBox) { errBox.textContent = msg; errBox.classList.remove('hidden'); }
  };

  // Same fetch/response split as mergeBranchInto (they submit the SAME merge —
  // this is just the conflict-resolved re-submit). A fetch REJECTION means the
  // merge committed and the target's post-commit service restart severed the
  // response — the canonical case is resolving conflicts on a merge into main
  // that touches the web-server serving this very request. Without this split
  // the catch reported "Failed to fetch" on an already-committed merge, leaving
  // the modal open so the user re-submits an already-done merge. A response that
  // DID arrive (even an error page) means the merge did NOT commit.
  let resp;
  try {
    resp = await window.authFetch(
      API.api_branches_ref_merge(targetRef),
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: sourceName,
          'conflict-resolutions': resolutions,
        }),
      });
  } catch (_netErr) {
    setError('Merge submitted — ' + targetName + ' is restarting, verifying…');
    if (await waitForServerBack(30000)) { closeConflictsModal(); location.reload(); return; }
    setError('Merge sent, but ' + targetName + ' has not come back yet — '
             + 'reload in a moment to confirm.');
    return;
  }
  if (resp.status === 401) { setError('Sign in to merge'); return; }
  const body = await resp.json().catch(() => ({}));
  if (!resp.ok || body?.ok === false) {
    setError(body?.error || ('HTTP ' + resp.status));
    return;
  }
  closeConflictsModal();
  location.reload();
}
