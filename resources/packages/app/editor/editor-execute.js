// Editor Execute — orchestrator.
//
// ▶ Run (fn-card root row / Explorer row ⋯) → the RUN PANE: the run
// form + result + this fn's history, mounted in the right inspector's
// Runs tab. It used to be a floating anchored popover; that shape
// fought the canvas it reports on — it parked over the very nodes a
// traced run highlights, could not be moved, and the first canvas pan
// dismissed it (2026-08-27 lesson-27 finding). The inspector is a
// fixed column, so the canvas stays fully visible and pannable while
// a run session is open — finishing the 2026-08 shell redesign that
// already retired the Run surface in favour of "▶ action + inspector".
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
//     → editor-execute-history.js — mounted BELOW the form in the
//     same tab (one list, with working Repeat / path / tree / expand
//     bindings; the tab's former htmx-only mount rendered those
//     buttons dead).
//
// Auth: ▶ is gated to authenticated users at the fn-card layer
// (matches the rest of the write-actions). The POST itself goes
// through `authFetch` so a stale session also surfaces a 401.

// graph-first-exception: the pane SHELL is server-rendered
// (/partials/execute-popover) and mounted here; this file owns the
// client-only run/poll/cancel state machine + /api/value-form widget
// mounts, which have no server-side representation between requests.

// Per-mount state — wiped on every (re)mount. `argFormHosts` is
// also read by editor-execute-history.js (Repeat re-fills these
// widgets); the editor JS bundle concatenates the scripts so the
// `let` is shared.
let pollState = null;     // { execId, attempt, timer }
let argFormHosts = [];    // [{ slotName, slotId, hostEl, read }]


function stopPolling() {
  if (pollState?.timer) {
    clearTimeout(pollState.timer);
  }
  pollState = null;
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
    // A widget with NO input at all (fn-typed slots and other
    // non-enterable kinds) used to render as a silent empty bar —
    // indistinguishable from "broken". Say what it is instead.
    if (!host.querySelector('input, textarea, select, button')) {
      const note = document.createElement('div');
      note.className = 'execute-arg-form-note';
      note.textContent = 'Not entered here — this argument is a function, '
        + 'resolved from the graph at run time.';
      host.appendChild(note);
    }
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
        authExpired: 'Sign-in expired during polling. Re-authenticate from the top bar; the run continues in the background and can be reopened from the Runs tab.',
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
      if (typeof appendPathViewAffordance === 'function') {
        appendPathViewAffordance(resultHostEl, row['path-trace']);
      }
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

// Debug P3 — estimated cost line for the capture-values confirm dialog
// (PHILOSOPHY § Debugging constraint 3). Counts the fn's forward
// ref-closure over whatever graphData currently holds (the selected
// fn's subtree is loaded with it; the sidebar is lazy, so this is a
// lower bound on fns — the wording stays "up to ~N KB" per the 4 KB
// per-entry cap, honest in both directions).
function estimateTraceClosureSize(fnId) {
  const seen = new Set();
  const stack = [fnId];
  while (stack.length > 0) {
    const id = stack.pop();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    const fn = lookups?.fnMap?.get(id);
    (fn?.['parent-ids'] || []).forEach((p) => stack.push(p));
    (lookups?.bindingsByFn?.get(id) || []).forEach((b) => {
      if (b['ref-fn-id']) stack.push(b['ref-fn-id']);
      (lookups?.itemsByBinding?.get(b.id) || []).forEach((it) => {
        if (it['ref-fn-id']) stack.push(it['ref-fn-id']);
      });
    });
  }
  return seen.size;
}


function confirmCaptureValues(fnEntity) {
  const n = Math.max(1, estimateTraceClosureSize(fnEntity.id));
  return window.confirm(
    'Capture intermediate values for this run?\n\n'
    + 'Every traversed fn\'s return value will be recorded, up to 4 KB '
    + 'each. Estimated cost: up to ~' + (n * 4) + ' KB (~' + n
    + ' fn' + (n === 1 ? '' : 's') + ' in this fn\'s reach).\n\n'
    + 'Values that touch :secret-typed data are never captured.');
}


// Cloud interactive preview: the apps-domain URL needs a freshly-minted
// capsule (short-TTL, fn+branch-scoped), so the pane renders a BUTTON and
// we mint on click. Self-host renders a plain <a> instead — this handler
// only ever sees the button variant. Delegated: result panes arrive via
// innerHTML swaps.
document.addEventListener('click', async (e) => {
  const btn = e.target.closest('button.execute-result-open-preview[data-preview-fn-id]');
  if (!btn) return;
  e.preventDefault();
  btn.disabled = true;
  try {
    const r = await authFetch('/api/preview-token', { // api-url-drift-allow: route-collection (tenancy addon)
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 'fn-id': btn.dataset.previewFnId }),
    });
    const body = await r.json().catch(() => null);
    if (r.ok && body?.url) {
      window.open(body.url, '_blank', 'noopener');
    } else {
      window.alert(body?.error
        || 'Interactive preview is unavailable (HTTP ' + r.status + ').');
    }
  } catch (err) {
    window.alert('Interactive preview failed: ' + err.message);
  } finally {
    btn.disabled = false;
  }
});


async function submitExecution(fnEntity, args, persist, trace, captureValues,
                               resultHostEl, cancelBtn) {
  resultHostEl.textContent = '';
  resultHostEl.appendChild(renderSubmitSpinner('Submitting…'));
  try {
    const r = await authFetch(API.api_execute, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 'fn-id': fnEntity.id,
                              'args': args,
                              'persist?': persist,
                              'trace?': trace,
                              'capture-values?': captureValues }),
    });
    const body = await r.json().catch(() => null);
    resultHostEl.textContent = '';
    if (!r.ok) {
      const msg = authFetchErrorMessage(r, {
        authExpired: 'Sign-in expired. Re-authenticate from the top bar, then try Run again.',
        fallback: body?.error,
      });
      resultHostEl.appendChild(renderErrorPane(msg, body?.['error-data']));
      return;
    }
    const status = String(body?.status || '').replace(/^:/, '');
    const execId = body?.['execution-id'];
    // Mark "a persisted run happened this page-load" on <body> — the
    // interactive tour's lesson-09 step used to auto-pass off the mere
    // presence of the History toggle; this gives its `dom` check a real
    // signal to wait for.
    if (persist && status !== 'rejected') {
      document.body.dataset.gdPersistedRun = String(execId || 'yes');
    }
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
              // fn-id drives the typed-repr / component-preview
              // dispatch server-side; the inline /api/execute
              // response doesn't carry it.
              body: JSON.stringify({ ...body, 'fn-id': fnEntity.id }),
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
                              body?.['runtime-effects'],
                              body?.['declared-effects']);
    // Traced run (Debug P2) — the inline response carries the captured
    // :path-trace; offer the canvas highlight right from the result pane.
    if (typeof appendPathViewAffordance === 'function') {
      appendPathViewAffordance(resultHostEl, body?.['path-trace']);
    }
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


// === Main entry points =====================================================

// ▶ Run — select the fn (the inspector is the SELECTION's detail
// surface; a run pane for an unselected fn would show one fn's form
// under another fn's head) and land its inspector on the Runs tab,
// where `gdMountRunPane` (called from the tab renderer) mounts the
// form. `anchorEl` is accepted for call-site compatibility; nothing
// anchors to it anymore.
function showExecutePopover(fnEntity, _anchorEl) {
  if (!fnEntity) return;
  if (typeof window.gdInspectorShowRuns !== 'function') return;
  if (typeof selectedFnId !== 'undefined' && selectedFnId !== fnEntity.id
      && typeof selectFn === 'function') {
    // selectFn re-renders the inspector; the tab preset below makes
    // that render land on Runs directly.
    window.gdInspectorShowRuns(fnEntity.id, { preselectOnly: true });
    selectFn(fnEntity.id);
    return;
  }
  window.gdInspectorShowRuns(fnEntity.id);
}


// Mount the run pane (form + result host + history) into the Runs
// tab's hosts. Called by the inspector's tab renderer — every mount
// is a fresh build, so per-mount state resets here.
async function gdMountRunPane(fnId) {
  const host = document.getElementById('gd-insp-run-host');
  const runsHost = document.getElementById('gd-insp-runs');
  if (!host) return;
  const fnEntity = (typeof lookups !== 'undefined')
    ? lookups?.fnMap?.get(fnId) : null;
  if (!fnEntity) return;
  if (typeof fetchValueForm !== 'function') return;  // value-form not loaded
  if (typeof isAuthenticated === 'function' && !isAuthenticated()) {
    // The runs list already shows its own sign-in note; an
    // unauthenticated form would only 401 on Run.
    return;
  }
  stopPolling();
  const el = document.createElement('div');
  // Keeps the .execute-popover styling family (and the e2e suite's
  // `.execute-popover.visible` waits); `gd-insp-run` switches the
  // geometry from floating-fixed to in-panel static. `visible` is
  // added only once the shell + arg forms are mounted — the class
  // pair means "form ready", same contract the floating popover had.
  el.className = 'execute-popover gd-insp-run';
  el.setAttribute('role', 'form');
  el.setAttribute('aria-label', 'Run function');
  // Keyboard: Enter (outside a textarea, where it inserts a newline)
  // triggers Run when the button is enabled. The pane is rebuilt per
  // mount, so binding here cannot stack stale listeners.
  el.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    if (e.target?.tagName === 'TEXTAREA') return;
    const runBtn = el.querySelector('.execute-run-btn');
    if (!runBtn || runBtn.disabled) return;
    e.preventDefault();
    runBtn.click();
  });
  host.textContent = '';
  host.appendChild(el);
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
  const confirmCb = el.querySelector('.execute-confirm-checkbox');
  const persistCb = el.querySelector('.execute-persist-checkbox');
  const traceCb = el.querySelector('.execute-trace-checkbox');
  const captureCb = el.querySelector('.execute-capture-values-checkbox');
  const runBtn = el.querySelector('.execute-run-btn');
  const cancelBtn = el.querySelector('.execute-cancel-btn');
  const resultHost = el.querySelector('.execute-result-host');

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

  // Capture-values second-step control (Debug P3, PHILOSOPHY
  // § Debugging constraint 3): the server emits the checkbox disabled;
  // it only unlocks while "Trace path" is checked, and checking it
  // requires the explicit confirm dialog (with an estimated cost line)
  // before it sticks — declining reverts the checkbox.
  if (traceCb && captureCb) {
    traceCb.addEventListener('change', () => {
      if (traceCb.checked) {
        captureCb.disabled = false;
      } else {
        captureCb.checked = false;
        captureCb.disabled = true;
      }
    });
    captureCb.addEventListener('change', () => {
      if (captureCb.checked && !confirmCaptureValues(fnEntity)) {
        captureCb.checked = false;
      }
    });
  }

  // History — always mounted below the form in the same tab (the
  // Runs tab IS the history surface; the former in-form toggle is
  // gone). buildHistoryPanel binds Repeat / path / tree / row-expand
  // against this pane's resultHost.
  async function mountHistory() {
    if (!runsHost) return;
    runsHost.textContent = '';
    runsHost.appendChild(renderSubmitSpinner('Loading runs…'));
    const panel = await buildHistoryPanel(fnEntity, resultHost);
    // A slow fetch may resolve after the tab re-rendered — only land
    // in the CURRENT runs host.
    if (document.getElementById('gd-insp-runs') !== runsHost) return;
    runsHost.textContent = '';
    runsHost.appendChild(panel);
  }
  mountHistory();

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
    await submitExecution(fnEntity, args, persistCb.checked,
                          !!traceCb?.checked, !!captureCb?.checked,
                          resultHost, cancelBtn);
    // Run completed — the new row (if persisted) belongs in the runs
    // list below; refresh it in place.
    mountHistory();
  });

  cancelBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    const id = cancelBtn.dataset.execId;
    if (id) await submitCancel(id, resultHost);
  });

  el.classList.add('visible');
  // Auto-focus the first form input (textarea / text input) inside
  // the pane so the user can start typing or hit Enter immediately
  // without an extra click. Skip if no free-args exist (focus would
  // land on the Run button, which is fine on its own). RAF wait gives
  // the value-form's deferred renderers (Tier-2 widgets) a tick to
  // mount.
  requestAnimationFrame(() => {
    const first = el.querySelector(
      '.execute-popover-body textarea, .execute-popover-body input:not([type=checkbox])');
    if (!first) return;
    // A code field is CodeMirror-enhanced (editor-code.js) — the
    // textarea is hidden, focus its view instead.
    if (first.dataset?.cmEnhanced && window.gdCode) {
      try { window.gdCode.viewOf(first)?.focus(); } catch (_) {}
    } else {
      try { first.focus(); } catch (_) {}
    }
  });
}


window.showExecutePopover = showExecutePopover;
window.gdMountRunPane = gdMountRunPane;
