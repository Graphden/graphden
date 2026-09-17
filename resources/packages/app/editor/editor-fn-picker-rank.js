// Editor Fn-Picker — ranking + arrangement. Pure helpers behind the
// picker's list: how a candidate's WHOLE signature sits in the slot (its
// fit tier), and how the candidates are laid out for reading — the
// Explorer's search shape (an "Exact match" block, then namespace groups)
// with the type verdict as a per-row mark, never as a section that hides
// what the reader typed.
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
//
// WHY THIS SHAPE (2026-09-16, a reader on lesson 15 typing `map`): the
// previous list was an accordion — Compatible / Other — with the
// compatible half cut into collapsible tiers (Ready / Needs inputs /
// Ignores). Typing `map` put six test fns whose names merely contain
// "map" under "Ready" ABOVE `core.hof.map` (which sat first in "Needs
// inputs", one header down) — the exact-name hit the reader typed. And
// the accordion folded one section when the other opened, so "Other"
// seemed to swallow "Compatible". Now: an exact-name match is always the
// first row; a typed filter shows EVERY name match, compatible or not
// (the incompatible ones dimmed, with the explainer a click away); tiers
// are a sort order plus a small chip, not a fold; namespaces group the
// rows the way the Explorer's tree does, and only the BROWSE view (no
// filter) collapses groups — because 900 rows are not something to scroll.

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

// Chip text per tier. A value slot has no "input" to ignore, so its
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

// The candidate's tier: the server's verdict when it spoke, else the local
// arity approximation.
function pickerTierOf(expected, c) {
  return PICKER_TIERS.includes(c.fit) ? c.fit : pickerFitTier(expected, c.arity);
}

// A `tests` namespace segment marks the platform's own test fns — real fns,
// but rarely what a slot wants; they sort after everything else.
function pickerIsTestNs(ns) {
  return typeof ns === 'string' && ns.split('.').includes('tests');
}

// Arrange candidates for display.
//
//   candidates  [{name, qualified, ns, sameNs, compatible, fit, arity, …}]
//   opts.q      the typed filter, lowercased, `/`→`.` (empty = browse)
//   opts.expected  the slot type (null = untyped picker: no verdicts)
//   opts.openGroups   Set of namespace paths the reader toggled OPEN
//   opts.closedGroups Set of namespace paths the reader toggled CLOSED
//   opts.showOther  browse mode: also list the incompatible rows (dimmed)
//   opts.cap    most rows rendered in total (default 120)
//   opts.autoOpenUnder  browse mode: when the row total is at or under
//               this, every group starts open (default 60)
//
// Returns `{exact, groups, shown, total, hiddenOther}`:
//   exact   rows whose bare name IS the query (query mode only) — the
//           Explorer's "Exact match" block; compatible first, then
//           non-test before test, public before private, alphabetical
//   groups  [{ns, rows, open, compat, other, truncated}] in display order:
//           the owner's own namespace first, then alphabetical, `tests`
//           namespaces last; rows within a group: compatible before
//           incompatible, then by tier (exact → captures → ignores),
//           public before private, alphabetical. In query mode every group
//           is open. In browse mode a group is open when the reader toggled
//           it, else when the whole list is small (≤ autoOpenUnder) or it is
//           the owner's own namespace.
//   shown / total  rows rendered vs rows that matched (the cap)
//   hiddenOther  browse mode: incompatible rows left out because
//           `showOther` is off — the count the toggle advertises
function pickerArrange(candidates, opts) {
  const q = opts?.q || '';
  const expected = opts?.expected || null;
  const openGroups = opts?.openGroups || new Set();
  const closedGroups = opts?.closedGroups || new Set();
  const showOther = !!opts?.showOther;
  const cap = opts?.cap || 120;
  const autoOpenUnder = (typeof opts?.autoOpenUnder === 'number') ? opts.autoOpenUnder : 60;
  const typed = !!expected;
  const isPrivate = (c) => typeof c.name === 'string' && c.name.startsWith('_');
  const nsOf = (c) => c.ns || '';
  const isCompat = (c) => !typed || c.compatible !== false;
  const tierRank = (c) => (typed && isCompat(c)) ? PICKER_TIERS.indexOf(pickerTierOf(expected, c)) : 0;
  const rowOrder = (a, b) =>
    (Number(!isCompat(a)) - Number(!isCompat(b)))
    || (tierRank(a) - tierRank(b))
    || (Number(isPrivate(a)) - Number(isPrivate(b)))
    || (a.name || '').localeCompare(b.name || '');

  let matched = candidates;
  if (q) {
    matched = candidates.filter((c) => (c.qualified || '').toLowerCase().includes(q)
                                     || (c.name || '').toLowerCase().includes(q));
  }
  let hiddenOther = 0;
  if (!q && typed && !showOther) {
    const before = matched.length;
    matched = matched.filter(isCompat);
    hiddenOther = before - matched.length;
  }

  // Exact-name hits leave the groups for the block on top (query mode).
  const exact = [];
  const rest = [];
  for (const c of matched) {
    if (q && (c.name || '').toLowerCase() === q) exact.push(c);
    else rest.push(c);
  }
  exact.sort((a, b) =>
    (Number(!isCompat(a)) - Number(!isCompat(b)))
    || (Number(pickerIsTestNs(nsOf(a))) - Number(pickerIsTestNs(nsOf(b))))
    || (Number(isPrivate(a)) - Number(isPrivate(b)))
    || (a.qualified || '').localeCompare(b.qualified || ''));

  const byNs = new Map();
  for (const c of rest) {
    const ns = nsOf(c);
    if (!byNs.has(ns)) byNs.set(ns, []);
    byNs.get(ns).push(c);
  }
  const nsOrder = (a, b) => {
    const [na, ra] = a;
    const [nb, rb] = b;
    const sameA = ra.some((c) => c.sameNs) ? 0 : 1;
    const sameB = rb.some((c) => c.sameNs) ? 0 : 1;
    if (sameA !== sameB) return sameA - sameB;
    const tA = pickerIsTestNs(na) ? 1 : 0;
    const tB = pickerIsTestNs(nb) ? 1 : 0;
    if (tA !== tB) return tA - tB;
    return na.localeCompare(nb);
  };
  const groups = [...byNs.entries()].sort(nsOrder).map(([ns, rows]) => {
    rows.sort(rowOrder);
    const compat = typed ? rows.filter(isCompat).length : rows.length;
    return { ns: ns || null, rows, compat, other: rows.length - compat, open: true, truncated: false };
  });

  const total = exact.length + rest.length;
  if (!q) {
    const smallList = total <= autoOpenUnder;
    for (const g of groups) {
      const key = g.ns || '';
      if (openGroups.has(key)) g.open = true;
      else if (closedGroups.has(key)) g.open = false;
      else g.open = smallList || g.rows.some((c) => c.sameNs);
    }
  }

  // Cap what is rendered (the exact block + rows inside open groups),
  // group by group, so a runaway filter ("a") stays a readable list.
  let budget = cap;
  const exactShown = exact.slice(0, Math.min(exact.length, budget));
  budget -= exactShown.length;
  let shown = exactShown.length;
  for (const g of groups) {
    if (!g.open) continue;
    if (g.rows.length > budget) { g.rows = g.rows.slice(0, Math.max(0, budget)); g.truncated = true; }
    budget -= g.rows.length;
    shown += g.rows.length;
  }
  return { exact: exactShown, groups, shown, total, hiddenOther };
}
