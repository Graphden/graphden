// Editor COMPARE MODE — authorized Explorer diff facts, native tree ownership.
//
// `gdDiffModeDecorateSidebar`
// projects the already authorized diff onto loaded rows and namespaces;
// app.ui-explorer renders statuses, summaries, counts and the lens bar.
// Native code still owns review notes, ghost identities/navigation, tree
// visibility and the lens model. `gdDiffEnsureLensBar` owns the outer
// `#gd-diff-lens` group under the kind chips;
// `gdDiffModeObserve` is the MutationObserver on `#entity-list` that keeps the
// decoration on across tree rebuilds. Reads the mode's state
// (`_gdDiffMode`, `gdDiffLens`) from editor-diff-mode.js; writes nothing but DOM.
//
// Loads BEFORE editor-diff-mode.js: its boot IIFE may reach the `let`s below
// synchronously, and a `let` is in its TDZ until its own line has run.

// --- sidebar decoration -----------------------------------------------------

let _gdDiffDecorating = false;

function gdDiffModeDecorateSidebar() {
  const list = document.getElementById('entity-list');
  if (!list) return;
  if (_gdDiffMode && _gdDiffMode.ownerContext !== gdDiffOwnerContext()) {
    gdExitDiffMode();
    return;
  }
  window.gdExplorerGraph?.sweepDiff();
  _gdDiffDecorating = true;
  try {
    if (!_gdDiffMode) window.gdExplorerGraph?.disposeDiff(document.body);
    // ns counts under the CURRENT lens.
    const nsCounts = new Map();
    const bump = (nsPath, kind) => {
      const parts = nsPath ? nsPath.split('.') : null;
      const keys = parts
        ? parts.map((_, i) => parts.slice(0, i + 1).join('.'))
        : ['__root__'];
      for (const p of keys) {
        const c = nsCounts.get(p) || { added: 0, missing: 0, modified: 0, inside: 0 };
        c[kind] += 1;
        nsCounts.set(p, c);
      }
    };
    if (_gdDiffMode) {
      for (const g of _gdDiffMode.byFnId.values()) {
        if (!gdDiffVisibleGroup(g['fn-id'])) continue;
        // A fn whose namespace is unknown to the tree (the tree loads
        // lazily) aggregates onto the root pseudo-group — same as before.
        bump(g.__nsPath, g.__kind);
      }
      for (const id of _gdDiffMode.affected.keys()) {
        const a = gdDiffAffectedInfo(id);
        if (a) bump(a.nsPath, 'inside');
      }
    }
    list.querySelectorAll('.entity-item[data-fn-id]').forEach((item) => {
      const g = _gdDiffMode ? gdDiffVisibleGroup(item.dataset.fnId) : null;
      const aff = (!g && _gdDiffMode) ? gdDiffAffectedInfo(item.dataset.fnId) : null;
      let body = item.querySelector(':scope > .gd-diff-graph-host');
      if (!g && !aff) {
        if (body) { window.gdExplorerGraph?.disposeDiff(body); body.remove(); }
        item.querySelector('.gd-diff-note-badge')?.remove();
        item.classList.remove('gd-diff-changed', 'gd-diff-inside');
        return;
      }
      item.classList.add('gd-diff-changed');
      item.classList.toggle('gd-diff-inside', !!aff);
      if (!body) {
        body = document.createElement('span');
        body.className = 'gd-diff-graph-host';
        item.appendChild(body);
      }
      // The native diff model supplies only fields already visible on this
      // row; the selected graph owns status and presentation.
      const parts = g ? gdDiffSummaryParts(g) : ['∿ via ' + aff.viaLabel];
      const full = parts.join(' · ');
      body.title = full;
      window.gdExplorerGraph?.mountDiffItem(body, {
        kind: g ? g.__kind : 'inside', title: g ? g.__title : aff.title,
        summary: full ? gdDiffShort(full, 160) : null,
        branch: _gdDiffMode.branch, counts: {},
      });
      if (!g) {
        item.querySelector('.gd-diff-note-badge')?.remove();
        return;
      }
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
    list.querySelectorAll('.gd-diff-ghost').forEach((g) => {
      window.gdExplorerGraph?.disposeDiff(g);
      g.remove();
    });
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
        const body = document.createElement('span');
        body.className = 'gd-diff-graph-host';
        ghost.appendChild(body);
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
        window.gdExplorerGraph?.mountDiffItem(body, {
          kind: 'missing', title: g.__title,
          summary: (g['fn-label'] || '').replace(/^:/, ''),
          branch: _gdDiffMode.branch, counts: {},
        });
      }
    }
    // changedOnly — the tree shows ONLY what differs (ghosts included).
    // Skipped while the server-side filter box is active, same as the
    // ghost injection above.
    list.querySelectorAll('.gd-diff-lens-hidden')
      .forEach((el) => { el.classList.remove('gd-diff-lens-hidden'); });
    if (_gdDiffMode && _gdDiffLens.changedOnly && !filtering) {
      list.querySelectorAll('.entity-item[data-fn-id]').forEach((item) => {
        if (!gdDiffVisibleGroup(item.dataset.fnId)
            && !gdDiffAffectedInfo(item.dataset.fnId)) {
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
      let body = header.querySelector(':scope > .gd-diff-graph-host');
      if (!c) {
        if (body) { window.gdExplorerGraph?.disposeDiff(body); body.remove(); }
        return;
      }
      if (!body) {
        body = document.createElement('span');
        body.className = 'gd-diff-graph-host';
        // Before the row-action buttons if present, else append.
        const actions = header.querySelector('.ns-row-actions');
        if (actions) header.insertBefore(body, actions);
        else header.appendChild(body);
      }
      window.gdExplorerGraph?.mountDiffItem(body, {
        kind: null, title: '', summary: null,
        branch: _gdDiffMode.branch, counts: c,
      });
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

// A second chip row under the kind-lens chips. Its buttons, labels and
// selected presentation come from the same ordinary Explorer graph.
function gdDiffLensClick(event) {
  const bar = event.currentTarget;
  const button = event.target.closest?.('button[data-kind]');
  if (!_gdDiffMode || !button || !bar.contains(button)) return;
  const current = _gdDiffMode;
  window.gdExplorerGraph?.selectDiffLens(button, (key) => {
    if (_gdDiffMode !== current) return;
    const turningOn = key === 'changedOnly' && !_gdDiffLens.changedOnly;
    gdDiffSetLens({[key]: !_gdDiffLens[key]});
    if (turningOn) gdDiffExpandChangedGroups();
  });
}

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
  if (!_gdDiffMode) {
    if (bar) { window.gdExplorerGraph?.disposeDiff(bar); bar.remove(); }
    return;
  }
  if (!host) return;
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'gd-diff-lens';
    bar.setAttribute('role', 'group');
    bar.setAttribute('aria-label', 'Diff lens — which changes the tree shows');
    const body = document.createElement('div');
    body.className = 'gd-diff-lens-graph-host';
    bar.appendChild(body);
    bar.addEventListener('click', gdDiffLensClick);
    document.getElementById('kind-filters')
      .insertAdjacentElement('afterend', bar);
  }
  window.gdExplorerGraph?.mountDiffLens(bar.querySelector('.gd-diff-lens-graph-host'),
    gdDiffLens());
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
  _gdDiffObserver = new MutationObserver((records) => {
    if (_gdDiffDecorating || !_gdDiffMode) return;
    if (records.every(record => record.target.nodeType === 1
      && record.target.closest('.gd-diff-graph-host'))) return;
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
