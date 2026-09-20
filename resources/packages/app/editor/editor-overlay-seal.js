// Editor Seal Overlay — the three author decisions that live on a slot
// beyond its value, made VISIBLE on the canvas and SETTABLE where the
// reader stands:
//
//   :terminal     on a binding — "sealed": descendants may not bind this
//                 slot (the server refuses with `:constraint-violation/
//                 terminal-seal`). The explicit form of the rule every
//                 VALUED binding already carries implicitly — a value an
//                 ancestor supplied is final (`value-override`).
//   :list-closed  on a list binding — "closed": descendants may not
//                 append (`list-closed`).
//   :required     on a slot (the declaration: `false` = optional) and, as
//                 a one-way ratchet, on a binding — a descendant may make
//                 an optional slot required HERE, never the reverse.
//
// Until 2026-09-20 these were fns.edn-only: the editor drew a closed list
// like an open one and offered a `+` the server then refused. The layout
// now emits WHO sealed / closed / required each edge (`edge-seal-fields`,
// layout/builder_helpers.clj — `sealedBy`, `listClosedBy`, `requiredBy`
// with `…Name` twins), and this module renders it:
//
//   - a lock badge on the edge label (`createSealBadge`) — 🔒 when a seal
//     is in force (here or above), a dim 🔓 on an editable edge with none,
//     so the affordance is discoverable the way the `i` badge is;
//   - the popover the badge opens (`openSealPopover`) — checkboxes for the
//     decisions THIS fn may make, read-only lines for what an ancestor
//     decided (a seal is lifted where it was set);
//   - the `+` gate (`gdSealBlocksBinder`) — a placeholder sealed / closed
//     ABOVE the card draws a lock ghost instead of a binder.
//
// Ownership gate = the same one every other binding write has: the fn is
// in the implementation, the session is signed in, the fn is not package-
// synced, and — under tenancy — the principal owns it (`graphdenIsFnOwned`).
// Writes go through `writeBindingFields` (editor-edit-modes.js), so a seal
// is one Undo away like any other binding change; the slot's own
// `:required` goes through the entity PUT, owner-only.

// The seal facts an edge / placeholder carries, resolved against the arg
// whose label this is. `here` = flags on THIS fn's own binding; `above` =
// a seal an ANCESTOR set (the fn can only read it).
function gdSealInfo(arg, data) {
  const fnId = arg?.['fn-id'] || null;
  const slotId = arg?.['slot-id'] || null;
  const own = (fnId && slotId && lookups?.bindingByFnSlot)
    ? (lookups.bindingByFnSlot.get(fnId + '|' + slotId) || null) : null;
  const slot = slotId ? lookups?.slotMap?.get(slotId) : null;
  const d = data || {};
  const above = (key) => (d[key] && d[key] !== fnId) ? d[key] : null;
  return {
    fnId, slotId, own, slot,
    here: {
      terminal: own?.terminal === true,
      listClosed: own?.['list-closed'] === true,
      required: own?.required === true,
    },
    above: {
      sealedBy: above('sealedBy'), sealedByName: d.sealedByName || '',
      listClosedBy: above('listClosedBy'), listClosedByName: d.listClosedByName || '',
      requiredBy: above('requiredBy'), requiredByName: d.requiredByName || '',
    },
    slotOptional: slot?.required === false,
    isSeq: !!(d.isSequenceAnchor || d.seqGroup || d.seqLabel
              || (slot?.['type-fn-id'] && lookups?.fnMap?.get(slot['type-fn-id'])?.name === 'sequence')
              || (typeof slotRichType === 'function'
                  && Array.isArray(slotRichType(arg)) && slotRichType(arg)[0] === 'list')),
  };
}

// Anything sealed on this edge — from this fn or an ancestor.
function gdSealAny(info) {
  return !!(info.here.terminal || info.here.listClosed || info.here.required
            || info.above.sealedBy || info.above.listClosedBy || info.above.requiredBy);
}

// May the current session change `fnId`'s bindings? The binder's own gate,
// in one place.
function gdSealCanEdit(fnId) {
  if (!fnId) return false;
  if (!(typeof implementationFnIds !== 'undefined' && implementationFnIds?.has(fnId))) return false;
  if (!(typeof isAuthenticated === 'function' && isAuthenticated())) return false;
  if (typeof isPackageOwnedFn === 'function' && isPackageOwnedFn(fnId)) return false;
  const fn = lookups?.fnMap?.get(fnId);
  if (typeof graphdenIsFnOwned === 'function' && !graphdenIsFnOwned(fn)) return false;
  return true;
}

// Does a seal set ABOVE this card take its `+` away? A slot sealed by an
// ancestor cannot be bound here; a list closed by an ancestor cannot be
// appended to here. A seal set on THIS fn does neither — the sealer may
// still bind its own slot (the server skips the writer's own binding).
// Returns the reason text, or null when the binder may stay.
function gdSealBlocksBinder(arg, data) {
  const info = gdSealInfo(arg, data);
  if (info.above.sealedBy) {
    return 'Sealed in ' + (info.above.sealedByName || 'an ancestor')
      + ' — descendants cannot bind this slot. Lift the seal there, or extend a different fn.';
  }
  if (info.isSeq && info.above.listClosedBy) {
    return 'List closed in ' + (info.above.listClosedByName || 'an ancestor')
      + ' — descendants cannot append. Reopen it there, or extend a different fn.';
  }
  return null;
}

// The lock ghost a blocked placeholder shows instead of `+` — the λ badge's
// shape: informational, for everyone, no click-to-edit.
function createSealGhost(reason) {
  const badge = document.createElement('span');
  badge.className = 'seal-ghost';
  badge.textContent = '🔒';
  badge.title = reason;
  badge.setAttribute('role', 'img');
  badge.setAttribute('aria-label', reason);
  badge.style.pointerEvents = 'auto';
  return badge;
}

// One line per seal in force, for tooltips and the popover's read-only part.
function gdSealLines(info) {
  const lines = [];
  const who = (id, name) => (id === info.fnId ? 'here' : ('in ' + (name || 'an ancestor')));
  if (info.here.terminal) lines.push('Sealed here — descendants cannot bind this slot.');
  else if (info.above.sealedBy) lines.push('Sealed ' + who(info.above.sealedBy, info.above.sealedByName) + ' — descendants cannot bind this slot.');
  if (info.here.listClosed) lines.push('List closed here — descendants cannot append.');
  else if (info.above.listClosedBy) lines.push('List closed ' + who(info.above.listClosedBy, info.above.listClosedByName) + ' — descendants cannot append.');
  if (info.slotOptional) {
    if (info.here.required) lines.push('Optional by declaration, required here — and in every descendant.');
    else if (info.above.requiredBy) lines.push('Optional by declaration, required since ' + (info.above.requiredByName || 'an ancestor') + '.');
    else lines.push('Optional — the fn runs without it.');
  }
  return lines;
}

// The badge on an edge label. State (🔒) whenever a seal is in force;
// otherwise a dim 🔓 on an edge the reader may seal — nothing at all on a
// read-only edge with nothing to say, so quiet canvases stay quiet.
function createSealBadge(arg, data) {
  const info = gdSealInfo(arg, data);
  const canEdit = gdSealCanEdit(info.fnId);
  const any = gdSealAny(info);
  if (!any && !canEdit) return null;
  const badge = document.createElement('button');
  badge.type = 'button';
  badge.className = 'seal-badge' + (any ? ' seal-badge-on' : ' seal-badge-off');
  badge.textContent = any ? '🔒' : '🔓';
  // Which seals, for a tour step or a test to ring / read without parsing
  // the tooltip: `data-seal="terminal list-closed"`.
  const kinds = [];
  if (info.here.terminal || info.above.sealedBy) kinds.push('terminal');
  if (info.here.listClosed || info.above.listClosedBy) kinds.push('list-closed');
  if (info.slotOptional && (info.here.required || info.above.requiredBy)) kinds.push('required');
  else if (info.slotOptional) kinds.push('optional');
  badge.dataset.seal = kinds.join(' ');
  if (arg?.name) badge.dataset.argName = arg.name;
  const lines = gdSealLines(info);
  const title = (lines.length ? lines.join('\n') : 'No seal on this slot.')
    + (canEdit ? '\nClick to change what descendants may do with it.' : '');
  badge.title = title;
  badge.setAttribute('aria-label', title);
  badge.style.pointerEvents = 'auto';
  badge.addEventListener('mousedown', (e) => e.stopPropagation());
  badge.addEventListener('click', (e) => {
    e.stopPropagation();
    e.preventDefault();
    openSealPopover(arg, data, badge);
  });
  return badge;
}

// The popover: the decisions THIS fn may make about the slot, as checkboxes
// — save writes only what changed — over the read-only lines about what an
// ancestor decided. Built on the shared arg-popover skeleton
// (`openInlineEditPopover`: Escape / outside-click / Cancel dismiss, focus
// into the first control, the server's refusal shown in place).
function openSealPopover(arg, data, anchorEl) {
  const info = gdSealInfo(arg, data);
  const canEdit = gdSealCanEdit(info.fnId);
  const fnName = lookups?.fnMap?.get(info.fnId)?.name || 'this fn';
  const ownerId = info.slotId ? lookups?.slotOwnerById?.get(info.slotId) : null;
  const ownsSlot = !!ownerId && ownerId === info.fnId && gdSealCanEdit(ownerId);
  // Closing a list needs a list binding to close: the `:list-closed` flag
  // rides on the same row as the items, and creating a flag-only row
  // would shadow the first append's own `:list-append` host.
  const hasListBinding = info.own?.['list-append'] === true;

  const boxes = {};
  const box = (host, key, label, hint, checked, disabled) => {
    const row = document.createElement('label');
    row.className = 'seal-popover-row';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = !!checked;
    cb.disabled = !!disabled;
    cb.dataset.seal = key;
    const text = document.createElement('span');
    text.className = 'seal-popover-label';
    text.textContent = label;
    const small = document.createElement('span');
    small.className = 'seal-popover-hint';
    small.textContent = hint;
    row.appendChild(cb);
    row.appendChild(text);
    row.appendChild(small);
    host.appendChild(row);
    boxes[key] = { cb, was: !!checked };
    return cb;
  };

  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Seals on ' + (arg?.name || 'this slot'),
    noSave: !canEdit,
    makeControl: (root) => {
      const wrap = document.createElement('div');
      wrap.className = 'seal-popover';
      const head = document.createElement('div');
      head.className = 'seal-popover-head';
      head.textContent = (arg?.name ? ':' + arg.name : 'This slot') + ' on ' + fnName;
      wrap.appendChild(head);

      // What an ancestor decided — read-only, with where to change it.
      const inherited = [];
      if (info.above.sealedBy) inherited.push('Sealed in ' + (info.above.sealedByName || 'an ancestor') + ' — lift it there.');
      if (info.above.listClosedBy) inherited.push('List closed in ' + (info.above.listClosedByName || 'an ancestor') + ' — reopen it there.');
      if (info.slotOptional && info.above.requiredBy) inherited.push('Required since ' + (info.above.requiredByName || 'an ancestor') + ' — a ratchet, it cannot be undone below.');
      if (!info.slotOptional && !ownsSlot) inherited.push('Required by declaration' + (ownerId && lookups?.fnMap?.get(ownerId)?.name ? ' (' + lookups.fnMap.get(ownerId).name + ')' : '') + '.');
      for (const line of inherited) {
        const p = document.createElement('div');
        p.className = 'seal-popover-inherited';
        p.textContent = line;
        wrap.appendChild(p);
      }

      if (canEdit) {
        box(wrap, 'terminal', 'Seal against descendants',
            info.above.sealedBy ? 'already sealed above' : 'no fn extending ' + fnName + ' may bind this slot',
            info.here.terminal, !!info.above.sealedBy);
        if (info.isSeq) {
          box(wrap, 'list-closed', 'Close the list',
              info.above.listClosedBy ? 'already closed above'
                : (hasListBinding ? 'no fn extending ' + fnName + ' may append' : 'append an item first'),
              info.here.listClosed, !!info.above.listClosedBy || !hasListBinding);
        }
        if (info.slotOptional) {
          box(wrap, 'required', 'Require it here',
              info.above.requiredBy ? 'already required above' : 'optional by declaration; required from ' + fnName + ' down',
              info.here.required, !!info.above.requiredBy);
        }
        if (ownsSlot) {
          box(wrap, 'slot-optional', 'Optional',
              'the declaration: the fn runs without it; a descendant may still require it',
              info.slotOptional, false);
        }
      } else {
        const p = document.createElement('div');
        p.className = 'seal-popover-inherited';
        p.textContent = gdSealAny(info) ? '' : 'No seal on this slot.';
        if (p.textContent) wrap.appendChild(p);
        const why = document.createElement('div');
        why.className = 'seal-popover-hint';
        why.textContent = (typeof isAuthenticated === 'function' && !isAuthenticated())
          ? 'Sign in to change seals on your own fns.'
          : 'Only the fn’s owner changes its seals.';
        wrap.appendChild(why);
      }
      root.appendChild(wrap);
      return Object.values(boxes)[0]?.cb || wrap;
    },
    doSave: async () => {
      const fields = {};
      for (const key of ['terminal', 'list-closed', 'required']) {
        const b = boxes[key];
        if (b && !b.cb.disabled && b.cb.checked !== b.was) fields[key] = b.cb.checked ? 'true' : '';
      }
      let res = { ok: true };
      if (Object.keys(fields).length) {
        res = await writeBindingFields(arg, fields);
        if (!res.ok) return res;
      }
      const so = boxes['slot-optional'];
      if (so && so.cb.checked !== so.was) {
        // The slot's own declaration — a slot PUT, owner-only. `required=false`
        // is the optional declaration; `true` restores the default.
        const r = await authMutate('PUT', API.api_entities_type_id('slot', info.slotId),
                                   'required=' + (so.cb.checked ? 'false' : 'true'));
        if (!r?.ok) return { ok: false, error: await responseError(r) };
      }
      return res;
    },
    onSaved: () => {
      if (typeof gdToast === 'function') gdToast('Seals on :' + (arg?.name || 'slot') + ' saved');
      if (typeof loadGraphData === 'function') loadGraphData();
    },
  });
}

window.gdSealInfo = gdSealInfo;
window.gdSealBlocksBinder = gdSealBlocksBinder;
