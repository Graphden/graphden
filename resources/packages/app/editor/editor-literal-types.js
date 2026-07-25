// Editor Literal Types - shared type-validation helpers used by inline
// edit popovers (arg-value, arg-type, free-arg-bind). All functions
// are pure JS mirrors of backend logic in graphden.types.check /
// graphden.types.core, plus a few presentation helpers.
//
// Globals consumed: `lookups` (editor-data.js), `richTypes` (set by
// editor-main.js after fetching /api/types). Loaded into the
// concatenated bundle BEFORE editor-tooltips.js.

// === Compatible-type options loader ==========================================
//
// One `GET /partials/compatible-type-options` returns the full
// <option> list of type names that can legally narrow `expected` —
// a single server-side alias-aware `subtype?` sweep. This replaced
// the per-name `/api/types/compatible` fan-out (~50 parallel POSTs
// per type-picker open) plus its session cache. The caller seeds the
// CURRENT type synchronously for instant render; the server excludes
// `opts.current` and appends the "(no compatible types)" placeholder
// when the list is empty and no current type exists.
async function loadCompatibleTypeOptions(select, expected, opts) {
  opts = opts || {};
  const params = new URLSearchParams({ expected: JSON.stringify(expected) });
  if (opts.current) params.set('current', opts.current);
  if (opts.includePrimitives) params.set('primitives', 'true');
  let html;
  try {
    const r = await authFetch('/partials/compatible-type-options?'
                              + params.toString());
    if (!r.ok) return false;
    html = await r.text();
  } catch (_) {
    return false;
  }
  const tpl = document.createElement('template');
  tpl.innerHTML = html;
  for (const o of tpl.content.querySelectorAll('option')) {
    select.appendChild(o);
  }
  return true;
}

// The rich (structural) declared type of `arg`'s slot, recovered from
// the rich-types registry. `[:list T]` / `[:map K V]` / `[:tuple …]`
// slot types degrade to the bare `:sequence` / `:jsonb` primitive on
// the storage slot row — T / K / V survive only in `richTypes`. Walk
// the owning fn's inheritance chain for the first ancestor whose rich
// `args` entry names this slot (closest narrowing wins). Returns null
// when nothing is found.
function slotRichType(arg) {
  if (!arg?.['fn-id'] || !arg['slot-id']) return null;
  if (typeof richTypes !== 'object' || !richTypes) return null;
  if (typeof getInheritanceChain !== 'function') return null;
  const slot = lookups?.slotMap?.get(arg['slot-id']);
  const slotName = slot?.name;
  if (!slotName) return null;
  for (const fid of getInheritanceChain(arg['fn-id'])) {
    const fn = lookups?.fnMap?.get(fid);
    const t = (fn?.name && richTypes[fn.name])
              ? richTypes[fn.name].args?.[slotName] : null;
    if (t != null) return t;
  }
  return null;
}

// Resolve an arg row's expected type from its slot. The arg row
// (synth-shape from the layout pipeline) carries `:slot-id` directly
// — look up the slot, get its type-fn-id, return either:
//   - the fn-row's `richTypes[name].return` structural form (for
//     refinements / lists / records), or
//   - the fn's name as a primitive keyword (for `:int`, `:text`, …).
// Used by the value-edit popover to show "Expected: <type>".
function expectedSlotType(arg) {
  if (!arg || !lookups?.slotMap || !lookups.fnMap) return null;
  const slotId = arg['slot-id'];
  if (!slotId) return null;
  // Per-position type for a nav-typed sequence item (`:update-in`
  // `:path`): walk the navigable structure along the live path
  // prefix. Wins over the homogeneous `[:list T]` element type — the
  // valid key-set at segment N depends on segments 0..N-1.
  if (arg['item-id']) {
    const navT = (typeof navItemType === 'function') ? navItemType(arg) : null;
    if (navT != null) return navT;
  }
  const slot = lookups.slotMap.get(slotId);
  if (!slot?.['type-fn-id']) return null;
  // Effective type at this binding site — most specific wins:
  //  1. Author's explicit pin (`{:type T}` on the binding) — strongest
  //     intent, narrows even inherited slot generics.
  //  2. Backward-unification result — when the fn-def's declared
  //     `:return-type` narrowed a parent type-var that ALSO types
  //     this slot, the type-checker records the narrowed type in
  //     rich-types `slot-types` (keyed by slot-name). E.g.
  //     `:default-security-headers` declaring `:security-headers-shape`
  //     over `:const` flows into the `:value` slot.
  //  3. ref's declared return-type — when `{:ref X}` without an
  //     explicit `:type`, the inferred narrow is what X returns.
  //  4. slot's declared `type-fn-id` — fallback.
  const bindingId = arg['binding-id'];
  const binding = (bindingId && lookups.bindingMap)
                  ? lookups.bindingMap.get(bindingId) : null;

  // Priority 2 — backward-unified slot type from rich-types. Skipped
  // when the author put an explicit `:type` override on the binding
  // (priority 1 wins below). The value is a rich-type directly, not a
  // fn-id, so it short-circuits the fn-id resolution path.
  if (binding?.['type-override-fn-id'] == null) {
    const ownFn = arg['fn-id'] ? lookups.fnMap.get(arg['fn-id']) : null;
    const unified = (ownFn?.name && typeof richTypes === 'object' && richTypes)
                    ? richTypes[ownFn.name]?.['slot-types']?.[slot.name]
                    : null;
    if (unified != null) {
      if (arg['item-id']) {
        const elem = listElementType(unified, null);
        if (elem !== null) return elem;
      }
      return unified;
    }
  }

  const refFnId = binding?.['ref-fn-id'];
  const refFn = refFnId ? lookups.fnMap.get(refFnId) : null;
  const tfnId = binding?.['type-override-fn-id']
                || refFn?.['return-type-fn-id']
                || slot['type-fn-id'];
  const tfn = lookups.fnMap.get(tfnId);
  if (!tfn) return null;
  const slotType = computeSlotType(tfn);
  // Priority 4 — slot's declared type. `[:list T]` / `[:map K V]` /
  // `[:tuple …]` degrade to the bare `:sequence` / `:jsonb` primitive
  // on the storage slot row; the structural form survives only in the
  // rich-types registry. Prefer it — but ONLY when this resolution
  // actually fell through to the slot declaration (no binding
  // type-override, no ref return-type pinning the type).
  const usingSlotDecl = !binding?.['type-override-fn-id']
                        && !refFn?.['return-type-fn-id'];
  const declRich = usingSlotDecl ? slotRichType(arg) : null;
  // Only let the rich-types form override when `slotType` came back
  // DEGRADED — a bare `:sequence` / `:jsonb` / `:any` primitive that
  // lost its structure on the storage slot row. When `computeSlotType`
  // already produced a structural form (e.g. `[:fn …]` recovered from
  // the slot's anonymous-row `:constraint`), THAT is the precise one
  // — a coarser rich-types entry must not clobber it.
  const degraded = slotType === 'sequence' || slotType === 'jsonb'
                   || slotType === 'any';
  const effective = (degraded && Array.isArray(declRich))
                    ? declRich : slotType;
  // For binding-list-items the slot's type is `[:list T]` / `:sequence`
  // but the ITEM-level expected is `T` (the element type). Without
  // this unfold a literal `:headers` keyword bound into a
  // `[:list :keyword]`-typed slot would mismatch against `:sequence`
  // and render with a red ring. Detect list-items by `:item-id` —
  // that field is the binding-list-item's row id.
  if (arg['item-id']) {
    const elemType = listElementType(effective, tfn);
    if (elemType !== null) return elemType;
  }
  return effective;
}


// Walk the inheritance chain from `fnId` to find the DEEPEST ancestor
// (root base-fn) whose fn-slot junctions include `slotId` — that's the
// fn that originally introduced the slot. Graphden slots are global
// identities (one-shot creation, immutable post-create), so the
// declaring fn is well-defined. Returns the {fnId, fnName} pair, or
// null when the slot can't be traced (shouldn't happen for any
// inherited slot).
function findSlotDeclaringFn(fnId, slotId) {
  if (!lookups?.fnMap || !lookups.fnSlotsByFn) return null;
  const chain = getInheritanceChain(fnId);
  // BFS-ordered, leaf first → root last. Iterate in REVERSE so the
  // deepest ancestor that owns the fn-slot wins.
  for (let i = chain.length - 1; i >= 0; i -= 1) {
    const fid = chain[i];
    const fnSlots = lookups.fnSlotsByFn.get(fid) || [];
    if (fnSlots.some((fs) => fs['slot-id'] === slotId)) {
      const fn = lookups.fnMap.get(fid);
      return { fnId: fid, fnName: fn?.name || null };
    }
  }
  return null;
}


// Walk the inheritance chain from `fnId` to find every ancestor that
// carries a BINDING (with or without :type-override) for `slotId`.
// Returns the list in CLOSER-WINS order (leaf-first). Used for the
// "Inherited via" chain row above the 4-tier resolution.
function findBindingOverrideChain(fnId, slotId) {
  if (!lookups?.fnMap || !lookups.bindingMap) return [];
  const chain = getInheritanceChain(fnId);
  const out = [];
  for (const fid of chain) {
    const binding = lookups.bindingMap.get(`${fid}/${slotId}`)
                 || (() => {
                   // Fallback: iterate bindingMap if it's not keyed compactly
                   for (const b of lookups.bindingMap.values()) {
                     if (b['fn-id'] === fid && b['slot-id'] === slotId) return b;
                   }
                   return null;
                 })();
    if (binding?.['type-override-fn-id']) {
      const fn = lookups.fnMap.get(fid);
      out.push({
        fnId: fid,
        fnName: fn?.name || null,
        overrideFnId: binding['type-override-fn-id'],
      });
    }
  }
  return out;
}


// Companion to `expectedSlotType`: reports HOW a slot's effective type
// resolved — the 4-tier priority chain (binding type-override →
// backward-unified slot-type → bound-fn return-type → slot
// declaration), the type each tier contributes (null when the tier
// doesn't apply), the SOURCE fn-name that contributed each tier, and
// which tier won. Returns null for list-item rows (their type comes
// from nav / element logic, not the slot chain) and for args that
// don't resolve. The editor's inline-expand panel renders this as a
// "Resolved via" section.
function slotTypeProvenance(arg) {
  if (!arg || !lookups?.slotMap || !lookups.fnMap) return null;
  if (arg['item-id']) return null;
  const slotId = arg['slot-id'];
  if (!slotId) return null;
  const slot = lookups.slotMap.get(slotId);
  if (!slot?.['type-fn-id']) return null;
  const binding = (arg['binding-id'] && lookups.bindingMap)
                  ? lookups.bindingMap.get(arg['binding-id']) : null;
  const typeOfFn = (fnId) => {
    const tfn = fnId ? lookups.fnMap.get(fnId) : null;
    return tfn ? computeSlotType(tfn) : null;
  };
  const overrideFnId = binding?.['type-override-fn-id'];
  // Tier 2 is skipped under an explicit override — mirrors expectedSlotType.
  let unifiedType = null;
  if (overrideFnId == null) {
    const ownFn = arg['fn-id'] ? lookups.fnMap.get(arg['fn-id']) : null;
    unifiedType = (ownFn?.name && typeof richTypes === 'object' && richTypes)
                  ? (richTypes[ownFn.name]?.['slot-types']?.[slot.name] ?? null)
                  : null;
  }
  const refFn = binding?.['ref-fn-id'] ? lookups.fnMap.get(binding['ref-fn-id']) : null;
  // Source attribution per tier:
  //   override  — the fn that owns this binding (i.e., arg['fn-id']).
  //   unified   — the same: backward-unification produced the narrowing
  //               at THIS fn-def's check.
  //   ref-return — the bound fn's name (refFn).
  //   slot      — the root base-fn that originally declared this slot.
  const ownFn = arg['fn-id'] ? lookups.fnMap.get(arg['fn-id']) : null;
  const declaringSource = (arg['fn-id'])
                          ? findSlotDeclaringFn(arg['fn-id'], slotId)
                          : null;
  // Each tier's source carries both fnName (display) and fnId (so the
  // popover can navigate to the source fn on click). The renderer
  // (appendResolutionSection in editor-type-format.js) treats
  // fnId as optional — plain text falls back gracefully.
  const tiers = [
    { key: 'override', label: 'Binding type-override',
      type: typeOfFn(overrideFnId),
      source: (overrideFnId && ownFn?.name)
              ? { fnName: ownFn.name, fnId: arg['fn-id'] } : null },
    { key: 'unified', label: 'Backward-unified return type',
      type: unifiedType,
      source: (unifiedType != null && ownFn?.name)
              ? { fnName: ownFn.name, fnId: arg['fn-id'] } : null },
    { key: 'ref-return', label: 'Bound fn return type',
      type: typeOfFn(refFn?.['return-type-fn-id']),
      source: refFn?.name
              ? { fnName: refFn.name, fnId: binding['ref-fn-id'] } : null },
    { key: 'slot', label: 'Slot declaration',
      type: typeOfFn(slot['type-fn-id']),
      source: declaringSource },
  ];
  const winner = tiers.find((t) => t.type != null);
  // Inheritance chain: every ancestor between `arg['fn-id']` and the
  // root base-fn that contributes a type-override binding for this
  // slot. The editor renders this as a leading "Inherited via:" row
  // ABOVE the 4 tiers, surfacing the multi-hop narrowing path.
  const inheritanceChain = arg['fn-id']
                           ? findBindingOverrideChain(arg['fn-id'], slotId)
                           : [];
  return {
    winner: winner ? winner.key : null,
    tiers,
    inheritanceChain,
  };
}


// Resolve the structural type of a slot's type-fn row, preferring the
// rich aliased form when one is registered. Pulled out of
// `expectedSlotType` so list-item lookup can reuse the same logic
// for the slot AND for the unfolded element type.
function computeSlotType(tfn) {
  if (!tfn) return null;
  // Anonymous fn-type / map-type rows (inline `[:fn args ret]` /
  // `[:map K V]` slot declarations) have nil `:name` but their
  // structural shape lives on `:constraint`. Recover it directly so
  // the chip / explainer / mismatch logic see the structural form
  // rather than the flat fallback "fn" / "jsonb".
  const c = tfn.constraint;
  if (Array.isArray(c) && (c[0] === 'fn' || c[0] === 'map' || c[0] === 'tuple')) return c;
  if (!tfn.name) return null;
  const rich = (typeof richTypes !== 'undefined' && richTypes) ? richTypes[tfn.name] : null;
  // For NAMED type-rows (record-shapes, list/union/variant aliases,
  // refinements) prefer the alias name — readers recognise
  // `ring-response-shape` instantly and the inline-expand panel still
  // unfolds the constituents on demand. Returning the expanded
  // structure (a JS object for records) breaks `compactTypeChipText`,
  // which only renders strings and arrays — falls through to flat
  // `any` / `jsonb`.
  if (rich?.['type-row?']) return tfn.name;
  if (rich?.return && rich.return !== 'any') return rich.return;
  return tfn.name;
}


// Given a slot's structural type AND its type-fn-row, recover the
// element type for `[:list T]` / `:sequence` slots. Returns null when
// the slot isn't a list (caller leaves the slot type unchanged).
function listElementType(slotType, tfn) {
  // Structural `[:list T]` — second element is the element type.
  if (Array.isArray(slotType) && slotType[0] === 'list') return slotType[1];
  // Bare `:sequence` primitive — element type lives on the row's
  // `:element-fn-id`. Look it up to recover the structural element.
  if (slotType === 'sequence' || (tfn?.['element-fn-id'])) {
    const elemFn = tfn?.['element-fn-id']
                 ? lookups.fnMap.get(tfn['element-fn-id'])
                 : null;
    if (elemFn) return computeSlotType(elemFn);
    return 'any';
  }
  return null;
}


// Mirror of backend's `classify-literal` (graphden.types.check).
// Picks a primitive type tag for a parsed JS value. Returns null
// when the shape doesn't match a recognised primitive — caller
// falls back to :any.
//
// Strings starting with `:` (e.g. `":foo"`) are treated as keywords —
// matches the codec's `preserve-keywords` / `normalize-parsed-json`
// round-trip convention. A bare string `"foo"` (no `:` prefix) stays
// as :text.
function classifyLiteralJS(v) {
  if (v === null) return 'null';
  if (typeof v === 'boolean') return 'bool';
  if (typeof v === 'number') return Number.isInteger(v) ? 'int' : 'float';
  if (typeof v === 'string') {
    return (v.length > 1 && v.charAt(0) === ':') ? 'keyword' : 'text';
  }
  if (Array.isArray(v)) return 'jsonb';
  if (typeof v === 'object') return 'jsonb';
  return null;
}


// Mirror of backend's `literal-satisfies-refinement?`. Returns
// true / false / 'unknown'. Supports the compound forms `[:and …]`
// / `[:or …]` so refinements like
//   `[:refine :int [:and [:>= 0] [:<= 100]]]` (a percent)
// validate correctly client-side.
function refinementOK(v, constraint) {
  if (!Array.isArray(constraint) || constraint.length === 0) return 'unknown';
  const head = constraint[0];
  if (head === 'and' || head === 'or') {
    const children = constraint.slice(1);
    if (children.length === 0) return head  === 'and';
    const results = children.map(c => refinementOK(v, c));
    if (head === 'and') {
      if (results.some(r => r === false)) return false;
      if (results.every(r => r === true)) return true;
      return 'unknown';
    } else {
      if (results.some(r => r === true)) return true;
      if (results.every(r => r === false)) return false;
      return 'unknown';
    }
  }
  if (constraint.length !== 2) return 'unknown';
  const [op, rhs] = constraint;
  switch (op) {
    case '>':    return typeof v === 'number' && typeof rhs === 'number' && v > rhs;
    case '>=':   return typeof v === 'number' && typeof rhs === 'number' && v >= rhs;
    case '<':    return typeof v === 'number' && typeof rhs === 'number' && v < rhs;
    case '<=':   return typeof v === 'number' && typeof rhs === 'number' && v <= rhs;
    case '=':    return v === rhs;
    case 'not=': return v !== rhs;
    // `[:in [m…]]` — membership in a finite set. Keyword members
    // serialise without their colon, the editor's keyword values
    // keep it, so accept either form.
    case 'in':   return Array.isArray(rhs)
                        && rhs.some(x => x === v || (':' + x) === v);
    default:     return 'unknown';
  }
}


// Detect a CLOSED ENUMERATION — a refinement whose constraint pins the
// value to a finite literal set (`[:refine base [:in […]]]`). Returns
// `{base, members:[{value,label}]}` so the value-edit popover can offer
// a <select>; null when the type isn't a closed enum. Keyword members
// arrive colon-stripped on the wire — re-prefixed here so the option
// `value` round-trips as the keyword it represents.
function closedEnumOf(expected) {
  const t = dereferenceType(expected);
  if (!Array.isArray(t) || t[0] !== 'refine') return null;
  const base = t[1];
  const c = t[2];
  if (!Array.isArray(c) || c[0] !== 'in' || !Array.isArray(c[1])) return null;
  const isKw = base === 'keyword';
  const members = c[1].slice()
    .map(m => String(m))
    .sort()
    .map(m => {
      const lit = (isKw && m.charAt(0) !== ':') ? ':' + m : m;
      return { value: lit, label: lit };
    });
  return { base: base, members: members };
}


// True when `t` resolves to a keyword-based type — a bare `keyword`
// or a refinement over one. Free text typed into such a slot's editor
// names a keyword, so it must be stored colon-prefixed (`:foo`), not
// as plain text.
function isKeywordType(t) {
  const d = dereferenceType(t);
  if (d === 'keyword') return true;
  return Array.isArray(d) && d[0] === 'refine' && d[1] === 'keyword';
}


// --- nav-type walk — per-position typing for sequence items that
// index INTO a structure (e.g. `:update-in`'s `:path` walks `:m`).
//
// The backend hands over the navigable structure verbatim (rich-type
// `nav-types`); the editor walks it against the LIVE path so each
// position stays correct even mid-edit. The walk itself is generic
// structural navigation — no base-fn knowledge.

// Type a segment that navigates `t`: a record → its closed key-set,
// an open map → a free keyword, a list → an int index. null = `t` is
// a scalar — no further segment is valid.
function navKeyType(t) {
  const d = dereferenceType(t);
  if (d && typeof d === 'object' && !Array.isArray(d)) {
    const keys = Object.keys(d);
    return keys.length ? ['refine', 'keyword', ['in', keys.slice().sort()]] : null;
  }
  if (d === 'jsonb' || d === 'any') return 'keyword';
  if (Array.isArray(d) && d[0] === 'list') return 'int';
  if (d === 'sequence') return 'int';
  return null;
}

// Structure reached by following segment `key` (a bare field-name, or
// null when the live segment is dynamic) into `t`.
function descendType(t, key) {
  const d = dereferenceType(t);
  if (d && typeof d === 'object' && !Array.isArray(d)) {
    return (key != null && Object.hasOwn(d, key))
           ? d[key] : 'any';
  }
  if (d === 'jsonb' || d === 'any') return d;
  if (Array.isArray(d) && d[0] === 'list') return d[1];
  if (d === 'sequence') return 'any';
  return null;
}

function walkNavType(navType, keys) {
  let t = navType;
  for (const k of keys) {
    if (t == null) return null;
    t = descendType(t, k);
  }
  return t;
}

// Ordered {position, key} of a (fn,slot) sequence's LIVE items — a
// literal keyword yields its bare name, a fn-ref / non-keyword yields
// null (dynamic; the walk treats it as an unknown level). Raw
// `position` values can have HOLES — a deleted item's slot is never
// reused — so callers must index by list ORDER, not by `position`.
function pathSegments(fnId, slotId) {
  if (!fnId || !slotId || !lookups?.bindingMap || !lookups.itemsByBinding) {
    return [];
  }
  let binding = null;
  for (const b of lookups.bindingMap.values()) {
    if (b['fn-id'] === fnId && b['slot-id'] === slotId) { binding = b; break; }
  }
  if (!binding) return [];
  const items = (lookups.itemsByBinding.get(binding.id) || []).slice()
                .sort((a, b) => Number(a.position) - Number(b.position));
  return items.map(it => {
    let key = null;
    if (!it['ref-fn-id'] && typeof it.value === 'string') {
      key = (it.value.charAt(0) === ':') ? it.value.slice(1) : it.value;
    }
    return { position: Number(it.position), key: key };
  });
}

// Just the ordered keys — the full live path, for append-type walks.
function pathSegKeys(fnId, slotId) {
  return pathSegments(fnId, slotId).map(s => s.key);
}

// nav-types entry for a (fn,slot) — the structure its items index
// into — or null when the slot isn't nav-typed.
function navTypeOf(fnId, slotId) {
  if (!fnId || !slotId || !lookups || typeof richTypes !== 'object' || !richTypes) {
    return null;
  }
  const fn = lookups.fnMap?.get(fnId);
  const slot = lookups.slotMap?.get(slotId);
  if (!fn?.name || !slot?.name) return null;
  const nav = richTypes[fn.name]?.['nav-types'];
  return (nav && nav[slot.name] != null) ? nav[slot.name] : null;
}

// Expected type of an existing nav-typed sequence item — walk the
// structure along the segments BEFORE this item. The prefix is taken
// by list ORDER (the item's index among live items), not by raw
// `position`, which may have holes from earlier deletions.
function navItemType(arg) {
  if (!arg?.['item-id']) return null;
  const navType = navTypeOf(arg['fn-id'], arg['slot-id']);
  if (navType == null) return null;
  const segs = pathSegments(arg['fn-id'], arg['slot-id']);
  const pos = Number(arg.position);
  let idx = segs.findIndex(s => s.position === pos);
  if (idx < 0) idx = segs.length;
  const prefix = segs.slice(0, idx).map(s => s.key);
  return navKeyType(walkNavType(navType, prefix));
}

// Type for the NEXT item appended to a (fn,slot) sequence:
//   undefined — not a nav-typed sequence (caller: append unconstrained)
//   null      — the live path can't be extended (caller: hide `+`)
//   <type>    — expected type for the new segment
function appendNavType(fnId, slotId) {
  const navType = navTypeOf(fnId, slotId);
  if (navType == null) return undefined;
  return navKeyType(walkNavType(navType, pathSegKeys(fnId, slotId)));
}


// Subtype check (compact, mirroring backend rules) — primitives
// only, plus jsonb / any escapes and the numeric hierarchy. Used
// for the live value-validate hint; structural types fall through
// to "ok-by-default" since the editor input is text-shaped values
// only.
const NUMERIC_SUPERS = { int: ['numeric'], float: ['numeric'] };
function primitiveSubtype(sub, sup) {
  if (sub === sup) return true;
  const sups = NUMERIC_SUPERS[sub] || [];
  return sups.some(s => primitiveSubtype(s, sup));
}


// Recursively dereference type names through richTypes so a refined
// slot like `port` (whose `args.port` value is the bare string
// `"port"`) gets expanded to its structural form
// `["refine", "int", […constraint…]]` before validation. Stops at
// the first type that's already structural OR not a known richTypes
// entry (a primitive like `int`, `text`, …).
function dereferenceType(t) {
  let cur = t;
  for (let i = 0; i < 10; i++) {
    if (typeof cur !== 'string') break;
    const entry = (typeof richTypes !== 'undefined' && richTypes) ? richTypes[cur] : null;
    if (!entry) break;
    const next = entry.return;
    if (next === undefined || next === null || next === cur) break;
    cur = next;
  }
  return cur;
}


// Run a literal value against the expected slot type. Returns
// `{ok: true|false, message}` so the caller can render a single
// status line.
function validateLiteralAgainstType(parsed, expected) {
  const expanded = dereferenceType(expected);
  const actual = classifyLiteralJS(parsed);
  if (actual === null) return { ok: true, message: '' };  // unrecognised — defer
  if (expanded === 'any' || expanded === 'jsonb' || actual === 'any' || actual === 'null') {
    return { ok: true, message: 'OK' };
  }
  expected = expanded;
  // Marker head ([:secret T] and any registered marker like [:pii T])
  // wraps an inner type; validate the LITERAL against the inner —
  // mirrors types.core marker-inner. Recognized structurally: a
  // 2-element vector whose head is a keyword-name string that is NOT
  // one of the structural constructors below.
  if (Array.isArray(expected) && expected.length === 2
      && typeof expected[0] === 'string'
      && !['union', 'refine', 'list', 'map', 'fn', 'variant',
           'tuple'].includes(expected[0])) {
    const inner = validateLiteralAgainstType(parsed, expected[1]);
    return inner.ok
      ? { ok: true, message: inner.message ? inner.message + ' [' + expected[0] + ']' : '' }
      : inner;
  }
  // Variant — desugars to a union of tagged records server-side; the
  // scalar popover can only check membership when branches are
  // primitive-ish, so try each payload branch like a union.
  if (Array.isArray(expected) && expected[0] === 'variant') {
    for (let i = 2; i < expected.length; i += 2) {
      const r = validateLiteralAgainstType(parsed, expected[i]);
      if (r.ok) return { ok: true, message: 'OK (variant branch)' };
    }
    return { ok: true, message: '' }; // structural — defer to server
  }
  // Union — try each branch, pass if any accepts.
  if (Array.isArray(expected) && expected[0] === 'union') {
    const branches = expected.slice(1);
    for (const branch of branches) {
      const r = validateLiteralAgainstType(parsed, branch);
      if (r.ok) return { ok: true, message: 'OK (matches ' + formatTypeHint(branch) + ')' };
    }
    return { ok: false,
             message: actual + ' is none of ' + branches.map(formatTypeHint).join('|') };
  }
  if (typeof expected === 'string') {
    return primitiveSubtype(actual, expected)
      ? { ok: true, message: 'OK (' + actual + ')' }
      : { ok: false, message: actual + ' is not a ' + expected };
  }
  if (Array.isArray(expected) && expected[0] === 'refine') {
    const [, base, constraint] = expected;
    if (!primitiveSubtype(actual, base)) {
      return { ok: false, message: actual + ' is not a ' + base };
    }
    const sat = refinementOK(parsed, constraint);
    if (sat === true)  return { ok: true,  message: 'OK (satisfies ' + JSON.stringify(constraint) + ')' };
    if (sat === false) return { ok: false, message: 'doesn’t satisfy ' + JSON.stringify(constraint) };
    return { ok: true, message: 'OK (' + actual + '; constraint not statically checked)' };
  }
  // Structural fn / list / record — value-edit popover only deals
  // in scalars; defer.
  return { ok: true, message: '' };
}
