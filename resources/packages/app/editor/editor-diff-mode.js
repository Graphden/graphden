// Editor COMPARE MODE (diff v2) — the editor-wide diff lens. Pick a
// branch to compare the CURRENT branch against (⋯ → "◐ Compare…" in
// the branch popover, persisted per browser like the branch choice)
// and the whole editor annotates itself: the Explorer marks every
// changed fn (+ / − / ± with counts aggregated onto namespace rows),
// opening a changed fn rings its changed args on the canvas
// (editor-overlay-arg.js consults `gdDiffSlotsForFn`), and a "◐ vs
// <branch>" chip next to the branch chip shows the mode is on (click
// it for the full diff modal — comments, suggestions — ×  to exit).
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
let _gdDiffLens = { added: true, missing: true, modified: true, substantiveOnly: false };
try {
  const raw = JSON.parse(localStorage.getItem(GD_DIFF_LENS_KEY) || 'null');
  if (raw && typeof raw === 'object') _gdDiffLens = Object.assign(_gdDiffLens, raw);
} catch (_) { /* malformed pref — defaults */ }

function gdDiffLens() { return Object.assign({}, _gdDiffLens); }

function gdDiffLensFiltering() {
  const l = _gdDiffLens;
  return !l.added || !l.missing || !l.modified || l.substantiveOnly;
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
    + (l.substantiveOnly ? ', substantive only' : ''));
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
  return g;
}

// `lookups` is a top-level `let` in editor-data.js — bundle-scoped,
// NOT a window property. Same-scope lexical access with a typeof
// guard (this module may evaluate before editor-data).
function gdDmLookups() {
  return (typeof lookups !== 'undefined' && lookups) ? lookups : null;
}

// {branch, byFnId: Map fnId→group+ui, nsCounts: Map nsPath→{a,m,d}, fetchedAt}
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
  const slots = {};
  for (const e of (g.entries || [])) {
    const slot = e['slot-name'];
    if (!slot || slot in slots) continue;
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
  const url = API.api_branches_ref_diff_view(cur)
    + '?against=' + encodeURIComponent(otherBranch);
  const r = await window.authFetch(url);
  const d = await r.json();
  if (!d.ok) throw new Error(d.error || ('HTTP ' + r.status));
  const byFnId = new Map();
  const nsCounts = new Map();
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
  void nsCounts;   // per-lens aggregation happens at decorate time
  return { branch: otherBranch, byFnId, fetchedAt: Date.now() };
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
        if (!g.__nsPath) continue;
        const parts = g.__nsPath.split('.');
        for (let i = 1; i <= parts.length; i++) {
          const p = parts.slice(0, i).join('.');
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
        item.classList.remove('gd-diff-changed');
        return;
      }
      item.classList.add('gd-diff-changed');
      if (!b) {
        b = document.createElement('span');
        b.className = 'gd-diff-badge';
        item.appendChild(b);
      }
      b.textContent = GD_DIFF_GLYPH[g.__kind];
      b.className = 'gd-diff-badge ' + GD_DIFF_CLS[g.__kind];
      b.title = g.__title;
    });
    list.querySelectorAll('.ns-header[data-ns-path]').forEach((header) => {
      const c = nsCounts.get(header.dataset.nsPath);
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
    _gdDiffDecorating = false;
  }
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

// Card-level mark for `editor-overlay-fn.js` — ring the whole fn card
// when the fn differs under the current lens.
function gdDiffModeCardInfo(fnId) {
  const g = fnId ? gdDiffVisibleGroup(fnId) : null;
  if (!g) return null;
  return { kind: g.__kind, cls: GD_DIFF_CLS[g.__kind], title: g.__title };
}

// --- the "◐ vs <branch>" chip ----------------------------------------------

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
  // up believing two branches are identical. Say it on the chip.
  const filtering = gdDiffLensFiltering();
  label.textContent = '◐ vs ' + _gdDiffMode.branch + (filtering ? ' · filtered' : '');
  chip.classList.toggle('gd-diff-chip-filtered', filtering);
  label.title = 'Compare mode — differences vs "' + _gdDiffMode.branch
    + '" are marked in the Explorer and on the canvas. Click for the diff, '
    + 'review actions and the type lens.'
    + (filtering
       ? ' LENS ACTIVE — some change kinds are hidden from the annotations '
         + '(the Δ modal always shows everything).'
       : '');
}

// The chip's menu — the review COCKPIT for the compared pair: the full
// diff, propose-for-review (the merge-request act) / merge shortcuts,
// and the type lens. Torn down on any outside click.
function gdCloseDiffChipMenu() {
  document.getElementById('gd-diff-chip-pop')?.remove();
  document.getElementById('gd-diff-chip-scrim')?.remove();
}

async function gdOpenDiffChipMenu(anchorBtn) {
  gdCloseDiffChipMenu();
  if (!_gdDiffMode) return;
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

  item('Δ Open full diff', 'Field-level diff + comments + suggestions',
       () => {
         if (typeof showBranchDiff === 'function') {
           showBranchDiff(cur, other, otherRow?.id);
         }
       });
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
         if (typeof mergeBranchInto === 'function') mergeBranchInto(other, cur);
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
}

// --- enter / exit / boot ----------------------------------------------------

async function gdEnterDiffMode(otherBranch) {
  if (!otherBranch || otherBranch === getCurrentBranchName()) return;
  _gdDiffModeFetching = true;
  try {
    _gdDiffMode = await gdDiffModeFetch(otherBranch);
    try { localStorage.setItem(GD_DIFF_MODE_KEY, otherBranch); } catch (_) {}
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
  try {
    _gdDiffMode = await gdDiffModeFetch(_gdDiffMode.branch);
    gdDiffModeDecorateSidebar();
  } catch (_) { /* keep the stale annotations */ }
  _gdDiffModeFetching = false;
}

function gdExitDiffMode() {
  gdCloseDiffChipMenu();
  _gdDiffMode = null;
  try { localStorage.removeItem(GD_DIFF_MODE_KEY); } catch (_) {}
  gdDiffModeRenderChip();
  gdDiffModeDecorateSidebar();
  document.querySelectorAll('.arg-overlay-diff-focus')
    .forEach((el) => el.classList.remove('arg-overlay-diff-focus'));
  document.querySelectorAll('.arg-diff-badge').forEach((el) => el.remove());
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
