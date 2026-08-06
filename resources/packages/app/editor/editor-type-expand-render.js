// Editor Type-Expand Render — the structural type-INTERPRETATION half
// of the inline `▸/▾` type-expand panel: per-kind constituent rows
// (refine→base+constraint, list→element, map/tuple, union→branches,
// record→fields, fn→args/ret/effects), the subtype-chain breadcrumb,
// and the small type-grammar readers they share (typeKindLabel /
// resolveOneHop / refinementChain / constraintToString). The
// fixed-position host lifecycle (pan/zoom re-anchor, expandedTypePaths
// persistence) and the edit affordances (tighten / rename / promote /
// usages) live in editor-overlay-type-expand.js.
//
// Loaded immediately BEFORE editor-overlay-type-expand.js in
// `_editor-script-paths`.

// graph-first-exception: the type-grammar readers here (typeKindLabel /
// refinementChain / constraintToString) are the SAME ones the always-visible
// canvas chips use on the sub-100ms path — rendering the expand rows
// server-side would ADD a second (server) type formatter while these JS
// readers stay for the chips, i.e. one more mirror, not one less.
function compactTypeAsValue(t) {
  if (typeof t === 'string') return t.replace(/^:/, '');
  return JSON.stringify(t);  // structural — Edit by typing isn't supported
                             // yet; the picker only offers named alternates.
}

// Populate `<select>` with the current type + every named alias
// that's a subtype of it. Async; the picker shows the current
// option immediately so the user has an answer even before the
// fetches complete.
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

function constraintToString(c) {
  if (!Array.isArray(c)) return JSON.stringify(c);
  const op = c[0];
  if (op === 'and' || op === 'or') {
    return c.slice(1).map(constraintToString).join(' ' + op + ' ');
  }
  if (c.length === 2) return op + ' ' + JSON.stringify(c[1]);
  return JSON.stringify(c);
}
