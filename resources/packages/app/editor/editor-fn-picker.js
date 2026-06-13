// Editor Fn-Picker — type-aware popover for picking a fn from
// graphData.fns. Used by Phase 2's arg-type flip (literal → :fn) and
// reused by Phase 3-4 for re-parent / MI-add. Mounts as an
// absolutely-positioned overlay anchored to a caller-supplied DOM
// element.
//
// Public API:
//   openFnPicker({anchorEl, excludeIds, fnNamespaceId,
//                 expectedType, onPick(fn), onCancel?})
//   closeFnPicker()
//
// `excludeIds` is a Set/Array of fn-ids to omit (e.g. self +
// descendants when re-parenting to avoid cycles). `fnNamespaceId`
// boosts fns sharing that namespace to the top of each section.
// `expectedType` (optional) is the slot type the picked fn will be
// bound to — type-compatible fns appear in the "Compatible" section
// at the top; the rest are in a collapsible "Other" section.
//
// Phase 3 changes vs the previous flat-list design:
//   - Two sections (Compatible / Other) instead of mixed sort with
//     per-row dimming. The compat group is a clean white-list; users
//     no longer have to scroll past dimmed rows to find their target.
//   - Each row shows return-type chip + effect badges (db, env, …)
//     so the user can spot pure-only / effectful candidates at a
//     glance.
//   - Click on an incompatible row opens a mismatch explainer
//     popover (with a "Pick anyway" override) instead of silently
//     picking — closes the "why is this dimmed?" loop without forcing
//     the user back to the type-edit popover.
//   - Touch-target friendly: rows have min-height: 32px; sections are
//     keyboard-navigable across.

let fnPickerEl = null;
let fnPickerOutsideHandler = null;
let fnPickerEscHandler = null;

function closeFnPicker() {
  if (fnPickerEl) {
    fnPickerEl.remove();
    fnPickerEl = null;
  }
  if (fnPickerOutsideHandler) {
    document.removeEventListener('pointerdown', fnPickerOutsideHandler);
    fnPickerOutsideHandler = null;
  }
  if (fnPickerEscHandler) {
    document.removeEventListener('keydown', fnPickerEscHandler);
    fnPickerEscHandler = null;
  }
}

// Look up a fn's RICH return type + effects from /api/types when
// available — that's the structural shape (records, lists,
// refinements) that the type-checker actually uses, plus the
// computed effect set. Falls back to the flat `return-type` column
// for fns the registry hasn't snapshot'd yet.
function fnRichInfo(f) {
  const rich = (typeof richTypes === 'object' && richTypes && f.name)
               ? richTypes[f.name] : null;
  return {
    return: (rich && rich.return != null) ? rich.return : (f['return-type'] || null),
    effects: (rich && Array.isArray(rich.effects)) ? rich.effects : [],
  };
}

function openFnPicker(opts) {
  closeFnPicker();
  if (!opts?.anchorEl) return;
  if (!graphData || !Array.isArray(graphData.fns)) return;

  const excludeSet = new Set(opts.excludeIds || []);
  const wantNs = opts.fnNamespaceId || null;
  const expected = opts.expectedType || null;

  // Only globally-named fns are eligible — anonymous locals can't be
  // referenced by id from a different fn's binding-graph anyway.
  const candidates = graphData.fns
    .filter(f => f?.name && !excludeSet.has(f.id))
    .map(f => {
      const info = fnRichInfo(f);
      // Compatibility check: clientSubtype is the fast local
      // primitive-only fallback; structural cases (records, fn-types,
      // refinements) defer to the row-tap explainer that calls
      // /api/types/compatible. For the initial render we treat
      // unknown-structural as "compatible" (best-effort) — a stricter
      // fetch refines the answer if the user asks why.
      const compatible = expected && info.return
        ? (typeof clientSubtype === 'function' ? clientSubtype(info.return, expected) : true)
        : null;   // null = no expectedType supplied; section headers hide
      return {
        id: f.id,
        name: f.name,
        qualified: (typeof getQualifiedFnName === 'function')
                   ? getQualifiedFnName(f) : f.name,
        sameNs: wantNs && f['namespace-id'] === wantNs,
        flatReturn: f['return-type'] || null,
        richReturn: info.return,
        effects: info.effects,
        compatible: compatible,
        // Surface the type-row kind so the row can carry a small
        // annotation ("refinement", "record", …). For "composed"
        // fns we leave it null — the return-type chip already says
        // what a regular fn returns; the kind tag is redundant.
        kind: f.role && f.role !== 'composed'
              ? String(f.role).replace(/^:/, '')
              : null,
      };
    });

  // Build the popup.
  const el = document.createElement('div');
  el.className = 'fn-picker-popover';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'false');
  el.setAttribute('aria-label', expected ? ('Pick a function compatible with ' + (typeof formatTypeHint === 'function' ? formatTypeHint(expected) : 'expected type'))
                                         : 'Pick a function');
  const rect = opts.anchorEl.getBoundingClientRect();
  el.style.top  = (rect.bottom + 6) + 'px';
  el.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - 360)) + 'px';

  // Header — when an expectedType is supplied, show it so the user
  // knows what kind of fn they're picking.
  if (expected && typeof formatTypeHint === 'function') {
    const header = document.createElement('div');
    header.className = 'fn-picker-expected';
    header.textContent = 'Expected: ' + formatTypeHint(expected);
    el.appendChild(header);
  }

  const search = document.createElement('input');
  search.type = 'text';
  search.className = 'fn-picker-search';
  search.placeholder = 'Filter fns…';
  search.setAttribute('aria-label', 'Filter functions in picker');
  el.appendChild(search);

  // Two list containers — compatible first, "Other" as a collapsible
  // disclosure below. When no expectedType is set, only one section
  // renders and the disclosure is unused (everything goes into the
  // "compat" container without a header).
  const sections = document.createElement('div');
  sections.className = 'fn-picker-sections';
  el.appendChild(sections);

  const compatHeader = document.createElement('div');
  compatHeader.className = 'fn-picker-section-header';
  const compatList = document.createElement('div');
  compatList.className = 'fn-picker-list';
  sections.appendChild(compatHeader);
  sections.appendChild(compatList);

  const otherHeader = document.createElement('button');
  otherHeader.type = 'button';
  otherHeader.className = 'fn-picker-section-header fn-picker-section-disclosure';
  // Collapsed-by-default when there's at least one compatible row.
  // Otherwise expanded so the user has SOMETHING to pick from.
  let otherExpanded = !expected;
  const otherList = document.createElement('div');
  otherList.className = 'fn-picker-list fn-picker-list-other';
  otherHeader.addEventListener('click', () => {
    otherExpanded = !otherExpanded;
    render();
  });
  sections.appendChild(otherHeader);
  sections.appendChild(otherList);

  // Cancel button row — outside-click and Esc also dismiss.
  const cancelRow = document.createElement('div');
  cancelRow.className = 'fn-picker-cancel-row';
  const cancelBtn = document.createElement('button');
  cancelBtn.type = 'button';
  cancelBtn.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
  cancelBtn.textContent = 'Cancel';
  cancelBtn.addEventListener('click', () => {
    closeFnPicker();
    if (typeof opts.onCancel === 'function') opts.onCancel();
  });
  cancelRow.appendChild(cancelBtn);
  el.appendChild(cancelRow);

  document.body.appendChild(el);
  fnPickerEl = el;

  // -------- Pick / explainer wiring --------

  function pickFn(c) {
    const fn = (graphData.fns || []).find(f => f.id === c.id);
    closeFnPicker();
    if (typeof opts.onPick === 'function') {
      opts.onPick(fn || { id: c.id, name: c.name });
    }
  }

  function explainAndOfferAnyway(c, anchorRow) {
    if (typeof renderMismatchExplainer !== 'function') {
      // Fallback: just pick. The explainer module isn't loaded.
      pickFn(c);
      return;
    }
    const formatExpected = (typeof formatTypeHint === 'function')
                           ? formatTypeHint(expected) : String(expected);
    const formatGot = compactTypeChipText(c.richReturn, c.flatReturn) || '(unknown)';
    // Best-effort reason — clientSubtype was already false. The
    // backend /api/types/compatible would give a richer message, but
    // it's a network roundtrip per click. For now, derive a generic
    // explanation; later phases can fetch.
    const reason = 'This fn returns ' + formatGot
                 + ' which is not a subtype of ' + formatExpected
                 + '. Picking it anyway will store the binding but the'
                 + ' type-checker will flag it on save.';
    renderMismatchExplainer({
      expected: formatExpected,
      actual: formatGot,
      reason: reason,
      onEdit: () => pickFn(c),
      editLabel: 'Pick anyway',
      hint: null,
    }, anchorRow);
  }

  // -------- Render --------

  let activeIdx = 0;     // global index across visible (filtered) rows
  let visibleRows = [];  // flat array of {c, section} for keyboard nav

  function buildEffectsBadges(effects) {
    if (!effects || effects.length === 0) return null;
    const wrap = document.createElement('span');
    wrap.className = 'fn-picker-row-effects';
    effects.forEach(e => {
      const tag = document.createElement('span');
      tag.className = 'effects-chip effects-chip-' + e;
      tag.textContent = String(e).toUpperCase();
      wrap.appendChild(tag);
    });
    return wrap;
  }

  function renderRow(c, section, idx) {
    const row = document.createElement('div');
    row.className = 'fn-picker-row'
      + (idx === activeIdx ? ' fn-picker-row-active' : '')
      + (section === 'incompat' ? ' fn-picker-row-incompat' : '')
      + (section === 'compat' ? ' fn-picker-row-compat' : '');
    row.setAttribute('role', 'option');
    row.setAttribute('aria-selected', idx === activeIdx ? 'true' : 'false');

    if (section === 'compat') {
      const ok = document.createElement('span');
      ok.className = 'fn-picker-row-ok';
      ok.textContent = '✓';
      ok.setAttribute('aria-hidden', 'true');
      row.appendChild(ok);
    }

    const main = document.createElement('span');
    main.className = 'fn-picker-row-main';
    const lastDot = c.qualified.lastIndexOf('.');
    const visible = lastDot >= 0
      ? c.qualified.slice(0, lastDot + 1)
        + (typeof displayLabel === 'function'
           ? displayLabel(c.qualified.slice(lastDot + 1))
           : c.qualified.slice(lastDot + 1))
      : (typeof displayLabel === 'function' ? displayLabel(c.qualified) : c.qualified);
    main.textContent = visible;
    row.appendChild(main);

    // Kind annotation pill — refinement / record / union / variant /
    // list / fn-type / base-fn / primitive. Lets the user
    // disambiguate type-rows from regular fns at a glance.
    if (c.kind) {
      const kindEl = document.createElement('span');
      kindEl.className = 'fn-picker-row-kind fn-picker-row-kind-' + c.kind;
      kindEl.textContent = c.kind;
      kindEl.setAttribute('aria-label', 'Kind: ' + c.kind);
      row.appendChild(kindEl);
    }

    const effects = buildEffectsBadges(c.effects);
    if (effects) row.appendChild(effects);

    const rt = compactTypeChipText(c.richReturn, c.flatReturn);
    if (rt) {
      const rtEl = document.createElement('span');
      rtEl.className = 'fn-picker-row-rt';
      rtEl.textContent = '→ ' + rt;
      row.appendChild(rtEl);
    }

    row.addEventListener('mouseenter', () => {
      activeIdx = idx;
      sections.querySelectorAll('.fn-picker-row-active')
              .forEach(r => {
                r.classList.remove('fn-picker-row-active');
                r.setAttribute('aria-selected', 'false');
              });
      row.classList.add('fn-picker-row-active');
      row.setAttribute('aria-selected', 'true');
    });
    row.addEventListener('click', () => {
      if (section === 'incompat') {
        explainAndOfferAnyway(c, row);
      } else {
        pickFn(c);
      }
    });
    return row;
  }

  function render() {
    const q = search.value.trim().toLowerCase();
    const filtered = candidates
      .filter(c => !q || c.qualified.toLowerCase().includes(q)
                       || c.name.toLowerCase().includes(q))
      .sort((a, b) => {
        if (a.sameNs !== b.sameNs) return a.sameNs ? -1 : 1;
        return a.qualified.localeCompare(b.qualified);
      });

    let compat, incompat;
    if (expected) {
      compat = filtered.filter(c => c.compatible === true).slice(0, 50);
      incompat = filtered.filter(c => c.compatible === false).slice(0, 50);
    } else {
      compat = filtered.slice(0, 50);
      incompat = [];
    }

    // -------- Compat header --------
    if (expected) {
      compatHeader.textContent = 'Compatible · ' + compat.length;
      compatHeader.style.display = 'block';
    } else {
      compatHeader.style.display = 'none';
    }

    // -------- Other header (collapsible) --------
    if (expected && incompat.length > 0) {
      otherHeader.style.display = 'flex';
      otherHeader.textContent = '';
      const arrow = document.createElement('span');
      arrow.className = 'fn-picker-disclosure-arrow';
      arrow.textContent = otherExpanded ? '▼' : '▶';
      otherHeader.appendChild(arrow);
      const lbl = document.createElement('span');
      lbl.textContent = ' Other · ' + incompat.length;
      otherHeader.appendChild(lbl);
      otherHeader.setAttribute('aria-expanded', otherExpanded ? 'true' : 'false');
    } else {
      otherHeader.style.display = 'none';
    }

    // -------- Rows --------
    compatList.innerHTML = '';
    otherList.innerHTML = '';

    visibleRows = [];
    if (compat.length === 0 && incompat.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'fn-picker-empty';
      empty.textContent = 'No matches';
      compatList.appendChild(empty);
      return;
    }

    if (activeIdx >= compat.length + (otherExpanded ? incompat.length : 0)) {
      activeIdx = 0;
    }

    compat.forEach(c => {
      visibleRows.push({ c, section: expected ? 'compat' : 'neutral' });
    });
    if (otherExpanded) {
      incompat.forEach(c => visibleRows.push({ c, section: 'incompat' }));
    }
    visibleRows.forEach((entry, idx) => {
      const row = renderRow(entry.c, entry.section, idx);
      if (entry.section === 'incompat') {
        otherList.appendChild(row);
      } else {
        compatList.appendChild(row);
      }
    });

    if (compat.length === 0 && expected) {
      const empty = document.createElement('div');
      empty.className = 'fn-picker-empty';
      empty.textContent = 'No compatible fns — try "Other" below';
      compatList.appendChild(empty);
    }

    otherList.style.display = otherExpanded ? 'block' : 'none';
  }
  render();

  search.addEventListener('input', () => { activeIdx = 0; render(); });
  search.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (activeIdx < visibleRows.length - 1) { activeIdx++; render(); }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (activeIdx > 0) { activeIdx--; render(); }
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const entry = visibleRows[activeIdx];
      if (!entry) return;
      if (entry.section === 'incompat') {
        const row = otherList.children[activeIdx - (visibleRows.length - otherList.children.length)];
        explainAndOfferAnyway(entry.c, row || el);
      } else {
        pickFn(entry.c);
      }
    } else if (e.key === 'Escape') {
      e.preventDefault();
      closeFnPicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }
  });

  setTimeout(() => search.focus(), 0);

  fnPickerOutsideHandler = (e) => {
    if (!el.contains(e.target)) {
      // The mismatch explainer popover lives outside the picker but
      // is logically part of the same flow — clicks inside it
      // shouldn't dismiss the picker.
      const explainerEl = document.querySelector('.mismatch-explainer.visible');
      if (explainerEl?.contains(e.target)) return;
      closeFnPicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }
  };
  setTimeout(() => document.addEventListener('pointerdown', fnPickerOutsideHandler), 0);

  fnPickerEscHandler = (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      closeFnPicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }
  };
  document.addEventListener('keydown', fnPickerEscHandler);
}
