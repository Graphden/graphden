// Editor Type Format — the PRESENTATION half of the type helpers:
// human-readable / hint / compact-chip / short-label formatters,
// refinement-constraint text + alias resolution, and the shared
// `appendResolutionSection` DOM renderer (4-tier resolution +
// inheritance chain, consumed by the type-expand panel and the
// mismatch explainer). The RESOLUTION + keystroke-VALIDATION half
// (expectedSlotType / slotTypeProvenance / validateLiteralAgainstType
// / the nav-type walk) lives in editor-literal-types.js. Loaded
// immediately AFTER
// editor-literal-types.js in `_editor-script-paths`.

// Display receipts come from the ordinary local type-structure graph. These
// wrappers retain their consumers' synchronous ABI. Canonical type hints,
// identity lookup, validation and submitted values stay in native helpers.
function formatTypeHumanReadable(t) {
  return window.gdCanvasTypeStructureGraph.format(t, null).human;
}

function constraintHuman(constraint) {
  return window.gdCanvasTypeStructureGraph.constraint(constraint);
}


// Render a structural type as a compact human-readable string —
// `[refine, int, [>, 0]]` becomes `:int (> 0)`, `[fn, {…}, b]`
// becomes `(args) → b`, etc. Keeps the popover hint readable.
function formatExecutionConstraint(raw) {
  if (Array.isArray(raw) && raw[0] === 'execution-var') return 'inferred ' + raw[1];
  if (!Array.isArray(raw)) return 'execution unknown';
  if (!raw.length) return 'no proven runtime';
  return raw.map(p => p === 'browser-main' ? 'Browser' : p === 'jvm-server' ? 'JVM' : String(p)).sort().join(' + ');
}

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
      return '(' + args + ') → ' + ret + effSuffix + ' [' + formatExecutionConstraint(t[4]) + ']';
    }
    // Marker head ([:secret T], …) — wraps an inner type; render as
    // `secret text` instead of falling through to raw JSON.
    if (t.length === 2 && typeof head === 'string') {
      return head + ' ' + formatTypeHint(t[1]);
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
  return window.gdCanvasTypeStructureGraph.format(rich, flat).compact;
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
  let lo;
  let hi;
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
  return window.gdCanvasTypeStructureGraph.format(rich, null).short;
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
  const makeFnLabel = (fnName, fnId, facts = {}) => {
    const text = fnName || '(anonymous)';
    if (onNavigate && fnId) {
      const link = document.createElement('a');
      link.href = '#';
      link.className = 'type-inline-resolution-label type-inline-resolution-link';
      window.gdAppendTypeControlMark(link, '↗');
      window.gdMountTypeStructureBody(link, link, 'resolution-label', {label: text, ...facts});
      link.title = 'Open :' + text;
      link.setAttribute('aria-label', 'Open function ' + text);
      link.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        onNavigate(fnId);
      });
      return link;
    }
    const plain = document.createElement('span');
    plain.className = 'type-inline-resolution-label';
    window.gdMountTypeStructureBody(section, plain, 'resolution-label', {label: text, ...facts});
    return plain;
  };

  // Render a type column — clickable when onNavigate is provided AND
  // the type is a string that names a known type-row entity. Structural
  // arrays (`[:list T]`, `[:fn …]`) render as plain text since they
  // don't have a single fn-id to navigate to (compact form via
  // shortTypeLabel keeps the row narrow). Lets the reader jump from
  // ":user-port" in a chain to the type-row that defines it.
  const makeTypeCell = (type, facts = {}) => {
    const cell = document.createElement('span');
    cell.className = 'type-inline-resolution-type';
    const display = () => ({label: type == null ? '—' : shortTypeLabel(type), ...facts});
    const body = window.gdMountTypeStructureBody(cell, cell, 'resolution-label', display());
    window.gdCanvasTypeStructureGraph.bind(cell, () => body.update(display()));
    if (type == null) return cell;
    if (onNavigate && typeof type === 'string'
        && typeof lookups !== 'undefined' && lookups?.fnByName) {
      const entry = lookups.fnByName.get(type);
      if (entry?.id) {
        cell.classList.add('type-inline-resolution-link');
        window.gdAppendTypeControlMark(cell, '↗');
        cell.setAttribute('role', 'link');
        cell.setAttribute('tabindex', '0');
        cell.title = 'Open type :' + type;
        cell.setAttribute('aria-label', cell.title);
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
    window.gdMountTypeStructureBody(section, chainHead, 'resolution-head', {label: '', mode: 'inherited'});
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
      const facts = {mode: 'chain', multiple: multi, winner};
      window.gdMountTypeStructureBody(section, mark, 'resolution-mark', {label: '', ...facts});
      row.appendChild(mark);
      row.appendChild(makeFnLabel(link.fnName, link.fnId, facts));
      // Suffix tag — only present in multi-override scenarios. Lets the
      // reader see "this was the closer-fn-wins pick; sibling X had a
      // candidate too but lost". Single-override case stays unadorned
      // — the chain row is already self-explanatory.
      if (multi) {
        const tag = document.createElement('span');
        tag.className = 'type-inline-resolution-chain-tag';
        window.gdMountTypeStructureBody(section, tag, 'resolution-tag', {label: '', ...facts});
        row.appendChild(tag);
      }
      const overrideFn = (typeof lookups !== 'undefined'
                          && lookups?.fnMap?.get(link.overrideFnId)) || null;
      const overrideType = overrideFn ? computeSlotType(overrideFn) : null;
      row.appendChild(makeTypeCell(overrideType, facts));
      section.appendChild(row);
    });
  }

  const head = document.createElement('div');
  head.className = 'type-inline-resolution-head';
  window.gdMountTypeStructureBody(section, head, 'resolution-head', {label: '', mode: 'resolved'});
  section.appendChild(head);
  for (const tier of prov.tiers) {
    const won = tier.key === prov.winner;
    const row = document.createElement('div');
    row.className = 'type-inline-resolution-row'
                  + (won ? ' type-inline-resolution-active' : '');
    const mark = document.createElement('span');
    mark.className = 'type-inline-resolution-mark';
    const facts = {mode: 'tier', winner: won};
    window.gdMountTypeStructureBody(section, mark, 'resolution-mark', {label: '', ...facts});
    row.appendChild(mark);
    // The mechanism label and the source-fn name go in separate
    // spans so the fn-name can be a clickable link without dragging
    // the mechanism text into the click target.
    const lab = document.createElement('span');
    lab.className = 'type-inline-resolution-label';
    window.gdMountTypeStructureBody(section, lab, 'resolution-label', {label: tier.label, ...facts});
    row.appendChild(lab);
    if (tier.source?.fnName) {
      const sep = document.createElement('span');
      sep.className = 'type-inline-resolution-sep';
      window.gdMountTypeStructureBody(section, sep, 'resolution-label', {label: ' · '});
      row.appendChild(sep);
      // tier.source carries fnName today; resolve fn-id when possible
      // so the link can navigate (slot tier surfaces declaring base-fn
      // via name — look up by name in fnByName when available).
      const sourceFnId = tier.source.fnId
        || (typeof lookups !== 'undefined' && lookups?.fnByName?.get(tier.source.fnName)?.id)
        || null;
      row.appendChild(makeFnLabel(tier.source.fnName, sourceFnId, facts));
    }
    row.appendChild(makeTypeCell(tier.type, facts));
    section.appendChild(row);
  }
  host.appendChild(section);
}


// ============================================================================
// EDN type-string → JS type shape — for SERVER partials that print
// rich types as EDN text (inspector-detail's `.gd-bind-type` /
// `.gd-prov-mono`). The client re-renders those through
// `formatTypeHint` so every surface speaks ONE type notation; the raw
// EDN stays in `title=`. Subset parser: vectors, maps, keywords,
// numbers, strings, bare symbols — exactly what type printing emits.
// ============================================================================

function parseEdnTypeString(src) {
  let i = 0;
  const isWs = (c) => c === ' ' || c === ',' || c === '\n' || c === '\t';
  const skipWs = () => { while (i < src.length && isWs(src[i])) i++; };
  function readValue() {
    skipWs();
    const c = src[i];
    if (c === undefined) throw new Error('eof');
    if (c === '[') {
      i++;
      const out = [];
      for (skipWs(); src[i] !== ']';) { out.push(readValue()); skipWs(); }
      i++;
      return out;
    }
    if (c === '{') {
      i++;
      const out = {};
      for (skipWs(); src[i] !== '}';) {
        const k = readValue();
        const v = readValue();
        out[String(k)] = v;
        skipWs();
      }
      i++;
      return out;
    }
    if (c === '"') {
      i++;
      let s = '';
      while (i < src.length && src[i] !== '"') {
        if (src[i] === '\\') i++;
        s += src[i]; i++;
      }
      i++;
      return s;
    }
    // atom: keyword / number / bare symbol
    let a = '';
    while (i < src.length && !isWs(src[i]) && !'[]{}'.includes(src[i])) { a += src[i]; i++; }
    if (a.startsWith(':')) return a.slice(1);           // keyword → wire form
    if (/^-?\d+(\.\d+)?$/.test(a)) return Number(a);
    return a;                                            // bare symbol
  }
  const v = readValue();
  skipWs();
  if (i !== src.length) throw new Error('trailing');
  return v;
}

// Deep-resolve GENERATED alias names (`a-1234`) — meaningless to a
// reader — into their definitions; named aliases stay by name.
function resolveGeneratedAliases(t, depth = 0) {
  if (depth > 8) return t;
  if (typeof t === 'string' && /^a-\d+$/.test(t)
      && typeof dereferenceType === 'function') {
    const d = dereferenceType(t);
    if (d !== t && d != null) return resolveGeneratedAliases(d, depth + 1);
    return t;
  }
  if (Array.isArray(t)) return t.map((x) => resolveGeneratedAliases(x, depth + 1));
  if (t && typeof t === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(t)) out[k] = resolveGeneratedAliases(v, depth + 1);
    return out;
  }
  return t;
}

// Re-render every EDN-printed type inside `root` through
// formatTypeHint. Non-type text (parse failure) is left untouched.
function formatServerTypeTexts(root) {
  if (!root) return;
  for (const el of root.querySelectorAll('.gd-bind-type, .gd-prov-mono')) {
    const raw = (el.textContent || '').trim();
    // Obvious type shapes, plus BARE generated aliases (`a-3449`) —
    // provenance also prints `:ring-handler` (a fn ref, keep) in the
    // same mono class.
    if (!raw || !(raw.startsWith('[') || raw.startsWith('{') || /^a-\d+$/.test(raw))) continue;
    try {
      const parsed = resolveGeneratedAliases(parseEdnTypeString(raw));
      // A generated alias the CLIENT registry can't expand (they are
      // server-internal names) still must not leak an internal id —
      // an inline/unnamed type is exactly what it is.
      const pretty = formatTypeHint(parsed).replace(/\ba-\d+\b/g, 'unnamed');
      if (pretty && pretty !== raw) {
        el.title = raw;
        el.textContent = pretty;
      }
    } catch (_) { /* not a type literal — leave as printed */ }
  }
}
