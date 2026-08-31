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

const GD_DIFF_MODE_KEY = 'graphden.diffAgainst';
// The TYPE LENS — which change kinds the annotations show, and whether
// cosmetic-only edits (name / description fields alone — nothing that
// affects behaviour) count at all. Persisted beside the branch choice.
const GD_DIFF_LENS_KEY = 'graphden.diffLens';
let _gdDiffLens = { added: true, missing: true, modified: true,
                    substantiveOnly: false, effectsOnly: false,
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

// --- effect deltas ----------------------------------------------------------

// The registry is branch-scoped as of 2026-08-31 (per-ctx slices bound
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
      if (!here.has(n)) gdDiffEffectsOfName(n).forEach((x) => plus.add(x));
    }
    for (const n of here) {
      if (!there.has(n)) gdDiffEffectsOfName(n).forEach((x) => minus.add(x));
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

const GD_DIFF_GLYPH = { added: '+', missing: '−', modified: '±' };
const GD_DIFF_CLS = { added: 'bd-added', missing: 'bd-removed', modified: 'bd-modified' };

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
  if (!d.ok) throw new Error(d.error || ('HTTP ' + r.status));
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
    g.__nsPath = (fn && lk?.nsPathMap?.get(fn['namespace-id'])) || null;
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
  try {
    const curRow0 = rows.find((b) => b.name === cur);
    const otherRow0 = rows.find((b) => b.name === otherBranch);
    const proposalRef =
      (curRow0?.['base-branch-id'] && otherRow0
        && curRow0['base-branch-id'] === otherRow0.id) ? curRow0.id
      : (otherRow0?.['base-branch-id'] && curRow0
         && otherRow0['base-branch-id'] === curRow0.id) ? otherRow0.id
      : (otherId || otherBranch);
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
  return { branch: otherBranch, branchId: otherId, currentId: curId,
           byFnId, noteCounts, fetchedAt: Date.now() };
}

// --- sidebar decoration -----------------------------------------------------

let _gdDiffDecorating = false;

function gdDiffModeDecorateSidebar() {
  const list = document.getElementById('entity-list');
  if (!list) return;
  _gdDiffDecorating = true;
  try {
    // ns counts under the CURRENT lens.
    const nsCounts = new Map();
    if (_gdDiffMode) {
      for (const g of _gdDiffMode.byFnId.values()) {
        if (!gdDiffVisibleGroup(g['fn-id'])) continue;
        // Root-level fns (no namespace) aggregate under the pseudo
        // "(primitives)" header — without this, entering the mode
        // shows NOTHING until the user happens to expand that group.
        const parts = g.__nsPath ? g.__nsPath.split('.') : null;
        const keys = parts
          ? parts.map((_, i) => parts.slice(0, i + 1).join('.'))
          : ['__root__'];
        for (const p of keys) {
          const c = nsCounts.get(p) || { added: 0, missing: 0, modified: 0 };
          c[g.__kind] += 1;
          nsCounts.set(p, c);
        }
      }
    }
    list.querySelectorAll('.entity-item[data-fn-id]').forEach((item) => {
      const g = _gdDiffMode ? gdDiffVisibleGroup(item.dataset.fnId) : null;
      let b = item.querySelector('.gd-diff-badge');
      if (!g) {
        if (b) b.remove();
        item.querySelector('.gd-diff-note-badge')?.remove();
        item.classList.remove('gd-diff-changed');
        return;
      }
      item.classList.add('gd-diff-changed');
      if (!b) {
        b = document.createElement('span');
        b.className = 'gd-diff-badge';
        item.appendChild(b);
      }
      const glyph = GD_DIFF_GLYPH[g.__kind];
      if (b.textContent !== glyph) b.textContent = glyph;
      const cls = 'gd-diff-badge ' + GD_DIFF_CLS[g.__kind];
      if (b.className !== cls) b.className = cls;
      if (b.title !== g.__title) b.title = g.__title;
      // 💬 — anchored review comments live on this fn's changes.
      const notes = _gdDiffLens.notes
        ? (_gdDiffMode.noteCounts?.get(item.dataset.fnId) || 0) : 0;
      let nb = item.querySelector('.gd-diff-note-badge');
      if (!notes) { nb?.remove(); } else {
        if (!nb) {
          nb = document.createElement('span');
          nb.className = 'gd-diff-note-badge';
          item.appendChild(nb);
        }
        const txt = '💬' + (notes > 1 ? notes : '');
        if (nb.textContent !== txt) nb.textContent = txt;
        nb.title = notes + ' review comment' + (notes === 1 ? '' : 's')
          + ' anchored here — open the fn to read the thread';
      }
    });
    // GHOST ROWS — fns that exist only on the COMPARED branch have no
    // row of their own (the Explorer renders the current branch), so
    // без них "− deleted here" was visible only as a namespace
    // aggregate. Inject dimmed placeholder rows into every RENDERED
    // (expanded) namespace group; collapsed groups keep the aggregate
    // badge as their signal. Skipped while the filter box is active —
    // ghosts don't participate in server-side filtering.
    list.querySelectorAll('.gd-diff-ghost').forEach((g) => g.remove());
    const filterBox = document.getElementById('search-input');
    const filtering = !!filterBox?.value.trim();
    if (_gdDiffMode && !filtering) {
      for (const g of _gdDiffMode.byFnId.values()) {
        if (g.__kind !== 'missing') continue;
        if (!gdDiffVisibleGroup(g['fn-id'])) continue;
        const container = g.__nsPath
          ? list.querySelector('.ns-children[data-ns-children="'
                               + CSS.escape(g.__nsPath) + '"]')
          : list.querySelector('.ns-children[data-ns-children="__root__"]');
        if (!container || container.hidden) continue;
        const ghost = document.createElement('div');
        ghost.className = 'entity-item gd-diff-ghost';
        ghost.setAttribute('role', 'treeitem');
        ghost.setAttribute('tabindex', '-1');
        ghost.setAttribute('aria-level',
          String((g.__nsPath ? g.__nsPath.split('.').length : 1) + 1));
        const name = document.createElement('span');
        name.className = 'name';
        name.textContent = (g['fn-label'] || '').replace(/^:/, '');
        ghost.appendChild(name);
        const badge = document.createElement('span');
        badge.className = 'gd-diff-badge bd-removed';
        badge.textContent = '−';
        ghost.appendChild(badge);
        const gnotes = _gdDiffLens.notes
          ? (_gdDiffMode.noteCounts?.get(g['fn-id']) || 0) : 0;
        if (gnotes) {
          const nb = document.createElement('span');
          nb.className = 'gd-diff-note-badge';
          nb.textContent = '💬' + (gnotes > 1 ? gnotes : '');
          nb.title = gnotes + ' review comment'
            + (gnotes === 1 ? '' : 's') + ' anchored here';
          ghost.appendChild(nb);
        }
        ghost.dataset.ghostFnId = g['fn-id'];
        ghost.title = 'Exists only on "' + _gdDiffMode.branch
          + '" — click to switch there and open it';
        ghost.addEventListener('click', () => {
          const nm = g['fn-name'];
          if (!nm || typeof switchToBranch !== 'function') return;
          if (!confirm('“' + nm + '” lives only on "' + _gdDiffMode.branch
                       + '". Switch to that branch to view it?')) return;
          try { window.history.pushState(null, '', '#' + nm); } catch (_) {}
          switchToBranch(_gdDiffMode.branch);
        });
        container.appendChild(ghost);
      }
    }
    // changedOnly — the tree shows ONLY what differs (ghosts included).
    // Skipped while the server-side filter box is active, same as the
    // ghost injection above.
    list.querySelectorAll('.gd-diff-lens-hidden')
      .forEach((el) => el.classList.remove('gd-diff-lens-hidden'));
    if (_gdDiffMode && _gdDiffLens.changedOnly && !filtering) {
      list.querySelectorAll('.entity-item[data-fn-id]').forEach((item) => {
        if (!gdDiffVisibleGroup(item.dataset.fnId)) {
          item.classList.add('gd-diff-lens-hidden');
        }
      });
      list.querySelectorAll('.ns-header[data-ns-path]').forEach((header) => {
        if (!nsCounts.has(header.dataset.nsPath)) {
          header.classList.add('gd-diff-lens-hidden');
          const group = list.querySelector('.ns-children[data-ns-children="'
            + CSS.escape(header.dataset.nsPath) + '"]');
          if (group) group.classList.add('gd-diff-lens-hidden');
        }
      });
      // The pseudo-root group: hide when no root-level fn changed and
      // no ghost landed there.
      const rootGroup = list.querySelector('.ns-children[data-ns-children="__root__"]');
      if (rootGroup
          && !nsCounts.has('__root__')
          && !rootGroup.querySelector('.gd-diff-ghost')) {
        rootGroup.classList.add('gd-diff-lens-hidden');
        list.querySelector('.ns-header-pseudo')
          ?.classList.add('gd-diff-lens-hidden');
      }
    }

    gdDiffEnsureLensBar();

    const headerTargets = [...list.querySelectorAll('.ns-header[data-ns-path]')];
    const pseudo = list.querySelector('.ns-header-pseudo');
    if (pseudo) headerTargets.push(pseudo);
    headerTargets.forEach((header) => {
      const c = nsCounts.get(header.dataset.nsPath || '__root__');
      let b = header.querySelector('.gd-diff-ns-badge');
      if (!c) { if (b) b.remove(); return; }
      if (!b) {
        b = document.createElement('span');
        b.className = 'gd-diff-ns-badge';
        // Before the row-action buttons if present, else append.
        const actions = header.querySelector('.ns-row-actions');
        if (actions) header.insertBefore(b, actions);
        else header.appendChild(b);
      }
      const parts = [];
      if (c.added) parts.push('+' + c.added);
      if (c.modified) parts.push('±' + c.modified);
      if (c.missing) parts.push('−' + c.missing);
      b.textContent = parts.join(' ');
      b.title = 'vs ' + _gdDiffMode.branch + ': '
        + [c.added ? c.added + ' added' : null,
           c.modified ? c.modified + ' modified' : null,
           c.missing ? c.missing + ' only there' : null]
          .filter(Boolean).join(', ');
    });
  } finally {
    // MutationObserver callbacks fire on a MICROTASK — after this
    // synchronous block ends — so a flag alone can't hide our own
    // mutations from the observer. Drain the queued records while the
    // flag is still up: they never reach the callback, and only real
    // external re-renders re-trigger decoration (pre-fix this looped
    // decorate→observe→decorate every 150ms and re-fetched the diff
    // every 20s, forever).
    _gdDiffObserver?.takeRecords();
    _gdDiffDecorating = false;
  }
}

// --- the sidebar DIFF LENS bar ---------------------------------------------

// A second chip row under the kind-lens chips, present only in compare
// mode. Same .kind-toggle look so the Explorer reads as one system.
const GD_DIFF_LENS_CHIPS = [
  { key: 'changedOnly', glyph: 'Δ', label: 'changed',
    title: 'Show only what differs vs the compared branch' },
  { key: 'added', glyph: '+', label: 'added', invertless: true,
    title: 'Show fns added on this branch' },
  { key: 'modified', glyph: '±', label: 'mod', invertless: true,
    title: 'Show modified fns' },
  { key: 'missing', glyph: '−', label: 'there', invertless: true,
    title: 'Show fns that exist only on the compared branch' },
  { key: 'substantiveOnly', glyph: 'Aa', label: 'core',
    title: 'Hide edits that touch nothing but names and descriptions' },
  { key: 'notes', glyph: '💬', label: 'notes',
    title: 'Mark fns that carry anchored review comments' },
  { key: 'effectsOnly', glyph: 'fx', label: 'fx',
    title: 'Show only changes whose effect footprint differs' },
];

// Expand every collapsed Explorer group that holds a visible change
// (used when the "only changed" lens flips on). Clicking the header is
// the Explorer's own expand path, so the tree state stays consistent.
function gdDiffExpandChangedGroups() {
  if (!_gdDiffMode) return;
  const list = document.getElementById('entity-list');
  if (!list) return;
  const changedPaths = new Set();
  let rootChanged = false;
  for (const g of _gdDiffMode.byFnId.values()) {
    if (!gdDiffVisibleGroup(g['fn-id'])) continue;
    if (g.__nsPath) {
      const parts = g.__nsPath.split('.');
      for (let i = 1; i <= parts.length; i++) {
        changedPaths.add(parts.slice(0, i).join('.'));
      }
    } else {
      rootChanged = true;
    }
  }
  // Nested headers only EXIST once their parent expands (collapsed
  // groups render no children), so one pass cannot reach a change
  // inside a.b.c while `a` is collapsed — re-query until a pass
  // clicks nothing (expansion is synchronous; bounded by tree depth).
  let clicked = true;
  while (clicked) {
    clicked = false;
    for (const h of list.querySelectorAll('.ns-header[data-ns-path]')) {
      if (changedPaths.has(h.dataset.nsPath)
          && h.getAttribute('aria-expanded') !== 'true') {
        h.click();
        clicked = true;
      }
    }
  }
  const pseudo = list.querySelector('.ns-header-pseudo');
  if (rootChanged && pseudo
      && pseudo.getAttribute('aria-expanded') !== 'true') pseudo.click();
}

function gdDiffEnsureLensBar() {
  const host = document.getElementById('kind-filters')?.parentElement;
  let bar = document.getElementById('gd-diff-lens');
  if (!_gdDiffMode) { bar?.remove(); return; }
  if (!host) return;
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'gd-diff-lens';
    bar.setAttribute('role', 'group');
    bar.setAttribute('aria-label', 'Diff lens — which changes the tree shows');
    for (const chip of GD_DIFF_LENS_CHIPS) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'kind-toggle gd-diff-lens-chip';
      b.dataset.lensKey = chip.key;
      b.title = chip.title;
      const glyph = document.createElement('span');
      glyph.className = 'kind-glyph';
      glyph.setAttribute('aria-hidden', 'true');
      glyph.textContent = chip.glyph;
      b.appendChild(glyph);
      const label = document.createElement('span');
      label.className = 'kind-label';
      label.textContent = chip.label;
      b.appendChild(label);
      b.addEventListener('click', () => {
        const turningOn = chip.key === 'changedOnly' && !_gdDiffLens.changedOnly;
        gdDiffSetLens({ [chip.key]: !_gdDiffLens[chip.key] });
        // "Only changed" leaves just the touched groups — expand them
        // so the survivors are visible without a second round of
        // clicking through collapsed headers.
        if (turningOn) gdDiffExpandChangedGroups();
      });
      bar.appendChild(b);
    }
    document.getElementById('kind-filters')
      .insertAdjacentElement('afterend', bar);
  }
  bar.querySelectorAll('.gd-diff-lens-chip').forEach((b) => {
    b.setAttribute('aria-pressed', String(!!_gdDiffLens[b.dataset.lensKey]));
  });
}

// Re-decorate whenever the Explorer re-renders (lens flips, search,
// expand/collapse — the tree is rebuilt wholesale). The observer
// ignores its own mutations via the `_gdDiffDecorating` flag.
let _gdDiffObserver = null;
let _gdDiffDecorateTimer = null;

function gdDiffModeObserve() {
  if (_gdDiffObserver) return;
  const list = document.getElementById('entity-list');
  if (!list) return;
  _gdDiffObserver = new MutationObserver(() => {
    if (_gdDiffDecorating || !_gdDiffMode) return;
    clearTimeout(_gdDiffDecorateTimer);
    _gdDiffDecorateTimer = setTimeout(() => {
      // Refresh stale data opportunistically: a rebuild usually means
      // the graph changed. At most one refetch per 20s.
      if (_gdDiffMode && Date.now() - _gdDiffMode.fetchedAt > 20000
          && !_gdDiffModeFetching) {
        gdDiffModeRefresh();
      } else {
        gdDiffModeDecorateSidebar();
      }
    }, 150);
  });
  _gdDiffObserver.observe(list, { childList: true, subtree: true });
}

// --- the inspector diff panel ----------------------------------------------

// Injected by editor-shell.js right after the inspector head whenever
// a fn renders while compare mode is on and the fn differs under the
// current lens: the per-fn detail surface (entries old→new, effects,
// branch-local marker) + the ANCHORED comment threads, exactly where
// the user is already looking at the selected node.
function gdDiffRenderInspectorSection(inspectorEl, fnId) {
  inspectorEl.querySelector('#gd-diff-insp')?.remove();
  const g = fnId ? gdDiffVisibleGroup(fnId) : null;
  if (!g || !_gdDiffMode) return;
  const panel = document.createElement('div');
  panel.id = 'gd-diff-insp';
  panel.className = 'gd-diff-insp';
  const head = document.createElement('div');
  head.className = 'gd-diff-insp-head';
  head.appendChild(gdDiffMarkerEl(g.change));
  const title = document.createElement('span');
  title.textContent = 'vs ' + _gdDiffMode.branch;
  head.appendChild(title);
  if (g.__effects) {
    const fx = document.createElement('span');
    fx.className = 'bd-effects-chip';
    fx.textContent = g.__effects;
    head.appendChild(fx);
  }
  if (g['branch-local?']) {
    const badge = document.createElement('span');
    badge.className = 'branch-diff-row-local-badge';
    badge.title = "Won't propagate on merge — branch-local fn";
    badge.textContent = '📍 branch-local';
    head.appendChild(badge);
  }
  head.appendChild(gdDiffCommentBtnEl('fn', g['fn-id']));
  // The fn-level anchor target (threads mount after this head).
  head.setAttribute('data-anchor-name', 'fn');
  head.setAttribute('data-anchor-id', g['fn-id']);
  panel.appendChild(head);
  panel.appendChild(gdDiffRenderGroup(g, { entriesOnly: true }));
  const inspHead = inspectorEl.querySelector('.gd-insp-head');
  (inspHead || inspectorEl).insertAdjacentElement('afterend', panel);
  // Anchored threads (fn + entries) with composers; no general thread
  // here — that lives in the review dialog.
  if (typeof gdDiffAttachThreads === 'function') {
    gdDiffAttachThreads(panel, _gdDiffMode.branch, _gdDiffMode.branchId,
                        { anchoredOnly: true });
  }
}

// Card-level mark for `editor-overlay-fn.js` — ring the whole fn card
// when the fn differs under the current lens.
function gdDiffModeCardInfo(fnId) {
  const g = fnId ? gdDiffVisibleGroup(fnId) : null;
  if (!g) return null;
  return { kind: g.__kind, cls: GD_DIFF_CLS[g.__kind], title: g.__title };
}

// --- the "Δ vs <branch>" chip ----------------------------------------------

function gdDiffModeRenderChip() {
  const mount = document.getElementById('branch-mount');
  let chip = document.getElementById('gd-diff-chip');
  if (!_gdDiffMode) { if (chip) chip.remove(); return; }
  if (!mount) return;
  if (!chip) {
    chip = document.createElement('span');
    chip.id = 'gd-diff-chip';
    chip.className = 'gd-diff-chip';
    const label = document.createElement('button');
    label.className = 'gd-diff-chip-label';
    label.setAttribute('aria-haspopup', 'menu');
    label.setAttribute('aria-expanded', 'false');
    label.addEventListener('click', () => gdOpenDiffChipMenu(label));
    chip.appendChild(label);
    const off = document.createElement('button');
    off.className = 'gd-diff-chip-off';
    off.textContent = '×';
    off.title = 'Exit compare mode';
    off.setAttribute('aria-label', 'Exit compare mode');
    off.addEventListener('click', () => gdExitDiffMode());
    chip.appendChild(off);
    mount.appendChild(chip);
  }
  const label = chip.querySelector('.gd-diff-chip-label');
  // An active lens HIDES things — silent filtering is how a user ends
  // up believing two branches are identical. Say it on the chip: the
  // count goes `visible/total` while any lens filter is on (plus the
  // dashed border), and a plain total otherwise.
  const filtering = gdDiffLensFiltering();
  const total = _gdDiffMode.byFnId.size;
  let visible = total;
  if (filtering) {
    visible = 0;
    for (const g of _gdDiffMode.byFnId.values()) {
      if (gdDiffVisibleGroup(g['fn-id'])) visible += 1;
    }
  }
  const count = filtering ? (visible + '/' + total) : String(total);
  // Two spans, not one text node: the NAME ellipsizes on long branch
  // names while the count always stays visible at the right edge.
  label.textContent = '';
  const nameEl = document.createElement('span');
  nameEl.className = 'gd-diff-chip-name';
  nameEl.textContent = 'Δ vs ' + _gdDiffMode.branch;
  label.appendChild(nameEl);
  const countEl = document.createElement('span');
  countEl.className = 'gd-diff-chip-count';
  countEl.textContent = ' · ' + count;
  label.appendChild(countEl);
  chip.classList.toggle('gd-diff-chip-filtered', filtering);
  label.title = 'Compare mode — ' + total + ' changed fn'
    + (total === 1 ? '' : 's') + ' vs "' + _gdDiffMode.branch
    + '", marked in the Explorer and on the canvas. Click for the '
    + 'review actions and the type lens.'
    + (filtering
       ? ' LENS ACTIVE — showing ' + visible + ' of ' + total
         + '; the rest are hidden from the annotations.'
       : '');
}

// The chip's menu — the review COCKPIT for the compared pair: the full
// diff, propose-for-review (the merge-request act) / merge shortcuts,
// and the type lens. Torn down on any outside click.
function gdCloseDiffChipMenu() {
  const pop = document.getElementById('gd-diff-chip-pop');
  if (pop && typeof returnFocusTo === 'function'
      && pop.contains(document.activeElement)) {
    returnFocusTo(document.querySelector('.gd-diff-chip-label'));
  }
  pop?.remove();
  document.getElementById('gd-diff-chip-scrim')?.remove();
  document.querySelector('.gd-diff-chip-label')
    ?.setAttribute('aria-expanded', 'false');
}

// One-time registration of the shared dismissal contract (Escape +
// outside pointer) — the menu exists only transiently, so the hooks
// read the live DOM each time.
let _gdDiffChipDismissInstalled = false;

function gdDiffChipInstallDismiss() {
  if (_gdDiffChipDismissInstalled
      || typeof installPopoverDismiss !== 'function') return;
  _gdDiffChipDismissInstalled = true;
  installPopoverDismiss({
    getEl: () => document.getElementById('gd-diff-chip-pop'),
    getAnchor: () => document.querySelector('.gd-diff-chip-label'),
    isVisible: () => !!document.getElementById('gd-diff-chip-pop'),
    onDismiss: gdCloseDiffChipMenu,
    getReturnFocus: () => document.querySelector('.gd-diff-chip-label'),
  });
}

let _gdDiffChipMenuOpening = false;

async function gdOpenDiffChipMenu(anchorBtn) {
  gdCloseDiffChipMenu();
  if (!_gdDiffMode || _gdDiffChipMenuOpening) return;
  _gdDiffChipMenuOpening = true;
  try {
  const other = _gdDiffMode.branch;
  const cur = getCurrentBranchName();
  // One list fetch resolves both branches' ids + the current proposal
  // state (ids ride /api/branches/:ref/* paths safely — names with "/"
  // can't).
  let rows = [];
  try {
    const r = await window.authFetch(API.api_branches);
    rows = (await r.json())?.branches || [];
  } catch (_) { /* menu still renders; actions fall back to names */ }
  const curRow = rows.find((b) => b.name === cur);
  const otherRow = rows.find((b) => b.name === other);
  const proposed = curRow?.['review-state'] === 'proposed';

  const scrim = document.createElement('div');
  scrim.id = 'gd-diff-chip-scrim';
  scrim.className = 'gd-pop-scrim';
  scrim.addEventListener('click', gdCloseDiffChipMenu);
  document.body.appendChild(scrim);

  const pop = document.createElement('div');
  pop.id = 'gd-diff-chip-pop';
  pop.className = 'gd-pop';
  const heading = document.createElement('h5');
  heading.textContent = cur + ' vs ' + other;
  pop.appendChild(heading);

  const item = (text, title, onClick) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'gd-pop-item';
    b.textContent = text;
    if (title) b.title = title;
    b.addEventListener('click', () => { gdCloseDiffChipMenu(); onClick(); });
    pop.appendChild(b);
    return b;
  };

  // The dialog frames a branch against its BASE — never open it for a
  // root branch (an empty "Review: main → main"). Prefer the side of
  // the compared pair whose base IS the other side; else any side with
  // a base; a pair of two roots gets no review item at all.
  const basedOn = (row, baseRow) =>
    row?.['base-branch-id'] && baseRow?.id
      && row['base-branch-id'] === baseRow.id;
  const reviewRow = basedOn(otherRow, curRow) ? otherRow
    : basedOn(curRow, otherRow) ? curRow
    : otherRow?.['base-branch-id'] ? otherRow
    : curRow?.['base-branch-id'] ? curRow : null;
  if (reviewRow) {
    item('💬 Review & comments',
         'The proposal conversation — change list, threads, suggestions',
         () => {
           if (typeof showReviewDialog === 'function') {
             showReviewDialog(reviewRow.name, reviewRow.id);
           }
         });
  }
  // Proposing aims at the branch's BASE — the root branch has none.
  if (curRow?.['base-branch-id']) {
    item(proposed ? '📤 Withdraw the proposal'
                : '📤 Propose "' + cur + '" for review',
       proposed ? 'Take the current branch out of review'
                : 'Submit the current branch for review into its base — the merge-request act',
       async () => {
         try {
           const r = await window.authFetch(
             API.api_branches_ref_propose(curRow?.id || cur), {
               method: 'POST',
               headers: { 'Content-Type': 'application/json' },
               body: JSON.stringify({ proposed: !proposed }),
             });
           const d = await r.json().catch(() => ({}));
           if (!d.ok && typeof gdToast === 'function') {
             gdToast(d.error || ('Could not change the proposal: HTTP ' + r.status));
           } else if (typeof gdToast === 'function') {
             gdToast(proposed ? 'Proposal withdrawn'
                              : '"' + cur + '" proposed for review');
           }
         } catch (e2) {
           if (typeof gdToast === 'function') {
             gdToast('Network error: ' + (e2?.message || e2));
           }
         }
       });
  }
  item('⇢ Merge "' + other + '" into "' + cur + '"',
       'Fold the compared branch into the one you are on',
       async () => {
         // The merge flow reports into the branch popover's error slot —
         // bring the popover up first so failures stay visible.
         if (typeof openBranchPopover === 'function') await openBranchPopover();
         if (typeof mergeBranchInto === 'function') {
           mergeBranchInto(other, cur,
                           null,
                           curRow?.id || _gdDiffMode?.currentId || null);
         }
       });

  // --- the type lens ---
  const lensHead = document.createElement('h5');
  lensHead.textContent = 'Show changes';
  pop.appendChild(lensHead);
  const lensOpt = (key, text, title) => {
    const label = document.createElement('label');
    label.className = 'gd-protect-opt';
    if (title) label.title = title;
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = !!_gdDiffLens[key];
    box.addEventListener('change', () => gdDiffSetLens({ [key]: box.checked }));
    label.appendChild(box);
    const span = document.createElement('span');
    span.textContent = text;
    label.appendChild(span);
    pop.appendChild(label);
  };
  lensOpt('added', '+ added here');
  lensOpt('modified', '± modified');
  lensOpt('missing', '− only on ' + other);
  lensOpt('substantiveOnly', 'Substantive only',
          'Hide edits that touch nothing but names and descriptions');
  lensOpt('effectsOnly', 'Effects touched only',
          'Show only changes that wire an effect-carrying fn in or out');

  const exit = document.createElement('button');
  exit.type = 'button';
  exit.className = 'gd-pop-item';
  exit.textContent = '× Exit compare mode';
  exit.addEventListener('click', () => { gdCloseDiffChipMenu(); gdExitDiffMode(); });
  pop.appendChild(exit);

  const r = anchorBtn.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 300)) + 'px';
  pop.style.top = (r.bottom + 6) + 'px';
  document.body.appendChild(pop);
  anchorBtn.setAttribute('aria-expanded', 'true');
  gdDiffChipInstallDismiss();
  if (typeof focusIntoDialog === 'function') focusIntoDialog(pop);
  } finally {
    _gdDiffChipMenuOpening = false;
  }
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
    if (typeof gdToast === 'function') {
      gdToast('Could not load the diff vs "' + otherBranch + '": '
              + (err?.message || 'error'));
    }
    _gdDiffMode = null;
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
  gdCloseDiffChipMenu();
  document.getElementById('gd-diff-lens')?.remove();
  document.getElementById('gd-diff-insp')?.remove();
  _gdDiffMode = null;
  try { localStorage.removeItem(GD_DIFF_MODE_KEY); } catch (_) {}
  gdDiffModeRenderChip();
  gdDiffModeDecorateSidebar();
  // Arg rings/badges.
  document.querySelectorAll('.arg-overlay-diff-focus')
    .forEach((el) => el.classList.remove('arg-overlay-diff-focus'));
  document.querySelectorAll('.arg-diff-badge').forEach((el) => el.remove());
  // Card-level rings (fn-overlay-diff-*) + the titles the mode set.
  document.querySelectorAll('.fn-overlay-diff').forEach((el) => {
    el.classList.remove('fn-overlay-diff', 'fn-overlay-diff-added',
                        'fn-overlay-diff-missing', 'fn-overlay-diff-modified');
    el.removeAttribute('title');
  });
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
window.gdExitDiffMode = gdExitDiffMode;
window.gdDiffVisibleGroup = gdDiffVisibleGroup;
window.gdDiffModeCardInfo = gdDiffModeCardInfo;
window.gdDiffLens = gdDiffLens;
window.gdDiffSetLens = gdDiffSetLens;
