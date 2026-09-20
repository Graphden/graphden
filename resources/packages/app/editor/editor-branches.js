// Editor Branches — current-branch state, fetch interception, top-bar
// selector + branch-CRUD popover.
//
// State sources (read precedence):
//   1. URL ?branch=<name>          — shareable / explicit override
//   2. localStorage                — persists across reloads
//   3. 'main'                      — default
//
// Switching branches mutates BOTH localStorage AND the URL, then
// reloads the page. Reload is the simplest "invalidate everything"
// strategy — the editor caches graph data, layout positions, lookup
// maps, etc.; rebuilding them in-place would require touching every
// state owner. The backend's per-branch ctx cache means the new
// branch's first request pays a one-time compile, not every request.
//
// Every fetch to /api/* gets `X-Graphden-Branch: <name>` (when not
// main). We monkey-patch `window.fetch` at load time so direct
// fetch calls (editor-main, editor-layout, editor-value-form, …)
// pick up branch context without each call site being touched. The
// matching authFetch in editor-auth.js stacks Authorization on top
// of this wrapped fetch.
//
// Branch/org/workspace CONTEXT + the fetch wrap are
// editor-branch-context.js (loads before this file), the ⛨ policy and ⚙
// protection menus are editor-branch-policy.js, merge + the conflict modal
// are editor-branch-merge.js. This file is the CHIP and its POPOVER: the
// row list, per-row ⋯ menu, hub sync, create / delete / archive / propose /
// approve, and `branchRefFrom`, the ref resolver every row op shares.


// ============================================================================
// UI — top-bar branch chip + branch CRUD popover
// ============================================================================

const BRANCH_ICON_SVG =
  '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
  + '<line x1="6" y1="3" x2="6" y2="15"/>'
  + '<circle cx="18" cy="6" r="3"/>'
  + '<circle cx="6" cy="18" r="3"/>'
  + '<path d="M18 9a9 9 0 0 1-9 9"/>'
  + '</svg>';

function initBranchSelector() {
  const mount = document.getElementById('branch-mount');
  if (!mount) return;
  mount.innerHTML =
    '<button id="branch-chip-btn" class="branch-chip-btn" title="Switch branch">'
    + BRANCH_ICON_SVG
    + '<span id="branch-chip-name"></span>'
    + '</button>'
    + '<div id="branch-popover" class="branch-popover hidden" role="dialog"'
    + ' aria-label="Switch branch"></div>';

  renderBranchChip();
  document.getElementById('branch-chip-btn').addEventListener('click', toggleBranchPopover);
  document.addEventListener('click', (e) => {
    // A click anywhere outside a ⋯ button / its menu collapses the
    // open ⋯ menu — including clicks on other popover rows.
    if (!e.target.closest?.('.branch-row-more, .branch-row-more-menu')) {
      closeBranchMoreMenus();
    }
    const popover = document.getElementById('branch-popover');
    const btn = document.getElementById('branch-chip-btn');
    if (!popover || popover.classList.contains('hidden')) return;
    if (popover.contains(e.target) || btn.contains(e.target)) return;
    // The ⚙ protection / ⛨ policy mini-menus are appended to <body>,
    // OUTSIDE the popover element — a click on a control inside them
    // (the approvals segment, a checkbox) used to read as "outside the
    // popover" here and closed BOTH popovers mid-interaction (the
    // lesson-23 "the menu closes before I can pick" bug).
    if (e.target.closest?.('#gd-protect-pop, #gd-branch-policy-pop, .gd-pop-scrim')) return;
    if (pointerEventInTour(e)) return;
    closeBranchPopover();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    // Only when the popover is actually OPEN: this is a document-level
    // handler, and marking every Escape as consumed (or calling close on
    // one) makes the key useless for whatever else is listening — the
    // interactive tutorial ends on Escape and stopped being able to.
    const popover = document.getElementById('branch-popover');
    if (!popover || popover.classList.contains('hidden')) return;
    e.preventDefault();
    closeBranchPopover();
  });
}

function renderBranchChip() {
  const name = getCurrentBranchName();
  const label = document.getElementById('branch-chip-name');
  const btn = document.getElementById('branch-chip-btn');
  if (!label || !btn) return;
  label.textContent = name;
  btn.classList.toggle('branch-chip-non-default', name !== DEFAULT_BRANCH);
  btn.title = name === DEFAULT_BRANCH
    ? 'On main — click to switch branch'
    : 'On "' + name + '" — click to switch';
  gdSyncEdgeBranchBadge();
}

// Collapsed-Explorer branch badge — the branch chip lives in the Explorer
// now, so collapsing it would hide the WRITE CONTEXT. On a non-default
// branch the left-edge expand tab carries the branch name (and the accent
// wash); on main it stays a bare chevron and the screen stays clean.
function gdSyncEdgeBranchBadge() {
  const tab = document.getElementById('sidebar-expand-floating');
  if (!tab) return;
  const name = getCurrentBranchName();
  const nonDefault = name !== DEFAULT_BRANCH;
  tab.classList.toggle('gd-edge-nondefault', nonDefault);
  let badge = tab.querySelector('.gd-edge-branch');
  if (nonDefault) {
    if (!badge) {
      badge = document.createElement('span');
      badge.className = 'gd-edge-branch';
      tab.appendChild(badge);
    }
    badge.textContent = name;
    tab.title = 'Show the function browser — on branch "' + name + '"';
  } else if (badge) {
    badge.remove();
    tab.title = 'Show the function browser';
  }
}
window.gdSyncEdgeBranchBadge = gdSyncEdgeBranchBadge;

function toggleBranchPopover() {
  const popover = document.getElementById('branch-popover');
  if (!popover) return;
  if (popover.classList.contains('hidden')) openBranchPopover();
  else closeBranchPopover();
}

function closeBranchPopover() {
  closeProtectionMenu();   // don't orphan the ⚙ menu over a hidden popover
  closeBranchMoreMenus();
  const popover = document.getElementById('branch-popover');
  if (popover) popover.classList.add('hidden');
}

// Close every open per-row ⋯ menu.
function closeBranchMoreMenus() {
  document.querySelectorAll('.branch-row-more-menu.open').forEach((m) => {
    m.classList.remove('open');
  });
  document.querySelectorAll('.branch-row-more[aria-expanded="true"]').forEach((b) => {
    b.setAttribute('aria-expanded', 'false');
  });
}

// Toggle one row's ⋯ menu; position it under the button (the menu is
// `position: fixed`, so the popover list's overflow can't clip it).
function toggleBranchMoreMenu(btn) {
  const name = btn.getAttribute('data-more-branch');
  const menu = btn.parentElement?.querySelector(
    '.branch-row-more-menu[data-more-menu="' + (window.CSS?.escape ? CSS.escape(name) : name) + '"]')
    || btn.nextElementSibling;
  if (!menu) return;
  const wasOpen = menu.classList.contains('open');
  closeBranchMoreMenus();
  if (wasOpen) return;
  const r = btn.getBoundingClientRect();
  menu.style.top = (r.bottom + 4) + 'px';
  menu.style.left = Math.max(8, Math.min(r.right - 200, window.innerWidth - 210)) + 'px';
  menu.classList.add('open');
  btn.setAttribute('aria-expanded', 'true');
  // Menu items either reload the popover (propose / delete / policy)
  // or open their own floating menu (protection) — close the ⋯ shell
  // as soon as one is picked.
  menu.querySelectorAll('button').forEach((item) => {
    item.addEventListener('click', () => closeBranchMoreMenus(), { once: true });
  });
}

function positionBranchPopover() {
  const popover = document.getElementById('branch-popover');
  const btn = document.getElementById('branch-chip-btn');
  if (!popover || !btn) return;
  // Reparent to <body> on first open: the popover's mount point
  // lives inside #side-menu, whose `transform: translateX(0)` (used
  // for the collapse slide-out animation) makes #side-menu the
  // containing block for `position: fixed` descendants — clipping
  // the popover to the sidebar's bounds and trapping it underneath
  // the sidebar's opaque background. Same fix as editor-auth.js.
  if (popover.parentElement !== document.body) {
    document.body.appendChild(popover);
  }
  if (typeof anchorBelowClamped === 'function') {
    anchorBelowClamped(popover, btn);
  }
}

async function openBranchPopover() {
  const popover = document.getElementById('branch-popover');
  if (!popover) return;
  popover.classList.remove('hidden');
  popover.innerHTML = '<div class="branch-popover-loading">Loading branches…</div>';
  positionBranchPopover();
  try {
    const resp = await window.authFetch('/partials/branch-popover');
    if (resp.status === 401) {
      throw Object.assign(new Error('Sign in to manage branches'), { code: 'unauth' });
    }
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    popover.innerHTML = await resp.text();
    wireBranchPopoverHandlers(popover, getCurrentBranchName());
  } catch (err) {
    popover.innerHTML = '<div class="branch-popover-error">'
      + 'Failed to load branches: ' + (err?.message || 'unknown error')
      + '</div>';
  }
  // Re-anchor — content size changed after the loading → list swap.
  positionBranchPopover();
}

// Bind row + action click handlers to the swapped partial body. The
// graph renders `data-branch-name` / `data-merge-source` /
// `data-diff-source` attrs on every interactive element; this fn
// translates those into the corresponding navigations / mutations.
function wireBranchPopoverHandlers(popover, current) {
  // Row clicks switch branch. Buttons inside `.branch-row-actions`
  // stop propagation so they don't double-fire as a switch. Clicking
  // the CURRENT row is a no-op for switching but still dismisses the
  // popover.
  popover.querySelectorAll('.branch-row[data-branch-name]').forEach((row) => {
    row.addEventListener('click', async (e) => {
      if (e.target.closest('.branch-row-actions')) return;
      const name = row.getAttribute('data-branch-name');
      if (name === current) { closeBranchPopover(); return; }
      // A row in the "Merged" group is folded away; opening it brings it
      // back to the active list — asked first, so a stray click on the
      // folded group does not silently un-archive (the ⋯ menu's "Reopen"
      // does the same without switching).
      if (row.getAttribute('data-archived') === '1') {
        if (!confirm('Reopen "' + name + '"? It moves back to the active list.')) return;
        try {
          await window.authFetch(API.api_branches_ref_archive(name), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ archived: false }),
          });
        } catch (_) { /* switching still works; the row stays folded until the next open */ }
      }
      switchToBranch(name);
    });
  });

  // Protected-branch shield (tenancy only — CSS hides it otherwise):
  // a mini-menu of the three write policies; picking one POSTs
  // /api/branches/:ref/policy and reloads the popover. WHO may flip a
  // policy is enforced server-side (owner / org admins) — a rejected
  // change surfaces in the shared error slot.
  popover.querySelectorAll('.branch-row-policy').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      openBranchPolicyMenu(btn);
    });
  });

  // ⚙ Protection menu (open-core): require-merge / required-approvals /
  // count-self-approval, consolidated into one popover so the row action
  // bar stays uncluttered.
  popover.querySelectorAll('.branch-row-protect').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      openProtectionMenu(btn);
    });
  });

  // Change-proposal toggle (open-core): mark/unmark this branch as a
  // proposal for review into its base. Click POSTs the negation of the
  // current state (read off `data-review-state`) to /branches/:ref/propose,
  // then reloads the popover.
  popover.querySelectorAll('.branch-row-propose').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleBranchPropose(btn);
    });
  });

  // ⋯ → Archive / Reopen: POSTs /branches/:ref/archive with the flipped
  // state (read off `data-archived`) and re-renders the popover.
  popover.querySelectorAll('.branch-row-archive').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleBranchArchive(btn);
    });
  });

  // Approve a proposal (open-core reviewer action). POSTs
  // /branches/:ref/approve; a 403 (not allowed) surfaces in the slot.
  popover.querySelectorAll('.branch-row-approve').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      approveProposal(btn);
    });
  });

  // Fill "n/N approvals" onto each proposed row + a "Proposals (N)"
  // header, so a reviewer sees review status at a glance.
  populateReviewStatus(popover);

  popover.querySelectorAll('.branch-row-delete').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteBranchWithConfirm(btn.getAttribute('data-branch-name'),
                              branchRefFrom(btn, btn.getAttribute('data-branch-name')));
    });
  });

  popover.querySelectorAll('.branch-row-merge').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      // Target = the CURRENT branch; its row is in this very popover —
      // use its id so a slash-named current branch can be a target.
      const curRow = popover.querySelector(
        '.branch-row[data-branch-name="' + (window.CSS?.escape ? CSS.escape(current) : current) + '"]');
      mergeBranchInto(btn.getAttribute('data-merge-source'), current,
                      null, curRow?.getAttribute('data-branch-id') || null);
    });
  });

  // ⋯ overflow menu — server-rendered hidden inside each row (so the
  // propose / protect / policy / delete bindings above keep finding
  // their buttons); JS toggles + positions it. One open menu at a time.
  popover.querySelectorAll('.branch-row-more').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleBranchMoreMenu(btn);
    });
  });

    // Δ — TOGGLES compare mode (UX-v3: picking the branch IS entering
  // the diff; the row's Δ is lit while comparing, click again exits).
  const comparedNow = (typeof gdDiffModeBranch === 'function')
    ? gdDiffModeBranch() : null;
  popover.querySelectorAll('.branch-row-diff').forEach((btn) => {
    const mine = btn.getAttribute('data-diff-source');
    if (comparedNow && mine === comparedNow) {
      btn.classList.add('on');
      btn.setAttribute('data-tip', 'Stop comparing');
    }
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      closeBranchPopover();
      if (btn.classList.contains('on')) {
        if (typeof gdExitDiffMode === 'function') gdExitDiffMode();
      } else if (typeof gdEnterDiffMode === 'function') {
        gdEnterDiffMode(mine);
      }
    });
  });

  // 💬 in the ⋯ menu — the review conversation dialog.
  popover.querySelectorAll('.branch-row-review').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const source = btn.getAttribute('data-review-branch');
      closeBranchPopover();
      if (typeof showReviewDialog === 'function') {
        showReviewDialog(source, branchRefFrom(btn, source));
      }
    });
  });

  const createInput = document.getElementById('branch-create-input');
  const createBtn = document.getElementById('branch-create-btn');
  if (createBtn && createInput) {
    createBtn.addEventListener('click', () => createBranchFromInput(current));
    createInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') createBranchFromInput(current);
    });
  }

  wireHubSyncSection(popover);
}

// Hub sync — the partial renders `.branch-popover-hub` only when the
// server is wired to a hub (GRAPHDEN_HUB_URL). The /api/sync/* routes
// live in the OPTIONAL registry package, so the section is removed when
// window.API lacks them (hub env set but registry package dropped).
// Push snapshots the CURRENT branch (the branch header scopes the POST)
// onto the hub as push/<branch>; Pull lands the hub's main locally as
// hub/main and refreshes the list so the new branch shows up.
function wireHubSyncSection(popover) {
  const section = popover.querySelector('.branch-popover-hub');
  if (!section) return;
  const api = (typeof window.API === 'object' && window.API) ? window.API : null;
  if (!api || typeof api.api_sync_push === 'undefined') {
    section.remove();
    return;
  }
  const pushBtn = section.querySelector('#branch-hub-push');
  const pullBtn = section.querySelector('#branch-hub-pull');
  const setBusy = (busy) => {
    [pushBtn, pullBtn].forEach((b) => { if (b) b.disabled = busy; });
  };
  const report = (text, isError) => {
    const status = document.getElementById('branch-hub-status');
    if (!status) return;
    status.textContent = text;
    status.classList.toggle('branch-hub-status-error', !!isError);
  };
  async function runSync(url) {
    setBusy(true);
    report('Syncing with the hub…', false);
    try {
      const resp = await window.authFetch(url, { method: 'POST' });
      const data = await resp.json().catch(() => null);
      if (!resp.ok || !data || data.ok === false) {
        const reason = (data && (data.reason || data.error))
          || ('HTTP ' + resp.status);
        const detail = data?.hint ? ' — ' + data.hint : '';
        report('Failed: ' + reason + detail, true);
        return null;
      }
      return data;
    } catch (err) {
      report('Failed: ' + (err?.message || 'network error'), true);
      return null;
    } finally {
      setBusy(false);
    }
  }
  if (pushBtn) {
    pushBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const d = await runSync(api.api_sync_push);
      if (d) {
        const n = (d['fn-ids'] || []).length;
        report('Pushed → ' + (d.target || 'push branch') + ' (' + n
          + ' fns). Review + merge on the hub.', false);
      }
    });
  }
  if (pullBtn) {
    pullBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const d = await runSync(api.api_sync_pull);
      if (d) {
        // Refresh the list so hub/main appears, then restate the outcome
        // (the reload swaps the status slot out with the rest of the body).
        await openBranchPopover();
        const status = document.getElementById('branch-hub-status');
        if (status) {
          status.textContent = 'Pulled → ' + (d.branch || 'hub/main')
            + ' — Δ compare it against your branch, then ⇢ merge.';
        }
      }
    });
  }
}


// The /api/branches/:ref/* ops take the ref as ONE path segment, so a
// branch NAME containing "/" (the hub's push/<x> convention, or any
// user-typed slash) can never round-trip through the URL. Every row
// carries `data-branch-id`; prefer it — `resolve-branch-ref` accepts a
// UUID — and fall back to the name for markup that predates the attr.
function branchRefFrom(el, fallbackName) {
  return el?.closest?.('.branch-row')?.getAttribute('data-branch-id')
    || fallbackName;
}


// Mark/unmark a branch as a change proposal for review into its base.
// `proposed` is the JSON key the /propose handler reads. WHO may
// propose/withdraw is open-core (any authenticated writer of the branch);
// a rejection surfaces in the shared slot.
async function toggleBranchArchive(btn) {
  const branchName = btn.getAttribute('data-archive-branch');
  const branchRef = branchRefFrom(btn, branchName);
  const next = btn.getAttribute('data-archived') !== '1';
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(API.api_branches_ref_archive(branchRef), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ archived: next }),
    });
    const body = await resp.json();
    if (body.ok) {
      openBranchPopover(); // re-render: the row moves between the lists
    } else if (err) {
      err.textContent = body.error || 'Could not change the archive state';
      err.classList.remove('hidden');
    }
  } catch (e2) {
    if (err) {
      err.textContent = 'Network error: ' + (e2?.message || e2);
      err.classList.remove('hidden');
    }
  }
}


async function toggleBranchPropose(btn) {
  const branchName = btn.getAttribute('data-propose-branch');
  const branchRef = branchRefFrom(btn, branchName);
  const next = btn.getAttribute('data-review-state') !== '1';
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(API.api_branches_ref_propose(branchRef), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ proposed: next }),
    });
    const body = await resp.json();
    if (body.ok) {
      openBranchPopover(); // re-render rows with the new proposal state
    } else if (err) {
      err.textContent = body.error || 'Could not change the proposal state';
      err.classList.remove('hidden');
    }
  } catch (e2) {
    if (err) {
      err.textContent = 'Network error: ' + (e2?.message || e2);
      err.classList.remove('hidden');
    }
  }
}

// Record the caller's approval of a proposal branch. A 403 (the caller
// may not approve merges into the target) or any error surfaces in the
// shared slot.
async function approveProposal(btn) {
  const branchName = btn.getAttribute('data-approve-branch');
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(
      API.api_branches_ref_approve(branchRefFrom(btn, branchName)), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
    const body = await resp.json();
    if (resp.ok && body.ok) {
      openBranchPopover();
    } else if (err) {
      err.textContent = body.error
        || (resp.status === 403 ? 'You are not allowed to approve merges into this branch' : 'Could not approve');
      err.classList.remove('hidden');
    }
  } catch (e2) {
    if (err) {
      err.textContent = 'Network error: ' + (e2?.message || e2);
      err.classList.remove('hidden');
    }
  }
}

// Fill "n/N approvals" onto each PROPOSED row + a "Proposals (N)" header,
// so a reviewer sees review status at a glance. One /approvals fetch per
// proposed row (proposals are few); best-effort — a failure is silent.
async function populateReviewStatus(popover) {
  const approveBtns = [...popover.querySelectorAll('.branch-row-approve[data-approve-branch]')];
  if (!approveBtns.length) return;
  const header = document.createElement('div');
  header.className = 'branch-proposals-header';
  header.textContent = approveBtns.length
    + (approveBtns.length === 1 ? ' proposal awaiting review' : ' proposals awaiting review');
  const anchor = popover.querySelector('.branch-section-rows');
  if (anchor?.parentNode) anchor.parentNode.insertBefore(header, anchor);
  for (const btn of approveBtns) {
    const name = btn.getAttribute('data-approve-branch');
    try {
      const resp = await window.authFetch(
        API.api_branches_ref_approvals(branchRefFrom(btn, name)));
      if (!resp.ok) continue;
      const st = await resp.json();
      const req = st.required ?? 0;
      if (req <= 0) continue; // no approvals required → nothing to show
      const badge = document.createElement('span');
      badge.className = 'branch-appr-count' + (st.satisfied ? ' ok' : '');
      badge.textContent = (st.have ?? 0) + '/' + req;
      badge.title = 'approvals recorded / required';
      btn.insertAdjacentElement('afterend', badge);
    } catch (_) { /* best-effort */ }
  }
}

async function createBranchFromInput(parentName) {
  const input = document.getElementById('branch-create-input');
  const err = document.getElementById('branch-popover-error');
  const name = input.value.trim();
  if (!name) {
    err.textContent = 'Branch name is required';
    err.classList.remove('hidden');
    return;
  }
  err.classList.add('hidden');
  // Advanced → protected-branch write policy; "open" (the default) is
  // simply not sent, so the ordinary path stays a plain branch.
  const policySel = document.getElementById('branch-create-policy');
  const policy = policySel && policySel.value !== 'open' ? policySel.value : null;
  try {
    const resp = await window.authFetch(API.api_branches, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Object.assign(
        { name, 'base-branch-id': parentName },
        policy ? { 'write-policy': policy } : {}
      )),
    });
    const body = await resp.json();
    if (resp.status === 401) {
      err.textContent = 'Sign in to create branches';
      err.classList.remove('hidden');
      return;
    }
    if (!resp.ok || body.ok === false) {
      err.textContent = body?.error || ('HTTP ' + resp.status);
      err.classList.remove('hidden');
      return;
    }
    // Switch immediately — the user just created this; almost certainly
    // they want to start working on it.
    switchToBranch(name);
  } catch (e) {
    err.textContent = e?.message || 'Create failed';
    err.classList.remove('hidden');
  }
}

async function deleteBranchWithConfirm(name, ref) {
  if (!confirm('Delete branch "' + name + '"? Every version row on it will be removed.')) return;
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(
      API.api_branches_ref(ref || name),
      { method: 'DELETE' });
    const body = await resp.json();
    if (resp.status === 401) {
      err.textContent = 'Sign in to delete branches';
      err.classList.remove('hidden');
      return;
    }
    if (!resp.ok || body.ok === false) {
      const detail = body?.['child-branch-ids']
        ? ' (' + body['child-branch-ids'].length + ' child branch(es) block deletion)'
        : '';
      err.textContent = (body?.error || ('HTTP ' + resp.status)) + detail;
      err.classList.remove('hidden');
      return;
    }
    // If we just deleted the current branch, fall back to main.
    if (name === getCurrentBranchName()) {
      switchToBranch(DEFAULT_BRANCH);
      return;
    }
    // Otherwise just re-render the popover with the updated list.
    openBranchPopover();
  } catch (e) {
    err.textContent = e?.message || 'Delete failed';
    err.classList.remove('hidden');
  }
}


// Public API for sibling modules.
window.initBranchSelector = initBranchSelector;
