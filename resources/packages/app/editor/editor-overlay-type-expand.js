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
// Pan / zoom / resize re-position via cy events + window listener.
//
// State (`expandedTypePaths`) is a Set of stable string paths so the
// user's expanded selections survive overlay rebuilds and preview
// redraws. Hosts are keyed by the same path in `inlineHostsByPath`.

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
// Moved here from the former editor-type-explainer.js: the inline-
// expansion picker below is their only consumer.

// Effect categories — drive the effect-tightening rows in the
// inline-expand panel.
const EFFECT_CATEGORIES = ['db', 'env', 'io', 'network', 'time', 'random'];

// Render a structural type as the SAME wire-shape `/api/types/
// compatible` accepts — keyword names stripped of leading `:`,
// structural arrays kept as-is. The arg/ret pickers compare the
// user's selection against this so a no-op selection (current
// type) doesn't get sent to the backend.
function compactTypeAsValue(t) {
  if (typeof t === 'string') return t.replace(/^:/, '');
  return JSON.stringify(t);  // structural — Edit by typing isn't supported
                             // yet; the picker only offers named alternates.
}

// Populate `<select>` with the current type + every named alias
// that's a subtype of it. Async; the picker shows the current
// option immediately so the user has an answer even before the
// fetches complete.
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
  if (typeof richTypes !== 'object' || !richTypes) return;
  // Candidates: every type-row entry. Filter via /api/types/
  // compatible. Same approach as the main type-edit picker.
  const aliases = Object.keys(richTypes)
    .filter(k => richTypes[k] && richTypes[k]['type-row?'] === true);
  if (aliases.length === 0) return;
  try {
    const results = await Promise.all(aliases.map(async name => {
      const ok = await typesCompatible(currentType, name);
      return { name, ok };
    }));
    for (const r of results) {
      if (r.ok && r.name !== curVal) {
        const o = document.createElement('option');
        o.value = r.name;
        o.textContent = r.name;
        select.appendChild(o);
      }
    }
  } catch (_) { /* leave just the current option */ }
}

function rcLookupRich(name) {
  if (typeof name !== 'string') return null;
  if (typeof richTypes !== 'object' || !richTypes) return null;
  const e = richTypes[name];
  return (e && e.return != null) ? e.return : null;
}

// True if a rich type has constituent structure worth revealing. A
// bare primitive string is expandable iff it resolves to a named
// composite via `richTypes`; that lets `[positive-int]` expand even
// though the chip stores the alias name as a string.
function isTypeExpandable(rich) {
  if (rich == null) return false;
  if (Array.isArray(rich)) {
    const head = rich[0];
    return head === 'refine' || head === 'list' || head === 'union'
        || head === 'fn' || head === 'map' || head === 'tuple';
  }
  if (typeof rich === 'object') return Object.keys(rich).length > 0;
  if (typeof rich === 'string') {
    const sub = rcLookupRich(rich);
    return sub != null && sub !== rich && isTypeExpandable(sub);
  }
  return false;
}

// Render one mini-chip wrapped in a row that may carry a left
// affordance (field-name / tag) plus a recursive child slot. Returns
// the row element.
function buildInlineTypeRow(label, rich, path, parentAncestors) {
  const row = document.createElement('div');
  row.className = 'type-inline-row';

  if (label != null && label !== '') {
    const lab = document.createElement('span');
    lab.className = 'type-inline-label';
    lab.textContent = label;
    row.appendChild(lab);
  }

  // Cycle detection — a self-recursive alias (e.g. `:tree = [:list :tree]`)
  // would otherwise let the user click-click-click forever. If the
  // chip's type name is already in the parent expansion chain, render
  // a non-expandable cycle indicator (`↻ :tree`) instead of an
  // expandable chip. parentAncestors is a Set<string> threaded
  // through from renderInlineExpansionInto; missing → empty.
  const ancestors = parentAncestors || new Set();
  const richName = (typeof rich === 'string') ? rich : null;
  const isCycle = !!(richName && ancestors.has(richName));

  const chip = document.createElement('span');
  chip.className = 'arg-type-chip arg-type-chip-readonly type-inline-chip';
  if (isCycle) {
    chip.classList.add('type-inline-chip-cycle');
    chip.textContent = '↻ ' + richName;
    chip.title = 'Recursive type — the chain loops back to :' + richName;
    chip.setAttribute('aria-label', chip.title);
    row.appendChild(chip);
    return row;
  }
  chip.textContent = shortTypeLabel(rich);
  chip.title = (typeof formatTypeHumanReadable === 'function')
               ? ('Type: ' + formatTypeHumanReadable(rich))
               : 'Type';
  chip.setAttribute('aria-label', chip.title);
  row.appendChild(chip);

  const slot = document.createElement('div');
  slot.className = 'type-inline-child';
  row.appendChild(slot);

  const expandable = isTypeExpandable(rich);
  if (expandable) {
    chip.classList.add('type-inline-chip-expandable');
    chip.setAttribute('role', 'button');
    chip.setAttribute('tabindex', '0');
    chip.setAttribute('aria-expanded', expandedTypePaths.has(path) ? 'true' : 'false');
    chip.style.cursor = 'pointer';
    // For a named child type (e.g. `ring-request-shape`), thread the
    // name through so the recursive panel can show that type's
    // description in its header. The cycle-detection set extends with
    // THIS chip's type name when present, so a downstream re-encounter
    // gets rendered as `↻ :name` instead of expanding endlessly.
    const childAncestors = richName
      ? new Set([...ancestors, richName])
      : ancestors;
    const childCtx = richName
      ? { typeName: richName, ancestorTypes: childAncestors }
      : { ancestorTypes: childAncestors };
    chip.addEventListener('click', (e) => {
      e.stopPropagation();
      const willOpen = !expandedTypePaths.has(path);
      if (willOpen) expandedTypePaths.add(path);
      else expandedTypePaths.delete(path);
      if (willOpen) {
        renderInlineExpansionInto(slot, rich, path, childCtx);
      } else {
        // Collapsing: drop the children we previously rendered into
        // `slot`. Re-rendering with the now-unset state wouldn't
        // clear them — `renderInlineExpansionInto` unconditionally
        // appends rows for an expandable type.
        slot.textContent = '';
      }
      chip.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
      // Re-position any open ancestor hosts since the tree's height
      // changed.
      repositionAllInlineHosts();
    });
    // Keyboard activation for the button-role chip.
    chip.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        chip.click();
      }
    });
    if (expandedTypePaths.has(path)) {
      renderInlineExpansionInto(slot, rich, path, childCtx);
    }
  }
  return row;
}

// Resolve a possibly-aliased rich type one level. Named refinements /
// records arrive at the chip as bare strings (`"positive-int"`); we
// walk one hop via richTypes so the constituent rendering shows the
// actual structure rather than treating the alias as a primitive.
function resolveOneHop(rich) {
  if (typeof rich !== 'string') return rich;
  const sub = rcLookupRich(rich);
  return (sub != null && sub !== rich) ? sub : rich;
}


// Human-readable kind tag for a structural type. Surfaced in the
// inline-expand panel header so anonymous record vs anonymous map are
// distinguishable at a glance (both expand into a row list; the body
// alone doesn't say which is which). Returns null for plain
// primitives / aliases — those render their name directly, no tag
// needed.
function typeKindLabel(rich) {
  if (rich == null || typeof rich === 'string') return null;
  if (Array.isArray(rich)) {
    switch (rich[0]) {
      case 'map':    return 'Map';
      case 'list':   return 'List';
      case 'tuple':  return 'Tuple';
      case 'fn':     return 'Function';
      case 'refine': return 'Refinement';
      case 'union':  return 'Union';
      default:       return null;
    }
  }
  if (typeof rich === 'object') return 'Record';
  return null;
}


// Frontend mirror of `graphden.types.core/primitive-supers` — the
// arithmetic-narrowing hierarchy backend uses for `subtype?`. Only two
// entries (`:int ⊂ :numeric`, `:float ⊂ :numeric`); the rest of the
// primitives are leaves. Used to extend the refinement chain past the
// last `:refine` link so the reader sees `:user-port ⊂ :int ⊂ :numeric`
// instead of stopping at `:int`.
const PRIMITIVE_SUPERS = { int: 'numeric', float: 'numeric' };


// Walk every `:refine` link in `rich` down to the first non-refinement
// base, then follow `PRIMITIVE_SUPERS` once if the base is a primitive
// with a known super. Each link carries the NAMED alias at that step
// (when present) plus the constraint at that level. The chain is what
// `subtype?` reasoning actually traverses — surfacing it in the
// inline-expand panel as a one-line breadcrumb answers "what's this
// type a subtype of?" at a glance.
//
// Returns `{ steps: [{name?, constraint?, rich}, …] }` — the FULL
// breadcrumb from the original type down to the top primitive. The
// first step is the original; intermediate steps each carry their
// `:refine` constraint; the last step (primitive or alias) has no
// constraint.
function refinementChain(rich) {
  const steps = [];
  let cur = rich;
  let curName = (typeof rich === 'string') ? rich : null;
  // First step — the type ITSELF (before any walking). Constraint is
  // null at this entry; subsequent steps record the constraint of the
  // refine link that PRODUCED them.
  steps.push({ name: curName, constraint: null, rich: cur });
  // Cap the walk so a circular alias can't lock us up.
  let hops = 0;
  while (hops < 32) {
    hops += 1;
    const resolved = (typeof cur === 'string') ? rcLookupRich(cur) : cur;
    if (!Array.isArray(resolved) || resolved[0] !== 'refine') break;
    cur = resolved[1];
    curName = (typeof cur === 'string') ? cur : null;
    // Stamp the previous step with the constraint that links it to
    // this one — the constraint at refinement level N applies to the
    // value at step N, narrowing it to step N's base.
    steps[steps.length - 1].constraint = resolved[2];
    steps.push({ name: curName, constraint: null, rich: cur });
  }
  // Extend through one primitive super if applicable (`:int ⊂ :numeric`).
  if (typeof cur === 'string' && PRIMITIVE_SUPERS[cur]) {
    steps.push({ name: PRIMITIVE_SUPERS[cur], constraint: null,
                 rich: PRIMITIVE_SUPERS[cur] });
  }
  return { steps };
}


// Render the refinement-chain breadcrumb section. One row showing
// `:user-port ⊂ :int ⊂ :numeric` joined by `⊂` glyphs; each name is
// clickable when a named type-row backs it (selects that type-row in
// the editor). Constraint per step shown as a tooltip on the name's
// chip so the cumulative narrowing is discoverable without bloating
// the row width.
function buildRefinementChainSection(chain, ctx) {
  const wrap = document.createElement('div');
  wrap.className = 'type-inline-refinement-chain';
  const head = document.createElement('div');
  head.className = 'type-inline-resolution-head';
  head.textContent = 'Subtype chain';
  wrap.appendChild(head);
  const row = document.createElement('div');
  row.className = 'type-inline-refinement-chain-row';
  const findFnIdByName = (name) => {
    if (!name || typeof lookups === 'undefined' || !lookups?.fnByName) return null;
    const e = lookups.fnByName.get(name);
    return e?.id || null;
  };
  const makeStepEl = (step) => {
    const name = step.name;
    const label = name ? (':' + name) : '(anonymous)';
    const tooltipParts = [name ? ('Type :' + name) : 'Anonymous type'];
    if (step.constraint != null) {
      tooltipParts.push('Constraint: ' + constraintToString(step.constraint));
    }
    const tooltip = tooltipParts.join(' — ');
    const id = name ? findFnIdByName(name) : null;
    if (id && typeof selectFn === 'function') {
      const a = document.createElement('a');
      a.href = '#';
      a.className = 'type-inline-resolution-link';
      a.textContent = label;
      a.title = tooltip;
      a.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        selectFn(id);
      });
      return a;
    }
    const sp = document.createElement('span');
    sp.className = name
      ? 'type-inline-resolution-label'
      : 'type-inline-refinement-chain-anon';
    sp.textContent = label;
    sp.title = tooltip;
    return sp;
  };
  chain.steps.forEach((step, idx) => {
    if (idx > 0) {
      const sep = document.createElement('span');
      sep.className = 'type-inline-refinement-chain-sep';
      sep.textContent = '⊂';
      row.appendChild(sep);
    }
    row.appendChild(makeStepEl(step));
  });
  wrap.appendChild(row);
  return wrap;
}

// Render an inline expansion into `host`, replacing any previous
// content. Each call is independent — re-rendering clears + rebuilds.
// `ctx` (optional): `{ typeName, editable, onEdit, bindingId }` for
// the panel header (name + Change button) and the fn-effect tightening
// row when applicable.
function renderInlineExpansionInto(host, rich, path, ctx) {
  host.textContent = '';
  const c = ctx || {};
  const richEntry = (c.typeName && typeof richTypes === 'object' && richTypes)
                    ? richTypes[c.typeName] : null;
  const description = richEntry?.description;
  if (c.typeName || c.editable) {
    const head = document.createElement('div');
    head.className = 'type-inline-header';
    if (c.typeName) {
      const name = document.createElement('span');
      name.className = 'type-inline-header-name';
      name.textContent = c.typeName;
      head.appendChild(name);
    }
    // Kind tag — `{K→V}` map vs `{a b}` record both expand into a row
    // list, so the structural reader can't tell them apart by the
    // expanded body alone. The tag (Map / Record / Refinement / Union
    // / Tuple / Function) sits in the header next to the name so the
    // reader knows the structural KIND at a glance, including for
    // anonymous types with no alias to fall back on.
    const kindTag = typeKindLabel(resolveOneHop(rich));
    if (kindTag) {
      const tag = document.createElement('span');
      tag.className = 'type-inline-header-kind';
      tag.textContent = kindTag;
      head.appendChild(tag);
    }
    if (c.editable && typeof c.onEdit === 'function') {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'type-inline-header-edit';
      btn.textContent = 'Change type';
      btn.title = 'Replace the slot\'s type';
      btn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        // Collapse before edit so the popover that enterArgTypeEditMode
        // opens has room and isn't anchored to a now-hidden chip.
        expandedTypePaths.delete(path);
        host.style.display = 'none';
        c.onEdit();
      });
      head.appendChild(btn);
    }
    // "↳ provenance" link — the inline panel itself is structural-only
    // (base/constraint, list elem, record fields, fn args/ret/eff). The
    // 4-tier resolution + inheritance chain lives in the dedicated
    // provenance popover; this link is the in-panel entry point so a
    // user reading structure doesn't have to close the panel and hunt
    // for the `↳` badge on the chip. Renders only when the panel was
    // opened on a slot-bound arg AND slotTypeProvenance has something
    // to show (non-null winner).
    if (c.arg && typeof slotTypeProvenance === 'function'
        && typeof showProvenancePopover === 'function') {
      const prov = slotTypeProvenance(c.arg);
      if (prov?.winner) {
        const provBtn = document.createElement('button');
        provBtn.type = 'button';
        provBtn.className = 'type-inline-header-provenance';
        provBtn.textContent = '↳ provenance';
        provBtn.title = 'Show how this slot\'s type was resolved';
        provBtn.setAttribute('aria-haspopup', 'dialog');
        provBtn.setAttribute('aria-expanded', 'false');
        provBtn.addEventListener('click', (ev) => {
          ev.stopPropagation();
          showProvenancePopover(c.arg, provBtn);
        });
        head.appendChild(provBtn);
      }
    }
    host.appendChild(head);
  }
  // Human-readable description from the type-row's `description`
  // field — surfaces author intent without taking a popover detour
  // through the `i` badge.
  if (description) {
    const descEl = document.createElement('div');
    descEl.className = 'type-inline-description';
    descEl.textContent = description;
    host.appendChild(descEl);
  }
  // Promote-anonymous affordance — when the expansion is on an
  // anonymous structural type-row (no name), offer a "Name this
  // type…" action. Backed by PUT /api/entities/fn/:id with the new
  // `:name`; existing slot.type-fn-id references stay valid since
  // the underlying fn-id doesn't change.
  if (!c.typeName && c.editable && c.anonymousFnId) {
    appendPromoteAnonymousButton(host, c.anonymousFnId);
  }
  // "Used by N" back-link for named type-rows. Lets users navigate
  // from a type to every fn that mentions it as base/element/return/
  // slot-type/binding-override/constraint-branch.
  if (c.typeName && lookups?.fnMap) {
    appendTypeUsagesSection(host, c.typeName);
  }
  if (!isTypeExpandable(rich)) return;
  const effective = resolveOneHop(rich);

  if (Array.isArray(effective)) {
    const head = effective[0];
    if (head === 'refine') {
      // Refinement chain summary — walks every `:refine` link from
      // THIS level down to the base primitive, so the reader sees
      // `:user-port ⊂ :port ⊂ :int` (each link clickable to navigate
      // to that named type-row) without re-clicking the `base` chip
      // at every level. Constraints listed inline next to each link
      // so the cumulative tightening is legible at a glance.
      const chain = refinementChain(rich);
      // Show the chain only when it adds info beyond the single
      // `base / where` row below — at minimum 2 steps means there's a
      // transition to display.
      if (chain.steps.length >= 2) {
        host.appendChild(buildRefinementChainSection(chain, c));
      }
      // The structural one-hop rows STAY — they're the click-to-drill
      // surface (rename / tighten / promote-anonymous) and the
      // breadcrumb is read-only. Two views, same data.
      host.appendChild(buildInlineTypeRow('base', effective[1], path + '/base', c.ancestorTypes));
      const consRow = document.createElement('div');
      consRow.className = 'type-inline-row type-inline-constraint';
      const lab = document.createElement('span');
      lab.className = 'type-inline-label';
      lab.textContent = 'where';
      consRow.appendChild(lab);
      const val = document.createElement('span');
      val.className = 'type-inline-constraint-text';
      val.textContent = constraintToString(effective[2]);
      consRow.appendChild(val);
      host.appendChild(consRow);
      return;
    }
    if (head === 'list') {
      host.appendChild(buildInlineTypeRow('element', effective[1], path + '/element', c.ancestorTypes));
      return;
    }
    if (head === 'map') {
      host.appendChild(buildInlineTypeRow('key', effective[1], path + '/key', c.ancestorTypes));
      host.appendChild(buildInlineTypeRow('value', effective[2], path + '/value', c.ancestorTypes));
      return;
    }
    if (head === 'tuple') {
      effective.slice(1).forEach((el, idx) => {
        host.appendChild(buildInlineTypeRow('#' + idx, el, path + '/t' + idx, c.ancestorTypes));
      });
      return;
    }
    if (head === 'union') {
      effective.slice(1).forEach((branch, idx) => {
        host.appendChild(buildInlineTypeRow('', branch, path + '/u' + idx, c.ancestorTypes));
      });
      return;
    }
    if (head === 'fn') {
      const argMap = effective[1] || {};
      const tighten = !!(c.editable && c.bindingId
                          && typeof populateNarrowerOptions === 'function');
      const renameable = !!(c.editable && c.anonymousFnId);
      const argSelects = {};
      let retSelect = null;
      // Effects slot — 4th element. Post-T4 canonical form is always
      // 4-elem with either `:any` (string 'any', no constraint) or a
      // concrete set/array.
      const effRaw = (effective.length === 4) ? effective[3] : null;
      const effIsAny = effRaw == null || effRaw === 'any' || effRaw === ':any';
      const currentEff = (!effIsAny && Array.isArray(effRaw))
        ? new Set(effRaw.map(e => typeof e === 'string' ? e.replace(/^:/, '') : String(e)))
        : (!effIsAny && effective.length === 4)
          ? new Set([])
          : null;

      // Each structural row gets the narrowing select inline next to
      // the chip — that's where the user looks to read the current
      // type, so the picker for a different (narrower) type sits
      // right beside it. No separate Tighten section listing the
      // same arg-names again.
      Object.entries(argMap).forEach(([k, v]) => {
        const row = buildInlineTypeRow(k, v, path + '/a/' + k, c.ancestorTypes);
        if (renameable) {
          // The arg-name label becomes click-to-rename — backed by an
          // in-place PUT on the anonymous fn-row's constraint (the
          // shape changes but the fn-id stays, so every callsite
          // keeps its `slot.type-fn-id` pointer intact).
          const labelEl = row.querySelector('.type-inline-label');
          if (labelEl) {
            labelEl.classList.add('type-inline-label-renameable');
            labelEl.title = 'Click to rename this arg';
            labelEl.addEventListener('click', (ev) => {
              ev.stopPropagation();
              promptRenameFnTypeArg(c.anonymousFnId, effective, k);
            });
          }
        }
        if (tighten) {
          const sel = makeInlineNarrowerSelect(v, 'Narrower type for ' + k);
          row.insertBefore(sel, row.querySelector('.type-inline-child'));
          argSelects[k] = { sel, current: v };
        }
        host.appendChild(row);
      });
      // `→` instead of the word "returns" — the arrow is already the
      // canonical return marker in the compact chip form
      // (`(request)→ring-res…`) and in standard fn-type notation.
      const retRow = buildInlineTypeRow('→', effective[2], path + '/ret', c.ancestorTypes);
      if (tighten) {
        retSelect = makeInlineNarrowerSelect(effective[2], 'Narrower return type');
        retRow.insertBefore(retSelect, retRow.querySelector('.type-inline-child'));
      }
      host.appendChild(retRow);

      if (tighten) {
        const effects = makeEffectsRow(currentEff);
        host.appendChild(effects.el);
        const errEl = document.createElement('div');
        errEl.className = 'type-explainer-tighten-err';
        errEl.style.display = 'none';
        host.appendChild(errEl);
        const btn = makeTightenButton({
          argSelects,
          retSelect,
          curRet: effective[2],
          checkboxes: effects.checkboxes,
          currentEff,
          bindingId: c.bindingId,
          errEl,
        });
        host.appendChild(btn);
      } else if (currentEff != null) {
        // Read-only callers: always show the slot's effect contract
        // so a reader knows whether the bound callable must be pure
        // (#{}) or limited to specific categories — without opening
        // edit mode. Skipped only when the 4th element is `:any`
        // (no constraint, the default).
        host.appendChild(makeEffectsReadOnly(currentEff));
      }
      return;
    }
  }
  if (typeof effective === 'object' && effective !== null) {
    Object.entries(effective).forEach(([k, v]) => {
      host.appendChild(buildInlineTypeRow(k, v, path + '/f/' + k, c.ancestorTypes));
    });
  }
}

// ---------- Rename fn-type arg (T4.1) --------------------------------

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
      '/api/entities/fn/' + encodeURIComponent(fnId),
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
        '/api/entities/fn/' + encodeURIComponent(fnId),
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
                        'return-of', 'union-branch', 'variant-branch'];
    const KIND_LABEL = {
      'slot-of':         'slot',
      'binding-of':      'binding',
      'base-of':         'narrows',
      'element-of':      'element of list',
      'return-of':       'returns',
      'union-branch':    'union branch',
      'variant-branch': 'variant branch',
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

  fetch('/api/types/usages', {
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
      console.error('/api/types/usages fetch failed', err);
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
        '/api/bindings/' + encodeURIComponent(bindingId) + '/tighten-fn-effects',
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


function constraintToString(c) {
  if (!Array.isArray(c)) return JSON.stringify(c);
  const op = c[0];
  if (op === 'and' || op === 'or') {
    return c.slice(1).map(constraintToString).join(' ' + op + ' ');
  }
  if (c.length === 2) return op + ' ' + JSON.stringify(c[1]);
  return JSON.stringify(c);
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
  // Scale the host with cy zoom so it visually tracks the chip
  // (which itself scales via the edge-label-overlay's transform).
  // Without this, the host stayed at native size while the chip
  // grew / shrunk with zoom, breaking the "this card describes
  // this chip" association at non-default zooms.
  const zoom = (typeof cy !== 'undefined' && cy && typeof cy.zoom === 'function')
               ? cy.zoom() : 1;
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
      host.style.display = 'none';
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
  if (typeof cy !== 'undefined' && cy && typeof cy.on === 'function') {
    cy.on('pan zoom', repositionAllInlineHosts);
  }
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
