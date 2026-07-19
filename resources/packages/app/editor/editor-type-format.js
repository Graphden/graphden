// Editor Type Format — the PRESENTATION half of the type helpers:
// human-readable / hint / compact-chip / short-label formatters,
// refinement-constraint text + alias resolution, and the shared
// `appendResolutionSection` DOM renderer (4-tier resolution +
// inheritance chain, consumed by the type-expand panel and the
// mismatch explainer). Split out of editor-literal-types.js, which
// keeps the RESOLUTION + keystroke-VALIDATION half
// (expectedSlotType / slotTypeProvenance / validateLiteralAgainstType
// / the nav-type walk). Loaded immediately AFTER
// editor-literal-types.js in `_editor-script-paths`.

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
