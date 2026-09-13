// Editor Fn-Picker — ranking. Pure helpers behind the picker's list:
// how a candidate's WHOLE signature sits in the slot (tier), and how a
// tier's rows are grouped for reading (namespace, private-last, cap).
//
// Nothing here touches the DOM or the network, so
// tools/runtime-test/fn-picker-rank.test.js exercises it directly.
//
// The tiers mirror `crud.types-api/candidate-fit` — the server's verdict
// wins whenever it has spoken (loadTypedCandidates copies `fit` per name);
// this is the approximation for rows the server has not classified yet
// (the loaded cache before the fetch lands, or a fn outside its list).
//
//   exact     fn slot: as many free args as the slot passes per call;
//             value slot: a ready value — no free args left to bind
//   captures  more free args than the slot supplies — admissible, the
//             rest surface as free args of the binding fn
//   ignores   fewer than the slot passes (a nullary callee in a 1-arg
//             slot) — admissible by the positional rule, input dropped
//
// Admissibility itself stays the checker's call (`subtype?`, via
// /api/types/candidates + clientSubtype); ranking never widens or narrows
// the Compatible set, it orders it.

const PICKER_TIERS = ['exact', 'captures', 'ignores'];

// How many free args a callable slot passes per call; null for a value slot.
function pickerSlotArity(expected) {
  if (Array.isArray(expected) && expected[0] === 'fn') {
    const args = expected[1];
    return args && typeof args === 'object' ? Object.keys(args).length : 0;
  }
  return null;
}

function pickerFitTier(expected, arity) {
  if (expected === 'fn-ref') return 'exact';
  if (typeof arity !== 'number') return 'exact';   // unknown arity — don't demote
  const k = pickerSlotArity(expected);
  if (k === null) return arity === 0 ? 'exact' : 'captures';
  if (arity === k) return 'exact';
  return arity > k ? 'captures' : 'ignores';
}

// Header text per tier. A value slot has no "input" to ignore, so its
// vocabulary is ready-vs-needs; a callable slot's is call-shaped.
function pickerTierLabel(expected, tier) {
  const callable = pickerSlotArity(expected) !== null || expected === 'fn-ref';
  if (tier === 'exact') return callable ? 'Exact fit' : 'Ready';
  if (tier === 'captures') return callable ? 'Extra inputs' : 'Needs inputs';
  return 'Ignores the input';
}

function pickerTierTitle(expected, tier) {
  const callable = pickerSlotArity(expected) !== null;
  if (tier === 'exact') {
    return callable
      ? 'Takes exactly what the slot passes per call — nothing left to wire'
      : 'No free arguments left — binds as a finished value';
  }
  if (tier === 'captures') {
    return 'Fits, but its remaining free arguments become free arguments of your fn';
  }
  return 'Takes no argument — the value the slot passes per call is dropped';
}

// Group one tier's rows for display: same-namespace rows first, then by
// namespace path, private (`_`-prefixed) names after public ones within a
// namespace, names alphabetical. Returns `{groups, shown, total}` where
// `groups` is `[{ns, rows}]` in display order and `shown` ≤ `cap`. A
// single-namespace tier gets one group with `ns: null` — no header worth
// drawing. `q` (the typed filter, lowercased) keeps the name-match tiering
// the flat list had: exact name, name-substring, qualified-only.
function groupPickerRows(rows, opts) {
  const cap = opts?.cap || 50;
  const q = opts?.q || '';
  const nameTier = (c) => {
    if (!q) return 0;
    const n = (c.name || '').toLowerCase();
    if (n === q) return 0;
    if (n.includes(q)) return 1;
    return 2;
  };
  const isPrivate = (c) => typeof c.name === 'string' && c.name.startsWith('_');
  const nsOf = (c) => c.ns || '';
  const sorted = rows.slice().sort((a, b) => {
    if (!!a.sameNs !== !!b.sameNs) return a.sameNs ? -1 : 1;
    const t = nameTier(a) - nameTier(b);
    if (t !== 0) return t;
    const n = nsOf(a).localeCompare(nsOf(b));
    if (n !== 0) return n;
    if (isPrivate(a) !== isPrivate(b)) return isPrivate(a) ? 1 : -1;
    return (a.name || '').localeCompare(b.name || '');
  });
  const shown = sorted.slice(0, cap);
  const distinct = new Set(shown.map(nsOf));
  const groups = [];
  if (distinct.size <= 1) {
    groups.push({ ns: null, rows: shown });
  } else {
    for (const c of shown) {
      const ns = nsOf(c) || null;
      const last = groups[groups.length - 1];
      if (last && last.ns === ns) last.rows.push(c);
      else groups.push({ ns, rows: [c] });
    }
  }
  return { groups, shown: shown.length, total: rows.length };
}

// Split compatible rows into display tiers, in PICKER_TIERS order, dropping
// empty ones. Each entry: `{tier, label, title, rows}`.
function pickerTiersOf(expected, compatRows) {
  const byTier = new Map(PICKER_TIERS.map((t) => [t, []]));
  for (const c of compatRows) {
    const t = PICKER_TIERS.includes(c.fit) ? c.fit : pickerFitTier(expected, c.arity);
    byTier.get(t).push(c);
  }
  return PICKER_TIERS
    .filter((t) => byTier.get(t).length > 0)
    .map((t) => ({
      tier: t,
      label: pickerTierLabel(expected, t),
      title: pickerTierTitle(expected, t),
      rows: byTier.get(t),
    }));
}
