// Editor Type Inline-Expand — same gesture as fn-card expansion, but
// applied to a type-chip on an edge label. Clicking the trigger
// (`▸ / ▾`) next to the chip reveals the type's constituents
// (refine→base+constraint, list→element, union→branches, record→
// fields, fn→args+return). Each nested chip is itself clickable for
// further expansion.
//
// The expansion panel lives at the BODY level (not inside the edge-
// label overlay), positioned with `position: fixed` next to the
// anchor chip. Keeping it out of the overlay means the overlay's
// own bounding box doesn't grow horizontally when the panel opens,
// so the right-anchored overlay can't push under the source fn card.
// Pan / zoom / resize re-position via `gv.onViewportChange` + window listener.
//
// State (`expandedTypePaths`) is a Set of stable string paths so the
// user's expanded selections survive overlay rebuilds and preview
// redraws. Hosts are keyed by the same path in `inlineHostsByPath`.

// graph-first-exception: host lifecycle of the inline expand panel —
// position:fixed hosts re-anchored on every pan/zoom frame, persisted
// expandedTypePaths, effect-tightening widgets bound to editor state; the
// row RENDERING rationale lives in editor-type-expand-render.js.
const expandedTypePaths = new Set();
const inlineHostsByPath = new Map();


// Close every open inline-expand panel and clear the persistent
// `expandedTypePaths` Set. Called when a sibling popover (currently
// the mismatch-explainer) needs to claim the user's focus — keeping
// two type popovers visible at once shows the same "Resolved via"
// chain in both places, which the type-system audit flagged as
// confusing duplication. Mutual dismissal collapses the overlap.
function hideAllInlineHosts() {
  for (const host of inlineHostsByPath.values()) {
    host.style.display = 'none';
  }
  expandedTypePaths.clear();
}
window.hideAllInlineHosts = hideAllInlineHosts;
let inlinePositionListenersInstalled = false;


// === Type-narrowing helpers ===
// The inline-expansion picker below is their only consumer.

// Effect categories — drive the effect-tightening rows in the
// inline-expand panel.
const EFFECT_CATEGORIES = ['db', 'env', 'io', 'network', 'time', 'random'];

// Render a structural type as the SAME wire-shape `/api/types/
// compatible` accepts — keyword names stripped of leading `:`,
// structural arrays kept as-is. The arg/ret pickers compare the
// user's selection against this so a no-op selection (current
// type) doesn't get sent to the backend.
async function populateNarrowerOptions(select, currentType) {
  const curVal = compactTypeAsValue(currentType);
  const curLabel = (typeof currentType === 'string')
                   ? currentType.replace(/^:/, '')
                   : JSON.stringify(currentType);
  const curOpt = document.createElement('option');
  curOpt.value = curVal;
  // No "(current)" suffix — selected=true plus the option being the
  // first item already conveys it, and the parent structural row
  // shows the same type as a chip immediately above.
  curOpt.textContent = curLabel;
  curOpt.selected = true;
  select.appendChild(curOpt);
  // Named type-rows only (no primitives) — one server-rendered
  // option list; same partial as the main type-edit picker.
  if (typeof loadCompatibleTypeOptions === 'function') {
    await loadCompatibleTypeOptions(select, currentType, { current: curVal });
  }
}

async function promptRenameFnTypeArg(fnId, currentFnType, oldArgName) {
  const newName = prompt('Rename `' + oldArgName + '` to:', oldArgName);
  if (!newName?.trim() || newName.trim() === oldArgName) return;
  const trimmed = newName.trim();
  // Rebuild the constraint vector with the arg map's key renamed.
  // currentFnType is the rich shape `["fn", {arg: type, …}, ret, eff?]`;
  // serialise it back to the constraint wire format keywords expect.
  try {
    const renamedArgs = {};
    for (const [k, v] of Object.entries(currentFnType[1] || {})) {
      renamedArgs[k === oldArgName ? trimmed : k] = v;
    }
    // The on-disk constraint uses Clojure-side names — strings here
    // (parse-fn-from-form keywordises on read). Keep the head as
    // "fn" so the JSON re-parse hits the :fn branch.
    const newConstraint = ['fn', renamedArgs, currentFnType[2]];
    if (currentFnType.length === 4) newConstraint.push(currentFnType[3] || []);
    const r = await authMutate('PUT',
      API.api_entities_type_id('fn', fnId),
      { constraint: JSON.stringify(newConstraint) });
    if (!(r.status >= 200 && r.status < 300)) {
      const text = await r.text().catch(() => '');
      throw new Error((text || '').slice(0, 200) || ('HTTP ' + r.status));
    }
    expandedTypePaths.clear();
    for (const h of inlineHostsByPath.values()) h.style.display = 'none';
    if (typeof initGraph === 'function') await initGraph();
  } catch (err) {
    alert('Rename failed: ' + (err.message || err));
  }
}


// ---------- Promote anonymous → named (T4.2) --------------------------

function appendPromoteAnonymousButton(host, fnId) {
  const wrap = document.createElement('div');
  wrap.className = 'type-inline-promote';
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'type-inline-promote-btn';
  btn.textContent = 'Name this type…';
  btn.title = 'Give this anonymous type a name so it can be referenced';
  btn.addEventListener('click', async (ev) => {
    ev.stopPropagation();
    const name = prompt('Name for this type (lowercase-with-hyphens):');
    if (!name?.trim()) return;
    const trimmed = name.trim();
    try {
      const r = await authMutate('PUT',
        API.api_entities_type_id('fn', fnId),
        { name: trimmed });
      if (!(r.status >= 200 && r.status < 300)) {
        const text = await r.text().catch(() => '');
        throw new Error((text || '').slice(0, 200) || ('HTTP ' + r.status));
      }
      expandedTypePaths.clear();
      for (const h of inlineHostsByPath.values()) h.style.display = 'none';
      if (typeof initGraph === 'function') await initGraph();
      if (typeof selectFnByName === 'function') selectFnByName(trimmed);
    } catch (err) {
      alert('Promote failed: ' + (err.message || err));
    }
  });
  wrap.appendChild(btn);
  host.appendChild(wrap);
}


// ---------- Used-by back-link (one fetch per named type per panel) ----

// Cache fetched usages so re-opening / re-rendering the same panel
// doesn't refetch. The cache is per-typeName; stale state after a
// CRUD mutation is acceptable for v1 (close + reopen to refresh).
const typeUsagesCache = new Map();

function appendTypeUsagesSection(host, typeName) {
  const section = document.createElement('div');
  section.className = 'type-inline-usages';
  const head = document.createElement('div');
  head.className = 'type-inline-usages-head';
  head.textContent = 'Used by…';
  section.appendChild(head);
  host.appendChild(section);

  const renderList = (usages) => {
    head.textContent = 'Used by ' + usages.length;
    if (!usages.length) return;
    const list = document.createElement('div');
    list.className = 'type-inline-usages-list';
    // Group by kind so similar references cluster (`slot-of` together,
    // `base-of` together, etc.) — easier to scan when there are many.
    const byKind = {};
    for (const u of usages) {
      if (!byKind[u.kind]) byKind[u.kind] = [];
      byKind[u.kind].push(u);
    }
    // Display order: slot-of first (most common reverse-nav point),
    // then binding-of, refinements, list-elements, return-types,
    // union/variant branches.
    const KIND_ORDER = ['slot-of', 'binding-of', 'base-of', 'element-of',
                        'return-of', 'union-branch', 'variant-branch',
                        'parent-of', 'ref-of', 'resolver-of'];
    const KIND_LABEL = {
      'slot-of':         'slot',
      'binding-of':      'binding',
      'base-of':         'narrows',
      'element-of':      'element of list',
      'return-of':       'returns',
      'union-branch':    'union branch',
      'variant-branch': 'variant branch',
      // Composition-plane kinds — the same endpoint also answers the
      // inspector's fn-level Used-by; rare for a type-row, but a row
      // CAN be both, and dropping them here would under-count.
      'parent-of':       'extended by',
      'ref-of':          'arg ref',
      'resolver-of':     'resolver',
    };
    for (const k of KIND_ORDER) {
      const group = byKind[k];
      if (!group?.length) continue;
      for (const u of group) {
        const row = document.createElement('div');
        row.className = 'type-inline-usage-row';
        const linkText = u['fn-name'] || '(anonymous)';
        const link = document.createElement('a');
        link.href = '#';
        link.className = 'type-inline-usage-link';
        link.textContent = linkText
          + (u['slot-name'] ? '.' + u['slot-name'] : '');
        link.title = 'Open ' + linkText
          + (u['slot-name'] ? ' · ' + u['slot-name'] : '');
        link.addEventListener('click', (e) => {
          e.preventDefault();
          e.stopPropagation();
          if (typeof selectFn === 'function' && u['fn-id']) {
            selectFn(u['fn-id']);
          }
        });
        row.appendChild(link);
        const kindEl = document.createElement('span');
        kindEl.className = 'type-inline-usage-kind';
        kindEl.textContent = KIND_LABEL[k] || k;
        row.appendChild(kindEl);
        list.appendChild(row);
      }
    }
    section.appendChild(list);
  };

  const cached = typeUsagesCache.get(typeName);
  if (cached) { renderList(cached); return; }

  // Need fn-id, not name. Look up via lookups.fnMap or graphData.fns.
  let typeFnId = null;
  if (typeof lookups !== 'undefined' && lookups?.fnMap) {
    for (const f of lookups.fnMap.values()) {
      if (f.name === typeName) { typeFnId = f.id; break; }
    }
  }
  if (!typeFnId) return;

  fetch(API.api_types_usages, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ 'type-fn-id': typeFnId })
  })
    .then(r => r.ok ? r.json() : null)
    .then(d => {
      if (!d?.ok) return;
      typeUsagesCache.set(typeName, d.usages || []);
      renderList(d.usages || []);
      if (typeof repositionAllInlineHosts === 'function') {
        repositionAllInlineHosts();
      }
    })
    .catch((err) => {
      // eslint-disable-next-line no-console
      console.error(API.api_types_usages + ' fetch failed', err);
      // UI: leave the placeholder header (no usage data shown).
    });
}


// ---------- Phase-8 fn-effect narrowing (inline) ----------------------
//
// Used by the fn-type branch in renderInlineExpansionInto. Each
// helper builds one piece of the narrowing form so the structural
// row can host its own <select> next to the chip; effects + submit
// sit below.

function makeInlineNarrowerSelect(currentType, ariaLabel) {
  const sel = document.createElement('select');
  sel.className = 'type-explainer-tighten-select type-inline-narrower';
  if (ariaLabel) sel.setAttribute('aria-label', ariaLabel);
  // Hidden until populateNarrowerOptions resolves with > 1 options.
  // Otherwise the select would just show the current type with no
  // alternatives — pure visual duplicate of the structural chip
  // immediately above it.
  sel.style.display = 'none';
  if (typeof populateNarrowerOptions === 'function') {
    Promise.resolve(populateNarrowerOptions(sel, currentType))
      .then(() => {
        if (sel.options.length > 1) {
          sel.style.display = '';
          if (typeof repositionAllInlineHosts === 'function') {
            repositionAllInlineHosts();
          }
        }
      });
  }
  // Don't toggle the parent row's expand state when the user opens
  // the dropdown.
  sel.addEventListener('click', (e) => e.stopPropagation());
  return sel;
}

// Read-only effect-constraint badge row — one line of mini effect
// chips showing the slot's allowed effect set. Pure (`#{}`) renders
// as a single "pure" pill; a concrete set renders one chip per
// category. `:any` callers skip this (no constraint = nothing to
// show; that's the implicit default).
function makeEffectsReadOnly(currentEff) {
  const wrap = document.createElement('div');
  wrap.className = 'type-inline-effects-readonly';
  const label = document.createElement('span');
  label.className = 'type-inline-effects-label';
  label.textContent = 'eff:';
  wrap.appendChild(label);
  if (!currentEff || currentEff.size === 0) {
    const pill = document.createElement('span');
    pill.className = 'type-inline-effects-pure';
    pill.textContent = 'pure';
    pill.title = 'This slot requires a PURE callable (no side effects).';
    wrap.appendChild(pill);
    return wrap;
  }
  for (const cat of currentEff) {
    const chip = document.createElement('span');
    chip.className = 'effects-chip effects-chip-' + cat;
    chip.textContent = cat;
    chip.title = 'Allowed effect: ' + cat;
    wrap.appendChild(chip);
  }
  return wrap;
}


function makeEffectsRow(currentEff) {
  const cats = (typeof EFFECT_CATEGORIES !== 'undefined')
               ? EFFECT_CATEGORIES
               : ['db', 'env', 'io', 'network', 'time', 'random'];
  const wrap = document.createElement('div');
  wrap.className = 'type-explainer-tighten-row';
  const checkboxes = {};
  for (const cat of cats) {
    const lab = document.createElement('label');
    lab.className = 'type-explainer-tighten-chk';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.value = cat;
    if (currentEff?.has(cat)) cb.checked = true;
    checkboxes[cat] = cb;
    lab.appendChild(cb);
    const sp = document.createElement('span');
    sp.textContent = cat;
    lab.appendChild(sp);
    wrap.appendChild(lab);
  }
  return { el: wrap, checkboxes };
}

function makeTightenButton(opts) {
  const { argSelects, retSelect, curRet, checkboxes, currentEff,
          bindingId, errEl } = opts;
  const cats = (typeof EFFECT_CATEGORIES !== 'undefined')
               ? EFFECT_CATEGORIES
               : ['db', 'env', 'io', 'network', 'time', 'random'];

  // Build the body delta from current control state. Used both by
  // the submit handler and by the change-detector that gates the
  // button's `disabled` state — they must agree on what counts as
  // "no changes" so the button only enables when the submit would
  // actually send something.
  const buildBody = () => {
    const argDelta = {};
    let argsChanged = false;
    for (const [name, { sel, current }] of Object.entries(argSelects || {})) {
      if (sel.value && sel.value !== compactTypeAsValue(current)) {
        argDelta[name] = sel.value;
        argsChanged = true;
      }
    }
    const body = {};
    if (argsChanged) body.args = argDelta;
    if (retSelect?.value
        && retSelect.value !== compactTypeAsValue(curRet)) {
      body.ret = retSelect.value;
    }
    const effects = cats.filter(c => checkboxes[c].checked);
    // Send `effects` when: any checkbox is checked, OR the slot
    // already has an effect constraint AND it now differs from
    // what's checked (so user CAN clear back to "none allowed").
    if (effects.length > 0
        || (currentEff && !setsEqual(currentEff, new Set(effects)))) {
      body.effects = effects;
    }
    return body;
  };

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'type-explainer-btn';
  btn.textContent = 'Tighten';
  btn.disabled = true;
  btn.title = 'Pick a narrower type or change an effect to enable';

  // Refresh disabled state from current control values. Wired
  // below as a `change` listener on each select + checkbox so the
  // button enables/disables live as the user fiddles.
  const refreshDisabled = () => {
    const body = buildBody();
    btn.disabled = Object.keys(body).length === 0;
  };
  for (const { sel } of Object.values(argSelects || {})) {
    sel.addEventListener('change', refreshDisabled);
  }
  if (retSelect) retSelect.addEventListener('change', refreshDisabled);
  for (const cb of Object.values(checkboxes || {})) {
    cb.addEventListener('change', refreshDisabled);
  }
  // populateNarrowerOptions resolves async — re-check after it
  // populates in case the current selection ended up different.
  setTimeout(refreshDisabled, 600);

  btn.addEventListener('click', async (e) => {
    e.stopPropagation();
    const body = buildBody();
    if (Object.keys(body).length === 0) return;  // disabled; defensive
    btn.disabled = true;
    errEl.style.display = 'none';
    try {
      const fetchFn = (typeof authFetch === 'function') ? authFetch : fetch;
      const resp = await fetchFn(
        API.api_bindings_binding_id_tighten_fn_effects(bindingId),
        { method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body) });
      if (!resp.ok) {
        const text = await resp.text();
        const m = text?.match(/<p class="error">([^<]+)<\/p>/);
        errEl.textContent = m ? m[1] : ('HTTP ' + resp.status);
        errEl.style.display = 'block';
        refreshDisabled();
        return;
      }
      // Close any open hosts and reload the graph.
      expandedTypePaths.clear();
      for (const h of inlineHostsByPath.values()) h.style.display = 'none';
      if (typeof initGraph === 'function') initGraph();
    } catch (err) {
      errEl.textContent = String(err?.message ? err.message : err);
      errEl.style.display = 'block';
      refreshDisabled();
    }
  });
  return btn;
}

function setsEqual(a, b) {
  if (a.size !== b.size) return false;
  for (const v of a) if (!b.has(v)) return false;
  return true;
}


function ensureInlineHost(path) {
  let host = inlineHostsByPath.get(path);
  if (!host) {
    host = document.createElement('div');
    host.className = 'type-inline-host';
    host.dataset.path = path;
    document.body.appendChild(host);
    inlineHostsByPath.set(path, host);
  }
  return host;
}

// CSS.escape may be missing in very old browsers; we only feed it
// our stable path strings which contain `/` and ascii — fall back
// to a tiny escape for those.
function escAttr(s) {
  if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') {
    return CSS.escape(s);
  }
  return String(s).replace(/(["\\])/g, '\\$1');
}

// Position a host fixed at the page level so it doesn't enlarge the
// overlay it logically belongs to. Tries below the anchor; flips
// above if it would overflow the viewport bottom, and shifts left
// if it would overflow the viewport right. Hidden if no anchor.
function positionInlineHost(host, anchorEl) {
  if (!anchorEl) {
    host.style.display = 'none';
    return;
  }
  const r = anchorEl.getBoundingClientRect();
  // Scale the host with the viewport zoom so it visually tracks the chip
  // (which itself scales via the edge-label-overlay's transform).
  // Without this, the host stayed at native size while the chip
  // grew / shrunk with zoom, breaking the "this card describes
  // this chip" association at non-default zooms.
  const zoom = (typeof gv !== 'undefined' && gv.ready()) ? gv.zoom() : 1;
  host.style.display = 'block';
  host.style.position = 'fixed';
  host.style.transformOrigin = 'top left';
  host.style.transform = 'scale(' + zoom + ')';
  // Render at offscreen origin first so offsetWidth/Height reflect
  // the unscaled wrapped content (transform doesn't affect those).
  host.style.top = '-9999px';
  host.style.left = '0px';
  const wUnscaled = host.offsetWidth || 0;
  const hUnscaled = host.offsetHeight || 0;
  const w = wUnscaled * zoom;
  const h = hUnscaled * zoom;
  const margin = 8;
  let top = r.bottom + 4;
  let left = r.left;
  if (left + w > window.innerWidth - margin) {
    left = Math.max(margin, window.innerWidth - w - margin);
  }
  if (top + h > window.innerHeight - margin) {
    top = Math.max(margin, r.top - h - 4);
  }
  host.style.top = top + 'px';
  host.style.left = left + 'px';
}

function repositionAllInlineHosts() {
  for (const [path, host] of inlineHostsByPath) {
    if (!expandedTypePaths.has(path)) {
      // Collapsed — REAP the host (DOM node + Map entry) rather than
      // leaving a hidden node behind for every path ever expanded this
      // session. Runs on pan/zoom/resize, so cleanup is prompt. Deleting
      // the current key mid-iteration is safe for a Map. Re-expanding the
      // path rebuilds the host on demand (see ensureInlineHost).
      host.remove();
      inlineHostsByPath.delete(path);
      continue;
    }
    const anchor = document.querySelector(
      '[data-inline-anchor="' + escAttr(path) + '"]'
    );
    positionInlineHost(host, anchor);
  }
}

function installInlinePositionListeners() {
  if (inlinePositionListenersInstalled) return;
  gv.onViewportChange(repositionAllInlineHosts);
  window.addEventListener('resize', repositionAllInlineHosts);
  // The overlay positioner runs on the same events but we trigger
  // ours separately so the host follows even when the overlay
  // doesn't get re-positioned (e.g. anchor chip already in DOM,
  // pan-event handler skips it).
  inlinePositionListenersInstalled = true;
}

// Public entry — wire the chip itself as the click target. Composite
// types toggle the inline expansion panel; editable primitives drop
// straight into `enterArgTypeEditMode` (no panel for a single-word
// type); read-only primitives are inert.
//
// `ctx` (optional): `{ typeName, editable, onEdit, bindingId }`.
// `typeName` is the user-facing display label rendered in the panel
// header. `onEdit` is the "Change type" entry-point (re-uses the
// existing enterArgTypeEditMode popover). `bindingId` is needed for
// the fn-effect tightening row (Phase 8).
function attachInlineExpand(chipEl, rich, path, ctx) {
  const expandable = isTypeExpandable(rich);
  const c = ctx || {};
  const editable = !!c.editable && typeof c.onEdit === 'function';

  if (!expandable) {
    // No structure to reveal. Editable: clicking the chip opens the
    // type-edit popover directly. Read-only: no-op.
    if (editable) {
      chipEl.style.cursor = 'pointer';
      chipEl.addEventListener('click', (e) => {
        e.stopPropagation();
        c.onEdit();
      });
    }
    return null;
  }

  installInlinePositionListeners();
  chipEl.dataset.inlineAnchor = path;
  chipEl.classList.add('type-chip-expandable');
  chipEl.style.cursor = 'pointer';
  // The chip is a real expand/collapse control: `aria-expanded` is
  // only valid on a widget role, and a `button` role must be
  // keyboard-operable — so set the role, make it focusable, and
  // mirror Enter / Space onto the click handler below.
  chipEl.setAttribute('role', 'button');
  chipEl.setAttribute('tabindex', '0');
  chipEl.setAttribute('aria-expanded', expandedTypePaths.has(path) ? 'true' : 'false');

  // If the path is already expanded (e.g. user toggled it on, then
  // navigated / rebuilt overlays), re-render and re-anchor the host.
  if (expandedTypePaths.has(path)) {
    const host = ensureInlineHost(path);
    renderInlineExpansionInto(host, rich, path, c);
    positionInlineHost(host, chipEl);
  }

  chipEl.addEventListener('click', (e) => {
    e.stopPropagation();
    const willOpen = !expandedTypePaths.has(path);
    if (willOpen) expandedTypePaths.add(path);
    else expandedTypePaths.delete(path);
    chipEl.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
    if (willOpen) {
      // Mutually exclusive with the mismatch-explainer: the inline
      // panel hosts its own copy of "Resolved via", so showing both
      // simultaneously duplicates the same provenance chain.
      if (typeof hideMismatchExplainer === 'function') hideMismatchExplainer();
      const host = ensureInlineHost(path);
      renderInlineExpansionInto(host, rich, path, c);
      positionInlineHost(host, chipEl);
    } else {
      const host = inlineHostsByPath.get(path);
      if (host) host.style.display = 'none';
    }
  });
  // Keyboard activation for the button-role chip.
  chipEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      chipEl.click();
    }
  });
  return null;
}
