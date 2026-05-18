// Editor Literal Types - shared type-validation helpers used by inline
// edit popovers (arg-value, arg-type, free-arg-bind). All functions
// are pure JS mirrors of backend logic in graphden.types.check /
// graphden.types.core, plus a few presentation helpers.
//
// Globals consumed: `lookups` (editor-data.js), `richTypes` (set by
// editor-cytoscape.js after fetching /api/types). Loaded into the
// concatenated bundle BEFORE editor-tooltips.js.

// Resolve an arg row's expected type from its slot. The arg row
// (synth-shape from the layout pipeline) carries `:slot-id` directly
// — look up the slot, get its type-fn-id, return either:
//   - the fn-row's `richTypes[name].return` structural form (for
//     refinements / lists / records), or
//   - the fn's name as a primitive keyword (for `:int`, `:text`, …).
// Used by the value-edit popover to show "Expected: <type>".
function expectedSlotType(arg) {
  if (!arg || !lookups || !lookups.slotMap || !lookups.fnMap) return null;
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
  if (!slot || !slot['type-fn-id']) return null;
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
  // For binding-list-items the slot's type is `[:list T]` / `:sequence`
  // but the ITEM-level expected is `T` (the element type). Without
  // this unfold a literal `:headers` keyword bound into a
  // `[:list :keyword]`-typed slot would mismatch against `:sequence`
  // and render with a red ring. Detect list-items by `:item-id` —
  // that field is the binding-list-item's row id.
  if (arg['item-id']) {
    const elemType = listElementType(slotType, tfn);
    if (elemType !== null) return elemType;
  }
  return slotType;
}

// Companion to `expectedSlotType`: reports HOW a slot's effective type
// resolved — the 4-tier priority chain (binding type-override →
// backward-unified slot-type → bound-fn return-type → slot
// declaration), the type each tier contributes (null when the tier
// doesn't apply), and which tier won. Returns null for list-item rows
// (their type comes from nav / element logic, not the slot chain) and
// for args that don't resolve. The editor's inline-expand panel
// renders this as a "Resolved via" section.
function slotTypeProvenance(arg) {
  if (!arg || !lookups || !lookups.slotMap || !lookups.fnMap) return null;
  if (arg['item-id']) return null;
  const slotId = arg['slot-id'];
  if (!slotId) return null;
  const slot = lookups.slotMap.get(slotId);
  if (!slot || !slot['type-fn-id']) return null;
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
  const tiers = [
    { key: 'override', label: 'Binding type-override',
      type: typeOfFn(overrideFnId) },
    { key: 'unified', label: 'Backward-unified return type',
      type: unifiedType },
    { key: 'ref-return', label: 'Bound fn return type',
      type: typeOfFn(refFn?.['return-type-fn-id']) },
    { key: 'slot', label: 'Slot declaration',
      type: typeOfFn(slot['type-fn-id']) },
  ];
  const winner = tiers.find((t) => t.type != null);
  return { winner: winner ? winner.key : null, tiers };
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
    return (key != null && Object.prototype.hasOwnProperty.call(d, key))
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
  if (!fnId || !slotId || !lookups || !lookups.bindingMap || !lookups.itemsByBinding) {
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
  if (!fn || !fn.name || !slot || !slot.name) return null;
  const nav = richTypes[fn.name]?.['nav-types'];
  return (nav && nav[slot.name] != null) ? nav[slot.name] : null;
}

// Expected type of an existing nav-typed sequence item — walk the
// structure along the segments BEFORE this item. The prefix is taken
// by list ORDER (the item's index among live items), not by raw
// `position`, which may have holes from earlier deletions.
function navItemType(arg) {
  if (!arg || !arg['item-id']) return null;
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
      return 'function: ' + argsPart + 'returns ' + ret;
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
      const c = t[2];
      // Closed enum — render the member set, not the raw constraint.
      if (Array.isArray(c) && c[0] === 'in' && Array.isArray(c[1])) {
        const isKw = t[1] === 'keyword';
        const ms = c[1].map(m => (isKw && String(m).charAt(0) !== ':')
                                  ? ':' + m : String(m));
        return ':' + t[1] + ' (' + ms.join('|') + ')';
      }
      return ':' + t[1] + ' (' + c.join(' ') + ')';
    }
    if (head === 'list')     return '[' + formatTypeHint(t[1]) + ']';
    if (head === 'map')      return '{' + formatTypeHint(t[1]) + ' → '
                                    + formatTypeHint(t[2]) + '}';
    if (head === 'tuple')    return '(' + t.slice(1).map(formatTypeHint).join(', ') + ')';
    if (head === 'union')    return t.slice(1).map(formatTypeHint).join('|');
    if (head === 'fn') {
      const args = Object.entries(t[1]).map(([k, v]) => k + ':' + formatTypeHint(v)).join(', ');
      return '(' + args + ') → ' + formatTypeHint(t[2]);
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
      // is too wide. Pre-fix the chip always read "fn" — uninformative.
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
