// Editor Provenance Popover — click-driven popover anchored to the `↳`
// provenance badge on an arg-overlay's type-chip. Surfaces the FULL
// narrowing chain (declaration → ancestor overrides → ref-return →
// effective slot type) as a clickable breadcrumb so the reader can
// answer "where did THIS type constraint come from?" without opening
// the inline `▸/▾` type-expand panel and scrolling past structural
// detail.
//
// Reuses `slotTypeProvenance` (editor-literal-types.js) for data and
// `appendResolutionSection` (editor-overlay-type-expand.js) for
// rendering — passing `onNavigate: selectFn` so each ancestor /
// source-fn name renders as a clickable link.
//
// Globals consumed: anchorBelowClamped, installPopoverDismiss
// (editor-popover-base.js), slotTypeProvenance, appendResolutionSection,
// selectFn.

let provenancePopoverEl = null;
let provenancePopoverAnchor = null;

function ensureProvenancePopoverEl() {
  if (provenancePopoverEl) return provenancePopoverEl;
  const el = document.createElement('div');
  el.className = 'provenance-popover';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Type narrowing provenance');
  document.body.appendChild(el);
  provenancePopoverEl = el;
  return el;
}

function provenancePopoverVisible() {
  return !!provenancePopoverEl
         && provenancePopoverEl.classList.contains('visible');
}

function hideProvenancePopover() {
  if (!provenancePopoverEl) return;
  provenancePopoverEl.classList.remove('visible');
  provenancePopoverEl.style.display = 'none';
  // Sync `aria-expanded` on the trigger that opened the popover so
  // screen readers see the disclosure flip back to closed. Both
  // provenance triggers (arg-type-provenance, return-type-strip-
  // provenance) start at "false" when rendered and we set "true"
  // when opening from them — undo that here.
  if (provenancePopoverAnchor) {
    try {
      provenancePopoverAnchor.setAttribute('aria-expanded', 'false');
    } catch (_) {}
  }
  provenancePopoverAnchor = null;
}


// Reposition + state-sync helper shared by both show* entry points.
// Before swapping the tracked anchor:
//   - the PREVIOUS anchor (if any) needs aria-expanded="false" so a
//     stale trigger doesn't keep claiming "I'm open"
//   - the NEW anchor gets aria-expanded="true"
// Then we anchor-clamp + reveal.
function attachAndShow(anchorEl) {
  if (provenancePopoverAnchor && provenancePopoverAnchor !== anchorEl) {
    try {
      provenancePopoverAnchor.setAttribute('aria-expanded', 'false');
    } catch (_) {}
  }
  try {
    anchorEl.setAttribute('aria-expanded', 'true');
  } catch (_) {}
  provenancePopoverEl.classList.add('visible');
  anchorBelowClamped(provenancePopoverEl, anchorEl);
  provenancePopoverAnchor = anchorEl;
}

function showProvenancePopover(arg, anchorEl) {
  if (!arg || !anchorEl) return;
  if (typeof slotTypeProvenance !== 'function'
      || typeof appendResolutionSection !== 'function') return;
  const prov = slotTypeProvenance(arg);
  // No narrowing chain to show — bail rather than open an empty popover.
  if (!prov?.winner) return;

  const el = ensureProvenancePopoverEl();
  el.textContent = '';

  const head = document.createElement('div');
  head.className = 'provenance-popover-header';
  const titleEl = document.createElement('span');
  titleEl.className = 'provenance-popover-title';
  titleEl.textContent = 'Type narrowing';
  head.appendChild(titleEl);
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'provenance-popover-close';
  close.setAttribute('aria-label', 'Close type narrowing popover');
  close.textContent = '×';
  close.addEventListener('click', (e) => {
    e.stopPropagation();
    hideProvenancePopover();
  });
  head.appendChild(close);
  el.appendChild(head);

  // Reuses the shared 4-tier + inheritance-chain renderer; passing
  // onNavigate makes ancestor / source-fn labels clickable links that
  // select the owning fn-row and dismiss the popover.
  appendResolutionSection(el, prov, {
    onNavigate: (fnId) => {
      if (typeof selectFn === 'function' && fnId) {
        hideProvenancePopover();
        selectFn(fnId);
      }
    },
  });

  // Closed-enum reveal — when the resolved slot type is a refinement
  // pinning the value to a literal set (`[:refine kw [:in [...]]]`),
  // surface the allowed values directly. Saves the reader from
  // opening the inline `▸/▾` panel to discover the (often tiny) set.
  const winnerType = prov.tiers?.find(t => t.key === prov.winner)?.type;
  if (winnerType != null && typeof closedEnumOf === 'function') {
    const enumInfo = closedEnumOf(winnerType);
    if (enumInfo && enumInfo.members.length > 0) {
      appendClosedEnumSection(el, enumInfo);
    }
  }

  // Slot-effect constraint reveal — when the resolved slot type is a
  // `[:fn args ret eff]` with a CONCRETE eff set (not `:any`), surface
  // the constraint so the reader sees "this slot demands a pure
  // callable" / "this slot allows only :env reads" without opening
  // the inline `▸/▾` panel where the read-only effect row lives.
  if (Array.isArray(winnerType) && winnerType[0] === 'fn'
      && winnerType.length === 4 && winnerType[3] !== 'any'
      && winnerType[3] !== ':any'
      && typeof makeEffectsReadOnly === 'function') {
    const effRaw = winnerType[3];
    const currentEff = Array.isArray(effRaw)
      ? new Set(effRaw.map(e => typeof e === 'string' ? e.replace(/^:/, '') : String(e)))
      : new Set([]);
    appendEffectConstraintSection(el, currentEff);
  }

  attachAndShow(anchorEl);
}


// Shared "header + body" section helper. Every section in the
// provenance popover (Resolved via, Allowed values, Slot effect
// bound, Inputs) follows the same shape: a small uppercase head
// label over a body element. `extraClass` parameterises the wrapper
// so per-section CSS (border-top, spacing) still attaches.
function appendPopoverSection(host, headText, body, extraClass) {
  const section = document.createElement('div');
  if (extraClass) section.className = extraClass;
  const head = document.createElement('div');
  head.className = 'type-inline-resolution-head';
  head.textContent = headText;
  section.appendChild(head);
  if (body) section.appendChild(body);
  host.appendChild(section);
  return section;
}


// Render the "Slot effect bound" section — a header + the existing
// `makeEffectsReadOnly` chip row. Surfaces the slot-level effect
// constraint (Phase 8) in the provenance popover so the reader
// understands WHY a callable bound here would be accepted or
// rejected, without opening the inline `▸/▾` panel.
function appendEffectConstraintSection(host, currentEff) {
  appendPopoverSection(host, 'Slot effect bound',
                       makeEffectsReadOnly(currentEff),
                       'provenance-popover-effects');
}


// Render an "Allowed values" section listing every member of a
// closed-enum refinement (`[:refine T [:in [m₁ m₂ …]]]`). Members are
// rendered as inline chips; the section is read-only — the value-edit
// popover is the place to PICK a member.
function appendClosedEnumSection(host, enumInfo) {
  const list = document.createElement('div');
  list.className = 'provenance-popover-enum-list';
  for (const member of enumInfo.members) {
    const chip = document.createElement('span');
    chip.className = 'provenance-popover-enum-chip';
    chip.textContent = member.label;
    list.appendChild(chip);
  }
  appendPopoverSection(host, 'Allowed values', list,
                       'provenance-popover-enum');
}

// Per-rule narrative templates. Given `resolved-bindings` (the slots
// the type-checker actually saw) plus the computed return-type, each
// template returns a SHORT prose sentence explaining what the rule
// did. Returns null when the bindings don't fit the rule's shape
// (degenerate case → the inputs table speaks for itself).
//
// The templates intentionally avoid using `shortTypeLabel` on
// free-form bindings — the inputs table below renders the full type
// — and focus on naming the load-bearing INPUT (literal key / sub-
// shape) so the reader understands the dependent-type-like logic.
const ruleNarrators = {
  assoc(b, ret) {
    const k = b?.key;
    const v = b?.value;
    if (!k) return null;
    const keyLit = (k.value !== undefined && k.value !== null)
                   ? JSON.stringify(k.value) : null;
    if (!keyLit) return 'key is computed (not a literal) — result widens to :jsonb.';
    const vt = (v && v.type != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(v.type) : '?';
    return `Literal key ${keyLit}, value typed ${vt} — added field ${keyLit}: ${vt} to map's record shape.`;
  },
  dissoc(b) {
    const k = b?.key;
    if (!k) return null;
    const keyLit = (k.value !== undefined && k.value !== null)
                   ? JSON.stringify(k.value) : null;
    return keyLit
      ? `Literal key ${keyLit} removed from map's record shape.`
      : 'key is computed — result falls back to :jsonb.';
  },
  get(b, ret) {
    const k = b?.key;
    if (!k) return null;
    const keyLit = (k.value !== undefined && k.value !== null)
                   ? JSON.stringify(k.value) : null;
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    if (!keyLit) return `key is computed (not literal) — returned ${rt} (slot's declared default).`;
    return `Field ${keyLit} looked up in coll's record shape — returned ${rt}.`;
  },
  'get-in'(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Path walks coll's record shape — returned ${rt}.`;
  },
  'assoc-in'(b) {
    return 'Path-walked update — inner record shapes refined when every segment is a literal key.';
  },
  'update-in'(b) {
    return "Lambda applied at path — result type follows `:f`'s return.";
  },
  conj(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Item appended/prepended to coll — element type widened, return ${rt}.`;
  },
  first() {
    return "Returns `[:union :null a]` — head of a list, or :null when empty.";
  },
  rest() {
    return 'Returns `[:list a]` — drops the head, element type preserved.';
  },
  cons(b) {
    const i = b?.item;
    const it = (i && i.type != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(i.type) : '?';
    return `Item typed ${it} prepended — element type widens to LUB with coll.`;
  },
  list(b) {
    const items = b?.items;
    const elem = items?.['elem-types'];
    if (Array.isArray(elem) && elem.length > 0 && typeof shortTypeLabel === 'function') {
      const labels = elem.map(shortTypeLabel).join(', ');
      return `Items: [${labels}] — element type LUB'd over the literal vector.`;
    }
    return 'Element type LUB\'d from the literal vector of items.';
  },
  merge() {
    return 'Records merged left-to-right — later keys win, per-item record shapes unioned.';
  },
  into() {
    return "Items poured into destination — returns destination's effective shape.";
  },
  range(b) {
    const fmt = (k) => {
      const x = b?.[k];
      return (x && x.value !== undefined && x.value !== null) ? x.value : '?';
    };
    return `Integer sequence [${fmt('start')}, ${fmt('end')}) by ${fmt('step')} — `
         + 'return [:list :int].';
  },
  repeat(b) {
    const n = b?.count?.value;
    return (n != null) ? `${n} copies — return [:list a].` : 'Fixed-count copies of value.';
  },
  keys() { return 'Returns `[:list k]` — keys of the homogeneous `[:map k v]`.'; },
  vals() { return 'Returns `[:list v]` — values of the homogeneous `[:map k v]`.'; },
  case(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Dispatch table union'd with default — returned ${rt}.`;
  },
  cond(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Result-position branches union'd — returned ${rt}.`;
  },
  coalesce(b, ret) {
    const v = b?.value;
    const d = b?.default;
    const vt = (v && v.type != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(v.type) : '?';
    const dt = (d && d.type != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(d.type) : '?';
    return `:null stripped from value (${vt}); unioned with default (${dt}).`;
  },
  if(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Branches union'd: [:union then-type else-type] — returned ${rt}.`;
  },
  invoke() {
    return "Bound callable's return type pulled in; runtime decides between hof-wrap and thunk by produces-callable?.";
  },
  const() { return "Identity passthrough — return type follows `:value`."; },
  identity() { return "Identity passthrough — return type follows `:value`."; },

  // List-preserving HOFs — same shape, fewer/reordered elements.
  take(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Returns ${rt} — first :count items, element type preserved from coll.`;
  },
  drop(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Returns ${rt} — all but first :count items, element type preserved.`;
  },
  reverse(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Returns ${rt} — items reversed, element type preserved.`;
  },
  sort(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Returns ${rt} — items sorted, element type preserved.`;
  },
  distinct(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Returns ${rt} — duplicates removed, element type preserved.`;
  },
  concat(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Returns ${rt} — colls concatenated, element type LUB'd across inputs.`;
  },

  // Arithmetic — `narrow-numeric-to-int` rule: if every input is :int,
  // return :int; otherwise the declared :numeric. Note: arithmetic
  // does NOT propagate refinement constraints (no SMT inference) —
  // a `:positive-int + 1` is typed `:int`, not `:positive-int`.
  add(b, ret) { return arithRetNarrator('+', b, ret); },
  sub(b, ret) { return arithRetNarrator('-', b, ret); },
  mul(b, ret) { return arithRetNarrator('*', b, ret); },
  mod(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Modulo — return ${rt} (`+
           ":int when dividend and divisor are :int, else :numeric).";
  },
  neg(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Negation — return ${rt} (refinement constraints don't propagate `+
           "through arithmetic; a :positive-int negated is :int).";
  },
  abs(b, ret) {
    const rt = (ret != null && typeof shortTypeLabel === 'function')
               ? shortTypeLabel(ret) : '?';
    return `Absolute value — return ${rt} (constraint inference deliberately `+
           "not propagated through arithmetic).";
  },
};


// Shared helper for add/sub/mul narrators — same shape, different op.
function arithRetNarrator(op, b, ret) {
  const rt = (ret != null && typeof shortTypeLabel === 'function')
             ? shortTypeLabel(ret) : '?';
  return `Returns ${rt} — when every input is :int the result is :int, `+
         "else widens to :numeric. Refinement constraints (e.g. :positive-int) "+
         `do NOT propagate through ${op}.`;
}


// Return-type variant — anchored to the `↳` glyph on a fn-card's
// return-type strip when the fn's primary-parent has a registered
// :return-type-rule (assoc / get / dissoc / conj / first / cons / …).
// The base-fn's rule computed THIS fn-def's return-type from the
// resolved bindings; the popover names the rule's source, surfaces a
// one-line per-rule narrative explaining the computation (e.g. for
// :assoc — "literal key \"name\", value typed :int — added field
// :name :int to map's record shape"), and lists the inputs that fed
// into it.
//
// Reuses the same singleton DOM element and dismiss handler as the
// slot-narrowing popover above — only one provenance popover is open
// at a time.
function showReturnTypeRulePopover(fnEntry, parentName, parentFnId, anchorEl) {
  if (!fnEntry || !parentName || !anchorEl) return;
  const el = ensureProvenancePopoverEl();
  el.textContent = '';

  const head = document.createElement('div');
  head.className = 'provenance-popover-header';
  const titleEl = document.createElement('span');
  titleEl.className = 'provenance-popover-title';
  titleEl.textContent = 'Type rule';
  head.appendChild(titleEl);
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'provenance-popover-close';
  close.setAttribute('aria-label', 'Close type rule popover');
  close.textContent = '×';
  close.addEventListener('click', (e) => {
    e.stopPropagation();
    hideProvenancePopover();
  });
  head.appendChild(close);
  el.appendChild(head);

  // Prose line — "Return type computed by :<parent>'s :return-type-rule".
  // The parent name is a clickable link (navigates to the base-fn) so
  // the reader can inspect the rule's owning fn directly.
  const intro = document.createElement('div');
  intro.className = 'provenance-popover-intro';
  intro.appendChild(document.createTextNode('Return type computed by '));
  if (parentFnId && typeof selectFn === 'function') {
    const link = document.createElement('a');
    link.href = '#';
    link.className = 'type-inline-resolution-link';
    link.textContent = ':' + parentName;
    link.title = 'Open base-fn :' + parentName;
    link.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      hideProvenancePopover();
      selectFn(parentFnId);
    });
    intro.appendChild(link);
  } else {
    const plain = document.createElement('span');
    plain.textContent = ':' + parentName;
    intro.appendChild(plain);
  }
  intro.appendChild(document.createTextNode("'s :return-type-rule"));
  el.appendChild(intro);

  // Per-rule narrative — a short prose sentence interpreting what the
  // rule did with the resolved bindings (e.g. for :assoc: "literal key
  // \"name\", value typed :int — added field :name :int to the record
  // shape"). The inputs table below carries the raw bindings; this
  // line carries the INTERPRETATION so the reader doesn't have to
  // re-derive it. Falls through silently when the rule has no
  // narrator template — the inputs list already speaks for itself.
  const bindingsForNarrator = fnEntry['resolved-bindings'] || {};
  const narrator = ruleNarrators[parentName];
  if (typeof narrator === 'function') {
    try {
      const sentence = narrator(bindingsForNarrator, fnEntry.return);
      if (sentence) {
        const nar = document.createElement('div');
        nar.className = 'provenance-popover-narrative';
        nar.textContent = sentence;
        el.appendChild(nar);
      }
    } catch (_) {
      // Narrator templates are best-effort: a malformed binding
      // shouldn't break the popover. Fall back to the inputs table.
    }
  }

  // Inputs table — one row per resolved binding, columns name / kind / type.
  // The shape mirrors the type-rule's view of the world: which slot was
  // bound by literal vs ref, and what its EFFECTIVE type ended up.
  const bindings = fnEntry['resolved-bindings'] || {};
  const keys = Object.keys(bindings);
  if (keys.length > 0) {
    const head2 = document.createElement('div');
    head2.className = 'type-inline-resolution-head';
    head2.textContent = 'Inputs';
    el.appendChild(head2);
    for (const k of keys) {
      const b = bindings[k];
      const row = document.createElement('div');
      row.className = 'type-inline-resolution-row';
      const mark = document.createElement('span');
      mark.className = 'type-inline-resolution-mark';
      mark.textContent = '·';
      row.appendChild(mark);
      const lab = document.createElement('span');
      lab.className = 'type-inline-resolution-label';
      // Annotate kind: ref(ref-name) / value(JSON) / free. Short, so the
      // type column stays readable; the user-facing distinction between
      // "rule input is a literal {}" and "rule input is a fn-ref :get-x"
      // is what makes the rule's output explicable.
      const kind = (b.ref != null) ? ('ref→:' + b.ref)
                 : (b.value !== null && b.value !== undefined)
                   ? ('= ' + JSON.stringify(b.value).slice(0, 24))
                   : '(free)';
      lab.textContent = k + ' ' + kind;
      row.appendChild(lab);
      const val = document.createElement('span');
      val.className = 'type-inline-resolution-type';
      val.textContent = (typeof shortTypeLabel === 'function' && b.type != null)
                        ? shortTypeLabel(b.type)
                        : (b.type != null ? String(b.type) : '—');
      row.appendChild(val);
      el.appendChild(row);
    }
  }

  attachAndShow(anchorEl);
}

installPopoverDismiss({
  getEl: () => provenancePopoverEl,
  getAnchor: () => provenancePopoverAnchor,
  isVisible: provenancePopoverVisible,
  onDismiss: hideProvenancePopover,
});

window.showProvenancePopover = showProvenancePopover;
window.showReturnTypeRulePopover = showReturnTypeRulePopover;
window.hideProvenancePopover = hideProvenancePopover;
