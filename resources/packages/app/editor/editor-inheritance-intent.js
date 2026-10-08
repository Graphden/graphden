// Graph partials own intent availability, markup and scope wording. This host
// owns focus, anchoring and dispatching canonical server commands.
let inheritanceIntentEl = null;
let inheritanceIntentAnchor = null;
let inheritanceIntentCommand = null;
let stopInheritanceAnchorObservation = null;
let inheritanceIntentPending = false;
let inheritanceAnchorObserver = null;

installPopoverDismiss({
  getEl: () => inheritanceIntentEl, getAnchor: () => inheritanceIntentAnchor,
  isVisible: () => !!inheritanceIntentEl, onDismiss: closeInheritanceIntent, trapFocus: true,
});

function closeInheritanceIntent() {
  if (inheritanceIntentPending) return;
  stopInheritanceAnchorObservation?.();
  stopInheritanceAnchorObservation = null;
  inheritanceAnchorObserver?.disconnect();
  inheritanceAnchorObserver = null;
  const hadFocus = inheritanceIntentEl?.contains(document.activeElement);
  inheritanceIntentEl?.remove();
  inheritanceIntentEl = null;
  inheritanceIntentCommand = null;
  if (hadFocus) returnFocusTo(inheritanceIntentAnchor);
  inheritanceIntentAnchor = null;
}

async function openInheritanceIntent(anchor, command) {
  closeInheritanceIntent();
  if (inheritanceIntentPending || !anchor || !isAuthenticated()) return;
  const host = document.createElement('div');
  host.className = 'inheritance-intent-popover';
  host.setAttribute('role', 'dialog');
  host.setAttribute('aria-label', 'Inheritance actions');
  ensurePopoverClose(host, closeInheritanceIntent, 'Close inheritance actions', { prepend: true });
  document.body.appendChild(host);
  inheritanceIntentEl = host;
  inheritanceIntentAnchor = anchor;
  inheritanceIntentCommand = command;
  const place = () => {
    const rect = anchor.getBoundingClientRect();
    if (!anchor.isConnected || rect.width === 0 || rect.height === 0
        || rect.right < 0 || rect.bottom < 0 || rect.left > window.innerWidth || rect.top > window.innerHeight) {
      closeInheritanceIntent();
      return;
    }
    const viewport = window.visualViewport;
    const left = viewport?.offsetLeft || 0;
    const top = viewport?.offsetTop || 0;
    const width = viewport?.width || window.innerWidth;
    const height = viewport?.height || window.innerHeight;
    host.style.maxWidth = Math.max(0, width - 16) + 'px';
    host.style.maxHeight = Math.max(0, height - 16) + 'px';
    host.style.left = Math.max(left + 8, Math.min(rect.left, left + width - host.offsetWidth - 8)) + 'px';
    host.style.top = Math.max(top + 8, Math.min(rect.bottom + 6, top + height - host.offsetHeight - 8)) + 'px';
  };
  stopInheritanceAnchorObservation = observePopoverAnchor(host, anchor, place, onViewportChanged);
  const cardLayer = anchor.closest('.node-overlay')?.parentNode;
  if (cardLayer && typeof MutationObserver === 'function') {
    inheritanceAnchorObserver = new MutationObserver(() => {
      if (!anchor.isConnected) closeInheritanceIntent();
    });
    inheritanceAnchorObserver.observe(cardLayer, { childList: true, subtree: true });
  }
  focusSafely(host.querySelector('[data-gd-pop-x]'));
  place();
  try {
    const response = await authFetch('/partials/inheritance-intent?command=' + encodeURIComponent(JSON.stringify(command)));
    if (inheritanceIntentEl !== host) return;
    if (!response.ok) throw new Error(await extractResponseError(response));
    const html = await response.text();
    if (inheritanceIntentEl !== host) return;
    host.innerHTML = html;
    ensurePopoverClose(host, closeInheritanceIntent, 'Close inheritance actions', { prepend: true });
    bindActionDispatch(host);
    place();
    focusSafely(host.querySelector('button'));
  } catch (error) {
    if (inheritanceIntentEl === host) {
      host.textContent = error.message;
      ensurePopoverClose(host, closeInheritanceIntent, 'Close inheritance actions', { prepend: true });
      place();
    }
  }
}

async function navigateInheritanceSource(fnId) {
  if (!fnId) return;
  await ensureSubtreeFor(fnId);
  if (!lookups?.fnMap?.has(fnId)) throw new Error('Source is unavailable.');
  closeInheritanceIntent();
  // IDs remain authoritative even for anonymous or duplicate-named sources.
  selectFn(fnId, false);
}

async function applyIntentPreview(preview, proposedName) {
  if (inheritanceIntentPending) return;
  const host = inheritanceIntentEl;
  inheritanceIntentPending = true;
  for (const control of host?.querySelectorAll('button, input') || []) control.disabled = true;
  try {
    if (proposedName !== undefined && proposedName !== preview.proposed?.name) {
      inheritanceIntentCommand = { ...preview.request, 'proposed-name': proposedName };
      preview = await inheritanceRequest('preview', inheritanceIntentCommand);
    }
    const result = await applyInheritancePreview(preview, staged => {
      if (typeof _tourTrackInheritanceVariation === 'function') _tourTrackInheritanceVariation(staged);
    });
    inheritanceIntentPending = false;
    if (!result) return;
    closeInheritanceIntent();
    if (typeof _tourLoadInheritanceCheckSource === 'function') {
      try { await _tourLoadInheritanceCheckSource(preview); }
      catch (error) { gdToast('Change saved. ' + error.message); }
    }
    await initGraph();
    if (result['created-fn-id']) await navigateInheritanceSource(result['created-fn-id']);
  } catch (error) {
    gdToast(error.message);
  } finally {
    inheritanceIntentPending = false;
    if (inheritanceIntentEl === host && host) {
      // Restore authoritative disabled states by obtaining a new preview;
      // never automatically apply or accept its potentially changed orphan set.
      void openInheritanceIntent(inheritanceIntentAnchor, inheritanceIntentCommand);
    }
  }
}

registerActionHandler('inheritance-intent', (button, event) => {
  event.preventDefault();
  event.stopPropagation();
  const command = JSON.parse(button.dataset.inheritanceCommand);
  const anchor = rowActionsPopoverAnchor || button;
  rowActionsPopoverSticky = false;
  hideRowActionsPopover();
  void openInheritanceIntent(anchor, command);
});

registerActionHandler('inheritance-source', (button, event) => {
  event.preventDefault();
  void navigateInheritanceSource(button.dataset.fnId).catch(error => gdToast(error.message));
});

registerActionHandler('inheritance-variation', (button, event) => {
  event.preventDefault();
  const view = button.closest('[data-preview]');
  if (view) void applyIntentPreview(JSON.parse(view.dataset.preview),
    view.querySelector('[data-variation-name]')?.value.trim());
});

registerActionHandler('inheritance-ancestors', async (_button, event) => {
  event.preventDefault();
  const command = inheritanceIntentCommand;
  const anchor = inheritanceIntentAnchor;
  if (!command || inheritanceIntentPending) return;
  try {
    const result = await inheritanceRequest('preview', {
      action: 'candidates', 'target-fn-id': command['target-fn-id'], 'source-fn-id': command['source-fn-id'],
    });
    if (inheritanceIntentCommand !== command) return;
    const candidates = (result.candidates || []).filter(c => !c.current).map(c => ({
      ...c, qualified: c['qualified-name'] || c.name || c.id,
      ns: lookups?.nsPathMap?.get(c['namespace-id']) || null,
      compatible: !!c.allowed, effects: [], flatReturn: null, richReturn: null,
    }));
    closeInheritanceIntent();
    openFnPicker({ anchorEl: anchor, label: 'Choose ancestor', candidates,
      onPick: async (_fn, candidate) => {
        try { if (await applyInheritancePreview(candidate)) await initGraph(); }
        catch (error) { gdToast(error.message); }
      },
    });
  } catch (error) { gdToast(error.message); }
});
