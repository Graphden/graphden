// Editor Literal Types - shared type-validation helpers used by inline
// edit popovers (arg-value, arg-type, free-arg-bind). All functions
// are pure JS mirrors of backend logic in graphden.types.check /
// graphden.types.core, plus a few presentation helpers.
//
// Globals consumed: `lookups` (editor-data.js), `richTypes` (set by
// editor-main.js after fetching /api/types). Loaded into the
// concatenated bundle BEFORE editor-tooltips.js.

// === Session-level cache for /api/types/compatible ============================
//
// Compatibility is determined by the type registry, which only changes
// when a fn-def is added/renamed/retyped (rare during an editing
// session). populateCompatibleTypes() in editor-edit-modes.js fans out
// ~50 parallel fetches every time a type-select popover opens; the
// fn-picker and mismatch explainer call it too. Without a cache the
// same (expected, candidate) pair re-hits the backend on every popover
// open in the same session. The cache stores the bare boolean — the
// network savings are 5–15 ms per cached pair (median).
//
// Invalidate via `clearTypesCompatibleCache()` after edits that could
// change types. NOTE: nothing currently calls it — the type-registry
// refresh path that used to (`applyGraphDataRefresh`) is no longer
// wired, so the cache lives for the whole session. Harmless while the
// registry is session-stable; re-wire the call if a mid-session retype
// starts showing stale compatibility.
const _typesCompatibleCache = new Map();

function _typesCompatibleKey(expected, candidate) {
  // Expected may be a structural array (e.g. `[":refine", ":int",
  // [":>=", 1]]`); candidate is usually a bare string. JSON-stringify
  // both for deterministic keying.
  return JSON.stringify(expected) + '\x00' + JSON.stringify(candidate);
}

async function typesCompatible(expected, candidate) {
  const key = _typesCompatibleKey(expected, candidate);
  if (_typesCompatibleCache.has(key)) {
    return _typesCompatibleCache.get(key);
  }
  let ok = false;
  try {
    const r = await fetch(API.api_types_compatible, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expected, candidate }),
    }).then((r) => r.json());
    ok = !!r.ok;
  } catch (_) {
    ok = false;
  }
  _typesCompatibleCache.set(key, ok);
  return ok;
}

function clearTypesCompatibleCache() {
  _typesCompatibleCache.clear();
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

// `[":secret", T]` predicate — true for a rich-type tagged as
// secret-labelled at the top level. Used by the inline value-form to
// switch to the path+value widget instead of the regular literal
// editor. Wire format strips the colon: `["secret", "text"]`.
function isSecretType(t) {
  return Array.isArray(t) && t[0] === 'secret';
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
  // (appendResolutionSection in editor-overlay-type-expand.js) treats
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


// Walk a literal VALUE against an EXPECTED type and collect the
// leaf-level disagreements as `[{path, expected, actual}]`. Used by
// the mismatch-explainer to point the user at the EXACT field /
// element that doesn't fit instead of just saying "list of ints
// doesn't match".
//
// Path format: dot-notation strings. `.users[2].age` reads "the age
// field of the third users element". Empty path = top-level
// disagreement.
//
// Returns `[]` for a full match. Stops walking once the structural
// form diverges (e.g. expected list, got string) — that's reported
// once at the divergence point rather than spamming.
function diffValueAgainstType(value, expected, path) {
  const out = [];
  const pathStr = path || '';
  const exp = dereferenceType(expected);
  if (exp === 'any' || exp === 'jsonb') return out;
  // Union — accept if any branch matches; report the BEST near-miss
  // (the branch with the fewest leaf disagreements) when no branch
  // accepts. Falls back to a top-level "is none of …" when all
  // branches reject.
  if (Array.isArray(exp) && exp[0] === 'union') {
    const branches = exp.slice(1);
    let bestLeaves = null;
    for (const branch of branches) {
      const leaves = diffValueAgainstType(value, branch, pathStr);
      if (leaves.length === 0) return [];
      if (bestLeaves === null || leaves.length < bestLeaves.length) {
        bestLeaves = leaves;
      }
    }
    return bestLeaves || [];
  }
  if (Array.isArray(exp) && exp[0] === 'refine') {
    const [, base, constraint] = exp;
    const baseLeaves = diffValueAgainstType(value, base, pathStr);
    if (baseLeaves.length > 0) return baseLeaves;
    const sat = refinementOK(value, constraint);
    if (sat === false) {
      out.push({ path: pathStr, expected: exp, actual: classifyLiteralJS(value) });
    }
    return out;
  }
  if (Array.isArray(exp) && exp[0] === 'list') {
    if (!Array.isArray(value)) {
      out.push({ path: pathStr, expected: exp, actual: classifyLiteralJS(value) });
      return out;
    }
    const elemType = exp[1];
    for (let i = 0; i < value.length; i += 1) {
      const sub = diffValueAgainstType(value[i], elemType, `${pathStr}[${i}]`);
      for (const leaf of sub) out.push(leaf);
    }
    return out;
  }
  if (Array.isArray(exp) && exp[0] === 'map') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      out.push({ path: pathStr, expected: exp, actual: classifyLiteralJS(value) });
      return out;
    }
    const [, _kType, vType] = exp;
    for (const [k, v] of Object.entries(value)) {
      const sub = diffValueAgainstType(v, vType, `${pathStr}.${k}`);
      for (const leaf of sub) out.push(leaf);
    }
    return out;
  }
  if (Array.isArray(exp) && exp[0] === 'tuple') {
    if (!Array.isArray(value)) {
      out.push({ path: pathStr, expected: exp, actual: classifyLiteralJS(value) });
      return out;
    }
    const elems = exp.slice(1);
    if (value.length !== elems.length) {
      out.push({ path: pathStr, expected: exp,
                 actual: `tuple of length ${value.length}` });
      return out;
    }
    for (let i = 0; i < elems.length; i += 1) {
      const sub = diffValueAgainstType(value[i], elems[i], `${pathStr}[${i}]`);
      for (const leaf of sub) out.push(leaf);
    }
    return out;
  }
  if (typeof exp === 'object' && exp !== null && !Array.isArray(exp)) {
    // Record — keyword-keyed fixed-field map.
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      out.push({ path: pathStr, expected: exp, actual: classifyLiteralJS(value) });
      return out;
    }
    for (const [k, fieldType] of Object.entries(exp)) {
      if (!(k in value)) {
        out.push({ path: `${pathStr}.${k}`, expected: fieldType,
                   actual: 'missing' });
      } else {
        const sub = diffValueAgainstType(value[k], fieldType, `${pathStr}.${k}`);
        for (const leaf of sub) out.push(leaf);
      }
    }
    return out;
  }
  // Primitive — single check at this path.
  if (typeof exp === 'string') {
    const actual = classifyLiteralJS(value);
    if (actual !== null && !primitiveSubtype(actual, exp)) {
      out.push({ path: pathStr, expected: exp, actual });
    }
    return out;
  }
  return out;
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


// Render a structural type as a NATURAL-LANGUAGE string for the
// type-explainer popover. The terse `formatTypeHint` is the
// machine-readable form (`:int (> 0)`, `[int]`); this is the
// "what does this mean?" text a non-Clojure user reads.
//
// Heuristics over a curated lookup — common refinements get
// dedicated phrases (`positive integer`, `non-empty text`); the
// long tail falls back to "X where CONSTRAINT".
function formatTypeHumanReadable(t) {
  if (t == null) return '';
  if (typeof t === 'string') return primitiveHuman(t);
  if (Array.isArray(t)) {
    const head = t[0];
    if (head === 'refine') {
      const baseHuman = formatTypeHumanReadable(t[1]);
      const friendly = refinementFriendlyName(t[1], t[2]);
      if (friendly) return friendly;
      return baseHuman + ' where ' + constraintHuman(t[2]);
    }
    if (head === 'list') {
      return 'list of ' + pluralise(formatTypeHumanReadable(t[1]));
    }
    if (head === 'map') {
      return 'map of ' + formatTypeHumanReadable(t[1]) + ' to '
           + formatTypeHumanReadable(t[2]);
    }
    if (head === 'tuple') {
      return 'tuple of (' + t.slice(1).map(formatTypeHumanReadable).join(', ') + ')';
    }
    if (head === 'union') {
      const parts = t.slice(1).map(formatTypeHumanReadable);
      if (parts.length === 2) return 'either ' + parts[0] + ' or ' + parts[1];
      return 'one of: ' + parts.join(', ');
    }
    if (head === 'fn') {
      const args = Object.entries(t[1])
        .map(([k, v]) => k + ' (' + formatTypeHumanReadable(v) + ')')
        .join(', ');
      const ret = formatTypeHumanReadable(t[2]);
      const argsPart = args.length ? 'takes ' + args + ', ' : 'takes no args, ';
      // 4th element (when present) is the slot-level effect constraint.
      // `null`/`'any'`/missing → unconstrained. `[]` → pure (no effects
      // allowed). Otherwise a list of category names — the callable's
      // effects must be a SUBSET of these.
      const effSet = t[3];
      let effSuffix = '';
      if (effSet === undefined || effSet === null || effSet === 'any') {
        effSuffix = '';
      } else if (Array.isArray(effSet) && effSet.length === 0) {
        effSuffix = '; must be pure (no effects allowed)';
      } else if (Array.isArray(effSet)) {
        effSuffix = '; allowed effects: ' + effSet.join(', ');
      }
      // Variance hint — one short line so the tooltip stays compact.
      // Args are contravariant (callable may accept WIDER inputs),
      // return is covariant (callable may produce NARROWER outputs).
      // We only mention it when the function shape is non-trivial
      // (≥ 1 arg) so the hint doesn't pollute trivial cases.
      const varianceHint = (args.length > 0)
                           ? '. Args contravariant; return covariant.'
                           : '';
      return 'function: ' + argsPart + 'returns ' + ret + effSuffix + varianceHint;
    }
    return JSON.stringify(t);
  }
  if (typeof t === 'object') {
    const fields = Object.entries(t)
      .map(([k, v]) => k + ' (' + formatTypeHumanReadable(v) + ')');
    if (fields.length === 0) return 'empty record';
    if (fields.length === 1) return 'record with ' + fields[0];
    return 'record with ' + fields.slice(0, -1).join(', ')
         + ' and ' + fields[fields.length - 1];
  }
  return String(t);
}

function primitiveHuman(name) {
  const map = {
    'int':         'integer',
    'numeric':     'number',
    'text':        'text',
    'bool':        'true/false',
    'null':        'null',
    'jsonb':       'any JSON value',
    'any':         'any value',
    'fn':          'function',
    'sequence':    'sequence',
    'uuid':        'UUID',
    'bytes':       'binary data',
    'timestamptz': 'timestamp',
    'keyword':     'keyword',
    'float':       'decimal number',
  };
  return map[name] || name;
}

// Recognise a handful of common refinements and turn them into a
// single-word phrase. Anything outside this list flows to the
// generic "INT where CONSTRAINT" fallback.
function refinementFriendlyName(base, constraint) {
  if (base !== 'int' && base !== 'numeric') return null;
  if (!Array.isArray(constraint)) return null;
  const op = constraint[0];
  const v = constraint[1];
  const baseHuman = primitiveHuman(base);
  if (op === '>'  && v === 0) return 'positive ' + baseHuman;
  if (op === '<'  && v === 0) return 'negative ' + baseHuman;
  if (op === '>=' && v === 0) return 'non-negative ' + baseHuman;
  if (op === '<=' && v === 0) return 'non-positive ' + baseHuman;
  return null;
}

function constraintHuman(c) {
  if (!Array.isArray(c)) return JSON.stringify(c);
  const op = c[0];
  if (op === 'and') {
    return c.slice(1).map(constraintHuman).join(' and ');
  }
  if (op === 'or') {
    return c.slice(1).map(constraintHuman).join(' or ');
  }
  if (op === 'in' && Array.isArray(c[1])) {
    return 'one of ' + c[1].map(String).join(', ');
  }
  if (c.length === 2) return op + ' ' + JSON.stringify(c[1]);
  return JSON.stringify(c);
}

// English-plural the head noun. We only handle the cases primitiveHuman
// emits — everything else stays singular and the surrounding phrase
// still reads OK (e.g. "list of any JSON value").
function pluralise(noun) {
  const map = {
    'integer': 'integers',
    'number': 'numbers',
    'text': 'text values',
    'true/false': 'booleans',
    'null': 'nulls',
    'UUID': 'UUIDs',
    'timestamp': 'timestamps',
    'keyword': 'keywords',
    'function': 'functions',
    'sequence': 'sequences',
    'decimal number': 'decimal numbers',
    'binary data': 'binary blobs',
  };
  return map[noun] || noun;
}


// Render a structural type as a compact human-readable string —
// `[refine, int, [>, 0]]` becomes `:int (> 0)`, `[fn, {…}, b]`
// becomes `(args) → b`, etc. Keeps the popover hint readable.
function formatTypeHint(t) {
  if (t == null) return '';
  if (typeof t === 'string') return t;
  if (Array.isArray(t)) {
    const head = t[0];
    if (head === 'refine') {
      // Constraint string comes from the shared helper (used by chip
      // stacking too); prefix the base with `:` for the hint form.
      const cText = refinementConstraintText(t);
      return ':' + t[1] + (cText ? ' ' + cText : '');
    }
    if (head === 'list')     return '[' + formatTypeHint(t[1]) + ']';
    if (head === 'map')      return '{' + formatTypeHint(t[1]) + ' → '
                                    + formatTypeHint(t[2]) + '}';
    if (head === 'tuple')    return '(' + t.slice(1).map(formatTypeHint).join(', ') + ')';
    if (head === 'union')    return t.slice(1).map(formatTypeHint).join('|');
    if (head === 'fn') {
      const args = Object.entries(t[1]).map(([k, v]) => k + ':' + formatTypeHint(v)).join(', ');
      const ret = formatTypeHint(t[2]);
      // 4th element (when present) is the slot-level effect constraint
      // — append it compactly so the chip title still shows the
      // contract. `[]` = pure, `[db, env]` = subset of those.
      const eff = t[3];
      let effSuffix = '';
      if (eff !== undefined && eff !== null && eff !== 'any') {
        if (Array.isArray(eff)) {
          effSuffix = eff.length === 0 ? ' pure' : ' eff:' + eff.join(',');
        }
      }
      return '(' + args + ') → ' + ret + effSuffix;
    }
    return JSON.stringify(t);
  }
  if (typeof t === 'object') {
    const fields = Object.entries(t).map(([k, v]) => k + ':' + formatTypeHint(v)).join(', ');
    return '{' + fields + '}';
  }
  return String(t);
}


// Pick a type-chip's visible text. Prefer a compact rendering of the
// rich structural type; fall back to the flat storage primitive.
//   - type-var (bare lowercase letter, optionally `-N` from freshening)
//     → `'a`  (the apostrophe distinguishes "polymorphic" from `any`)
//   - `[:list T]` → `[T]`  (recursive)
//   - `[:refine B …]` → `B`  (constraint elided — full form in title)
//   - `[:union …]` → `T1|T2` (truncated past 12 chars)
//   - record / fn-type → flat primitive (`jsonb` / `fn`) — too wide
//                        to render legibly in a chip
// Canonical home for chip text — consumed by editor-overlay-arg.js,
// editor-fn-picker.js and editor-layout.js.
function compactTypeChipText(rich, flat) {
  if (rich == null) return flat;
  if (typeof rich === 'string') {
    // Type-var: lowercase letter, optionally "<letter>-<digits>".
    if (/^[a-z](-\d+)?$/.test(rich) && rich !== flat) return "'" + rich.charAt(0);
    return rich;
  }
  if (Array.isArray(rich)) {
    const head = rich[0];
    if (head === 'list')   return '[' + compactTypeChipText(rich[1], 'any') + ']';
    if (head === 'map') {
      const joined = '{' + compactTypeChipText(rich[1], 'any') + '→'
                   + compactTypeChipText(rich[2], 'any') + '}';
      return joined.length > 14 ? flat : joined;
    }
    if (head === 'tuple') {
      const joined = '(' + rich.slice(1)
                       .map(t => compactTypeChipText(t, 'any')).join(',') + ')';
      return joined.length > 16 ? flat : joined;
    }
    if (head === 'refine') return compactTypeChipText(rich[1], flat);
    if (head === 'union') {
      const parts = rich.slice(1).map(t => compactTypeChipText(t, 'any'));
      const joined = parts.join('|');
      return joined.length > 12 ? flat : joined;
    }
    if (head === 'fn') {
      // Two-tier rendering:
      //  - full `(arg:T,arg:T)→R` if it fits in 32 chars (room for one
      //    named shape like `:ring-request-shape` in the arg position
      //    + a short return-type alias)
      //  - terse `(N)→ret-prefix` otherwise (e.g. `(request)→ring-r…`)
      // Falls through to `flat` ("fn") only when even the terse form
      // is too wide.
      const argEntries = Object.entries(rich[1] || {});
      const ret = compactTypeChipText(rich[2], 'any');
      const argsFull = argEntries
        .map(([k, v]) => k + ':' + compactTypeChipText(v, 'any'))
        .join(',');
      const full = '(' + argsFull + ')→' + ret;
      if (full.length <= 32) return full;
      const argsTerse = argEntries.length === 0 ? '()'
                       : argEntries.length === 1 ? '(' + argEntries[0][0].slice(0, 8) + ')'
                       : '(' + argEntries.length + ')';
      const retTerse = (typeof rich[2] === 'string')
                       ? (rich[2].length > 9 ? rich[2].slice(0, 8) + '…' : rich[2])
                       : '*';
      const terse = argsTerse + '→' + retTerse;
      return terse.length > 32 ? flat : terse;
    }
  }
  return flat;
}


// Format a refinement's constraint as a compact chip-second-line
// string: `[:refine :int [:> 0]]` → `> 0`. Mirrors formatTypeHint's
// refine branch but returns only the constraint half so a chip can
// stack base over constraint. Returns null when `rich` isn't a
// refinement vector.
//
// Common shapes get human-friendly renderings. Tooltip via
// formatTypeHumanReadable still spells everything out verbosely.
//
//   [':and' [:>= lo] [:<= hi]]    → `lo..hi`   (closed range)
//   [':and' [:>  lo] [:<  hi]]    → `lo<..<hi` (open range)
//   [:>= n]                       → `≥n`
//   [:<= n]                       → `≤n`
//   [:>  n]                       → `>n`
//   [:<  n]                       → `<n`
//   [:=  v]                       → `=v`
//   [:not= v]                     → `≠v`
//   [:in [a b c]]                 → `(a|b|c)` (with `:` prefix when base is :keyword)
//   anything else                 → `(<op> <arg…>)` fallback (legacy LISPy join)
function refinementConstraintText(rich) {
  if (!Array.isArray(rich) || rich[0] !== 'refine') return null;
  const c = rich[2];
  if (!Array.isArray(c)) return null;
  const fmtAtom = (v) => {
    if (Array.isArray(v)) return refinementAtomText(v);
    return String(v);
  };
  // :in handled distinctly (different shape: c[1] is the member array).
  if (c[0] === 'in' && Array.isArray(c[1])) {
    const isKw = rich[1] === 'keyword';
    const ms = c[1].map((m) => (isKw && String(m).charAt(0) !== ':') ? ':' + m : String(m));
    return '(' + ms.join('|') + ')';
  }
  // :and with two comparison children that bracket a range — fold into
  // `lo..hi` (or `lo<..<hi` for strict-on-both-sides). Other :and
  // shapes fall through to per-atom rendering.
  if (c[0] === 'and' && c.length === 3
      && Array.isArray(c[1]) && Array.isArray(c[2])) {
    const r = rangeFold(c[1], c[2]);
    if (r) return r;
  }
  // Top-level atom: render directly without parens.
  return refinementAtomText(c);
}

// Compact text for a single constraint atom (`[op, arg]`) — shared
// with refinementConstraintText for fold-into-range fallback.
function refinementAtomText(node) {
  if (!Array.isArray(node)) return String(node);
  const op = node[0];
  const a = node[1];
  if (op === '>=') return '≥' + a;
  if (op === '<=') return '≤' + a;
  if (op === '>')  return '>' + a;
  if (op === '<')  return '<' + a;
  if (op === '=')  return '=' + a;
  if (op === 'not=') return '≠' + a;
  if (op === 'and' || op === 'or') {
    const sep = op === 'and' ? ' & ' : ' | ';
    return '(' + node.slice(1).map(refinementAtomText).join(sep) + ')';
  }
  // Unknown shape — fall back to legacy LISPy join so we never blank.
  return '(' + node.join(' ') + ')';
}

// `[':and' [:>= lo] [:<= hi]]` → `lo..hi`. Accepts both orderings
// of the two children; returns null if either side isn't a numeric
// comparison.
function rangeFold(left, right) {
  const op = (n) => Array.isArray(n) ? n[0] : null;
  const arg = (n) => Array.isArray(n) ? n[1] : null;
  const isLo = (n) => op(n) === '>=' || op(n) === '>';
  const isHi = (n) => op(n) === '<=' || op(n) === '<';
  let lo, hi;
  if (isLo(left) && isHi(right)) { lo = left; hi = right; }
  else if (isLo(right) && isHi(left)) { lo = right; hi = left; }
  else return null;
  const loArg = arg(lo);
  const hiArg = arg(hi);
  if (typeof loArg !== 'number' || typeof hiArg !== 'number') return null;
  const loStrict = op(lo) === '>';
  const hiStrict = op(hi) === '<';
  return loArg + (loStrict ? '<..' : '..') + (hiStrict ? '<' : '') + hiArg;
}


// Lift a named refinement alias (e.g. `'non-negative-int'`) to its
// structural body (`['refine', 'int', ['>=', 0]]`) by looking up the
// rich-types snapshot. Returns the array form when the alias resolves
// to a refinement; null otherwise (alias not registered or names a
// non-refinement type).
function resolveRefinementAlias(rich) {
  if (typeof rich !== 'string') return null;
  if (typeof richTypes !== 'object' || richTypes == null) return null;
  const entry = richTypes[rich];
  const r = entry?.return;
  if (Array.isArray(r) && r[0] === 'refine') return r;
  return null;
}


// === Provenance rendering (shared DOM-builder) ===
//
// Lives in literal-types.js so the three callers — inline-expand
// panel, ↳ provenance popover, mismatch-explainer — all see it
// regardless of bundle order (literal-types loads first).

// Tiny compact label for a rich type, used both by the inline-expand
// mini-chips and by the provenance row "type at this tier" column.
// Stays short: refinements show their base name (constraint elided),
// lists / maps / tuples render with structural brackets, functions
// and unions collapse to a single word. Pure (no globals).
function shortTypeLabel(rich) {
  if (rich == null) return 'any';
  if (typeof rich === 'string') return rich;
  if (Array.isArray(rich)) {
    const head = rich[0];
    if (head === 'refine') return shortTypeLabel(rich[1]);
    if (head === 'list')   return '[' + shortTypeLabel(rich[1]) + ']';
    if (head === 'union')  return 'union';
    if (head === 'fn')     return 'fn';
    if (head === 'map')    return '{' + shortTypeLabel(rich[1]) + '→'
                                  + shortTypeLabel(rich[2]) + '}';
    if (head === 'tuple')  return '(' + rich.slice(1).map(shortTypeLabel).join(',')
                                  + ')';
    return 'type';
  }
  if (typeof rich === 'object') return 'record';
  return String(rich);
}


// Render the type-resolution chain from `slotTypeProvenance`.
//
// Two parts:
//   1. "Inherited via" — for each ancestor in the inheritance chain
//      that contributes a type-override binding, a small row naming
//      the ancestor + the override it carries. Closer-wins order.
//      Skipped when the chain is empty (no inherited overrides).
//   2. "Resolved via" — the 4-tier priority chain (override → unified
//      → ref-return → slot). Each row labels the SOURCE fn (the
//      ancestor or ref that contributed it) so the user can answer
//      "where did this type come from?" by reading the name. Winning
//      tier is marked with ✓.
//
// Optional `opts.onNavigate(fnId)` — when supplied, each ancestor /
// source fn-name renders as a clickable link calling this callback.
// The provenance-popover entry point passes `selectFn` here so the
// chain becomes a navigable "open the fn that pinned this constraint"
// breadcrumb. When omitted, names render as plain text (the in-panel
// embedding stays read-only).
function appendResolutionSection(host, prov, opts) {
  const onNavigate = opts && typeof opts.onNavigate === 'function'
                     ? opts.onNavigate : null;
  const section = document.createElement('div');
  section.className = 'type-inline-resolution';

  // Render a fn-name span — clickable (anchor-like) when onNavigate is
  // provided and fnId is non-null, plain span otherwise.
  const makeFnLabel = (fnName, fnId) => {
    const text = fnName || '(anonymous)';
    if (onNavigate && fnId) {
      const link = document.createElement('a');
      link.href = '#';
      link.className = 'type-inline-resolution-label type-inline-resolution-link';
      link.textContent = text;
      link.title = 'Open :' + text;
      link.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        onNavigate(fnId);
      });
      return link;
    }
    const plain = document.createElement('span');
    plain.className = 'type-inline-resolution-label';
    plain.textContent = text;
    return plain;
  };

  // Render a type column — clickable when onNavigate is provided AND
  // the type is a string that names a known type-row entity. Structural
  // arrays (`[:list T]`, `[:fn …]`) render as plain text since they
  // don't have a single fn-id to navigate to (compact form via
  // shortTypeLabel keeps the row narrow). Lets the reader jump from
  // ":user-port" in a chain to the type-row that defines it.
  const makeTypeCell = (type) => {
    const cell = document.createElement('span');
    cell.className = 'type-inline-resolution-type';
    if (type == null) { cell.textContent = '—'; return cell; }
    const label = shortTypeLabel(type);
    cell.textContent = label;
    if (onNavigate && typeof type === 'string'
        && typeof lookups !== 'undefined' && lookups?.fnByName) {
      const entry = lookups.fnByName.get(type);
      if (entry?.id) {
        cell.classList.add('type-inline-resolution-link');
        cell.setAttribute('role', 'link');
        cell.setAttribute('tabindex', '0');
        cell.title = 'Open type :' + type;
        cell.style.cursor = 'pointer';
        const navigate = (e) => {
          e.preventDefault();
          e.stopPropagation();
          onNavigate(entry.id);
        };
        cell.addEventListener('click', navigate);
        cell.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' || e.key === ' ') navigate(e);
        });
      }
    }
    return cell;
  };

  // Inheritance chain — multi-hop narrowing path. Render ONLY when
  // there's something to say (>0 entries); otherwise the simple
  // 4-tier list speaks for itself.
  //
  // The chain comes back in closer-first order from
  // `findBindingOverrideChain`. Backend resolves multi-parent override
  // conflicts by closer-fn-wins, so the FIRST entry is the override
  // actually applied; subsequent entries are siblings / farther
  // ancestors whose overrides exist but were shadowed. When there are
  // ≥ 2 candidates we mark the winner with ✓ (chosen) and the rest
  // with "(also by)" so the user can see which parent's narrowing was
  // selected and what the alternatives were — otherwise the closer-
  // wins decision is invisible.
  if (prov.inheritanceChain && prov.inheritanceChain.length > 0) {
    const chainHead = document.createElement('div');
    chainHead.className = 'type-inline-resolution-head';
    chainHead.textContent = 'Inherited via';
    section.appendChild(chainHead);
    const multi = prov.inheritanceChain.length >= 2;
    prov.inheritanceChain.forEach((link, idx) => {
      const winner = idx === 0;
      const row = document.createElement('div');
      row.className = 'type-inline-resolution-row type-inline-resolution-chain-link'
                    + (winner && multi ? ' type-inline-resolution-chain-winner' : '')
                    + (!winner && multi ? ' type-inline-resolution-chain-also'  : '');
      const mark = document.createElement('span');
      mark.className = 'type-inline-resolution-mark';
      mark.textContent = (multi && winner) ? '✓' : '↳';
      row.appendChild(mark);
      row.appendChild(makeFnLabel(link.fnName, link.fnId));
      // Suffix tag — only present in multi-override scenarios. Lets the
      // reader see "this was the closer-fn-wins pick; sibling X had a
      // candidate too but lost". Single-override case stays unadorned
      // — the chain row is already self-explanatory.
      if (multi) {
        const tag = document.createElement('span');
        tag.className = 'type-inline-resolution-chain-tag';
        tag.textContent = winner ? '(chosen)' : '(also by)';
        row.appendChild(tag);
      }
      const overrideFn = (typeof lookups !== 'undefined'
                          && lookups?.fnMap?.get(link.overrideFnId)) || null;
      const overrideType = overrideFn ? computeSlotType(overrideFn) : null;
      row.appendChild(makeTypeCell(overrideType));
      section.appendChild(row);
    });
  }

  const head = document.createElement('div');
  head.className = 'type-inline-resolution-head';
  head.textContent = 'Resolved via';
  section.appendChild(head);
  for (const tier of prov.tiers) {
    const won = tier.key === prov.winner;
    const row = document.createElement('div');
    row.className = 'type-inline-resolution-row'
                  + (won ? ' type-inline-resolution-active' : '');
    const mark = document.createElement('span');
    mark.className = 'type-inline-resolution-mark';
    mark.textContent = won ? '✓' : '·';
    row.appendChild(mark);
    // The mechanism label and the source-fn name go in separate
    // spans so the fn-name can be a clickable link without dragging
    // the mechanism text into the click target.
    const lab = document.createElement('span');
    lab.className = 'type-inline-resolution-label';
    lab.textContent = tier.label;
    row.appendChild(lab);
    if (tier.source?.fnName) {
      const sep = document.createElement('span');
      sep.className = 'type-inline-resolution-sep';
      sep.textContent = ' · ';
      row.appendChild(sep);
      // tier.source carries fnName today; resolve fn-id when possible
      // so the link can navigate (slot tier surfaces declaring base-fn
      // via name — look up by name in fnByName when available).
      const sourceFnId = tier.source.fnId
        || (typeof lookups !== 'undefined' && lookups?.fnByName?.get(tier.source.fnName)?.id)
        || null;
      row.appendChild(makeFnLabel(tier.source.fnName, sourceFnId));
    }
    row.appendChild(makeTypeCell(tier.type));
    section.appendChild(row);
  }
  host.appendChild(section);
}
