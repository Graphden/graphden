// Editor COMPARE MODE (diff v2) — THE diff surface. Pick a branch to
// compare the CURRENT branch against (the Δ on a branch-popover row,
// persisted per browser like the branch choice) and the whole editor
// annotates itself: the Explorer marks every changed fn (+ / − / ±
// with counts aggregated onto namespace rows) and gains a lens bar
// filtering by change type, opening a changed fn rings its changed
// args on the canvas (editor-overlay-arg.js consults
// `gdDiffSlotsForFn`) and shows an inspector diff panel (old → new
// fields + anchored 💬 threads), and a "Δ vs <branch>" chip next to
// the branch chip shows the mode is on (click it for the review
// cockpit — Review & comments / propose / merge — × to exit).
//
// Data: GET /api/branches/:current/diff-view?against=<other> — the
// grouped `:diff-branches-view` payload. Direction: target = the
// branch you are ON, source = the compared one; so `added-in-target`
// = "added here", `added-in-source` = "only on <other>" (missing
// here), `modified` = differs. Reads stay on the current branch —
// compare mode changes ANNOTATIONS only, never data routing, so you
// keep working (editing, running) with the lens on.
//
// The Explorer decoration + lens bar is
// editor-diff-sidebar.js, the inspector panel + card marks are
// editor-diff-inspector.js, the Δ chip + its menu is editor-diff-chip.js
// (all three load before this file). This file is the MODE itself: state,
// lens toggles, classification helpers, the diff-view fetch, effect deltas,
// enter / exit / boot, and the window exports.

const GD_DIFF_MODE_KEY = 'graphden.diffAgainst';
// The TYPE LENS — which change kinds the annotations show, and whether
// cosmetic-only edits (name / description fields alone — nothing that
// affects behaviour) count at all. Persisted beside the branch choice.
const GD_DIFF_LENS_KEY = 'graphden.diffLens';
let _gdDiffLens = { added: true, missing: true, modified: true,
                    inside: true, substantiveOnly: false, effectsOnly: false,
                    changedOnly: false, notes: true };
try {
  const raw = JSON.parse(localStorage.getItem(GD_DIFF_LENS_KEY) || 'null');
  if (raw && typeof raw === 'object') _gdDiffLens = Object.assign(_gdDiffLens, raw);
} catch (_) { /* malformed pref — defaults */ }

function gdDiffLens() { return Object.assign({}, _gdDiffLens); }

function gdDiffLensFiltering() {
  const l = _gdDiffLens;
  return !l.added || !l.missing || !l.modified || l.substantiveOnly
    || l.effectsOnly;
  // (changedOnly narrows what the TREE shows, but hides nothing that
  // changed — it is not a "some changes are hidden" state.)
}

function gdDiffSetLens(patch) {
  _gdDiffLens = Object.assign({}, _gdDiffLens, patch || {});
  try { localStorage.setItem(GD_DIFF_LENS_KEY, JSON.stringify(_gdDiffLens)); } catch (_) {}
  gdDiffModeDecorateSidebar();
  gdDiffModeRenderChip();
  gdDiffModeAnnounceLens();
  // Re-ring the open graph under the new lens.
  if (typeof selectFn === 'function' && typeof selectedFnId !== 'undefined'
      && selectedFnId) selectFn(selectedFnId);
}

function gdDiffModeAnnounceLens() {
  if (typeof gdAnnounce !== 'function' || !_gdDiffMode) return;
  const l = _gdDiffLens;
  gdAnnounce('Diff lens: '
    + [l.added ? 'added' : null, l.modified ? 'modified' : null,
       l.missing ? 'only-there' : null].filter(Boolean).join(', ')
    + (l.inside ? '' : ', changed-inside marks off')
    + (l.changedOnly ? ', only changed rows' : '')
    + (l.substantiveOnly ? ', substantive only' : '')
    + (l.effectsOnly ? ', effects touched only' : '')
    + (l.notes ? '' : ', comment markers off'));
}

// An entry is COSMETIC when it is a modification that touches only the
// name / description fields — nothing execution-visible.
function gdDiffEntryCosmetic(e) {
  return e.change === 'modified'
    && Array.isArray(e.fields) && e.fields.length > 0
    && e.fields.every((f) => f.field === 'name' || f.field === 'description');
}

function gdDiffGroupSubstantive(g) {
  return (g.entries || []).some((e) => !gdDiffEntryCosmetic(e));
}

// The group as the CURRENT lens shows it — or null when filtered out.
function gdDiffVisibleGroup(fnId) {
  const g = gdDiffModeGroup(fnId);
  if (!g) return null;
  if (!_gdDiffLens[g.__kind]) return null;
  if (_gdDiffLens.substantiveOnly && !gdDiffGroupSubstantive(g)) return null;
  // "Effects changed" — the strongest behaviour signal. Only applied
  // once the async effect-set comparison landed; until then the toggle
  // is a no-op rather than a false "everything is equal".
  if (_gdDiffLens.effectsOnly && _gdDiffMode?.effectsReady
      && !g.__effects) return null;
  return g;
}

// `lookups` is a top-level `let` in editor-data.js — bundle-scoped,
// NOT a window property. Same-scope lexical access with a typeof
// guard (this module may evaluate before editor-data).
function gdDmLookups() {
  return (typeof lookups !== 'undefined' && lookups) ? lookups : null;
}

// {branch, branchId, currentId, byFnId: Map fnId→group+ui, fetchedAt}
// (ns aggregation is computed per-lens at decorate time)
let _gdDiffMode = null;
let _gdDiffModeFetching = false;

function gdDiffModeActive() { return !!_gdDiffMode; }
function gdDiffModeBranch() { return _gdDiffMode?.branch || null; }
function gdDiffModeGroup(fnId) {
  return _gdDiffMode ? (_gdDiffMode.byFnId.get(fnId) || null) : null;
}

// Changed-slot summaries for one fn — the canvas ring hand-off.
// {slotName: "value: 1 here · 2 there"} — "here" is the branch on
// screen (the diff TARGET), "there" the compared one. Spelled out
// instead of a bare arrow: the modal's old → new reads in the
// review direction, and reusing an arrow here with the opposite
// meaning would mislead.
function gdDiffSlotsForFn(fnId) {
  const g = gdDiffVisibleGroup(fnId);
  if (!g) return null;
  const slots = Object.create(null);   // slot named "toString" must not vanish
  for (const e of (g.entries || [])) {
    const slot = e['slot-name'];
    if (!slot || slots[slot] !== undefined) continue;
    if (_gdDiffLens.substantiveOnly && gdDiffEntryCosmetic(e)) continue;
    let summary;
    if (Array.isArray(e.fields) && e.fields.length) {
      summary = e.fields
        .map((f) => f.field + ': ' + (f.target ?? '∅') + ' here · '
                    + (f.source ?? '∅') + ' there')
        .join('; ');
    } else {
      summary = e.preview || 'differs';
    }
    slots[slot] = summary;
  }
  return Object.keys(slots).length ? slots : null;
}

// Changed INSIDE — this fn's own rows are equal on both branches, but
// something it depends on (an ancestor, a ref target, a type) differs.
// Lens-aware: off under the `inside` lens, and hidden when the change
// it inherits is itself hidden (a cosmetic-only seed under `core`).
function gdDiffAffectedInfo(fnId) {
  if (!_gdDiffMode || !_gdDiffLens.inside || !fnId) return null;
  if (gdDiffModeGroup(fnId)) return null;   // its own change wins
  const a = _gdDiffMode.affected.get(fnId);
  if (!a || !gdDiffVisibleGroup(a.via)) return null;
  const lk = gdDmLookups();
  const viaFn = lk?.fnMap?.get(a.via);
  const viaLabel = viaFn?.name
    ? (typeof getQualifiedFnName === 'function' ? getQualifiedFnName(viaFn) : viaFn.name)
    : (_gdDiffMode.byFnId.get(a.via)?.['fn-label'] || a.via.slice(0, 8));
  return {
    via: a.via, depth: a.depth, viaLabel, nsPath: a['ns-path'] || null,
    title: 'Changed inside — differs through ' + viaLabel
      + (a.depth > 1 ? ' (' + a.depth + ' hops)' : '')
      + ' vs "' + _gdDiffMode.branch + '". Open it to see the change in context.',
  };
}

// Whether an entry shows under the current lens (the `core` lens hides
// cosmetic-only edits).
function gdDiffEntryVisible(e) {
  return !(_gdDiffLens.substantiveOnly && gdDiffEntryCosmetic(e));
}

// Per-slot detail for one fn — what the canvas draws ON the arg (the
// "there" line, the added ring) and what the ghost module needs (the
// compared branch's ref id). `{slotName: {change, fields, sourceRef,
// targetRef, preview, items}}`; `fields` are `{field, source, target}`
// with source = THERE (the compared branch), target = HERE.
function gdDiffSlotDetails(fnId) {
  const g = gdDiffVisibleGroup(fnId);
  if (!g) return null;
  const out = Object.create(null);
  for (const e of (g.entries || [])) {
    const slot = e['slot-name'];
    if (!slot || !gdDiffEntryVisible(e)) continue;
    if (!out[slot]) {
      out[slot] = { change: null, fields: [], sourceRef: null, targetRef: null,
                    preview: null, items: 0, slotRow: null };
    }
    const d = out[slot];
    const en = e['entity-name'];
    if (en === 'binding') {
      d.change = e.change;
      d.sourceRef = e['source-ref'] || null;
      d.targetRef = e['target-ref'] || null;
      if (e.preview) d.preview = e.preview;
      for (const f of (e.fields || [])) d.fields.push(f);
    } else if (en === 'binding-list-item') {
      d.items += 1;
      if (!d.change) d.change = 'modified';
      if (e['source-ref'] && e['source-ref'] !== (e['target-ref'] || null)
          && !d.sourceRef) {
        d.sourceRef = e['source-ref'];
        d.targetRef = e['target-ref'] || null;
        d.itemPosition = e.position;
      }
      for (const f of (e.fields || [])) {
        d.fields.push(Object.assign({ position: e.position }, f));
      }
      if (!e.fields && e.preview) {
        d.fields.push({ field: 'item', position: e.position,
                        source: e.change === 'added-in-source' ? e.preview : '∅',
                        target: e.change === 'added-in-target' ? e.preview : '∅' });
      }
    } else if (en === 'fn-slot') {
      d.slotRow = e.change;
    }
  }
  return Object.keys(out).length ? out : null;
}

// The fn's OWN row change (rename, description, …), if any.
function gdDiffFnOwnFields(fnId) {
  const g = gdDiffVisibleGroup(fnId);
  if (!g) return null;
  const e = (g.entries || []).find((x) => x['entity-name'] === 'fn');
  if (!e || !gdDiffEntryVisible(e)) return null;
  return { change: e.change, fields: e.fields || [], preview: e.preview || null };
}

function gdDiffShort(v, n) {
  const s = (v === null || v === undefined) ? '∅' : String(v);
  const max = n || 24;
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

// One-line summary parts of a group — the Explorer's per-fn digest.
// Wording is HERE (this branch) first, "there" = the compared one.
function gdDiffSummaryParts(g) {
  const parts = [];
  for (const e of (g.entries || [])) {
    if (!gdDiffEntryVisible(e)) continue;
    const en = e['entity-name'];
    const slot = e['slot-name'];
    if (en === 'fn') {
      if (e.change !== 'modified') continue;
      for (const f of (e.fields || [])) {
        if (f.field === 'name') {
          parts.push('name ' + gdDiffShort(f.target) + ' (there ' + gdDiffShort(f.source) + ')');
        } else if (f.field === 'description') {
          parts.push('description ~');
        } else {
          parts.push(f.field + ' ~');
        }
      }
      continue;
    }
    if (en === 'fn-slot') {
      parts.push((e.change === 'added-in-target' ? '+slot '
                  : e.change === 'added-in-source' ? '−slot ' : 'slot ') + (slot || '?'));
      continue;
    }
    if (en === 'resource-override') { parts.push('asset ~'); continue; }
    const label = (slot || '?')
      + (en === 'binding-list-item' ? '[' + (e.position ?? '') + ']' : '');
    if (e.change === 'modified') {
      for (const f of (e.fields || [])) {
        const isRef = f.field === 'ref-fn-id';
        const fname = (f.field === 'value' || isRef) ? '' : f.field + ' ';
        parts.push(label + (isRef ? ' → ' : ': ') + fname
          + gdDiffShort(f.target) + ' (there ' + gdDiffShort(f.source) + ')');
      }
    } else if (e.change === 'added-in-target') {
      parts.push('+' + label + (e.preview ? ' ' + gdDiffShort(e.preview) : ''));
    } else {
      parts.push(label + ': ∅ (there ' + gdDiffShort(e.preview || 'bound') + ')');
    }
  }
  return parts;
}

// --- effect deltas ----------------------------------------------------------

// The registry is branch-scoped (per-ctx slices bound
// at dispatch), so `/api/types` finally answers PER BRANCH and a full
// effect-set comparison is honest: fetch both branches' registries and
// diff each changed fn's `:effects`. For fns without a stable name on
// both sides (anonymous), fall back to the structural signal below —
// which effect-CARRYING fns the change wires in or out (every changed
// ref lands in the display model as `:name`).

async function gdDiffFetchTypes(branchName) {
  const r = await window.authFetch(API.api_types,
    { headers: { 'X-Graphden-Branch': branchName } });
  if (!r.ok) throw new Error('types HTTP ' + r.status);
  return r.json();
}

// {here, there} sorted effect arrays, or null when equal / unresolvable.
function gdDiffEffectSetDelta(hereTypes, thereTypes, name) {
  if (!name || !hereTypes?.[name] || !thereTypes?.[name]) return null;
  const a = (hereTypes[name].effects || []).slice().sort();
  const b = (thereTypes[name].effects || []).slice().sort();
  if (JSON.stringify(a) === JSON.stringify(b)) return null;
  return { here: a, there: b };
}

function gdDiffShowEffects(xs) {
  return xs.length ? xs.join(',') : 'pure';
}

function gdDiffEffectSetLabel(d) {
  return 'effects: ' + gdDiffShowEffects(d.here)
    + ' here · ' + gdDiffShowEffects(d.there) + ' there';
}

// Collect ':name' ref targets from one entry, split by side.
// Returns {there: Set<name>, here: Set<name>} — "there" = the compared
// branch's side (diff SOURCE), "here" = this branch's (TARGET).
function gdDiffEntryRefs(e) {
  const there = new Set();
  const here = new Set();
  const grab = (set, v) => {
    const m = typeof v === 'string' && v.match(/^:(.+)$/);
    if (m) set.add(m[1]);
  };
  for (const f of (e.fields || [])) {
    if (!/ref-fn-id|type-override-fn-id/.test(f.field)) continue;
    grab(there, f.source);
    grab(here, f.target);
  }
  if (e.preview) {
    const m = e.preview.match(/(?:ref|→)\s*→?\s*:(\S+)/);
    if (m) grab(e.change === 'added-in-target' ? here : there, ':' + m[1]);
  }
  return { there, here };
}

function gdDiffEffectsOfName(name) {
  const reg = (typeof richTypes !== 'undefined' && richTypes) || {};
  return reg[name]?.effects || [];
}

// "effects touched: +time −db" — effects reachable through refs the
// change ADDS on the compared side (+) or DROPS (−). Null when the
// change touches no effect-carrying refs.
function gdDiffEffectsTouched(g) {
  const plus = new Set();
  const minus = new Set();
  for (const e of (g.entries || [])) {
    const { there, here } = gdDiffEntryRefs(e);
    for (const n of there) {
      if (!here.has(n)) gdDiffEffectsOfName(n).forEach((x) => { plus.add(x); });
    }
    for (const n of here) {
      if (!there.has(n)) gdDiffEffectsOfName(n).forEach((x) => { minus.add(x); });
    }
  }
  if (!plus.size && !minus.size) return null;
  const parts = [];
  if (plus.size) parts.push('+' + [...plus].sort().join(',+'));
  if (minus.size) parts.push('−' + [...minus].sort().join(',−'));
  return 'effects touched: ' + parts.join(' ');
}

// Two fresh registry fetches (this branch + the compared one — the
// wrapper stamps the current branch's header on the first, we stamp
// the other explicitly), then per-group: the full effect-set delta
// where the fn resolves by name on both sides, the structural
// touched-refs signal otherwise. Async; annotations upgrade in place.
async function gdDiffModeLoadEffects(mode) {
  let hereTypes = null;
  let thereTypes = null;
  try {
    [hereTypes, thereTypes] = await Promise.all([
      window.authFetch(API.api_types).then((r) => (r.ok ? r.json() : null)),
      gdDiffFetchTypes(mode.branch),
    ]);
  } catch (_) { /* fall back to the structural signal alone */ }
  if (_gdDiffMode !== mode) return;   // mode changed under the fetch
  for (const g of mode.byFnId.values()) {
    const d = (hereTypes && thereTypes)
      ? gdDiffEffectSetDelta(hereTypes, thereTypes, g['fn-name'])
      : null;
    g.__effects = d ? gdDiffEffectSetLabel(d) : gdDiffEffectsTouched(g);
    if (g.__effects) g.__title += ' — ' + g.__effects;
  }
  mode.effectsReady = true;
  gdDiffModeDecorateSidebar();
  // The chip's visible/total under an effectsOnly lens can only be
  // computed once the effect deltas landed — refresh it.
  if (_gdDiffMode === mode) gdDiffModeRenderChip();
}

// --- classification helpers -------------------------------------------------

// UI classification of a group from the CURRENT branch's perspective.
function gdDiffModeKind(group) {
  if (group.change === 'added-in-target') return 'added';    // added here
  if (group.change === 'added-in-source') return 'missing';  // only on other
  return 'modified';
}

const GD_DIFF_GLYPH = { added: '+', missing: '−', modified: '±', inside: '∿' };
const GD_DIFF_CLS = { added: 'bd-added', missing: 'bd-removed', modified: 'bd-modified', inside: 'bd-inside' };

// --- data -------------------------------------------------------------------

async function gdDiffModeFetch(otherBranch) {
  const cur = (typeof getCurrentBranchName === 'function')
    ? getCurrentBranchName() : 'main';
  // Resolve both names to IDS first — the :ref path segment (and any
  // later /api/branches/:ref/* action from the cockpit) cannot carry a
  // "/" in a NAME (hub push/<x> convention). One small list fetch.
  let curId = null;
  let otherId = null;
  let rows = [];
  try {
    rows = (await (await window.authFetch(API.api_branches)).json())
      ?.branches || [];
    curId = rows.find((b) => b.name === cur)?.id || null;
    otherId = rows.find((b) => b.name === otherBranch)?.id || null;
  } catch (_) { /* fall back to names below */ }
  const url = API.api_branches_ref_diff_view(curId || cur)
    + '?against=' + encodeURIComponent(otherId || otherBranch);
  const r = await window.authFetch(url);
  const d = await r.json();
  if (!d.ok) throw new Error(d.message || d.error || ('HTTP ' + r.status));
  const byFnId = new Map();
  for (const g of (d.groups || [])) {
    if (!g['fn-id']) continue;
    const kind = gdDiffModeKind(g);
    const entryCount = (g.entries || []).length;
    g.__kind = kind;
    g.__title = (kind === 'added' ? 'Added on this branch'
                 : kind === 'missing' ? 'Only on ' + otherBranch
                 : 'Differs from ' + otherBranch)
      + (entryCount > 1 ? ' — ' + entryCount + ' changes' : '');
    const lk = gdDmLookups();
    const fn = lk?.fnMap?.get(g['fn-id']);
    // A fn that exists ONLY on the compared branch has no lookups row
    // here — without the server-provided path its ghost and aggregate
    // landed in the pseudo-root instead of its real namespace group.
    g.__nsPath = (fn && lk?.nsPathMap?.get(fn['namespace-id']))
      || g['ns-path'] || null;
    byFnId.set(g['fn-id'], g);
  }
  // Anchored review comments → per-fn counts for the tree's 💬 markers.
  // Every diffed element's entity-id is in the groups, so a comment
  // anchored to a binding/list-item attributes to its owning fn without
  // another lookup. The thread lives on the PROPOSAL side of the pair
  // (the branch whose base is the other side) — an author standing on
  // their feature branch comparing vs main must see the reviewer's
  // notes, which are anchored on the FEATURE's thread, not main's.
  // Best-effort: a comments failure never blocks the mode.
  const noteCounts = new Map();
  const curRow0 = rows.find((b) => b.name === cur);
  const otherRow0 = rows.find((b) => b.name === otherBranch);
  // The one review thread of the compared PAIR — same pick chain as
  // the cockpit's Review item, so all three 💬 surfaces (tree markers,
  // inspector threads, the Review dialog) read AND post the same
  // thread. nil when neither side has a base (two roots): there is no
  // review context, so markers and inspector threads stay off.
  const proposalRef =
    (curRow0?.['base-branch-id'] && otherRow0
      && curRow0['base-branch-id'] === otherRow0.id) ? curRow0.id
    : (otherRow0?.['base-branch-id'] && curRow0
       && otherRow0['base-branch-id'] === curRow0.id) ? otherRow0.id
    : otherRow0?.['base-branch-id'] ? otherRow0.id
    : curRow0?.['base-branch-id'] ? curRow0.id
    : null;
  if (proposalRef) {
    try {
      const cr = await window.authFetch(
        API.api_branches_ref_comments(proposalRef));
      const cd = await cr.json();
      if (cd.ok) {
        const ownerOf = new Map();
        for (const g of byFnId.values()) {
          ownerOf.set(g['fn-id'], g['fn-id']);
          for (const e of (g.entries || [])) {
            if (e['entity-id']) ownerOf.set(e['entity-id'], g['fn-id']);
          }
        }
        for (const c of (cd.comments || [])) {
          const owner = c['entity-id'] && ownerOf.get(c['entity-id']);
          if (owner) noteCounts.set(owner, (noteCounts.get(owner) || 0) + 1);
        }
      }
    } catch (_) { /* markers just stay absent */ }
  }
  // Changed INSIDE — own rows equal, a dependency in the diff. Server-
  // walked over the compiler's reverse-deps index, so it is exactly the
  // set the compiler would recompile for these changes.
  const affected = new Map();
  for (const [id, info] of Object.entries(d.affected || {})) {
    if (!byFnId.has(id)) affected.set(id, info);
  }
  return { branch: otherBranch, branchId: otherId, currentId: curId,
           byFnId, affected, noteCounts, proposalRef, fetchedAt: Date.now() };
}


// --- enter / exit / boot ----------------------------------------------------

let _gdDiffEnterEpoch = 0;

async function gdEnterDiffMode(otherBranch) {
  if (!otherBranch || otherBranch === getCurrentBranchName()) return;
  const epoch = ++_gdDiffEnterEpoch;
  _gdDiffModeFetching = true;
  try {
    const fetched = await gdDiffModeFetch(otherBranch);
    // The user may have exited (×) or picked ANOTHER branch while the
    // fetch was in flight — installing a stale result would resurrect
    // a dismissed mode (the refresh path has the same guard).
    if (epoch !== _gdDiffEnterEpoch) return;
    _gdDiffMode = fetched;
    try { localStorage.setItem(GD_DIFF_MODE_KEY, otherBranch); } catch (_) {}
    gdDiffModeLoadEffects(_gdDiffMode);
    gdDiffModeRenderChip();
    gdDiffModeDecorateSidebar();
    gdDiffModeObserve();
    // Re-ring the currently displayed graph — overlays are (re)built on
    // render, so re-selecting the current fn is the cheapest correct
    // refresh.
    if (typeof selectFn === 'function' && typeof selectedFnId !== 'undefined'
        && selectedFnId) {
      selectFn(selectedFnId);
    }
    if (typeof gdAnnounce === 'function') {
      gdAnnounce('Compare mode on — differences vs ' + otherBranch + ' are marked');
    }
  } catch (err) {
    // A STALE failure (user already exited or re-entered vs another
    // branch) must not clobber the fresh mode — or toast about a
    // comparison the user abandoned.
    if (epoch === _gdDiffEnterEpoch) {
      if (typeof gdToast === 'function') {
        gdToast('Could not load the diff vs "' + otherBranch + '": '
                + (err?.message || 'error'));
      }
      _gdDiffMode = null;
    }
  } finally {
    _gdDiffModeFetching = false;
  }
}

async function gdDiffModeRefresh() {
  if (!_gdDiffMode || _gdDiffModeFetching) return;
  _gdDiffModeFetching = true;
  const prev = _gdDiffMode;
  try {
    const fresh = await gdDiffModeFetch(prev.branch);
    // The user may have EXITED (or re-entered vs another branch) while
    // the fetch was in flight — installing the stale result would
    // resurrect a mode with no chip and no way out.
    if (_gdDiffMode === prev) {
      _gdDiffMode = fresh;
      gdDiffModeLoadEffects(fresh);
      gdDiffModeDecorateSidebar();
      gdDiffModeRenderChip();
    }
  } catch (_) { /* keep the stale annotations */ }
  _gdDiffModeFetching = false;
}

function gdExitDiffMode() {
  // Invalidate any in-flight ENTER fetch: without this bump the epoch
  // guard only caught enter→enter — an exit while a fetch was in
  // flight let the landing result resurrect the dismissed mode (and
  // re-persist it past reloads).
  _gdDiffEnterEpoch += 1;
  gdCloseDiffChipMenu();
  document.getElementById('gd-diff-lens')?.remove();
  document.getElementById('gd-diff-insp')?.remove();
  _gdDiffMode = null;
  try { localStorage.removeItem(GD_DIFF_MODE_KEY); } catch (_) {}
  gdDiffModeRenderChip();
  gdDiffModeDecorateSidebar();
  // Arg rings/badges.
  document.querySelectorAll('.arg-overlay-diff-focus')
    .forEach((el) => { el.classList.remove('arg-overlay-diff-focus'); });
  document.querySelectorAll('.arg-diff-badge').forEach((el) => { el.remove(); });
  // Card-level rings (fn-overlay-diff-*) + the titles the mode set.
  document.querySelectorAll('.fn-overlay-diff').forEach((el) => {
    el.classList.remove('fn-overlay-diff', 'fn-overlay-diff-added',
                        'fn-overlay-diff-missing', 'fn-overlay-diff-modified',
                        'fn-overlay-diff-inside');
    el.removeAttribute('title');
  });
  // UX-v4 marks: the there-values, the rename strip, the ∿ badges, the
  // edge marks and the ghost subtrees.
  document.querySelectorAll('.arg-diff-was, .fn-diff-was, .fn-diff-inside-badge')
    .forEach((el) => { el.remove(); });
  document.querySelectorAll('.arg-overlay-diff-added, .edge-label-diff')
    .forEach((el) => { el.classList.remove('arg-overlay-diff-added', 'edge-label-diff'); });
  if (typeof gdDiffGhostsClear === 'function') gdDiffGhostsClear();
  if (typeof gdAnnounce === 'function') gdAnnounce('Compare mode off');
}

// Boot: restore the persisted mode once the editor is up (API map +
// lookups land async; poll briefly instead of hooking editor-main).
(function gdDiffModeBoot() {
  let stored = null;
  try { stored = localStorage.getItem(GD_DIFF_MODE_KEY); } catch (_) {}
  if (!stored) return;
  let tries = 0;
  const t = setInterval(() => {
    tries += 1;
    if (typeof window.API === 'object' && window.API
        && window.API.api_branches_ref_diff_view
        && gdDmLookups()?.fnMap
        && document.getElementById('entity-list')) {
      clearInterval(t);
      if (stored === getCurrentBranchName()) {
        // Landed ON the compared branch — comparing with itself is
        // meaningless; drop the mode.
        try { localStorage.removeItem(GD_DIFF_MODE_KEY); } catch (_) {}
        return;
      }
      gdEnterDiffMode(stored);
    } else if (tries > 60) {
      clearInterval(t);
    }
  }, 500);
})();

window.gdDiffModeActive = gdDiffModeActive;
window.gdDiffModeBranch = gdDiffModeBranch;
window.gdDiffModeGroup = gdDiffModeGroup;
window.gdDiffSlotsForFn = gdDiffSlotsForFn;
window.gdEnterDiffMode = gdEnterDiffMode;
window.gdDiffAffectedInfo = gdDiffAffectedInfo;
window.gdDiffSlotDetails = gdDiffSlotDetails;
window.gdDiffSummaryParts = gdDiffSummaryParts;
window.gdExitDiffMode = gdExitDiffMode;
window.gdDiffVisibleGroup = gdDiffVisibleGroup;
window.gdDiffLens = gdDiffLens;
window.gdDiffSetLens = gdDiffSetLens;
