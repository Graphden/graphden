// Editor Fn-Picker — type-aware popover for picking a fn from the graph.
// Opened by a slot's `+` (bind / append a fn-ref), by re-parent / MI-add
// and by Wrap. Mounts as a fixed overlay anchored to a caller-supplied
// element.
//
// Public API:
//   openFnPicker({anchorEl, excludeIds, fnNamespaceId,
//                 expectedType, onPick(fn), onCancel?})
//   closeFnPicker()
//
// `excludeIds` is a Set/Array of fn-ids to omit (e.g. self + descendants
// when re-parenting to avoid cycles). `fnNamespaceId` is the namespace of
// the fn being edited — its group lists first. `expectedType` (optional)
// is the slot type the picked fn will be bound to: every row then carries
// the checker's verdict (✓ compatible, or dimmed with the mismatch
// explainer a click away) and its fit tier.
//
// THE LIST IS THE EXPLORER'S SEARCH. One scrolling list:
//   - typing a name shows EVERY fn whose name or namespace contains it —
//     an "Exact match" block first (the row the reader typed the full
//     name of is always on top), then the rest grouped under namespace
//     headers, compatible rows before incompatible ones inside a group,
//     the incompatible ones dimmed rather than hidden;
//   - with nothing typed the list is a browsable tree of compatible fns:
//     namespace groups that fold and unfold like the Explorer's (the fn's
//     own namespace open, the rest folded unless the whole list is short),
//     with one toggle at the bottom to also show the fns of other types.
// A fit tier (exact / needs inputs / ignores the input) is an ORDER and a
// small chip on the row, not a fold — nothing the reader is looking for
// is ever behind a header. The previous accordion (Compatible / Other,
// each cut into collapsible tiers) hid the exact-name hit under six test
// fns and folded one section when the other opened; see the header of
// editor-fn-picker-rank.js for the whole story.
//
// The candidate set: the loaded cache (graphData.fns) + the server's
// whole-graph compatible set for the slot type (/api/types/candidates,
// authoritative once it lands) + a debounced server name search while
// the reader types, so a fn outside the loaded cache is one keystroke
// away. Rows that arrive by name only are resolved to an id on pick.

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
  // The namespace whose group lists (and, in browse mode, opens) first:
  // the caller's `fnNamespaceId`, else the namespace of the first excluded
  // fn — the slot binders exclude exactly the fn being edited, which is
  // where a reader's own fns live.
  const firstExcluded = (opts.excludeIds || [])[0];
  const wantNs = opts.fnNamespaceId
    || (firstExcluded && typeof lookups !== 'undefined' ? lookups?.fnMap?.get(firstExcluded)?.['namespace-id'] : null)
    || null;
  const expected = opts.expectedType || null;
  // The server's whole-graph verdict, keyed by QUALIFIED name (two fns may
  // share a bare name across namespaces). `serverLoaded` flips when the
  // fetch lands: from then on the verdict is authoritative both ways — a
  // row the server did not list is incompatible, whatever the client's
  // primitive-only approximation guessed on the first paint.
  const serverCompat = new Set();
  const serverRows = new Map();
  let serverLoaded = false;
  // …and until it lands no row wears a verdict at all. The client's
  // `clientSubtype` is primitive-only — it judges a fn by its RETURN type,
  // so for a callable slot it called `str-upper` (→ text) incompatible with
  // `(item:a) → b`, and a reader who typed the name and clicked in that
  // first second got the mismatch explainer for a perfectly good pick
  // (lesson 15 walk). Pending rows render neutral; a click on
  // one waits for the verdict, then picks or explains.
  let serverFailed = false;
  let loadPromise = null;

  const qualifiedOf = (f) => (typeof getQualifiedFnName === 'function')
    ? getQualifiedFnName(f) : f.name;

  // Map a fn row to a picker candidate.
  function toCandidate(f) {
    const info = fnRichInfo(f);
    const qualified = qualifiedOf(f);
    // null = no verdict: an untyped picker, or a typed one whose server
    // verdict has not landed yet (or never will — then it stays neutral and
    // a click simply picks, the post-write type check being the safety net).
    const compatible = (expected && serverLoaded) ? serverCompat.has(qualified) : null;
    const rich = (typeof richTypeEntryOf === 'function') ? richTypeEntryOf(f) : null;
    const arity = rich?.args && typeof rich.args === 'object'
      ? Object.keys(rich.args).length : null;
    const srv = serverRows.get(qualified);
    return {
      id: f.id,
      name: f.name,
      qualified,
      ns: (typeof getFnNamespace === 'function') ? getFnNamespace(f) : null,
      sameNs: !!(wantNs && f['namespace-id'] === wantNs),
      arity: srv && typeof srv.arity === 'number' ? srv.arity : arity,
      fit: expected
        ? (srv?.fit ? srv.fit
           : (typeof pickerFitTier === 'function' ? pickerFitTier(expected, arity) : 'exact'))
        : null,
      flatReturn: f['return-type'] || null,
      richReturn: info.return,
      effects: info.effects,
      compatible,
      // Surface the type-row kind so the row can carry a small annotation
      // ("refinement", "record", …). "composed" fns leave it null — the
      // return-type chip already says what a regular fn returns.
      kind: f.role && f.role !== 'composed' ? String(f.role).replace(/^:/, '') : null,
    };
  }

  // Candidates come from the loaded fn cache (current subtree + expanded
  // namespaces + prior searches) plus the server's compatible set. Only
  // globally-named fns are eligible — anonymous locals can't be referenced
  // by id from another fn's binding graph anyway.
  function buildCandidates() {
    const local = (graphData.fns || [])
      .filter(f => f?.name && !excludeSet.has(f.id))
      .map(toCandidate);
    const have = new Set(local.map(c => c.qualified));
    const extra = [];
    for (const [qualified, c] of serverRows) {
      if (have.has(qualified) || !c?.name || c.name.startsWith('_anon-')) continue;
      extra.push({
        id: null,                       // resolved by name on pick
        name: c.name,
        qualified,
        ns: c.ns || null,
        sameNs: !!(wantNs && c['ns-id'] && c['ns-id'] === wantNs),
        arity: typeof c.arity === 'number' ? c.arity : null,
        fit: c.fit || 'exact',
        flatReturn: typeof c.return === 'string' ? c.return : null,
        richReturn: c.return || null,
        effects: Array.isArray(c.effects) ? c.effects : [],
        compatible: true,               // the server already type-checked it
        kind: null,
      });
    }
    return local.concat(extra);
  }
  let candidates = buildCandidates();

  // When a type is expected, pull the WHOLE-GRAPH type-compatible set from
  // the server (/api/types/candidates) so the picker isn't limited to the
  // loaded cache — this is the server-side type filter (SCALING §6.1). The
  // rows carry name / ns / return / effects / fit but no id.
  async function loadTypedCandidates() {
    if (!expected) return;
    const giveUp = () => { serverFailed = true; if (fnPickerEl) render(); };
    if (typeof authFetch !== 'function' || !API?.api_types_candidates) { giveUp(); return; }
    let data;
    try {
      const r = await authFetch(API.api_types_candidates, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expected }),
      });
      if (!r.ok) { giveUp(); return; }
      data = await r.json();
    } catch (_) { giveUp(); return; }
    if (!data?.ok || !Array.isArray(data.candidates)) { giveUp(); return; }
    if (!fnPickerEl) return;   // closed while the fetch was in flight
    for (const c of data.candidates) {
      if (!c?.name || c.name.startsWith('_anon-')) continue;
      const qualified = c.ns ? (c.ns + '.' + c.name) : c.name;
      serverCompat.add(qualified);
      serverRows.set(qualified, c);
    }
    serverLoaded = true;
    candidates = buildCandidates();
    // The verdicts are final from here — a hook for tests and tour steps
    // that must not read the first, approximate paint.
    list.dataset.loaded = 'true';
    render();
  }

  // -------- Build the popup --------
  const el = document.createElement('div');
  el.className = 'fn-picker-popover';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'false');
  el.setAttribute('aria-label', expected
    ? ('Pick a function compatible with ' + (typeof formatTypeHint === 'function' ? formatTypeHint(expected) : 'expected type'))
    : 'Pick a function');
  const rect = opts.anchorEl.getBoundingClientRect();
  el.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - 380)) + 'px';
  // Below the anchor when it fits, otherwise pushed up so the whole
  // popover stays on screen — re-run after every render, because folding
  // a group changes the height.
  const place = () => {
    const h = el.offsetHeight;
    let top = rect.bottom + 6;
    if (top + h > window.innerHeight - 8) top = Math.max(8, window.innerHeight - h - 8);
    el.style.top = top + 'px';
  };

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
  // Combobox: focus stays here while ↑↓ move the highlight in the list
  // below. `aria-activedescendant` is what makes a screen reader read out
  // the highlighted row — without it the arrows are silent.
  search.setAttribute('role', 'combobox');
  search.setAttribute('aria-expanded', 'true');
  search.setAttribute('aria-autocomplete', 'list');
  search.setAttribute('aria-controls', 'fn-picker-list');
  el.appendChild(search);

  // One list. Groups fold and unfold inside it; nothing else is stacked.
  const list = document.createElement('div');
  list.className = 'fn-picker-list';
  list.id = 'fn-picker-list';
  list.setAttribute('role', 'listbox');
  list.setAttribute('aria-label', expected ? 'Functions, compatible first' : 'Functions');
  el.appendChild(list);

  // A one-line summary under the list: how many rows, how many hidden.
  const status = document.createElement('div');
  status.className = 'fn-picker-status';
  status.setAttribute('aria-live', 'polite');
  el.appendChild(status);

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

  // The reader chose a row: pick it, or — for a row the checker rejects —
  // open the explainer. While the server verdict is still in flight the
  // choice WAITS for it (the row is neutral on screen; the candidate is
  // re-read afterwards, since the list was rebuilt), so a fast reader can
  // never be told a good fn is a mismatch.
  async function choose(c, rowEl) {
    let cur = c;
    if (expected && !serverLoaded && !serverFailed && loadPromise) {
      await loadPromise;
      if (!fnPickerEl) return;
      cur = candidates.find((x) => x.qualified === c.qualified) || c;
    }
    if (expected && serverLoaded && cur.compatible === false) explainAndOfferAnyway(cur, rowEl);
    else pickFn(cur);
  }

  async function pickFn(c) {
    let fn = c.id ? (graphData.fns || []).find(f => f.id === c.id) : null;
    // A server-sourced candidate carries a name but no id yet (it may be
    // outside the loaded set) — resolve it by QUALIFIED name on pick, so
    // two fns sharing a bare name cannot be confused.
    if (!fn && !c.id && c.qualified && typeof resolveFnByName === 'function') {
      try { fn = await resolveFnByName(c.qualified); } catch (_) { /* fall through */ }
    }
    closeFnPicker();
    if (typeof opts.onPick === 'function') {
      opts.onPick(fn || { id: c.id, name: c.name });
    }
  }

  // Open the server-rendered explainer popover for an incompatible row.
  // Fetches `/partials/fn-picker-incompat` with the slot's expected type +
  // the candidate fn-id; the partial calls `:describe-type-mismatch`
  // server-side so the reason text matches the backend's own
  // `/api/types/compatible` verdict. Mounts into the singleton
  // `.mismatch-explainer` element so dismissal + anchor positioning reuse
  // the mismatch-explainer machinery. Offers "Pick anyway".
  async function explainAndOfferAnyway(c, anchorRow) {
    if (!expected || !c.id) { pickFn(c); return; }
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
    const ex = (typeof ensureMismatchExplainerEl === 'function')
               ? ensureMismatchExplainerEl()
               : null;
    if (!ex) { pickFn(c); return; }
    ex.innerHTML = html;
    const close = ex.querySelector('[data-explainer-close]');
    if (close && typeof hideMismatchExplainer === 'function') {
      close.addEventListener('click', (e) => {
        e.stopPropagation();
        hideMismatchExplainer();
      });
    }
    const pickBtn = ex.querySelector('[data-pick-fn-id]');
    if (pickBtn) {
      pickBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        if (typeof hideMismatchExplainer === 'function') hideMismatchExplainer();
        pickFn(c);
      });
    }
    ex.classList.add('visible');
    ex.style.display = '';
    if (typeof anchorBelowClamped === 'function') {
      anchorBelowClamped(ex, anchorRow);
    }
  }

  // -------- Render --------

  let activeIdx = 0;     // index into visibleRows
  // Everything ↑↓ can land on, in DOM order: a fn row (`{c, rowEl}`) or a
  // FOLDABLE namespace header in browse mode (`{group, rowEl}`) — so a
  // keyboard reader can open a folded group with Enter / → and close it
  // with ←, the way the Explorer's tree works, without leaving the filter
  // field. The header is an option of the listbox for that purpose (its
  // `aria-expanded` says which kind it is).
  let visibleRows = [];
  // Browse-mode state the reader changes by hand: groups toggled open /
  // closed, and whether fns of other types are listed too.
  const openGroups = new Set();
  const closedGroups = new Set();
  let showOther = false;

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

  const setActive = (idx) => {
    if (!visibleRows.length) return;
    idx = Math.max(0, Math.min(idx, visibleRows.length - 1));
    if (visibleRows[activeIdx]) {
      visibleRows[activeIdx].rowEl.classList.remove('fn-picker-row-active');
      visibleRows[activeIdx].rowEl.setAttribute('aria-selected', 'false');
    }
    activeIdx = idx;
    const row = visibleRows[activeIdx].rowEl;
    row.classList.add('fn-picker-row-active');
    row.setAttribute('aria-selected', 'true');
    search.setAttribute('aria-activedescendant', row.id);
  };

  // `bareName` — under a namespace header the row carries the bare name (the
  // group already says where it lives); the Exact-match block spells the
  // namespace out, because that block mixes namespaces.
  function renderRow(c, idx, bareName) {
    const compat = (expected && serverLoaded) ? (c.compatible !== false) : null;
    const row = document.createElement('div');
    row.className = 'fn-picker-row'
      + (compat === true ? ' fn-picker-row-compat' : '')
      + (compat === false ? ' fn-picker-row-incompat' : '');
    row.setAttribute('role', 'option');
    row.id = 'fn-picker-opt-' + idx;
    row.setAttribute('aria-selected', 'false');
    // Stable hook for the tutorial spotlight (and tests): which fn this row is.
    row.dataset.fnName = c.qualified;

    const mark = document.createElement('span');
    mark.className = compat === false ? 'fn-picker-row-no' : 'fn-picker-row-ok';
    mark.textContent = compat === true ? '✓' : (compat === false ? '✗' : '');
    mark.setAttribute('aria-hidden', 'true');
    if (compat !== null) row.appendChild(mark);

    const main = document.createElement('span');
    main.className = 'fn-picker-row-main';
    const lastDot = c.qualified.lastIndexOf('.');
    const label = (n) => (typeof displayLabel === 'function' ? displayLabel(n) : n);
    main.textContent = bareName
      ? label(c.name)
      : (lastDot >= 0
         ? c.qualified.slice(0, lastDot + 1) + label(c.qualified.slice(lastDot + 1))
         : label(c.qualified));
    row.appendChild(main);

    // The fit tier as a chip — only when it is NOT the expectation: an
    // exact fit is what the ✓ already says.
    if (compat === true && typeof pickerTierOf === 'function') {
      const tier = pickerTierOf(expected, c);
      if (tier !== 'exact') {
        const chip = document.createElement('span');
        chip.className = 'fn-picker-row-fit fn-picker-fit-' + tier;
        chip.textContent = (typeof pickerTierLabel === 'function') ? pickerTierLabel(expected, tier) : tier;
        chip.title = (typeof pickerTierTitle === 'function') ? pickerTierTitle(expected, tier) : '';
        row.appendChild(chip);
      }
    }
    if (compat === false) {
      row.title = 'Not a subtype of the expected type — click to see why (and pick anyway)';
    }

    // Kind annotation pill — refinement / record / union / variant /
    // list / fn-type / base-fn / primitive, so type-rows read apart from
    // regular fns at a glance.
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

    row.addEventListener('mouseenter', () => setActive(idx));
    row.addEventListener('click', () => { choose(c, row); });
    return row;
  }

  function renderHeader(text, count, extra, opts2) {
    const h = document.createElement('div');
    h.className = 'fn-picker-ns-header' + (opts2?.button ? ' fn-picker-ns-toggle' : '');
    if (opts2?.button) {
      h.setAttribute('role', 'option');
      h.setAttribute('aria-selected', 'false');
      h.setAttribute('aria-expanded', opts2.open ? 'true' : 'false');
      const arrow = document.createElement('span');
      arrow.className = 'fn-picker-disclosure-arrow';
      arrow.textContent = opts2.open ? '▼' : '▶';
      h.appendChild(arrow);
    }
    const name = document.createElement('span');
    name.className = 'fn-picker-ns-name';
    name.textContent = text;
    h.appendChild(name);
    const n = document.createElement('span');
    n.className = 'fn-picker-ns-count';
    n.textContent = ' · ' + count + (extra ? ' · ' + extra : '');
    h.appendChild(n);
    return h;
  }

  function toggleGroup(g, force) {
    const key = g.ns || '';
    const open = (force === undefined) ? !g.open : force;
    if (open === g.open) return;
    if (open) { closedGroups.delete(key); openGroups.add(key); }
    else { openGroups.delete(key); closedGroups.add(key); }
    render();
  }

  function render() {
    // `/`→`.`: qualified candidate names are dotted, but the product
    // prints the canonical `ns.path/name` spelling everywhere — accept
    // a pasted qualified name in either form.
    const q = search.value.trim().toLowerCase().replace(/\//g, '.');
    const arranged = (typeof pickerArrange === 'function')
      ? pickerArrange(candidates, { q, expected, openGroups, closedGroups, showOther })
      : { exact: [], groups: [{ ns: null, rows: candidates.slice(0, 120), open: true }], shown: 0, total: candidates.length, hiddenOther: 0 };

    list.innerHTML = '';
    visibleRows = [];
    const addRow = (host, c, bareName) => {
      const idx = visibleRows.length;
      const row = renderRow(c, idx, bareName);
      visibleRows.push({ c, rowEl: row });
      host.appendChild(row);
    };

    if (arranged.total === 0) {
      const empty = document.createElement('div');
      empty.className = 'fn-picker-empty';
      empty.textContent = q ? 'No fn is named like that' : (expected ? 'No compatible fns yet' : 'No fns');
      list.appendChild(empty);
    }

    if (arranged.exact.length) {
      const sec = document.createElement('div');
      sec.className = 'fn-picker-exact';
      sec.appendChild(renderHeader('Exact match', arranged.exact.length, null, null));
      for (const c of arranged.exact) addRow(sec, c, false);
      list.appendChild(sec);
    }

    for (const g of arranged.groups) {
      const sec = document.createElement('div');
      sec.className = 'fn-picker-group' + (g.open ? '' : ' fn-picker-group-folded');
      if (g.ns !== null || arranged.groups.length > 1 || arranged.exact.length) {
        const extra = (expected && g.other > 0) ? (g.other + ' other') : null;
        const label = g.ns || '(root)';
        if (!q) {
          // Browse: a fold, like the Explorer's namespace rows — and a stop
          // for ↑↓, so the keyboard can open it.
          const h = renderHeader(label, expected ? g.compat : g.rows.length, extra, { button: true, open: g.open });
          const idx = visibleRows.length;
          h.id = 'fn-picker-opt-' + idx;
          visibleRows.push({ group: g, rowEl: h });
          h.addEventListener('mouseenter', () => setActive(idx));
          h.addEventListener('click', (e) => {
            e.stopPropagation();
            toggleGroup(g);
          });
          sec.appendChild(h);
        } else {
          sec.appendChild(renderHeader(label, g.rows.length, extra, null));
        }
      }
      if (g.open) for (const c of g.rows) addRow(sec, c, true);
      if (g.open && g.truncated) {
        const more = document.createElement('div');
        more.className = 'fn-picker-more';
        more.textContent = '… more — type to narrow';
        sec.appendChild(more);
      }
      list.appendChild(sec);
    }

    // Browse mode, typed slot: the fns of other types wait behind ONE
    // toggle at the end — never an accordion that folds the good rows.
    if (expected && !q && (arranged.hiddenOther > 0 || showOther)) {
      const t = document.createElement('button');
      t.type = 'button';
      t.className = 'fn-picker-other-toggle';
      t.setAttribute('aria-pressed', showOther ? 'true' : 'false');
      t.textContent = showOther
        ? 'Hide fns of other types'
        : ('Show ' + arranged.hiddenOther + ' fns of other types');
      t.addEventListener('click', (e) => {
        e.stopPropagation();
        showOther = !showOther;
        render();
      });
      list.appendChild(t);
    }

    const hidden = arranged.total - arranged.shown;
    const pending = expected && !serverLoaded && !serverFailed;
    status.textContent = (arranged.total === 0 ? ''
      : (arranged.shown + ' of ' + arranged.total
         + (hidden > 0 ? (q ? ' — type more to narrow' : ' — open a namespace or type a name') : '')))
      + (pending ? ' · checking types…' : '');

    activeIdx = Math.min(activeIdx, Math.max(0, visibleRows.length - 1));
    if (visibleRows[activeIdx]) setActive(activeIdx);
    else search.removeAttribute('aria-activedescendant');
    place();
  }
  render();
  // Augment the loaded candidates with the whole-graph type-compatible set
  // (no-op unless an expected type was supplied); `choose` awaits it.
  loadPromise = loadTypedCandidates();

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
        if (seq !== _pickerSearchSeq || !fnPickerEl) return;   // superseded, or closed
        candidates = buildCandidates();
        render();
      }).catch((err) => { console.error('fn-picker search failed', err); });
    }, 180);
  });
  search.addEventListener('keydown', (e) => {
    const entry = visibleRows[activeIdx];
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive(activeIdx + 1);
      visibleRows[activeIdx]?.rowEl.scrollIntoView({ block: 'nearest' });
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive(activeIdx - 1);
      visibleRows[activeIdx]?.rowEl.scrollIntoView({ block: 'nearest' });
    } else if (e.key === 'ArrowRight' && entry?.group) {
      e.preventDefault();
      toggleGroup(entry.group, true);
    } else if (e.key === 'ArrowLeft' && entry?.group) {
      e.preventDefault();
      toggleGroup(entry.group, false);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (!entry) return;
      if (entry.group) toggleGroup(entry.group);
      else choose(entry.c, entry.rowEl);
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
