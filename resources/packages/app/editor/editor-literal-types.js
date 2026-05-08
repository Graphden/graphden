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
  const slot = lookups.slotMap.get(slotId);
  if (!slot || !slot['type-fn-id']) return null;
  const tfn = lookups.fnMap.get(slot['type-fn-id']);
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


// Resolve the structural type of a slot's type-fn row, preferring the
// rich aliased form when one is registered. Pulled out of
// `expectedSlotType` so list-item lookup can reuse the same logic
// for the slot AND for the unfolded element type.
function computeSlotType(tfn) {
  if (!tfn) return null;
  // Anonymous fn-type rows (inline `[:fn args ret]` slot
  // declarations) have nil `:name` but their structural shape lives
  // on `:constraint`. Recover it directly so the chip / explainer
  // / mismatch logic see `[fn, {…}, ret]` rather than the flat
  // fallback "fn" / "jsonb".
  const c = tfn.constraint;
  if (Array.isArray(c) && c[0] === 'fn') return c;
  if (!tfn.name) return null;
  const rich = (typeof richTypes !== 'undefined' && richTypes) ? richTypes[tfn.name] : null;
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
    default:     return 'unknown';
  }
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
    if (head === 'refine')   return ':' + t[1] + ' (' + t[2].join(' ') + ')';
    if (head === 'list')     return '[' + formatTypeHint(t[1]) + ']';
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
