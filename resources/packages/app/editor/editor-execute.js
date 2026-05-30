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


// === Free-args lookup ======================================================
//
// Walk the fn's inheritance chain to collect every slot the executor
// would treat as a free-arg (no value / ref / list-append binding).
// Mirrors the backend's `free-arg-slot-map` — kept in JS so the form
// can render without an extra HTTP round-trip.
function freeArgsOf(fnEntity) {
  if (!fnEntity || typeof lookups === 'undefined' || !lookups) return [];
  const chain = (typeof getInheritanceChain === 'function')
    ? getInheritanceChain(fnEntity.id) : [fnEntity.id];
  const chainSet = new Set(chain);
  const fnSlots = [];
  const seenSlotIds = new Set();
  for (const fid of chain) {
    const list = lookups.fnSlotsByFn?.get(fid) || [];
    for (const fs of list) {
      if (!seenSlotIds.has(fs['slot-id'])) {
        seenSlotIds.add(fs['slot-id']);
        fnSlots.push(fs);
      }
    }
  }
  // Bound = any binding for this slot anywhere in the chain has a
  // value, ref, or list-append.
  const bound = new Set();
  if (lookups.bindingMap) {
    for (const b of lookups.bindingMap.values()) {
      if (!chainSet.has(b['fn-id'])) continue;
      if (b.value !== null && b.value !== undefined) bound.add(b['slot-id']);
      else if (b['ref-fn-id']) bound.add(b['slot-id']);
      else if (b['list-append'] === true) bound.add(b['slot-id']);
    }
  }
  return fnSlots
    .filter((fs) => !bound.has(fs['slot-id']))
    .map((fs) => {
      const slot = lookups.slotMap?.get(fs['slot-id']);
      return slot ? { name: slot.name, id: slot.id, position: fs.position } : null;
    })
    .filter(Boolean)
    .sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
}


// === Declared-effects lookup ===============================================

function declaredEffectsOf(fnEntity) {
  if (!fnEntity || !fnEntity.name) return [];
  if (typeof richTypes !== 'object' || !richTypes) return [];
  const entry = richTypes[fnEntity.name];
  const eff = entry?.effects || [];
  return Array.isArray(eff) ? eff : Array.from(eff);
}


// === Arg-form sections =====================================================

function makeRow(labelText, bodyEl) {
  const row = document.createElement('div');
  row.className = 'execute-arg-row';
  const label = document.createElement('div');
  label.className = 'execute-arg-label';
  label.textContent = labelText;
  row.appendChild(label);
  row.appendChild(bodyEl);
  return row;
}


// Build one arg's form by fetching the value-form payload for that
// (fn-id, slot-id). Returns the host element + a `read()` closure
// that collectFormValue's the current input.
async function buildArgFormSection(fnEntity, slot) {
  const host = document.createElement('div');
  host.className = 'execute-arg-form';
  host.textContent = '…';
  const payload = await fetchValueForm({ 'fn-id': fnEntity.id,
                                         'slot-id': slot.id });
  if (!payload || payload.ok === false) {
    host.textContent = payload?.error || 'Could not load form for ' + slot.name;
    host.classList.add('execute-arg-form-error');
  } else {
    renderValueForm(host, payload, {});
  }
  return {
    host,
    read: () => {
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
    },
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
    const r = await authFetch('/api/execute/' + encodeURIComponent(execId),
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
    const status = String(row.status || '').replace(/^:/, '');
    if (status === 'succeeded' || status === 'failed' || status === 'cancelled') {
      resultHostEl.textContent = '';
      if (isTaintedExecuteResponse(row)) {
        resultHostEl.appendChild(renderTaintedPane());
      } else if (status === 'succeeded') {
        resultHostEl.appendChild(renderResultBody(row.result,
                                                  { truncated: row['result-truncated?'] }));
      } else if (status === 'failed') {
        resultHostEl.appendChild(renderErrorPane(row.error, row['error-data']));
      } else {
        const cancelled = document.createElement('div');
        cancelled.className = 'execute-cancelled';
        cancelled.textContent = 'Cancelled.';
        resultHostEl.appendChild(cancelled);
      }
      const rtStrip = renderRuntimeEffectsStrip(row['runtime-effects'],
                                                row['declared-effects']);
      if (rtStrip) resultHostEl.appendChild(rtStrip);
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
    const r = await authFetch('/api/execute', {
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
    if (status === 'rejected') {
      resultHostEl.appendChild(renderErrorPane(body.error, body['error-data']));
    } else if (status === 'pending') {
      resultHostEl.appendChild(renderPendingPane(body['execution-id']));
      cancelBtn.style.display = '';
      cancelBtn.dataset.execId = body['execution-id'];
      startPolling(body['execution-id'], resultHostEl);
    } else if (isTaintedExecuteResponse(body)) {
      resultHostEl.appendChild(renderTaintedPane());
    } else if (status === 'succeeded') {
      resultHostEl.appendChild(renderResultBody(body.result,
                                                { truncated: body['result-truncated?'] }));
    } else if (status === 'failed') {
      resultHostEl.appendChild(renderErrorPane(body.error, body['error-data']));
    } else {
      const pre = document.createElement('pre');
      pre.textContent = JSON.stringify(body, null, 2);
      resultHostEl.appendChild(pre);
    }
    const rtStrip = renderRuntimeEffectsStrip(body['runtime-effects'],
                                              body['declared-effects']);
    if (rtStrip) resultHostEl.appendChild(rtStrip);
  } catch (e) {
    resultHostEl.textContent = '';
    resultHostEl.appendChild(renderErrorPane('Network error: ' + e.message));
  }
}


async function submitCancel(execId, resultHostEl) {
  try {
    await authFetch('/api/execute/' + encodeURIComponent(execId) + '/cancel',
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

  // Header
  const head = document.createElement('div');
  head.className = 'execute-popover-header';
  const title = document.createElement('span');
  title.className = 'execute-popover-title';
  title.textContent = 'Run :' + (fnEntity.name || '(anonymous)');
  head.appendChild(title);
  // Branch indicator — non-default-branch users SHOULD see at-a-glance
  // that ▶ won't run main's version. Inverted-pill style mirrors the
  // top-bar chip's "on a feature branch" affordance.
  if (typeof isOnDefaultBranch === 'function' && !isOnDefaultBranch()) {
    const branchPill = document.createElement('span');
    branchPill.className = 'execute-popover-branch';
    const branchName = typeof getCurrentBranchName === 'function'
      ? getCurrentBranchName() : '?';
    branchPill.textContent = 'on ' + branchName;
    branchPill.title = 'Run resolves to this fn as seen on branch "'
                       + branchName + '"; switch branches to run a different version';
    head.appendChild(branchPill);
  }
  // History toggle — toggles a collapsible panel listing persisted
  // runs for this fn. Lazy fetch on first open so the header doesn't
  // pay an HTTP roundtrip every time the popover opens.
  const historyBtn = document.createElement('button');
  historyBtn.type = 'button';
  historyBtn.className = 'execute-history-toggle';
  historyBtn.textContent = 'History';
  historyBtn.title = 'Show persisted runs of this fn';
  historyBtn.setAttribute('aria-expanded', 'false');
  head.appendChild(historyBtn);
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'execute-popover-close';
  close.setAttribute('aria-label', 'Close run-fn popover');
  close.textContent = '×';
  close.addEventListener('click', (e) => {
    e.stopPropagation();
    hideExecutePopover();
  });
  head.appendChild(close);
  el.appendChild(head);

  // History host — content built lazily after Result host is mounted
  // below (the panel rows need to know where to render an expanded
  // result). The click handler is wired AFTER resultHost exists.
  const historyHost = document.createElement('div');
  historyHost.className = 'execute-history-host';
  historyHost.style.display = 'none';
  el.appendChild(historyHost);

  // Effects banner — explicit warning when the fn isn't pure.
  const effs = declaredEffectsOf(fnEntity);
  if (effs.length > 0) {
    const banner = document.createElement('div');
    banner.className = 'execute-effects-warning';
    const lbl = document.createElement('span');
    lbl.className = 'execute-effects-warning-label';
    lbl.textContent = 'side effects: ';
    banner.appendChild(lbl);
    for (const cat of effs) {
      const chip = document.createElement('span');
      chip.className = 'effects-chip effects-chip-' + cat;
      chip.textContent = cat;
      banner.appendChild(chip);
    }
    el.appendChild(banner);
  }

  // Body — one form per free-arg.
  const body = document.createElement('div');
  body.className = 'execute-popover-body';
  el.appendChild(body);
  const frees = freeArgsOf(fnEntity);
  if (frees.length === 0) {
    const note = document.createElement('div');
    note.className = 'execute-no-args-note';
    note.textContent = 'No free arguments — click Run to invoke.';
    body.appendChild(note);
  }
  // Fetch every free-arg's value-form in parallel — sequential await
  // serialised the N HTTP roundtrips, making popover-open scale O(N)
  // in wall time for fns with multiple free args.
  const sections = await Promise.all(
    frees.map((slot) => buildArgFormSection(fnEntity, slot)),
  );
  for (let i = 0; i < frees.length; i++) {
    const slot = frees[i];
    const section = sections[i];
    argFormHosts.push({ slotName: slot.name, slotId: slot.id, hostEl: section.host,
                         read: section.read });
    body.appendChild(makeRow(slot.name, section.host));
  }

  // Option toggles row — sits above the action bar. Two checkboxes:
  //   * effect-confirm (only when effs.length > 0) — hard-gates Run
  //     so user can't fire an effectful fn without explicit OK.
  //   * persist? — when ticked, even pure fast fns leave a row in
  //     fn-execution storage (useful for tracking ad-hoc runs).
  //     Effects-bearing fns ignore this — they auto-persist on the
  //     backend regardless.
  const opts = document.createElement('div');
  opts.className = 'execute-options-row';
  let confirmCb = null;
  if (effs.length > 0) {
    const confirmLabel = document.createElement('label');
    confirmLabel.className = 'execute-option-label execute-confirm-label';
    confirmCb = document.createElement('input');
    confirmCb.type = 'checkbox';
    confirmCb.className = 'execute-confirm-checkbox';
    confirmLabel.appendChild(confirmCb);
    const txt = document.createElement('span');
    txt.textContent = ' I understand this will produce side effects';
    confirmLabel.appendChild(txt);
    opts.appendChild(confirmLabel);
  }
  const persistLabel = document.createElement('label');
  persistLabel.className = 'execute-option-label';
  const persistCb = document.createElement('input');
  persistCb.type = 'checkbox';
  persistCb.className = 'execute-persist-checkbox';
  // Effects-bearing fns auto-persist on the backend regardless of
  // this flag — pre-check + disable so the UI matches reality and
  // doesn't lie that the run might NOT be saved.
  if (effs.length > 0) {
    persistCb.checked = true;
    persistCb.disabled = true;
    persistLabel.classList.add('execute-option-label-locked');
    persistLabel.title =
      'Automatically saved — runs that produce side effects are '
      + 'always persisted for audit trail.';
  } else {
    persistLabel.title =
      'When checked, this run is saved to fn-execution storage. '
      + 'Pure fast runs are NOT saved by default — tick this for '
      + 'runs you want to keep.';
  }
  persistLabel.appendChild(persistCb);
  const persistTxt = document.createElement('span');
  persistTxt.textContent = ' Save to history';
  persistLabel.appendChild(persistTxt);
  opts.appendChild(persistLabel);
  el.appendChild(opts);

  // Action bar
  const actions = document.createElement('div');
  actions.className = 'execute-action-bar';
  const runBtn = document.createElement('button');
  runBtn.type = 'button';
  runBtn.className = 'execute-run-btn';
  runBtn.textContent = 'Run';
  // Effect-confirm gate — Run disabled until checkbox ticked when the
  // fn declares effects. Live-toggle as user checks/unchecks.
  if (confirmCb) {
    runBtn.disabled = true;
    runBtn.title = 'Confirm side-effects acknowledgement first';
    confirmCb.addEventListener('change', () => {
      runBtn.disabled = !confirmCb.checked;
      runBtn.title = confirmCb.checked ? 'Run' : 'Confirm side-effects acknowledgement first';
    });
  }
  const cancelBtn = document.createElement('button');
  cancelBtn.type = 'button';
  cancelBtn.className = 'execute-cancel-btn';
  cancelBtn.textContent = 'Cancel';
  cancelBtn.style.display = 'none';
  actions.appendChild(runBtn);
  actions.appendChild(cancelBtn);
  el.appendChild(actions);

  // Result host (filled after Run / history-row click)
  const resultHost = document.createElement('div');
  resultHost.className = 'execute-result-host';
  el.appendChild(resultHost);

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

  // Keyboard: Enter inside the popover (but NOT inside a textarea,
  // where Enter inserts a newline) triggers Run when the button is
  // enabled. Esc is already handled by installPopoverDismiss below.
  el.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    const tag = e.target?.tagName;
    if (tag === 'TEXTAREA') return;  // preserve newline insertion
    if (runBtn.disabled) return;
    e.preventDefault();
    runBtn.click();
  });

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
window.hideExecutePopover = hideExecutePopover;
