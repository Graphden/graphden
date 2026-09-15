// Editor Row-Actions — the `data-action` HANDLERS behind the popover's buttons.
//
// Split out of editor-row-actions.js (2026-09-13). Each `registerActionHandler`
// call below runs at load and is invoked by the runtime's `bindActionDispatch`
// when the user clicks an enabled button in the server-rendered popover
// (`:partial-row-actions`, docs/EDITOR_ROW_ACTIONS.md). Handlers take
// `(btn, event, host)`; the use-site ones recover the rich `useSiteArg` by
// `binding-id` from `_rowActionsUseSiteArgs`, the registry the loader in
// editor-row-actions.js fills before the fetch. Loads right after that file.

// =============================================================================
// HANDLER REGISTRATION
// =============================================================================
//
// Each `data-action="X"` handler registered here is invoked by
// the runtime's `bindActionDispatch` when the user clicks an
// enabled button. Handlers may use the second `event` arg (for
// preventDefault / stopPropagation) and the third `host` arg
// (for fall-through to `host.dataset.*`).

// The `ns` badge is primarily "WHERE does this fn live" — it opens a small
// popover showing the fn's namespace path + a "Reveal in Explorer" action to
// find it in the tree, and (only when the fn is editable) a "Move to another
// namespace…" action. So it's useful on ANY node — including a read-only /
// stdlib fn you just want to locate — instead of silently doing nothing.
registerActionHandler('namespace-move', (btn, e) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fn = lookups?.fnMap?.get(fnId);
  const signedIn = typeof isAuthenticated === 'function' && isAuthenticated();
  // Moving a fn to another namespace is an ownership edit — offer it on any
  // fn the principal owns (tenancy) that isn't package-synced. It does NOT
  // require the fn to be caller-free: identity is the id (ADR-identity-model),
  // callers reference it by id, and the server re-checks the (ns, name)
  // collision — the old isFnEditable gate made a mature project's namespaces
  // unreorganisable, since every load-bearing fn has callers. Reveal + the
  // namespace path stay available on ANY fn (read-only locate).
  const owned = (typeof graphdenIsFnOwned !== 'function') || graphdenIsFnOwned(fn);
  const editable = (typeof isPackageOwnedFn === 'function')
    ? (!isPackageOwnedFn(fnId) && owned)
    : ((typeof isFnEditable === 'function' && isFnEditable(fnId)) && owned);
  const nsPath = (fn && lookups?.nsPathMap && fn['namespace-id'])
    ? (lookups.nsPathMap.get(fn['namespace-id']) || '(root)')
    : '(root)';

  const menu = document.createElement('div');
  menu.className = 'ns-menu';
  const label = document.createElement('div');
  label.className = 'ns-menu-label';
  label.textContent = 'Namespace';
  const path = document.createElement('div');
  path.className = 'ns-menu-path';
  path.textContent = nsPath;
  menu.append(label, path);

  const reveal = document.createElement('button');
  reveal.type = 'button';
  reveal.className = 'ns-menu-btn';
  reveal.textContent = 'Reveal in Explorer';
  reveal.addEventListener('click', () => {
    if (typeof hideIconReasonPopover === 'function') hideIconReasonPopover();
    if (typeof revealFnInTree === 'function') revealFnInTree(fnId);
  });
  menu.appendChild(reveal);

  if (signedIn && editable && fn && typeof enterNamespaceMoveEditMode === 'function') {
    const move = document.createElement('button');
    move.type = 'button';
    move.className = 'ns-menu-btn';
    move.textContent = 'Move to another namespace…';
    move.addEventListener('click', () => {
      if (typeof hideIconReasonPopover === 'function') hideIconReasonPopover();
      enterNamespaceMoveEditMode(fn, btn);
    });
    menu.appendChild(move);
  }

  if (typeof showIconReasonPopover === 'function') showIconReasonPopover(btn, menu);
});


registerActionHandler('description', (btn, e, _host) => {
  // Click toggles sticky — match the legacy badge behaviour.
  e.preventDefault();
  e.stopPropagation();
  if (typeof descriptionTooltipSticky !== 'undefined') {
    descriptionTooltipSticky = !descriptionTooltipSticky;
  }
  if (typeof showDescriptionTooltip === 'function') {
    // Keyboard / synthetic clicks carry (0,0) — anchor at the button
    // instead so the tooltip opens next to its card, not in the corner.
    const evt = (e.clientX || e.clientY) ? e : (() => {
      const r = btn.getBoundingClientRect();
      return { clientX: r.left + r.width / 2, clientY: r.bottom };
    })();
    showDescriptionTooltip({
      name: null,
      namespace: null,
      description: btn.dataset.description || '',
      entityType: btn.dataset.entityType || null,
      entityId: btn.dataset.fnId
                || btn.closest('[data-fn-id]')?.dataset.fnId
                || null
    }, evt);
  }
});


registerActionHandler('peek-fn', (btn, e) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  if (fnId && typeof openFnPeek === 'function') openFnPeek(fnId, btn);
});

registerActionHandler('open', (btn, e) => {
  // Open THIS node's fn in a new tab. The server-rendered href is a fallback;
  // the editor navigates by the URL HASH (`#<qualified-name>`), not a `?fn=`
  // query (nothing reads that), so build the same hash the tree's ↗ uses from
  // the client's qualified name — robust against duplicate bare names.
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fn = (fnId && lookups?.fnMap) ? lookups.fnMap.get(fnId) : null;
  const name = (fn && typeof getQualifiedFnName === 'function') ? getQualifiedFnName(fn) : null;
  if (name && name !== '(anonymous)') {
    e.preventDefault();
    window.open('#' + encodeURIComponent(name), '_blank', 'noopener');
  }
  // else: fall through to the <a href> default (best-effort for an
  // unresolved / anonymous fn — the dispatcher never renders ↗ for those).
});


registerActionHandler('remove-mi-parent', (btn, e, _host) => {
  // Remove THIS cell's fn from the CARD-owning fn's parent-set.
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const cardFnId = btn.dataset.cardFnId
                  || btn.closest('[data-card-fn-id]')?.dataset.cardFnId;
  const cardFnEntity = lookups?.fnMap?.get(cardFnId);
  if (cardFnEntity && typeof removeParentInline === 'function') {
    removeParentInline(cardFnEntity, fnId);
  }
});


registerActionHandler('add-mi-parent', (btn, e, _host) => {
  // Open the MI picker for the CARD-owning fn. Compatibility
  // check (no candidates → disable + reason) stays in
  // `_applyAddMICompatibilityState` because `compatibleMIParentInfo`
  // reads client-cached `lookups`; server has no view of that map.
  e.preventDefault();
  e.stopPropagation();
  const cardFnId = btn.dataset.cardFnId
                  || btn.closest('[data-card-fn-id]')?.dataset.cardFnId;
  const cardFnEntity = lookups?.fnMap?.get(cardFnId);
  if (cardFnEntity && typeof addMIParentInline === 'function') {
    addMIParentInline(cardFnEntity, btn);
  }
});


registerActionHandler('remove-use-site-binding', (btn, e, _host) => {
  // Look up the rich `useSiteArg` via the binding-id-keyed
  // registry the caller populated pre-fetch. The arg carries
  // `:type` / `:item-id` / etc. that `deleteUseSiteBinding`
  // needs to choose between sequence-item-removal and binding-
  // deletion code paths.
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg && typeof deleteUseSiteBinding === 'function') {
    deleteUseSiteBinding(arg);
  }
});


registerActionHandler('change-use-site-value', (btn, e, _host) => {
  // `enterFreeArgBindEditMode` dispatches on the arg's effective
  // type (fn-picker for `:fn` slots, literal form for the rest) —
  // same registry lookup pattern as above.
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg && typeof enterFreeArgBindEditMode === 'function') {
    enterFreeArgBindEditMode(arg, btn);
  }
});


// --- sequence-item ordering (↑ / ↓ / + Insert-before) ---
// Same binding-id-keyed registry lookup as × / ✎; the rich arg
// carries the `item-id` and `position` the endpoints need.

registerActionHandler('seq-move-item-up', (btn, e, _host) => {
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg?.['item-id'] && typeof moveSequenceItem === 'function') {
    moveSequenceItem(arg['item-id'], 'up');
  }
});


registerActionHandler('seq-move-item-down', (btn, e, _host) => {
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg?.['item-id'] && typeof moveSequenceItem === 'function') {
    moveSequenceItem(arg['item-id'], 'down');
  }
});


registerActionHandler('seq-insert-before', (btn, e, _host) => {
  // Reuses the append chooser (literal vs fn-ref) with the anchor
  // item's position — the backend shifts later items +1.
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg?.['fn-id'] && typeof arg.position === 'number'
      && typeof appendSequenceItem === 'function') {
    appendSequenceItem(arg['fn-id'], btn, undefined,
                       { position: arg.position,
                         elemType: (typeof seqElemType === 'function' ? seqElemType(arg) : null) });
  }
});


registerActionHandler('run-fn', (btn, e, _host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof showExecutePopover === 'function') {
    showExecutePopover(fnEntity, btn);
  }
});


registerActionHandler('fn-versions', (btn, e, _host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof showFnVersionsPopover === 'function') {
    showFnVersionsPopover(fnEntity, btn);
  }
});


registerActionHandler('service-settings', (btn, e, _host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof showServicePopover === 'function') {
    showServicePopover(fnEntity, btn);
  }
});


registerActionHandler('apps', (btn, e, _host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof showFnAppsPopover === 'function') {
    showFnAppsPopover(fnEntity, btn);
  }
});


registerActionHandler('rename-fn', (btn, e, _host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof enterFnRenameEditMode === 'function') {
    enterFnRenameEditMode(fnEntity, btn);
  }
});


registerActionHandler('wrap-fn', (btn, e, _host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof enterWrapEditMode === 'function') {
    enterWrapEditMode(fnEntity, btn);
  }
});


registerActionHandler('extend-fn', (btn, e, _host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof enterExtendEditMode === 'function') {
    enterExtendEditMode(fnEntity, btn);
  }
});


registerActionHandler('delete-fn', (btn, e, _host) => {
  // Destructive — confirm + cascade + reload via initGraph,
  // mirroring the legacy in-card ✕ behaviour. `withBusy`
  // surfaces the deletion as a top-bar banner while it runs.
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (!fnEntity) return;
  const display = (typeof getQualifiedFnName === 'function')
                ? getQualifiedFnName(fnEntity)
                : (fnEntity.name || 'this fn');
  if (!confirm('Delete fn "' + display + '"? '
               + 'Bindings that reference it will fail to load.')) return;
  const opKey = 'delete-fn:' + fnEntity.id;
  if (typeof isOpInflight === 'function' && isOpInflight(opKey)) return;
  const work = async () => {
    try {
      const r = await deleteEntity('fn', fnEntity.id);
      if (r && r.status >= 200 && r.status < 300) {
        // Deleting the SELECTED fn drops the selection outright — an
        // empty hash alone left its dead card on the canvas whenever the
        // hash was already empty (no hashchange to route).
        if (selectedFnId === fnEntity.id && typeof gdClearSelection === 'function') {
          gdClearSelection();
        } else {
          try { window.location.hash = ''; } catch (_) {}
        }
        if (typeof initGraph === 'function') await initGraph();
      } else {
        const text = r ? await r.text().catch(() => '') : '';
        alert('Delete failed (' + (r?.status) + '): '
              + text.replace(/<[^>]+>/g, '').trim().slice(0, 200));
      }
    } catch (err) {
      alert('Network error: ' + err.message);
    }
  };
  if (typeof withBusy === 'function') {
    withBusy(opKey, 'Deleting ' + display + '…', work);
  } else {
    work();
  }
});
