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
  const g = gdDiffModeGroup(fnId);
  if (!g) return null;
  const slots = {};
  for (const e of (g.entries || [])) {
    const slot = e['slot-name'];
    if (!slot || slot in slots) continue;
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
    byFnId.set(g['fn-id'], g);
    // Aggregate onto every ancestor namespace path.
    const lk = gdDmLookups();
    const fn = lk?.fnMap?.get(g['fn-id']);
    const nsPath = fn && lk?.nsPathMap?.get(fn['namespace-id']);
    if (nsPath) {
      const parts = nsPath.split('.');
      for (let i = 1; i <= parts.length; i++) {
        const p = parts.slice(0, i).join('.');
        const c = nsCounts.get(p) || { added: 0, missing: 0, modified: 0 };
        c[kind] += 1;
        nsCounts.set(p, c);
      }
    }
  }
  return { branch: otherBranch, byFnId, nsCounts, fetchedAt: Date.now() };
}

// --- sidebar decoration -----------------------------------------------------

let _gdDiffDecorating = false;

function gdDiffModeDecorateSidebar() {
  const list = document.getElementById('entity-list');
  if (!list) return;
  _gdDiffDecorating = true;
  try {
    list.querySelectorAll('.entity-item[data-fn-id]').forEach((item) => {
      const g = _gdDiffMode?.byFnId.get(item.dataset.fnId);
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
      const c = _gdDiffMode?.nsCounts.get(header.dataset.nsPath);
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
    label.addEventListener('click', () => {
      // The full diff surface — conversation + suggestions.
      if (typeof showBranchDiff === 'function' && _gdDiffMode) {
        showBranchDiff(getCurrentBranchName(), _gdDiffMode.branch);
      }
    });
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
  label.textContent = '◐ vs ' + _gdDiffMode.branch;
  label.title = 'Compare mode — differences vs "' + _gdDiffMode.branch
    + '" are marked in the Explorer and on the canvas. Click for the full diff.';
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
