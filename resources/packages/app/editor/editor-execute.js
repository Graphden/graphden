// Editor Execute — orchestrator.
//
// Click ▶ on the fn-card root row → this module opens a singleton
// popover that:
//
//   1. Loads a type-aware `/api/value-form` for each free-arg of the
//      fn (reusing `editor-value-form.js` — same widgets that drive
//      binding-edit popovers).
//   2. Shows declared-effects chips when the fn isn't pure.
//   3. On Run: POSTs `/api/execute`, then either renders the inline
//      result OR (when the request flipped to async) starts a
//      polling loop with exponential backoff against
//      `/api/execute/:id` and offers Cancel.
//
// Splits:
//   * Pure rendering primitives → editor-execute-result.js
//   * History panel (fetch + render rows + Repeat)
//     → editor-execute-history.js
//
// One popover at a time — same dismiss scaffold the provenance /
// mismatch popovers use.
//
// Auth: ▶ is gated to authenticated users at the fn-card layer
// (matches the rest of the write-actions). The POST itself goes
// through `authFetch` so a stale session also surfaces a 401.

// graph-first-exception: the popover SHELL is server-rendered
// (/partials/execute-popover) and mounted here; this file owns the
// client-only run/poll/cancel state machine + /api/value-form widget
// mounts, which have no server-side representation between requests.
let executePopoverEl = null;
let executePopoverAnchor = null;

// Per-popover-instance state — wiped on dismiss. `argFormHosts` is
// also read by editor-execute-history.js (Repeat re-fills these
// widgets); the editor JS bundle concatenates the scripts so the
// `let` is shared.
let pollState = null;     // { execId, attempt, timer, anchorEl }
let argFormHosts = [];    // [{ slotName, slotId, hostEl, read }]


function ensureExecutePopoverEl() {
  if (executePopoverEl) return executePopoverEl;
  const el = document.createElement('div');
  el.className = 'execute-popover';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Run function');
  // Keyboard: Enter (outside a textarea, where it inserts a newline)
  // triggers Run when the button is enabled. Bound ONCE here — the popover
  // is a singleton reused across opens, so binding this in showExecutePopover
  // stacked a fresh keydown listener per open, each closing over a stale
  // (detached) run button → Enter re-ran previously-opened fns. Read the
  // CURRENT run button off the DOM at keypress time instead.
  el.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    if (e.target?.tagName === 'TEXTAREA') return;
    const runBtn = el.querySelector('.execute-run-btn');
    if (!runBtn || runBtn.disabled) return;
    e.preventDefault();
    runBtn.click();
  });
  document.body.appendChild(el);
  executePopoverEl = el;
  return el;
}


function executePopoverVisible() {
  return !!executePopoverEl && executePopoverEl.classList.contains('visible');
}


function stopPolling() {
  if (pollState?.timer) {
    clearTimeout(pollState.timer);
  }
  pollState = null;
}


function hideExecutePopover() {
  if (!executePopoverEl) return;
  stopPolling();
  argFormHosts = [];
  executePopoverEl.classList.remove('visible');
  executePopoverEl.style.display = 'none';
  if (executePopoverAnchor) {
    try { executePopoverAnchor.setAttribute('aria-expanded', 'false'); }
    catch (_) {}
  }
  executePopoverAnchor = null;
}


// === Arg-form mounting =====================================================
//
// The popover shell (header / effects banner / per-arg rows / options
// / action bar) ships from `GET /partials/execute-popover?fn-id=…` —
// including the free-arg set, which the server derives via its own
// `:free-arg-slot-map` (the former JS re-derivation of it lived here
// and could drift). Each `[data-slot-id]` host in the shell gets its
// `/api/value-form` widget mounted by `mountArgFormHost`.

// Mount one arg's form into a server-rendered host. Returns a
// `read()` closure that collectFormValue's the current input.
async function mountArgFormHost(fnEntity, host) {
  const slotId = host.dataset.slotId;
  const slotName = host.dataset.slotName;
  const payload = await fetchValueForm({ 'fn-id': fnEntity.id,
                                         'slot-id': slotId });
  host.textContent = '';
  if (!payload || payload.ok === false) {
    host.textContent = payload?.error || 'Could not load form for ' + slotName;
    host.classList.add('execute-arg-form-error');
  } else {
    renderValueForm(host, payload, {});
  }
  return () => {
    const root = host.querySelector('[data-form-root]') || host;
    if (typeof collectFormValue !== 'function') return null;
    const collected = collectFormValue(root);
    // `collectFormValue` returns `{ok, value, errors}` — when the
    // user's input fails type-validation, ok=false and value may be
    // stale / undefined. We surface the value either way; the
    // backend will reject if the unwrapped shape doesn't fit the
    // slot type. (Front-end validation is a hint, not a gate.)
    if (collected && typeof collected === 'object'
        && 'value' in collected && 'ok' in collected) {
      return collected.value;
    }
    return collected;
  };
}


// === Polling ===============================================================
//
// Exponential backoff: 500ms → 1s → 2s → 5s → 30s (then 30s forever).
// `attempt` indexes into this schedule. Stops when the row status
// flips OFF :pending (resolved/failed/cancelled) or when the popover
// is dismissed (`stopPolling` clears the timer).

const POLL_DELAYS_MS = [500, 1000, 2000, 5000, 30000];


function nextPollDelay(attempt) {
  return POLL_DELAYS_MS[Math.min(attempt, POLL_DELAYS_MS.length - 1)];
}


async function pollOnce(execId, resultHostEl) {
  try {
    const r = await authFetch(API.api_execute_id(execId),
                              { method: 'GET' });
    if (!r.ok) {
      resultHostEl.textContent = '';
      const msg = authFetchErrorMessage(r, {
        authExpired: 'Sign-in expired during polling. Re-authenticate via the toolbar lock icon; the run continues in the background and can be reopened from History.',
        fallback: (resp) => 'Polling failed: HTTP ' + resp.status,
      });
      resultHostEl.appendChild(renderErrorPane(msg));
      stopPolling();
      return;
    }
    const row = await r.json();
    // The popover may have been dismissed (stopPolling nulls pollState),
    // OR a newer Run replaced pollState with a different execId, while
    // this fetch was in flight. Bail unless we're still the current poll —
    // otherwise a stale poll would either reschedule onto the wrong execId
    // or call stopPolling() and silently kill the newer run.
    if (!pollState || pollState.execId !== execId) return;
    const status = String(row.status || '').replace(/^:/, '');
    if (status === 'succeeded' || status === 'failed' || status === 'cancelled') {
      // Body hiccup comes from `/partials/execute-result?id=…` — the
      // server walks the row's status / taint / result / error and
      // emits the matching pane (scalar / list / record / tainted /
      // error). The /api/execute JSON we just got still feeds the
      // runtime-effects strip URL params (declared+runtime arrays).
      try {
        const bodyResp = await authFetch('/partials/execute-result?id='
                                         + encodeURIComponent(execId));
        resultHostEl.textContent = '';
        if (bodyResp.ok) {
          resultHostEl.innerHTML = await bodyResp.text();
        } else {
          resultHostEl.appendChild(renderErrorPane('Load error: HTTP '
                                                   + bodyResp.status));
        }
      } catch (e) {
        resultHostEl.textContent = '';
        resultHostEl.appendChild(renderErrorPane('Load error: ' + e.message));
      }
      appendRuntimeEffectsStrip(resultHostEl,
                                row['runtime-effects'],
                                row['declared-effects']);
      stopPolling();
      return;
    }
    // Still pending — schedule the next poll.
    pollState.attempt += 1;
    pollState.timer = setTimeout(
      () => pollOnce(execId, resultHostEl),
      nextPollDelay(pollState.attempt));
  } catch (e) {
    resultHostEl.textContent = '';
    resultHostEl.appendChild(renderErrorPane('Polling error: ' + e.message));
    stopPolling();
  }
}


function startPolling(execId, resultHostEl) {
  stopPolling();
  pollState = { execId, attempt: 0, timer: null };
  pollState.timer = setTimeout(
    () => pollOnce(execId, resultHostEl),
    nextPollDelay(0));
}


// === Submit ================================================================

async function submitExecution(fnEntity, args, persist, resultHostEl, cancelBtn) {
  resultHostEl.textContent = '';
  resultHostEl.appendChild(renderSubmitSpinner('Submitting…'));
  try {
    const r = await authFetch(API.api_execute, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 'fn-id': fnEntity.id,
                              'args': args,
                              'persist?': persist }),
    });
    const body = await r.json().catch(() => null);
    resultHostEl.textContent = '';
    if (!r.ok) {
      const msg = authFetchErrorMessage(r, {
        authExpired: 'Sign-in expired. Click the lock icon in the toolbar to re-authenticate, then try Run again.',
        fallback: body?.error,
      });
      resultHostEl.appendChild(renderErrorPane(msg, body?.['error-data']));
      return;
    }
    const status = String(body?.status || '').replace(/^:/, '');
    const execId = body?.['execution-id'];
    if (status === 'rejected') {
      resultHostEl.appendChild(renderErrorPane(body.error, body['error-data']));
    } else if (status === 'pending') {
      resultHostEl.appendChild(renderPendingPane(execId));
      cancelBtn.style.display = '';
      cancelBtn.dataset.execId = execId;
      startPolling(execId, resultHostEl);
    } else {
      // Terminal status (succeeded / failed / tainted / cancelled) —
      // route the body through one of the two server partials so the
      // editor's render stays exclusively server-side. Persisted runs
      // (effectful + opt-in `persist?=true`) read off the stored row
      // via `?id=…`; non-persisted inline runs POST the response back
      // so the same `:render-execute-result-hiccup` walker emits the
      // same hiccup. Result preview, truncation note, list/record/
      // scalar dispatch, tainted pane all live source-side in
      // `crud.fn-execution/render-execute-result-hiccup`.
      try {
        const bodyResp = execId
          ? await authFetch('/partials/execute-result?id='
                            + encodeURIComponent(execId))
          : await authFetch('/partials/execute-result-inline', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify(body),
            });
        if (bodyResp.ok) {
          resultHostEl.innerHTML = await bodyResp.text();
        } else {
          resultHostEl.appendChild(renderErrorPane('Load error: HTTP '
                                                   + bodyResp.status));
        }
      } catch (e) {
        resultHostEl.appendChild(renderErrorPane('Load error: ' + e.message));
      }
    }
    appendRuntimeEffectsStrip(resultHostEl,
                              body['runtime-effects'],
                              body['declared-effects']);
  } catch (e) {
    resultHostEl.textContent = '';
    resultHostEl.appendChild(renderErrorPane('Network error: ' + e.message));
  }
}


async function submitCancel(execId, resultHostEl) {
  try {
    await authFetch(API.api_execute_id_cancel(execId),
                    { method: 'POST' });
    // The polling loop will observe the status flip on its next tick.
  } catch (e) {
    resultHostEl.appendChild(renderErrorPane('Cancel error: ' + e.message));
  }
}


// === Main entry point ======================================================

async function showExecutePopover(fnEntity, anchorEl) {
  if (!fnEntity || !anchorEl) return;
  if (typeof fetchValueForm !== 'function') return;  // value-form not loaded
  const el = ensureExecutePopoverEl();
  el.textContent = '';
  argFormHosts = [];

  // Shell ships from the server — header / effects banner / free-arg
  // hosts / options / action bar / hosts, all derived from the fn row
  // + rich-types registry + the backend's own free-arg-slot-map.
  let html;
  try {
    const r = await authFetch('/partials/execute-popover?fn-id='
                              + encodeURIComponent(fnEntity.id));
    if (!r.ok) {
      el.textContent = 'Could not load the run form (HTTP ' + r.status + ').';
      return;
    }
    html = await r.text();
  } catch (e) {
    el.textContent = 'Could not load the run form: ' + e.message;
    return;
  }
  el.innerHTML = html;

  // --- post-swap wiring ---
  const head = el.querySelector('.execute-popover-header');
  const historyBtn = el.querySelector('.execute-history-toggle');
  const historyHost = el.querySelector('.execute-history-host');
  const confirmCb = el.querySelector('.execute-confirm-checkbox');
  const persistCb = el.querySelector('.execute-persist-checkbox');
  const runBtn = el.querySelector('.execute-run-btn');
  const cancelBtn = el.querySelector('.execute-cancel-btn');
  const resultHost = el.querySelector('.execute-result-host');
  const close = el.querySelector('.execute-popover-close');
  if (close) {
    close.addEventListener('click', (e) => {
      e.stopPropagation();
      hideExecutePopover();
    });
  }

  // Branch indicator — which branch chip the user is on is CLIENT
  // state (URL ?branch= + localStorage), so the pill is inserted here
  // rather than baked into the partial. Inverted-pill style mirrors
  // the top-bar chip's "on a feature branch" affordance.
  if (head && typeof isOnDefaultBranch === 'function' && !isOnDefaultBranch()) {
    const branchPill = document.createElement('span');
    branchPill.className = 'execute-popover-branch';
    const branchName = typeof getCurrentBranchName === 'function'
      ? getCurrentBranchName() : '?';
    branchPill.textContent = 'on ' + branchName;
    branchPill.title = 'Run resolves to this fn as seen on branch "'
                       + branchName + '"; switch branches to run a different version';
    const titleEl = head.querySelector('.execute-popover-title');
    if (titleEl?.nextSibling) head.insertBefore(branchPill, titleEl.nextSibling);
    else head.appendChild(branchPill);
  }

  // Mount every free-arg's value-form in parallel — sequential await
  // would serialise the N HTTP roundtrips.
  const hosts = Array.from(el.querySelectorAll('.execute-arg-form[data-slot-id]'));
  const readers = await Promise.all(
    hosts.map((host) => mountArgFormHost(fnEntity, host)),
  );
  for (let i = 0; i < hosts.length; i++) {
    argFormHosts.push({ slotName: hosts[i].dataset.slotName,
                        slotId: hosts[i].dataset.slotId,
                        hostEl: hosts[i],
                        read: readers[i] });
  }

  // Effect-confirm gate — the partial emits Run disabled when the fn
  // declares effects; live-toggle as the user checks/unchecks.
  if (confirmCb && runBtn) {
    confirmCb.addEventListener('change', () => {
      runBtn.disabled = !confirmCb.checked;
      runBtn.title = confirmCb.checked
        ? 'Run' : 'Confirm side-effects acknowledgement first';
    });
  }

  // History toggle handler — needs resultHost in scope so panel
  // rows can expand their full result into it.
  let historyLoaded = false;
  historyBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    const isOpen = historyHost.style.display !== 'none';
    if (isOpen) {
      historyHost.style.display = 'none';
      historyBtn.setAttribute('aria-expanded', 'false');
      return;
    }
    historyHost.style.display = '';
    historyBtn.setAttribute('aria-expanded', 'true');
    if (!historyLoaded) {
      historyHost.textContent = '';
      historyHost.appendChild(renderSubmitSpinner('Loading history…'));
      const panel = await buildHistoryPanel(fnEntity, resultHost);
      historyHost.textContent = '';
      historyHost.appendChild(panel);
      historyLoaded = true;
    }
  });

  runBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    if (runBtn.disabled) return;  // defensive — gate already enforced
    const args = {};
    for (const a of argFormHosts) {
      const v = a.read();
      if (v !== undefined && v !== null && v !== '') {
        args[a.slotName] = v;
      }
    }
    await submitExecution(fnEntity, args, persistCb.checked, resultHost, cancelBtn);
    // Run completed — the new row (if persisted) belongs in History.
    // Invalidate the cached panel so the next toggle re-fetches; if the
    // panel is currently OPEN, refresh it in-place so the user sees the
    // new row immediately without having to close/reopen.
    historyLoaded = false;
    if (historyHost.style.display !== 'none') {
      historyHost.textContent = '';
      historyHost.appendChild(renderSubmitSpinner('Refreshing history…'));
      const panel = await buildHistoryPanel(fnEntity, resultHost);
      historyHost.textContent = '';
      historyHost.appendChild(panel);
      historyLoaded = true;
    }
  });

  cancelBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    const id = cancelBtn.dataset.execId;
    if (id) await submitCancel(id, resultHost);
  });

  // (Enter-to-Run keydown is bound once in ensureExecutePopoverEl —
  // binding it here would leak a listener per open onto the singleton.)

  // Show + position
  if (executePopoverAnchor && executePopoverAnchor !== anchorEl) {
    try { executePopoverAnchor.setAttribute('aria-expanded', 'false'); }
    catch (_) {}
  }
  try { anchorEl.setAttribute('aria-expanded', 'true'); }
  catch (_) {}
  executePopoverEl.classList.add('visible');
  anchorBelowClamped(executePopoverEl, anchorEl,
                     { fallbackW: 480, fallbackH: 320 });
  executePopoverAnchor = anchorEl;
  // Auto-focus the first form input (textarea / text input) inside
  // the popover so the user can start typing or hit Enter immediately
  // without an extra click. Skip if no free-args exist (focus would
  // land on the Run button, which is fine on its own). RAF wait gives
  // the value-form's deferred renderers (Tier-2 widgets) a tick to
  // mount.
  requestAnimationFrame(() => {
    const first = executePopoverEl.querySelector(
      '.execute-popover-body textarea, .execute-popover-body input:not([type=checkbox])');
    if (first) {
      try { first.focus(); }
      catch (_) {}
    }
  });
}


installPopoverDismiss({
  getEl: () => executePopoverEl,
  getAnchor: () => executePopoverAnchor,
  isVisible: executePopoverVisible,
  onDismiss: hideExecutePopover,
});


window.showExecutePopover = showExecutePopover;
