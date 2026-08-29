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
// The control the picker was opened from (an arg row, a parent chip). Every
// caller already passes it as opts.anchorEl; keeping it here lets close()
// hand the keyboard back however the picker was dismissed.
let fnPickerAnchor = null;

// Installed once — reads the live element and is inert while closed.
installTabTrap({
  getEl: () => fnPickerEl,
  isVisible: () => !!fnPickerEl,
});

function closeFnPicker() {
  if (fnPickerEl) {
    const hadFocus = fnPickerEl.contains(document.activeElement);
    fnPickerEl.remove();
    fnPickerEl = null;
    if (hadFocus) returnFocusTo(fnPickerAnchor);
    fnPickerAnchor = null;
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
  const rich = (typeof richTypeEntryOf === 'function')
               ? richTypeEntryOf(f) : null;
  return {
    return: (rich && rich.return != null) ? rich.return : (f['return-type'] || null),
    effects: (rich && Array.isArray(rich.effects)) ? rich.effects : [],
  };
}

// graph-first-exception: the candidate list is filtered + rendered from the
// in-memory `graphData.fns` cache (+ type-compatibility checks) and must appear
// instantly on click; a GET /partials/* would add a ~30ms round-trip per open
// AND move the client-only type-filter to the server (§6.1 perf).
function openFnPicker(opts) {
  closeFnPicker();
  if (!opts?.anchorEl) return;
  fnPickerAnchor = opts.anchorEl;
  if (!graphData || !Array.isArray(graphData.fns)) return;

  const excludeSet = new Set(opts.excludeIds || []);
  const wantNs = opts.fnNamespaceId || null;
  const expected = opts.expectedType || null;
  // Names the server (/api/types/candidates) confirmed compatible.
  // Held at picker scope so a filter keystroke — which REBUILDS the
  // candidate list from graphData — cannot downgrade them back to the
  // client's primitive-only approximation: a fully-bound fn offered to
  // a callable slot classified compatible on open, then fell into
  // "Other" with a false ":text is not a subtype of [:fn …]" as soon
  // as the reader typed its name (tutorial finding 2026-08-26,
  // lesson 32).
  const serverCompat = new Set();

  // Map a fn row to a picker candidate. Compatibility check: clientSubtype
  // is the fast local primitive-only fallback; structural cases (records,
  // fn-types, refinements) defer to the row-tap explainer that calls
  // /api/types/compatible. Unknown-structural is treated as "compatible"
  // (best-effort) on the initial render; a stricter fetch refines it.
  function toCandidate(f) {
    const info = fnRichInfo(f);
    const compatible = expected && info.return
      ? (serverCompat.has(f.name)
         || (typeof clientSubtype === 'function' ? clientSubtype(info.return, expected) : true))
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
      // Surface the type-row kind so the row can carry a small annotation
      // ("refinement", "record", …). "composed" fns leave it null — the
      // return-type chip already says what a regular fn returns.
      kind: f.role && f.role !== 'composed' ? String(f.role).replace(/^:/, '') : null,
    };
  }

  // Candidates come from the loaded fn cache (current subtree + expanded
  // namespaces + prior searches). Only globally-named fns are eligible —
  // anonymous locals can't be referenced by id from another fn's binding
  // graph anyway. Typing in the filter box fetches more via the server
  // (searchFns) and rebuilds this list — see the input handler below.
  function buildCandidates() {
    return (graphData.fns || [])
      .filter(f => f?.name && !excludeSet.has(f.id))
      .map(toCandidate);
  }
  let candidates = buildCandidates();

  // When a type is expected, pull the WHOLE-GRAPH type-compatible set from
  // the server (/api/types/candidates) so the picker isn't limited to the
  // loaded cache — this is the server-side type filter (SCALING §6.1). The
  // rows carry name / return / effects but no id (resolved on pick);
  // anonymous locals are dropped (not referenceable from another fn). Names
  // already present in the loaded candidates are skipped so we don't
  // double-list (and keep the richer, id-bearing local row).
  async function loadTypedCandidates() {
    if (!expected || typeof authFetch !== 'function' || !API?.api_types_candidates) return;
    let data;
    try {
      const r = await authFetch(API.api_types_candidates, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expected }),
      });
      if (!r.ok) return;
      data = await r.json();
    } catch (_) { return; }
    if (!data?.ok || !Array.isArray(data.candidates)) return;
    for (const c of data.candidates) {
      if (c?.name && !c.name.startsWith('_anon-')) serverCompat.add(c.name);
    }
    const compatNames = serverCompat;
    // The server's verdict is authoritative — upgrade any loaded candidate
    // it confirms compatible (beats the client's primitive-only
    // `clientSubtype` approximation, which can mis-rule structural types).
    for (const c of candidates) {
      if (compatNames.has(c.name)) c.compatible = true;
    }
    const have = new Set(candidates.map(c => c.name));
    const extra = data.candidates
      .filter(c => c?.name && !c.name.startsWith('_anon-') && !have.has(c.name))
      .map(c => ({
        id: null,                       // resolved by name on pick
        name: c.name,
        qualified: c.name,
        sameNs: false,
        flatReturn: c.return || null,
        richReturn: c.return || null,
        effects: Array.isArray(c.effects) ? c.effects : [],
        compatible: true,               // the server already type-checked it
        kind: null,
      }));
    candidates = candidates.concat(extra);
    render();
  }

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
  // Combobox: focus stays here while ↑↓ move the highlight in the lists
  // below. `aria-activedescendant` is what makes a screen reader read out
  // the highlighted row — without it the arrows are silent, which is the
  // whole point of the pattern.
  search.setAttribute('role', 'combobox');
  search.setAttribute('aria-expanded', 'true');
  search.setAttribute('aria-autocomplete', 'list');
  search.setAttribute('aria-controls', 'fn-picker-list-compat fn-picker-list-other');
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
  compatList.id = 'fn-picker-list-compat';
  compatList.setAttribute('role', 'listbox');
  compatList.setAttribute('aria-label', 'Compatible functions');
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
  otherList.id = 'fn-picker-list-other';
  otherList.setAttribute('role', 'listbox');
  otherList.setAttribute('aria-label', 'Other functions');
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

  async function pickFn(c) {
    let fn = c.id ? (graphData.fns || []).find(f => f.id === c.id) : null;
    // A server-sourced typed candidate carries a name but no id yet
    // (it may be outside the loaded set) — resolve it by name on pick.
    if (!fn && !c.id && c.name && typeof resolveFnByName === 'function') {
      // Pass the candidate's name WHOLE — the resolver handles
      // qualified (slash or legacy dotted) and bare forms; stripping
      // to the last segment defeated disambiguation for duplicates.
      try { fn = await resolveFnByName(c.name); } catch (_) { /* fall through */ }
    }
    closeFnPicker();
    if (typeof opts.onPick === 'function') {
      opts.onPick(fn || { id: c.id, name: c.name });
    }
  }

  // Open the server-rendered explainer popover. Fetches
  // `/partials/fn-picker-incompat` with the slot's expected type +
  // the candidate fn-id; the partial calls `:describe-type-mismatch`
  // server-side so the reason text matches the backend's own
  // `/api/types/compatible` verdict (no client-only "best-effort
  // reason" drift anymore). Mounts into the singleton `.mismatch-
  // explainer` element so dismissal + anchor positioning reuse the
  // mismatch-explainer machinery.
  async function explainAndOfferAnyway(c, anchorRow) {
    if (!expected) { pickFn(c); return; }
    const params = new URLSearchParams({
      expected: JSON.stringify(expected),
      'candidate-fn-id': c.id,
    });
    let html;
    try {
      const r = await authFetch('/partials/fn-picker-incompat?' + params.toString());
      if (!r.ok) { pickFn(c); return; }
      html = await r.text();
    } catch (_) {
      pickFn(c);
      return;
    }
    const el = (typeof ensureMismatchExplainerEl === 'function')
               ? ensureMismatchExplainerEl()
               : null;
    if (!el) { pickFn(c); return; }
    el.innerHTML = html;
    // Close button
    const close = el.querySelector('[data-explainer-close]');
    if (close && typeof hideMismatchExplainer === 'function') {
      close.addEventListener('click', (e) => {
        e.stopPropagation();
        hideMismatchExplainer();
      });
    }
    // Pick-anyway button — bind to the picker's pickFn closure.
    const pickBtn = el.querySelector('[data-pick-fn-id]');
    if (pickBtn) {
      pickBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        if (typeof hideMismatchExplainer === 'function') hideMismatchExplainer();
        pickFn(c);
      });
    }
    el.classList.add('visible');
    el.style.display = '';
    if (typeof anchorBelowClamped === 'function') {
      anchorBelowClamped(el, anchorRow);
    }
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
    row.id = 'fn-picker-opt-' + idx;
    row.setAttribute('aria-selected', idx === activeIdx ? 'true' : 'false');
    if (idx === activeIdx) search.setAttribute('aria-activedescendant', row.id);
    // Stable hook for the tutorial spotlight (and tests): which fn this row is.
    row.dataset.fnName = c.qualified;

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
      search.setAttribute('aria-activedescendant', row.id);
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
    // `/`→`.`: qualified candidate names are dotted, but the product
    // prints the canonical `ns.path/name` spelling everywhere — accept
    // a pasted qualified name in either form.
    const q = search.value.trim().toLowerCase().replace(/\//g, '.');
    // Mirror the server's ranking: exact name, then name-substring, then
    // qualified-only — so an exact hit isn't crowded below a namespace's
    // worth of qualified matches before the 50-row cap.
    const tier = (c) => {
      const n = c.name.toLowerCase();
      if (n === q) return 0;
      if (n.includes(q)) return 1;
      return 2;
    };
    const filtered = candidates
      .filter(c => !q || c.qualified.toLowerCase().includes(q)
                       || c.name.toLowerCase().includes(q))
      .sort((a, b) => {
        if (a.sameNs !== b.sameNs) return a.sameNs ? -1 : 1;
        if (q) {
          const t = tier(a) - tier(b);
          if (t !== 0) return t;
        }
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
  // Fire-and-forget: augment the loaded candidates with the whole-graph
  // type-compatible set (no-op unless an expected type was supplied).
  loadTypedCandidates();

  // Instant client-side filter over the loaded candidates, PLUS a debounced
  // server search so a fn outside the loaded set becomes pickable by typing
  // its name. searchFns merges matches into the cache; rebuild + re-render.
  let _pickerSearchSeq = 0;
  let _pickerSearchTimer = null;
  search.addEventListener('input', () => {
    activeIdx = 0;
    render();
    const q = search.value.trim();
    if (!q || typeof searchFns !== 'function') return;
    const seq = ++_pickerSearchSeq;
    clearTimeout(_pickerSearchTimer);
    _pickerSearchTimer = setTimeout(() => {
      searchFns(q).then(() => {
        if (seq !== _pickerSearchSeq) return;   // superseded by a later keystroke
        candidates = buildCandidates();
        render();
      }).catch((err) => { console.error('fn-picker search failed', err); });
    }, 180);
  });
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
      if (pointerEventInTour(e)) return;
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
